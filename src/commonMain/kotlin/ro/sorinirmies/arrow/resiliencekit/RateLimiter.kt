// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies


import arrow.fx.stm.TVar
import arrow.fx.stm.atomically
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import mu.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Rate limiter pattern implementation using token bucket algorithm with Arrow STM.
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
 * @param config Configuration for rate limiter behavior
 *
 * Example usage:
 * ```
 * val rateLimiter = RateLimiter(
 *     config = RateLimiterConfig(
 *         permitsPerSecond = 10.0,
 *         burstCapacity = 20
 *     )
 * )
 *
 * // Execute operation with rate limiting
 * val result = rateLimiter.execute {
 *     apiClient.call()
 * }
 * ```
 */
class RateLimiter(
    private val config: RateLimiterConfig = RateLimiterConfig(),
) {
    private val tokensVar = TVar(config.burstCapacity.toDouble())
    private val lastRefillTimeVar = TVar(Clock.System.now())
    private val totalRequestsVar = TVar(0L)
    private val acceptedRequestsVar = TVar(0L)
    private val rejectedRequestsVar = TVar(0L)
    private val listeners = mutableListOf<RateLimiterListener>()

    /**
     * Gets the current number of available tokens.
     */
    suspend fun availableTokens(): Double = atomically {
        refillTokens()
        tokensVar.read()
    }

    /**
     * Gets the current statistics.
     */
    suspend fun statistics(): RateLimiterStatistics = atomically {
        RateLimiterStatistics(
            availableTokens = tokensVar.read(),
            totalRequests = totalRequestsVar.read(),
            acceptedRequests = acceptedRequestsVar.read(),
            rejectedRequests = rejectedRequestsVar.read(),
        )
    }

    /**
     * Adds a listener to be notified of rate limiter events.
     */
    fun addListener(listener: RateLimiterListener) {
        listeners.add(listener)
    }

    /**
     * Removes a listener.
     */
    fun removeListener(listener: RateLimiterListener) {
        listeners.remove(listener)
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
    suspend fun <T> execute(
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
    suspend fun <T> tryExecute(
        permits: Int = 1,
        block: suspend () -> T,
    ): T? {
        require(permits > 0) { "permits must be > 0, but was $permits" }

        val acquired = tryAcquire(permits)
        if (!acquired) {
            logger.debug { "Rate limit reached, rejecting request" }
            notifyListeners { it.onRequestRejected() }
            return null
        }

        return try {
            block()
        } catch (e: Exception) {
            notifyListeners { it.onRequestFailed(e) }
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
    suspend fun <T> executeOrFallback(
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
    suspend fun tryAcquire(permits: Int = 1): Boolean {
        require(permits > 0) { "permits must be > 0, but was $permits" }

        val acquired = atomically {
            val total = totalRequestsVar.read()
            totalRequestsVar.write(total + 1)
            refillTokens()

            val currentTokens = tokensVar.read()
            if (currentTokens >= permits) {
                tokensVar.write(currentTokens - permits)
                val accepted = acceptedRequestsVar.read()
                acceptedRequestsVar.write(accepted + 1)
                true
            } else {
                val rejected = rejectedRequestsVar.read()
                rejectedRequestsVar.write(rejected + 1)
                false
            }
        }

        if (acquired) {
            notifyListeners { it.onRequestAccepted() }
        } else {
            notifyListeners { it.onRequestRejected() }
        }

        return acquired
    }

    /**
     * Resets all statistics counters.
     */
    suspend fun resetStatistics() {
        atomically {
            totalRequestsVar.write(0L)
            acceptedRequestsVar.write(0L)
            rejectedRequestsVar.write(0L)
        }
    }

    /**
     * Resets the rate limiter to initial state.
     */
    suspend fun reset() {
        atomically {
            tokensVar.write(config.burstCapacity.toDouble())
            lastRefillTimeVar.write(Clock.System.now())
            totalRequestsVar.write(0L)
            acceptedRequestsVar.write(0L)
            rejectedRequestsVar.write(0L)
        }
    }

    /**
     * Refills tokens based on elapsed time.
     * Must be called within an STM transaction.
     */
    private suspend fun refillTokens() {
        atomically {
            val now = Clock.System.now()
            val lastRefillTime = lastRefillTimeVar.read()
            val elapsed = now - lastRefillTime

            if (elapsed > Duration.ZERO) {
                val tokensToAdd = elapsed.inWholeMilliseconds * config.permitsPerSecond / 1000.0
                val currentTokens = tokensVar.read()
                val newTokens = minOf(
                    currentTokens + tokensToAdd,
                    config.burstCapacity.toDouble()
                )
                tokensVar.write(newTokens)
                lastRefillTimeVar.write(now)
            }
        }
    }

    /**
     * Calculates the wait time needed for the specified number of permits.
     */
    private fun calculateWaitTime(permits: Int): kotlin.time.Duration {
        val tokensNeeded = permits.toDouble()
        val millisecondsToWait = (tokensNeeded * 1000.0 / config.permitsPerSecond).toLong()
        return kotlin.time.Duration.parse("${millisecondsToWait}ms")
    }

    private fun notifyListeners(notify: (RateLimiterListener) -> Unit) {
        listeners.forEach { listener ->
            try {
                notify(listener)
            } catch (e: Exception) {
                logger.error(e) { "Error notifying rate limiter listener" }
            }
        }
    }
}

/**
 * Configuration for rate limiter behavior.
 *
 * @property permitsPerSecond Number of permits to add per second (default: 10.0)
 * @property burstCapacity Maximum number of permits that can be accumulated (default: 10)
 */
data class RateLimiterConfig(
    val permitsPerSecond: Double = 10.0,
    val burstCapacity: Int = 10,
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
data class RateLimiterStatistics(
    val availableTokens: Double,
    val totalRequests: Long,
    val acceptedRequests: Long,
    val rejectedRequests: Long,
) {
    val acceptanceRate: Double
        get() = if (totalRequests > 0) {
            acceptedRequests.toDouble() / totalRequests
        } else {
            0.0
        }

    val rejectionRate: Double
        get() = if (totalRequests > 0) {
            rejectedRequests.toDouble() / totalRequests
        } else {
            0.0
        }
}

/**
 * Listener for rate limiter events.
 */
interface RateLimiterListener {
    /**
     * Called when a request is accepted.
     */
    fun onRequestAccepted() {}

    /**
     * Called when a request is rejected due to rate limiting.
     */
    fun onRequestRejected() {}

    /**
     * Called when a request fails.
     */
    fun onRequestFailed(exception: Exception) {}
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
fun rateLimiter(configure: RateLimiterConfigBuilder.() -> Unit): RateLimiter {
    val builder = RateLimiterConfigBuilder()
    builder.configure()
    return RateLimiter(builder.build())
}

/**
 * Builder for rate limiter configuration.
 */
class RateLimiterConfigBuilder {
    var permitsPerSecond: Double = 10.0
    var burstCapacity: Int = 10

    fun build(): RateLimiterConfig {
        return RateLimiterConfig(
            permitsPerSecond = permitsPerSecond,
            burstCapacity = burstCapacity,
        )
    }
}

/**
 * Sliding window rate limiter implementation using Arrow STM.
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
class SlidingWindowRateLimiter(
    private val config: SlidingWindowConfig = SlidingWindowConfig(),
) {
    private val requestTimestampsVar = TVar(emptyList<Instant>())
    private val totalRequestsVar = TVar(0L)
    private val acceptedRequestsVar = TVar(0L)
    private val rejectedRequestsVar = TVar(0L)

    /**
     * Gets the current number of requests in the window.
     */
    suspend fun currentRequests(): Int = atomically {
        cleanupOldRequests()
        requestTimestampsVar.read().size
    }

    /**
     * Gets the current statistics.
     */
    suspend fun statistics(): SlidingWindowStatistics = atomically {
        cleanupOldRequests()
        SlidingWindowStatistics(
            currentRequests = requestTimestampsVar.read().size,
            totalRequests = totalRequestsVar.read(),
            acceptedRequests = acceptedRequestsVar.read(),
            rejectedRequests = rejectedRequestsVar.read(),
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
    suspend fun <T> execute(block: suspend () -> T): T {
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
    suspend fun <T> tryExecute(block: suspend () -> T): T? {
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
    suspend fun tryAcquire(): Boolean {
        return atomically {
            val total = totalRequestsVar.read()
            totalRequestsVar.write(total + 1)
            cleanupOldRequests()

            val currentRequests = requestTimestampsVar.read()
            if (currentRequests.size < config.maxRequests) {
                val now = Clock.System.now()
                requestTimestampsVar.write(currentRequests + now)
                val accepted = acceptedRequestsVar.read()
                acceptedRequestsVar.write(accepted + 1)
                true
            } else {
                val rejected = rejectedRequestsVar.read()
                rejectedRequestsVar.write(rejected + 1)
                false
            }
        }
    }

    /**
     * Resets the rate limiter to initial state.
     */
    suspend fun reset() {
        atomically {
            requestTimestampsVar.write(emptyList())
            totalRequestsVar.write(0L)
            acceptedRequestsVar.write(0L)
            rejectedRequestsVar.write(0L)
        }
    }

    /**
     * Removes requests older than the window duration.
     * Must be called within an STM transaction.
     */
    private suspend fun cleanupOldRequests() {
        atomically {
            val now = Clock.System.now()
            val cutoffTime = now - config.windowDuration
            val currentRequests = requestTimestampsVar.read()
            val validRequests = currentRequests.filter { it > cutoffTime }
            if (validRequests.size != currentRequests.size) {
                requestTimestampsVar.write(validRequests)
            }
        }
    }
}

/**
 * Configuration for sliding window rate limiter.
 *
 * @property maxRequests Maximum number of requests allowed in the window (default: 100)
 * @property windowDuration Duration of the sliding window (default: 1 minute)
 */
data class SlidingWindowConfig(
    val maxRequests: Int = 100,
    val windowDuration: Duration = 60.seconds,
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
data class SlidingWindowStatistics(
    val currentRequests: Int,
    val totalRequests: Long,
    val acceptedRequests: Long,
    val rejectedRequests: Long,
) {
    val acceptanceRate: Double
        get() = if (totalRequests > 0) {
            acceptedRequests.toDouble() / totalRequests
        } else {
            0.0
        }

    val rejectionRate: Double
        get() = if (totalRequests > 0) {
            rejectedRequests.toDouble() / totalRequests
        } else {
            0.0
        }
}

/**
 * Exception thrown when rate limit is exceeded.
 */
class RateLimitExceededException(
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
class RateLimiterRegistry {
    private val limiters = mutableMapOf<String, RateLimiter>()

    /**
     * Gets an existing rate limiter or creates a new one.
     */
    @Synchronized
    fun getOrCreate(
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
    fun get(name: String): RateLimiter? {
        return limiters[name]
    }

    /**
     * Removes a rate limiter from the registry.
     */
    @Synchronized
    fun remove(name: String): RateLimiter? {
        return limiters.remove(name)
    }

    /**
     * Resets all rate limiters in the registry.
     */
    suspend fun resetAll() {
        limiters.values.forEach { it.reset() }
    }

    /**
     * Gets all rate limiter names in the registry.
     */
    fun getNames(): Set<String> {
        return limiters.keys.toSet()
    }

    /**
     * Gets statistics for all rate limiters.
     */
    suspend fun getAllStatistics(): Map<String, RateLimiterStatistics> {
        return limiters.mapValues { (_, limiter) ->
            limiter.statistics()
        }
    }
}