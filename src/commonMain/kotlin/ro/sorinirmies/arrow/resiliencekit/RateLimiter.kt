// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package ro.sorinirmies.arrow.resiliencekit

import arrow.fx.stm.TVar
import arrow.fx.stm.atomically
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
 * val limiter = RateLimiter.create(config = RateLimiterConfig(
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
 * val limiter = SlidingWindowRateLimiter.create(config = SlidingWindowConfig(
 *     maxRequests = 100,
 *     windowDuration = 1.minutes
 * ))
 * ```
 *
 * @param config Configuration for rate limiter behavior
 */
public class RateLimiter private constructor(
    private val config: RateLimiterConfig,
    internal val clock: Clock,
    private val tokens: TVar<Double>,
    private val lastRefillTime: TVar<Instant>,
    private val totalRequests: TVar<Long>,
    private val acceptedRequests: TVar<Long>,
    private val rejectedRequests: TVar<Long>,
) {
    private val listeners = mutableListOf<RateLimiterListener>()

    public companion object {
        /**
         * Creates a new [RateLimiter] instance.
         */
        public suspend fun create(
            config: RateLimiterConfig = RateLimiterConfig(),
            clock: Clock = Clock.System,
        ): RateLimiter {
            val tokens = TVar.new(config.burstCapacity.toDouble())
            val lastRefillTime = TVar.new(clock.now())
            val totalRequests = TVar.new(0L)
            val acceptedRequests = TVar.new(0L)
            val rejectedRequests = TVar.new(0L)
            return RateLimiter(config, clock, tokens, lastRefillTime, totalRequests, acceptedRequests, rejectedRequests)
        }
    }

    /**
     * Adds a listener for rate limiter events.
     */
    public fun addListener(listener: RateLimiterListener) {
        listeners.add(listener)
    }

    /**
     * Gets the current number of available tokens.
     */
    public suspend fun availableTokens(): Double = atomically {
        doRefillTokens()
        tokens.read()
    }

    /**
     * Gets the current statistics.
     */
    public suspend fun statistics(): RateLimiterStatistics = atomically {
        RateLimiterStatistics(
            availableTokens = tokens.read(),
            totalRequests = totalRequests.read(),
            acceptedRequests = acceptedRequests.read(),
            rejectedRequests = rejectedRequests.read(),
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

        return try {
            block()
        } catch (e: Exception) {
            listeners.toList().forEach {
                try {
                    it.onRequestFailed(e)
                } catch (_: Exception) {
                }
            }
            throw e
        }
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
            listeners.toList().forEach {
                try {
                    it.onRequestFailed(e)
                } catch (_: Exception) {
                }
            }
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

        val acquired = atomically {
            totalRequests.write(totalRequests.read() + 1)
            doRefillTokens()

            val currentTokens = tokens.read()
            if (currentTokens >= permits) {
                tokens.write(currentTokens - permits)
                acceptedRequests.write(acceptedRequests.read() + 1)
                true
            } else {
                rejectedRequests.write(rejectedRequests.read() + 1)
                false
            }
        }

        if (acquired) {
            listeners.toList().forEach {
                try {
                    it.onRequestAccepted()
                } catch (_: Exception) {
                }
            }
        } else {
            listeners.toList().forEach {
                try {
                    it.onRequestRejected()
                } catch (_: Exception) {
                }
            }
        }

        return acquired
    }

    /**
     * Resets all statistics counters.
     */
    public suspend fun resetStatistics(): Unit {
        atomically {
            totalRequests.write(0L)
            acceptedRequests.write(0L)
            rejectedRequests.write(0L)
        }
    }

    /**
     * Resets the rate limiter to initial state.
     */
    public suspend fun reset(): Unit {
        atomically {
            tokens.write(config.burstCapacity.toDouble())
            lastRefillTime.write(clock.now())
            totalRequests.write(0L)
            acceptedRequests.write(0L)
            rejectedRequests.write(0L)
        }
    }

    /**
     * Refills tokens based on elapsed time.
     * Must be called within an STM transaction.
     */
    private fun arrow.fx.stm.STM.doRefillTokens() {
        val now = clock.now()
        val elapsed = now - lastRefillTime.read()

        if (elapsed > Duration.ZERO) {
            val tokensToAdd = elapsed.inWholeMilliseconds * config.permitsPerSecond / 1000.0
            val currentTokens = tokens.read()
            tokens.write(
                minOf(
                    currentTokens + tokensToAdd,
                    config.burstCapacity.toDouble()
                )
            )
            lastRefillTime.write(now)
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
    /** Acceptance rate as a ratio from 0.0 to 1.0. */
    public val acceptanceRate: Double
        get() = if (totalRequests > 0) {
            acceptedRequests.toDouble() / totalRequests
        } else {
            0.0
        }

    /** Rejection rate as a ratio from 0.0 to 1.0. */
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
public suspend fun rateLimiter(configure: RateLimiterConfigBuilder.() -> Unit): RateLimiter {
    val builder = RateLimiterConfigBuilder()
    builder.configure()
    return RateLimiter.create(builder.build())
}

/**
 * Builder for rate limiter configuration.
 */
public class RateLimiterConfigBuilder {
    /** Number of permits to add per second. */
    public var permitsPerSecond: Double = 10.0

    /** Maximum number of permits that can be accumulated. */
    public var burstCapacity: Int = 10

    /** Builds the [RateLimiterConfig] from the current builder state. */
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
 * val rateLimiter = SlidingWindowRateLimiter.create(
 *     config = SlidingWindowConfig(
 *         maxRequests = 100,
 *         windowDuration = 1.minutes
 *     )
 * )
 * ```
 */
public class SlidingWindowRateLimiter private constructor(
    private val config: SlidingWindowConfig,
    internal val clock: Clock,
    private val requestTimestamps: TVar<List<Instant>>,
    private val totalRequests: TVar<Long>,
    private val acceptedRequests: TVar<Long>,
    private val rejectedRequests: TVar<Long>,
) {
    public companion object {
        /**
         * Creates a new [SlidingWindowRateLimiter] instance.
         */
        public suspend fun create(
            config: SlidingWindowConfig = SlidingWindowConfig(),
            clock: Clock = Clock.System,
        ): SlidingWindowRateLimiter {
            val requestTimestamps = TVar.new(emptyList<Instant>())
            val totalRequests = TVar.new(0L)
            val acceptedRequests = TVar.new(0L)
            val rejectedRequests = TVar.new(0L)
            return SlidingWindowRateLimiter(
                config,
                clock,
                requestTimestamps,
                totalRequests,
                acceptedRequests,
                rejectedRequests
            )
        }
    }

    /**
     * Gets the current number of requests in the window.
     */
    public suspend fun currentRequests(): Int = atomically {
        doCleanupOldRequests()
        requestTimestamps.read().size
    }

    /**
     * Gets the current statistics.
     */
    public suspend fun statistics(): SlidingWindowStatistics = atomically {
        doCleanupOldRequests()
        SlidingWindowStatistics(
            currentRequests = requestTimestamps.read().size,
            totalRequests = totalRequests.read(),
            acceptedRequests = acceptedRequests.read(),
            rejectedRequests = rejectedRequests.read(),
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
        return atomically {
            totalRequests.write(totalRequests.read() + 1)
            doCleanupOldRequests()

            val timestamps = requestTimestamps.read()
            if (timestamps.size < config.maxRequests) {
                val now = clock.now()
                requestTimestamps.write(timestamps + now)
                acceptedRequests.write(acceptedRequests.read() + 1)
                true
            } else {
                rejectedRequests.write(rejectedRequests.read() + 1)
                false
            }
        }
    }

    /**
     * Resets the rate limiter to initial state.
     */
    public suspend fun reset(): Unit {
        atomically {
            requestTimestamps.write(emptyList())
            totalRequests.write(0L)
            acceptedRequests.write(0L)
            rejectedRequests.write(0L)
        }
    }

    /**
     * Removes requests older than the window duration.
     * Must be called within an STM transaction.
     */
    private fun arrow.fx.stm.STM.doCleanupOldRequests() {
        val now = clock.now()
        val cutoffTime = now - config.windowDuration
        val timestamps = requestTimestamps.read()
        requestTimestamps.write(timestamps.filter { it >= cutoffTime })
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
    /** Acceptance rate as a ratio from 0.0 to 1.0. */
    public val acceptanceRate: Double
        get() = if (totalRequests > 0) {
            acceptedRequests.toDouble() / totalRequests
        } else {
            0.0
        }

    /** Rejection rate as a ratio from 0.0 to 1.0. */
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
 * val registry = RateLimiterRegistry.create()
 *
 * val apiLimiter = registry.getOrCreate("api") {
 *     permitsPerSecond = 100.0
 *     burstCapacity = 200
 * }
 * ```
 */
public class RateLimiterRegistry private constructor(
    private val limiters: TVar<Map<String, RateLimiter>>,
) {
    public companion object {
        /**
         * Creates a new [RateLimiterRegistry] instance.
         */
        public suspend fun create(): RateLimiterRegistry {
            val limiters = TVar.new(emptyMap<String, RateLimiter>())
            return RateLimiterRegistry(limiters)
        }
    }

    /**
     * Gets an existing rate limiter or creates a new one.
     */
    public suspend fun getOrCreate(
        name: String,
        configure: (RateLimiterConfigBuilder.() -> Unit)? = null,
    ): RateLimiter {
        // First check if it already exists
        val existing = atomically { limiters.read()[name] }
        if (existing != null) return existing

        // Create outside the transaction, then atomically insert if still absent
        val newLimiter = if (configure != null) {
            rateLimiter(configure)
        } else {
            RateLimiter.create()
        }

        return atomically {
            val current = limiters.read()
            val existingInTx = current[name]
            if (existingInTx != null) {
                existingInTx
            } else {
                limiters.write(current + (name to newLimiter))
                newLimiter
            }
        }
    }

    /**
     * Gets an existing rate limiter by name.
     */
    public suspend fun get(name: String): RateLimiter? = atomically { limiters.read()[name] }

    /**
     * Removes a rate limiter from the registry.
     */
    public suspend fun remove(name: String): RateLimiter? = atomically {
        val current = limiters.read()
        val removed = current[name]
        if (removed != null) {
            limiters.write(current - name)
        }
        removed
    }

    /**
     * Resets all rate limiters in the registry.
     */
    public suspend fun resetAll(): Unit {
        val snapshot = atomically { limiters.read().values.toList() }
        snapshot.forEach { it.reset() }
    }

    /**
     * Gets all rate limiter names in the registry.
     */
    public suspend fun getNames(): Set<String> = atomically { limiters.read().keys }

    /**
     * Gets statistics for all rate limiters.
     */
    public suspend fun getAllStatistics(): Map<String, RateLimiterStatistics> {
        val snapshot = atomically { limiters.read() }
        return snapshot.mapValues { (_, limiter) ->
            limiter.statistics()
        }
    }
}
