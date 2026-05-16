// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package ro.sorinirmies.arrow.resiliencekit

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Time limiter for enforcing execution timeouts.
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
 * **Basic usage:**
 * ```kotlin
 * val limiter = TimeLimiter(config = TimeLimiterConfig(timeout = 5.seconds))
 *
 * val result = limiter.execute {
 *     callSlowService()
 * } // throws TimeoutCancellationException if > 5s
 * ```
 *
 * **With fallback:**
 * ```kotlin
 * val result = limiter.executeOrFallback(
 *     fallback = { "default" }
 * ) {
 *     callSlowService()
 * }
 * ```
 *
 * **Parallel execution with timeout:**
 * ```kotlin
 * val results = limiter.executeAll(
 *     blocks = listOf(
 *         { callService1() },
 *         { callService2() },
 *         { callService3() }
 *     )
 * ) // returns list with null for timed-out calls
 * ```
 *
 * @param config Configuration for time limiter behavior
 */
public class TimeLimiter(
    private val config: TimeLimiterConfig = TimeLimiterConfig(),
) {
    private val mutex = Mutex()
    private var totalCalls = 0L
    private var successfulCalls = 0L
    private var timedOutCalls = 0L
    private var failedCalls = 0L
    private var totalTimeoutDuration = 0L
    private val listeners = mutableListOf<TimeLimiterListener>()

    /**
     * Adds a listener for time limiter events.
     */
    public fun addListener(listener: TimeLimiterListener) {
        listeners.add(listener)
    }

    /**
     * Gets the current statistics.
     */
    public suspend fun statistics(): TimeLimiterStatistics = mutex.withLock {
        val avgDuration = if (timedOutCalls > 0) {
            Duration.parse("${totalTimeoutDuration / timedOutCalls}ms")
        } else {
            Duration.ZERO
        }

        TimeLimiterStatistics(
            totalCalls = totalCalls,
            successfulCalls = successfulCalls,
            timedOutCalls = timedOutCalls,
            failedCalls = failedCalls,
            averageTimeoutDuration = avgDuration,
        )
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
    public suspend fun <T> execute(
        timeout: Duration? = null,
        block: suspend () -> T,
    ): T {
        val effectiveTimeout = timeout ?: config.timeout

        mutex.withLock { totalCalls++ }

        val startTime = kotlinx.datetime.Clock.System.now()

        return try {
            val result = withTimeout(effectiveTimeout) {
                block()
            }

            val durationMs = (kotlinx.datetime.Clock.System.now() - startTime).inWholeMilliseconds
            mutex.withLock { successfulCalls++ }
            listeners.forEach { it.onSuccess(durationMs) }

            result
        } catch (e: TimeoutCancellationException) {
            val duration = kotlinx.datetime.Clock.System.now() - startTime
            mutex.withLock {
                timedOutCalls++
                totalTimeoutDuration += duration.inWholeMilliseconds
            }
            logger.warn { "Operation timed out after $effectiveTimeout" }
            listeners.forEach { it.onTimeout(effectiveTimeout) }
            throw e
        } catch (e: Exception) {
            mutex.withLock { failedCalls++ }
            listeners.forEach { it.onFailure(e) }
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
    public suspend fun <T> executeOrNull(
        timeout: Duration? = null,
        block: suspend () -> T,
    ): T? {
        val effectiveTimeout = timeout ?: config.timeout

        mutex.withLock { totalCalls++ }

        val startTime = kotlinx.datetime.Clock.System.now()

        return try {
            val result = withTimeoutOrNull(effectiveTimeout) {
                block()
            }

            if (result != null) {
                val durationMs = (kotlinx.datetime.Clock.System.now() - startTime).inWholeMilliseconds
                mutex.withLock { successfulCalls++ }
                listeners.forEach { it.onSuccess(durationMs) }
            } else {
                val duration = kotlinx.datetime.Clock.System.now() - startTime
                mutex.withLock {
                    timedOutCalls++
                    totalTimeoutDuration += duration.inWholeMilliseconds
                }
                logger.warn { "Operation timed out after $effectiveTimeout" }
                listeners.forEach { it.onTimeout(effectiveTimeout) }
            }

            result
        } catch (e: Exception) {
            mutex.withLock { failedCalls++ }
            listeners.forEach { it.onFailure(e) }
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
    public suspend fun <T> executeOrFallback(
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
    public suspend fun <T> executeOrDefault(
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
    public suspend fun <T> executeWithRetry(
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
    public suspend fun <T> executeAll(
        timeout: Duration? = null,
        blocks: List<suspend () -> T>,
    ): List<T?> {
        return coroutineScope {
            blocks.map { block ->
                async {
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
    public suspend fun <T> executeRace(
        timeout: Duration? = null,
        blocks: List<suspend () -> T>,
    ): T {
        require(blocks.isNotEmpty()) { "blocks must not be empty" }

        val effectiveTimeout = timeout ?: config.timeout

        return withTimeout(effectiveTimeout) {
            coroutineScope {
                select<T> {
                    blocks.forEach { block ->
                        async {
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
    public suspend fun resetStatistics() {
        mutex.withLock {
            totalCalls = 0L
            successfulCalls = 0L
            timedOutCalls = 0L
            failedCalls = 0L
            totalTimeoutDuration = 0L
        }
    }
}

/**
 * Configuration for time limiter behavior.
 *
 * @property timeout Default timeout duration (default: 30 seconds)
 */
public data class TimeLimiterConfig(
    public val timeout: Duration = 30.seconds,
) {
    init {
        require(timeout > Duration.ZERO) {
            "timeout must be > 0, but was $timeout"
        }
    }
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
public data class TimeLimiterStatistics(
    public val totalCalls: Long,
    public val successfulCalls: Long,
    public val timedOutCalls: Long,
    public val failedCalls: Long,
    public val averageTimeoutDuration: Duration,
) {
    /** Ratio of successful calls from 0.0 to 1.0. */
    public val successRate: Double
        get() = if (totalCalls > 0) {
            successfulCalls.toDouble() / totalCalls
        } else {
            0.0
        }

    /** Ratio of timed-out calls from 0.0 to 1.0. */
    public val timeoutRate: Double
        get() = if (totalCalls > 0) {
            timedOutCalls.toDouble() / totalCalls
        } else {
            0.0
        }

    /** Ratio of failed calls (non-timeout) from 0.0 to 1.0. */
    public val failureRate: Double
        get() = if (totalCalls > 0) {
            failedCalls.toDouble() / totalCalls
        } else {
            0.0
        }
}

/**
 * Listener for time limiter events.
 */
public interface TimeLimiterListener {
    /**
     * Called when an operation completes successfully.
     *
     * @param durationMs Duration of the operation in milliseconds
     */
    public fun onSuccess(durationMs: Long) {}

    /**
     * Called when an operation times out.
     *
     * @param timeout The timeout duration that was exceeded
     */
    public fun onTimeout(timeout: Duration) {}

    /**
     * Called when an operation fails (not due to timeout).
     *
     * @param exception The exception that occurred
     */
    public fun onFailure(exception: Exception) {}
}

/**
 * Creates a time limiter with DSL-style configuration.
 *
 * Example usage:
 * ```
 * val timeLimiter = timeLimiter {
 *     timeout = 10.seconds
 * }
 * ```
 */
public fun timeLimiter(configure: TimeLimiterConfigBuilder.() -> Unit): TimeLimiter {
    val builder = TimeLimiterConfigBuilder()
    builder.configure()
    return TimeLimiter(builder.build())
}

/**
 * Builder for time limiter configuration.
 */
public class TimeLimiterConfigBuilder {
    /** Default timeout duration for operations. */
    public var timeout: Duration = 30.seconds

    /** Builds the [TimeLimiterConfig] from the current builder state. */
    public fun build(): TimeLimiterConfig {
        return TimeLimiterConfig(
            timeout = timeout,
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
public class TimeLimiterRegistry {
    private val limiters = mutableMapOf<String, TimeLimiter>()

    /**
     * Gets an existing time limiter or creates a new one.
     */
    public fun getOrCreate(
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
    public fun get(name: String): TimeLimiter? {
        return limiters[name]
    }

    /**
     * Removes a time limiter from the registry.
     */
    public fun remove(name: String): TimeLimiter? {
        return limiters.remove(name)
    }

    /**
     * Resets statistics for all time limiters in the registry.
     */
    public suspend fun resetAllStatistics() {
        limiters.values.forEach { it.resetStatistics() }
    }

    /**
     * Gets all time limiter names in the registry.
     */
    public fun getNames(): Set<String> {
        return limiters.keys.toSet()
    }

    /**
     * Gets statistics for all time limiters.
     */
    public suspend fun getAllStatistics(): Map<String, TimeLimiterStatistics> {
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
public suspend fun <T> withTimeLimit(
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
public suspend fun <T> withTimeLimitOrDefault(
    timeout: Duration,
    default: T,
    block: suspend () -> T,
): T {
    return TimeLimiter(TimeLimiterConfig(timeout = timeout))
        .executeOrDefault(default = default, block = block)
}
