// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package ro.sorinirmies.arrow.resiliencekit

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import mu.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Token bucket rate limiter for controlling request throughput.
 *
 * The Rate Limiter pattern controls the rate of operations to prevent overwhelming
 * a system or service. It uses a token bucket algorithm where tokens are added at
 * a fixed rate, and operations consume tokens.
 *
 * **Use Cases:**
 * - API rate limiting
 * - Prevent service overload
 * - Comply with third-party API rate limits
 * - Control resource consumption
 *
 * **Token Bucket Algorithm:**
 * - Tokens are added to a bucket at a fixed rate
 * - Each operation consumes one or more tokens
 * - If no tokens are available, the operation is throttled
 * - Allows bursts up to bucket capacity
 *
 * **Basic usage:**
 * ```kotlin
 * val limiter = RateLimiter(config = RateLimiterConfig(
 *     permitsPerSecond = 10.0,
 *     burstCapacity = 20
 * ))
 *
 * val result = limiter.tryExecute {
 *     callApi()
 * } // returns null if rate exceeded
 * ```
 *
 * **Blocking execution:**
 * ```kotlin
 * val result = limiter.execute {
 *     callApi()
 * } // waits for available token
 * ```
 *
 * **Sliding window variant:**
 * ```kotlin
 * val limiter = SlidingWindowRateLimiter(config = SlidingWindowConfig(
 *     maxRequests = 100,
 *     windowDuration = 1.minutes
 * ))
 * ```
 *
 * @param config Configuration for rate limiter behavior
 */
