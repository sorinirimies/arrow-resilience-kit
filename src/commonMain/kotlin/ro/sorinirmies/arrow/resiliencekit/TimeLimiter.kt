// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies


import arrow.fx.stm.TVar
import arrow.fx.stm.atomically
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Time limiter pattern implementation for timeout handling with Arrow STM.
 *
 * The Time Limiter pattern ensures that operations complete within a specified
 * time limit. It provides various strategies for handling timeouts including
 * fallbacks, retries, and statistics tracking.
 *
 * **Use Cases:**
 * - Prevent operations from hanging indefinitely
 * - Enforce SLA requirements
 * - Detect slow or unresponsive services
 * - Set deadlines for operations
 *
 * @param config Configuration for time limiter behavior
 *
 * Example usage:
 * ```
 * val timeLimiter = TimeLimiter(
 *     config = TimeLimiterConfig(
 *         timeout = 5.seconds,
 *         onTimeout = TimeoutStrategy.THROW
 *     )
 * )
 *
 * val result = timeLimiter.execute {
 *     slowOperation()
 * }
 * ```
 */
class TimeLimiter(
    private val config: TimeLimiterConfig = TimeLimiterConfig(),
) {
    private val totalCallsVar: TVar<Long> = runBlocking { TVar.new(0L) }
    private val successfulCallsVar: TVar<Long> = runBlocking { TVar.new(0L) }
    private val timedOutCallsVar: TVar<Long> = runBlocking { TVar.new(0L) }
    private val failedCallsVar: TVar<Long> = runBlocking { TVar.new(0L) }
    private val totalTimeoutDurationVar: TVar<Long> = runBlocking { TVar.new(0L) } // in milliseconds

    /**
     * Gets the current statistics.
     */
    suspend fun statistics(): TimeLimiterStatistics = atomically {
        val timedOut = timedOutCallsVar.read()
        val avgDuration = if (timedOut > 0) {
            Duration.parse("${totalTimeoutDurationVar.read() / timedOut}ms")
        } else {
            Duration.ZERO
        }
        
        TimeLimiterStatistics(
            totalCalls = totalCallsVar.read(),
            successfulCalls = successfulCallsVar.read(),
            timedOutCalls = timedOut,
            failedCalls = failedCallsVar.read(),
            averageTimeoutDuration = avgDuration,
        )
    }

    /**
     * Adds a listener to be notified of time limiter events.
     */
    fun addListener(listener: TimeLimiterListener) {
        listeners.add(listener)
    }

    /**
     * Removes a listener.
     */
    fun removeListener(listener: TimeLimiterListener) {
        listeners.remove(listener)
    }

    /**
     * Executes an operation with a timeout.
     *
     * @param timeout Optional custom timeout (overrides config timeout)
     * @param block The operation to execute
     * @return The result of the operation
     * @throws TimeoutCancellationException if the operation times out
     * @throws Exception if the operation fails
     */
    suspend fun <T> execute(
        timeout: Duration? = null,
        block: suspend () -> T,
    ): T {
        val effectiveTimeout = timeout ?: config.timeout

        atomically {
            val total = totalCallsVar.read()
            totalCallsVar.write(total + 1)
        }

        val startTime = kotlinx.datetime.Clock.System.now()

        return try {
            val result = withTimeout(effectiveTimeout) {
                block()
            }

            val duration = kotlinx.datetime.Clock.System.now() - startTime
            atomically {
                val successful = successfulCallsVar.read()
                successfulCallsVar.write(successful + 1)
            }
            notifyListeners { it.onSuccess(duration.inWholeMilliseconds) }

            result
        } catch (e: TimeoutCancellationException) {
            val duration = kotlinx.datetime.Clock.System.now() - startTime
            atomically {
                val timedOut = timedOutCallsVar.read()
                timedOutCallsVar.write(timedOut + 1)
                val totalDuration = totalTimeoutDurationVar.read()
                totalTimeoutDurationVar.write(totalDuration + duration.inWholeMilliseconds)
            }
            logger.warn { "Operation timed out after $effectiveTimeout" }
            notifyListeners { it.onTimeout(effectiveTimeout) }
            throw e
        } catch (e: Exception) {
            atomically {
                val failed = failedCallsVar.read()
                failedCallsVar.write(failed + 1)
            }
            notifyListeners { it.onFailure(e) }
            throw e
        }
    }

    /**
     * Executes an operation with a timeout, returning null if it times out.
     *
     * @param timeout Optional custom timeout (overrides config timeout)
     * @param block The operation to execute
     * @return The result of the operation or null if it times out
     * @throws Exception if the operation fails (not timeout)
     */
    suspend fun <T> executeOrNull(
        timeout: Duration? = null,
        block: suspend () -> T,
    ): T? {
        val effectiveTimeout = timeout ?: config.timeout

        atomically {
            val total = totalCallsVar.read()
            totalCallsVar.write(total + 1)
        }

        val startTime = kotlinx.datetime.Clock.System.now()

        return try {
            val result = withTimeoutOrNull(effectiveTimeout) {
                block()
            }

            if (result != null) {
                val duration = kotlinx.datetime.Clock.System.now() - startTime
                atomically {
                    val successful = successfulCallsVar.read()
                    successfulCallsVar.write(successful + 1)
                }
                notifyListeners { it.onSuccess(duration.inWholeMilliseconds) }
            } else {
                val duration = kotlinx.datetime.Clock.System.now() - startTime
                atomically {
                    val timedOut = timedOutCallsVar.read()
                    timedOutCallsVar.write(timedOut + 1)
                    val totalDuration = totalTimeoutDurationVar.read()
                    totalTimeoutDurationVar.write(totalDuration + duration.inWholeMilliseconds)
                }
                logger.warn { "Operation timed out after $effectiveTimeout" }
                notifyListeners { it.onTimeout(effectiveTimeout) }
            }

            result
        } catch (e: Exception) {
            atomically {
                val failed = failedCallsVar.read()
                failedCallsVar.write(failed + 1)
            }
            notifyListeners { it.onFailure(e) }
            throw e
        }
    }

    /**
     * Executes an operation with a timeout and fallback.
     *
     * @param timeout Optional custom timeout (overrides config timeout)
     * @param fallback The fallback function to call if the operation times out
     * @param block The primary operation to execute
     * @return The result of the operation or fallback
     */
    suspend fun <T> executeOrFallback(
        timeout: Duration? = null,
        fallback: suspend (TimeoutCancellationException) -> T,
        block: suspend () -> T,
    ): T {
        return try {
            execute(timeout, block)
        } catch (e: TimeoutCancellationException) {
            logger.debug { "Operation timed out, using fallback" }
            fallback(e)
        }
    }

    /**
     * Executes an operation with a timeout and default value.
     *
     * @param timeout Optional custom timeout (overrides config timeout)
     * @param default The default value to return if the operation times out
     * @param block The operation to execute
     * @return The result of the operation or default value
     */
    suspend fun <T> executeOrDefault(
        timeout: Duration? = null,
        default: T,
        block: suspend () -> T,
    ): T {
        return executeOrNull(timeout, block) ?: default
    }

    /**
     * Executes an operation with a timeout and automatic retry on timeout.
     *
     * @param timeout Optional custom timeout (overrides config timeout)
     * @param retries Number of retry attempts on timeout (default: 3)
     * @param block The operation to execute
     * @return The result of the operation
     * @throws TimeoutCancellationException if all attempts time out
     * @throws Exception if the operation fails
     */
    suspend fun <T> executeWithRetry(
        timeout: Duration? = null,
        retries: Int = 3,
        block: suspend () -> T,
    ): T {
        require(retries >= 0) { "retries must be >= 0, but was $retries" }

        var lastException: TimeoutCancellationException? = null
        var attempt = 0

        while (attempt <= retries) {
            try {
                return execute(timeout, block)
            } catch (e: TimeoutCancellationException) {
                lastException = e
                if (attempt < retries) {
                    logger.debug { "Timeout on attempt ${attempt + 1}/$retries, retrying..." }
                }
                attempt++
            }
        }

        throw lastException!!
    }

    /**
     * Executes multiple operations with individual timeouts.
     *
     * Returns results for operations that complete within their timeouts.
     * Failed or timed-out operations are represented as null.
     *
     * @param timeout Timeout for each individual operation
     * @param blocks List of operations to execute
     * @return List of results (null for failed/timed-out operations)
     */
    suspend fun <T> executeAll(
        timeout: Duration? = null,
        blocks: List<suspend () -> T>,
    ): List<T?> {
        return kotlinx.coroutines.coroutineScope {
            blocks.map { block ->
                kotlinx.coroutines.async {
                    executeOrNull(timeout, block)
                }
            }.map { it.await() }
        }
    }

    /**
     * Executes the first operation that completes within the timeout.
     *
     * @param timeout Timeout for the race
     * @param blocks List of operations to race
     * @return The result of the first completing operation
     * @throws TimeoutCancellationException if all operations time out
     */
    suspend fun <T> executeRace(
        timeout: Duration? = null,
        blocks: List<suspend () -> T>,
    ): T {
        require(blocks.isNotEmpty()) { "blocks must not be empty" }

        val effectiveTimeout = timeout ?: config.timeout

        return withTimeout(effectiveTimeout) {
            kotlinx.coroutines.coroutineScope {
                kotlinx.coroutines.selects.select<T> {
                    blocks.forEach { block ->
                        kotlinx.coroutines.async {
                            block()
                        }.onAwait { it }
                    }
                }
            }
        }
    }

    /**
     * Resets all statistics counters.
     */
    suspend fun resetStatistics() {
        atomically {
            totalCallsVar.write(0L)
            successfulCallsVar.write(0L)
            timedOutCallsVar.write(0L)
            failedCallsVar.write(0L)
            totalTimeoutDurationVar.write(0L)
        }
    }

    private fun notifyListeners(notify: (TimeLimiterListener) -> Unit) {
        listeners.forEach { listener ->
            try {
                notify(listener)
            } catch (e: Exception) {
                logger.error(e) { "Error notifying time limiter listener" }
            }
        }
    }
}