public class RateLimiter(
    private val config: RateLimiterConfig = RateLimiterConfig(),
    internal val clock: Clock = Clock.System,
) {
    private val mutex = Mutex()
    private var tokens: Double = config.burstCapacity.toDouble()
    private var lastRefillTime: Instant = clock.now()
    private var totalRequests: Long = 0L
    private var acceptedRequests: Long = 0L
    private var rejectedRequests: Long = 0L
    private val listeners = mutableListOf<RateLimiterListener>()

    /**
     * Adds a listener for rate limiter events.
     */
    public fun addListener(listener: RateLimiterListener) {
        listeners.add(listener)
    }

    /**
     * Gets the current number of available tokens.
     */
    public suspend fun availableTokens(): Double = mutex.withLock {
        doRefillTokens()
        tokens
    }

    /**
     * Gets the current statistics.
     */
    public suspend fun statistics(): RateLimiterStatistics = mutex.withLock {
        RateLimiterStatistics(
            availableTokens = tokens,
            totalRequests = totalRequests,
            acceptedRequests = acceptedRequests,
            rejectedRequests = rejectedRequests,
        )
    }

    /**
     * Executes an operation with rate limiting.
     *
     * Waits (suspends) until a token is available, then executes the operation.
     *
     * @param permits Number of permits (tokens) required (default: 1)
     * @param block The operation to execute
     * @return The result of the operation
     * @throws Exception if the operation fails
     */
    public suspend fun <T> execute(
        permits: Int = 1,
        block: suspend () -> T,
    ): T {
        require(permits > 0) { "permits must be > 0, but was $permits" }
        require(permits <= config.burstCapacity) {
            "permits ($permits) cannot exceed burst capacity (${config.burstCapacity})"
        }

        // Wait until we have enough tokens
        while (true) {
            val acquired = tryAcquire(permits)
            if (acquired) {
                break
            }

            // Calculate wait time based on token refill rate
            val waitTime = calculateWaitTime(permits)
            logger.debug { "Rate limit reached, waiting ${waitTime}ms for tokens" }
            kotlinx.coroutines.delay(waitTime)
        }

        return block()
    }

    /**
     * Tries to execute an operation, returning null if rate limited.
     *
     * Non-blocking variant that immediately returns null if no tokens are available.
     *
     * @param permits Number of permits (tokens) required (default: 1)
     * @param block The operation to execute
     * @return The result of the operation or null if rate limited
     */
    public suspend fun <T> tryExecute(
        permits: Int = 1,
        block: suspend () -> T,
    ): T? {
        require(permits > 0) { "permits must be > 0, but was $permits" }

        val acquired = tryAcquire(permits)
        if (!acquired) {
            logger.debug { "Rate limit reached, rejecting request" }
            return null
        }

        return try {
            block()
        } catch (e: Exception) {
            throw e
        }
    }

    /**
     * Executes an operation with a fallback if rate limited.
     *
     * @param permits Number of permits (tokens) required (default: 1)
     * @param fallback The fallback function to call if rate limited
     * @param block The primary operation to execute
     * @return The result of the operation or fallback
     */
    public suspend fun <T> executeOrFallback(
        permits: Int = 1,
        fallback: suspend () -> T,
        block: suspend () -> T,
    ): T {
        return tryExecute(permits, block) ?: fallback()
    }

    /**
     * Tries to acquire the specified number of permits.
     *
     * @param permits Number of permits to acquire
     * @return true if permits were acquired, false otherwise
     */
    public suspend fun tryAcquire(permits: Int = 1): Boolean {
        require(permits > 0) { "permits must be > 0, but was $permits" }

        val acquired = mutex.withLock {
            totalRequests++
            doRefillTokens()

            if (tokens >= permits) {
                tokens -= permits
                acceptedRequests++
                true
            } else {
                rejectedRequests++
                false
            }
        }

        if (acquired) {
            listeners.forEach { it.onRequestAccepted() }
        } else {
            listeners.forEach { it.onRequestRejected() }
        }

        return acquired
    }

    /**
     * Resets all statistics counters.
     */
    public suspend fun resetStatistics(): Unit {
        mutex.withLock {
            totalRequests = 0L
            acceptedRequests = 0L
            rejectedRequests = 0L
        }
    }

    /**
     * Resets the rate limiter to initial state.
     */
    public suspend fun reset(): Unit {
        mutex.withLock {
            tokens = config.burstCapacity.toDouble()
            lastRefillTime = Clock.System.now()
            totalRequests = 0L
            acceptedRequests = 0L
            rejectedRequests = 0L
        }
    }

    /**
     * Refills tokens based on elapsed time.
     * Must be called while holding the mutex.
     */
    private fun doRefillTokens() {
        val now = clock.now()
        val elapsed = now - lastRefillTime

        if (elapsed > Duration.ZERO) {
            val tokensToAdd = elapsed.inWholeMilliseconds * config.permitsPerSecond / 1000.0
            tokens = minOf(
                tokens + tokensToAdd,
                config.burstCapacity.toDouble()
            )
            lastRefillTime = now
        }
    }

    /**
     * Calculates the wait time needed for the specified number of permits.
     */
    private fun calculateWaitTime(permits: Int): kotlin.time.Duration {
        val tokensNeeded = permits.toDouble()
        val millisecondsToWait = (tokensNeeded * 1000.0 / config.permitsPerSecond).toLong()
        return millisecondsToWait.milliseconds
    }
}

/**
 * Configuration for rate limiter behavior.
 *
 * @property permitsPerSecond Number of permits to add per second (default: 10.0)
 * @property burstCapacity Maximum number of permits that can be accumulated (default: 10)
 */
public data class RateLimiterConfig(
    public val permitsPerSecond: Double = 10.0,
    public val burstCapacity: Int = 10,
) {
    init {
        require(permitsPerSecond > 0) {
            "permitsPerSecond must be > 0, but was $permitsPerSecond"
        }
        require(burstCapacity > 0) {
            "burstCapacity must be > 0, but was $burstCapacity"
        }
    }
}

/**
 * Statistics for a rate limiter.
 *
 * @property availableTokens Current number of available tokens
 * @property totalRequests Total number of requests attempted
 * @property acceptedRequests Total number of accepted requests
 * @property rejectedRequests Total number of rejected requests
 */
public data class RateLimiterStatistics(
    public val availableTokens: Double,
    public val totalRequests: Long,
    public val acceptedRequests: Long,
    public val rejectedRequests: Long,
) {
    public val acceptanceRate: Double
        get() = if (totalRequests > 0) {
            acceptedRequests.toDouble() / totalRequests
        } else {
            0.0
        }

    public val rejectionRate: Double
        get() = if (totalRequests > 0) {
            rejectedRequests.toDouble() / totalRequests
        } else {
            0.0
        }
}

/**
 * Listener for rate limiter events.
 */
public interface RateLimiterListener {
    /**
     * Called when a request is accepted.
     */
    public fun onRequestAccepted(): Unit {}

    /**
     * Called when a request is rejected due to rate limiting.
     */
    public fun onRequestRejected(): Unit {}

    /**
     * Called when a request fails.
     */
    public fun onRequestFailed(exception: Exception): Unit {}
}

/**
 * Creates a rate limiter with DSL-style configuration.
 *
 * Example usage:
 * ```
 * val rateLimiter = rateLimiter {
 *     permitsPerSecond = 100.0
 *     burstCapacity = 200
 * }
 * ```
 */
public fun rateLimiter(configure: RateLimiterConfigBuilder.() -> Unit): RateLimiter {
    val builder = RateLimiterConfigBuilder()
    builder.configure()
    return RateLimiter(builder.build())
}

/**
 * Builder for rate limiter configuration.
 */
public class RateLimiterConfigBuilder {
    public var permitsPerSecond: Double = 10.0
    public var burstCapacity: Int = 10

    public fun build(): RateLimiterConfig {
        return RateLimiterConfig(
            permitsPerSecond = permitsPerSecond,
            burstCapacity = burstCapacity,
        )
    }
}

/**
 * Sliding window rate limiter implementation.
 *
 * Unlike token bucket, this tracks requests within a time window and rejects
 * requests if the limit is exceeded within that window.
 *
 * **Use Cases:**
 * - Strict rate limiting (e.g., "max 100 requests per minute")
 * - Time-based quotas
 * - More predictable rate limiting than token bucket
 *
 * @param config Configuration for sliding window rate limiter
 *
 * Example usage:
 * ```
 * val rateLimiter = SlidingWindowRateLimiter(
 *     config = SlidingWindowConfig(
 *         maxRequests = 100,
 *         windowDuration = 1.minutes
 *     )
 * )
 * ```
 */
public class SlidingWindowRateLimiter(
    private val config: SlidingWindowConfig = SlidingWindowConfig(),
    internal val clock: Clock = Clock.System,
) {
    private val mutex = Mutex()
    private var requestTimestamps: MutableList<Instant> = mutableListOf()
    private var totalRequests: Long = 0L
    private var acceptedRequests: Long = 0L
    private var rejectedRequests: Long = 0L

    /**
     * Gets the current number of requests in the window.
     */
    public suspend fun currentRequests(): Int = mutex.withLock {
        doCleanupOldRequests()
        requestTimestamps.size
    }

    /**
     * Gets the current statistics.
     */
    public suspend fun statistics(): SlidingWindowStatistics = mutex.withLock {
        doCleanupOldRequests()
        SlidingWindowStatistics(
            currentRequests = requestTimestamps.size,
            totalRequests = totalRequests,
            acceptedRequests = acceptedRequests,
            rejectedRequests = rejectedRequests,
        )
    }

    /**
     * Executes an operation with sliding window rate limiting.
     *
     * @param block The operation to execute
     * @return The result of the operation
     * @throws RateLimitExceededException if rate limit is exceeded
     * @throws Exception if the operation fails
     */
    public suspend fun <T> execute(block: suspend () -> T): T {
        val acquired = tryAcquire()
        if (!acquired) {
            throw RateLimitExceededException(
                "Rate limit exceeded: ${config.maxRequests} requests per ${config.windowDuration}"
            )
        }

        return block()
    }

    /**
     * Tries to execute an operation, returning null if rate limited.
     *
     * @param block The operation to execute
     * @return The result of the operation or null if rate limited
     */
    public suspend fun <T> tryExecute(block: suspend () -> T): T? {
        val acquired = tryAcquire()
        if (!acquired) {
            return null
        }

        return block()
    }

    /**
     * Tries to acquire a permit within the sliding window.
     *
     * @return true if permit was acquired, false otherwise
     */
    public suspend fun tryAcquire(): Boolean {
        return mutex.withLock {
            totalRequests++
            doCleanupOldRequests()

            if (requestTimestamps.size < config.maxRequests) {
                val now = clock.now()
                requestTimestamps.add(now)
                acceptedRequests++
                true
            } else {
                rejectedRequests++
                false
            }
        }
    }

    /**
     * Resets the rate limiter to initial state.
     */
    public suspend fun reset(): Unit {
        mutex.withLock {
            requestTimestamps.clear()
            totalRequests = 0L
            acceptedRequests = 0L
            rejectedRequests = 0L
        }
    }

    /**
     * Removes requests older than the window duration.
     * Must be called while holding the mutex.
     */
    private fun doCleanupOldRequests() {
        val now = clock.now()
        val cutoffTime = now - config.windowDuration
        requestTimestamps.removeAll { it < cutoffTime }
    }
}