/**
 * Configuration for time limiter behavior.
 *
 * @property timeout Default timeout duration (default: 30 seconds)
 * @property onTimeout Strategy for handling timeouts (default: THROW)
 */
data class TimeLimiterConfig(
    val timeout: Duration = 30.seconds,
    val onTimeout: TimeoutStrategy = TimeoutStrategy.THROW,
) {
    init {
        require(timeout > Duration.ZERO) {
            "timeout must be > 0, but was $timeout"
        }
    }
}

/**
 * Strategy for handling timeouts.
 */
enum class TimeoutStrategy {
    /**
     * Throw TimeoutCancellationException (default behavior).
     */
    THROW,

    /**
     * Return null when timeout occurs.
     */
    RETURN_NULL,

    /**
     * Use a fallback value when timeout occurs.
     */
    FALLBACK,
}

/**
 * Statistics for a time limiter.
 *
 * @property totalCalls Total number of calls attempted
 * @property successfulCalls Total number of successful calls
 * @property timedOutCalls Total number of timed-out calls
 * @property failedCalls Total number of failed calls
 * @property averageTimeoutDuration Average duration of timed-out calls
 */
data class TimeLimiterStatistics(
    val totalCalls: Long,
    val successfulCalls: Long,
    val timedOutCalls: Long,
    val failedCalls: Long,
    val averageTimeoutDuration: Duration,
) {
    val successRate: Double
        get() = if (totalCalls > 0) {
            successfulCalls.toDouble() / totalCalls
        } else {
            0.0
        }

    val timeoutRate: Double
        get() = if (totalCalls > 0) {
            timedOutCalls.toDouble() / totalCalls
        } else {
            0.0
        }

    val failureRate: Double
        get() = if (totalCalls > 0) {
            failedCalls.toDouble() / totalCalls
        } else {
            0.0
        }
}