/**
 * Configuration for sliding window rate limiter.
 *
 * @property maxRequests Maximum number of requests allowed in the window (default: 100)
 * @property windowDuration Duration of the sliding window (default: 1 minute)
 */
public data class SlidingWindowConfig(
    public val maxRequests: Int = 100,
    public val windowDuration: Duration = 60.seconds,
) {
    init {
        require(maxRequests > 0) {
            "maxRequests must be > 0, but was $maxRequests"
        }
        require(windowDuration > Duration.ZERO) {
            "windowDuration must be > 0, but was $windowDuration"
        }
    }
}

/**
 * Statistics for a sliding window rate limiter.
 *
 * @property currentRequests Current number of requests in the window
 * @property totalRequests Total number of requests attempted
 * @property acceptedRequests Total number of accepted requests
 * @property rejectedRequests Total number of rejected requests
 */
public data class SlidingWindowStatistics(
    public val currentRequests: Int,
    public val totalRequests: Long,
    public val acceptedRequests: Long,
    public val rejectedRequests: Long,
) {
    public val acceptanceRate: Double
        get() = if (totalRequests > 0) {
            acceptedRequests.toDouble() / totalRequests
        } else {
            0.0
        }

    public val rejectionRate: Double
        get() = if (totalRequests > 0) {
            rejectedRequests.toDouble() / totalRequests
        } else {
            0.0
        }
}

/**
 * Exception thrown when rate limit is exceeded.
 */
public class RateLimitExceededException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Registry for managing multiple named rate limiters.
 *
 * Example usage:
 * ```
 * val registry = RateLimiterRegistry()
 *
 * val apiLimiter = registry.getOrCreate("api") {
 *     permitsPerSecond = 100.0
 *     burstCapacity = 200
 * }
 * ```
 */
public class RateLimiterRegistry {
    private val limiters = mutableMapOf<String, RateLimiter>()

    /**
     * Gets an existing rate limiter or creates a new one.
     */
    public fun getOrCreate(
        name: String,
        configure: (RateLimiterConfigBuilder.() -> Unit)? = null,
    ): RateLimiter {
        return limiters.getOrPut(name) {
            if (configure != null) {
                rateLimiter(configure)
            } else {
                RateLimiter()
            }
        }
    }

    /**
     * Gets an existing rate limiter by name.
     */
    public fun get(name: String): RateLimiter? {
        return limiters[name]
    }

    /**
     * Removes a rate limiter from the registry.
     */
    public fun remove(name: String): RateLimiter? {
        return limiters.remove(name)
    }

    /**
     * Resets all rate limiters in the registry.
     */
    public suspend fun resetAll(): Unit {
        limiters.values.forEach { it.reset() }
    }

    /**
     * Gets all rate limiter names in the registry.
     */
    public fun getNames(): Set<String> {
        return limiters.keys.toSet()
    }

    /**
     * Gets statistics for all rate limiters.
     */
    public suspend fun getAllStatistics(): Map<String, RateLimiterStatistics> {
        return limiters.mapValues { (_, limiter) ->
            limiter.statistics()
        }
    }
}