/**
 * Listener for time limiter events.
 */
interface TimeLimiterListener {
    /**
     * Called when an operation completes successfully.
     *
     * @param durationMs Duration of the operation in milliseconds
     */
    fun onSuccess(durationMs: Long) {}

    /**
     * Called when an operation times out.
     *
     * @param timeout The timeout duration that was exceeded
     */
    fun onTimeout(timeout: Duration) {}

    /**
     * Called when an operation fails (not due to timeout).
     *
     * @param exception The exception that occurred
     */
    fun onFailure(exception: Exception) {}
}

/**
 * Creates a time limiter with DSL-style configuration.
 *
 * Example usage:
 * ```
 * val timeLimiter = timeLimiter {
 *     timeout = 10.seconds
 *     onTimeout = TimeoutStrategy.RETURN_NULL
 * }
 * ```
 */
fun timeLimiter(configure: TimeLimiterConfigBuilder.() -> Unit): TimeLimiter {
    val builder = TimeLimiterConfigBuilder()
    builder.configure()
    return TimeLimiter(builder.build())
}

/**
 * Builder for time limiter configuration.
 */
class TimeLimiterConfigBuilder {
    var timeout: Duration = 30.seconds
    var onTimeout: TimeoutStrategy = TimeoutStrategy.THROW

    fun build(): TimeLimiterConfig {
        return TimeLimiterConfig(
            timeout = timeout,
            onTimeout = onTimeout,
        )
    }
}

/**
 * Registry for managing multiple named time limiters.
 *
 * Example usage:
 * ```
 * val registry = TimeLimiterRegistry()
 *
 * val fastLimiter = registry.getOrCreate("fast-operations") {
 *     timeout = 1.seconds
 * }
 *
 * val slowLimiter = registry.getOrCreate("slow-operations") {
 *     timeout = 30.seconds
 * }
 * ```
 */
class TimeLimiterRegistry {
    private val limiters = mutableMapOf<String, TimeLimiter>()

    /**
     * Gets an existing time limiter or creates a new one.
     */
    fun getOrCreate(
        name: String,
        configure: (TimeLimiterConfigBuilder.() -> Unit)? = null,
    ): TimeLimiter {
        return limiters.getOrPut(name) {
            if (configure != null) {
                timeLimiter(configure)
            } else {
                TimeLimiter()
            }
        }
    }

    /**
     * Gets an existing time limiter by name.
     */
    fun get(name: String): TimeLimiter? {
        return limiters[name]
    }

    /**
     * Removes a time limiter from the registry.
     */
    fun remove(name: String): TimeLimiter? {
        return limiters.remove(name)
    }

    /**
     * Resets statistics for all time limiters in the registry.
     */
    suspend fun resetAllStatistics() {
        limiters.values.forEach { it.resetStatistics() }
    }

    /**
     * Gets all time limiter names in the registry.
     */
    fun getNames(): Set<String> {
        return limiters.keys.toSet()
    }

    /**
     * Gets statistics for all time limiters.
     */
    suspend fun getAllStatistics(): Map<String, TimeLimiterStatistics> {
        return limiters.mapValues { (_, limiter) ->
            limiter.statistics()
        }
    }
}

/**
 * Extension function to add timeout to any suspend function.
 *
 * Example usage:
 * ```
 * val result = withTimeLimit(5.seconds) {
 *     slowOperation()
 * }
 * ```
 */
suspend fun <T> withTimeLimit(
    timeout: Duration,
    block: suspend () -> T,
): T {
    return TimeLimiter(TimeLimiterConfig(timeout = timeout)).execute(block = block)
}

/**
 * Extension function to add timeout with fallback to any suspend function.
 *
 * Example usage:
 * ```
 * val result = withTimeLimitOrDefault(5.seconds, defaultValue = emptyList()) {
 *     slowOperation()
 * }
 * ```
 */
suspend fun <T> withTimeLimitOrDefault(
    timeout: Duration,
    default: T,
    block: suspend () -> T,
): T {
    return TimeLimiter(TimeLimiterConfig(timeout = timeout))
        .executeOrDefault(default = default, block = block)
}