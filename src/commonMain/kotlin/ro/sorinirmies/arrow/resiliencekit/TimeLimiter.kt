// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package ro.sorinirmies.arrow.resiliencekit

import arrow.fx.stm.TVar
import arrow.fx.stm.atomically
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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
 * val limiter = TimeLimiter.create(config = TimeLimiterConfig(timeout = 5.seconds))
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
public class TimeLimiter private constructor(
    private val config: TimeLimiterConfig,
    private val totalCalls: TVar<Long>,
    private val successfulCalls: TVar<Long>,
    private val timedOutCalls: TVar<Long>,
    private val failedCalls: TVar<Long>,
    private val totalTimeoutDuration: TVar<Long>,
) {
    private val listeners = mutableListOf<TimeLimiterListener>()

    public companion object {
        /**
         * Creates a new [TimeLimiter] instance.
         */
        public suspend fun create(
            config: TimeLimiterConfig = TimeLimiterConfig(),
        ): TimeLimiter {
            val totalCalls = TVar.new(0L)
            val successfulCalls = TVar.new(0L)
            val timedOutCalls = TVar.new(0L)
            val failedCalls = TVar.new(0L)
            val totalTimeoutDuration = TVar.new(0L)
            return TimeLimiter(config, totalCalls, successfulCalls, timedOutCalls, failedCalls, totalTimeoutDuration)
        }
    }

    /**
     * Adds a listener for time limiter events.
     */
    public fun addListener(listener: TimeLimiterListener) {
        listeners.add(listener)
    }

    /**
     * Gets the current statistics.
     */
    public suspend fun statistics(): TimeLimiterStatistics = atomically {
        val timedOut = timedOutCalls.read()
        val totalDuration = totalTimeoutDuration.read()
        val avgDuration = if (timedOut > 0) {
            (totalDuration / timedOut).milliseconds
        } else {
            Duration.ZERO
        }

        TimeLimiterStatistics(
            totalCalls = totalCalls.read(),
            successfulCalls = successfulCalls.read(),
            timedOutCalls = timedOut,
            failedCalls = failedCalls.read(),
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

        atomically { totalCalls.write(totalCalls.read() + 1) }

        val startTime = kotlinx.datetime.Clock.System.now()

        return try {
            val result = withTimeout(effectiveTimeout) {
                block()
            }

            val durationMs = (kotlinx.datetime.Clock.System.now() - startTime).inWholeMilliseconds
            atomically { successfulCalls.write(successfulCalls.read() + 1) }
            listeners.toList().forEach {
                try {
                    it.onSuccess(durationMs)
                } catch (_: Exception) {
                }
            }

            result
        } catch (e: TimeoutCancellationException) {
            val duration = kotlinx.datetime.Clock.System.now() - startTime
            atomically {
                timedOutCalls.write(timedOutCalls.read() + 1)
                totalTimeoutDuration.write(totalTimeoutDuration.read() + duration.inWholeMilliseconds)
            }
            logger.warn { "Operation timed out after $effectiveTimeout" }
            listeners.toList().forEach {
                try {
                    it.onTimeout(effectiveTimeout)
                } catch (_: Exception) {
                }
            }
            throw e
        } catch (e: Exception) {
            atomically { failedCalls.write(failedCalls.read() + 1) }
            listeners.toList().forEach {
                try {
                    it.onFailure(e)
                } catch (_: Exception) {
                }
            }
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

        atomically { totalCalls.write(totalCalls.read() + 1) }

        val startTime = kotlinx.datetime.Clock.System.now()

        return try {
            val result = withTimeoutOrNull(effectiveTimeout) {
                block()
            }

            if (result != null) {
                val durationMs = (kotlinx.datetime.Clock.System.now() - startTime).inWholeMilliseconds
                atomically { successfulCalls.write(successfulCalls.read() + 1) }
                listeners.toList().forEach {
                    try {
                        it.onSuccess(durationMs)
                    } catch (_: Exception) {
                    }
                }
            } else {
                val duration = kotlinx.datetime.Clock.System.now() - startTime
                atomically {
                    timedOutCalls.write(timedOutCalls.read() + 1)
                    totalTimeoutDuration.write(totalTimeoutDuration.read() + duration.inWholeMilliseconds)
                }
                logger.warn { "Operation timed out after $effectiveTimeout" }
                listeners.toList().forEach {
                    try {
                        it.onTimeout(effectiveTimeout)
                    } catch (_: Exception) {
                    }
                }
            }

            result
        } catch (e: Exception) {
            atomically { failedCalls.write(failedCalls.read() + 1) }
            listeners.toList().forEach {
                try {
                    it.onFailure(e)
                } catch (_: Exception) {
                }
            }
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
        atomically {
            totalCalls.write(0L)
            successfulCalls.write(0L)
            timedOutCalls.write(0L)
            failedCalls.write(0L)
            totalTimeoutDuration.write(0L)
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
public suspend fun timeLimiter(configure: TimeLimiterConfigBuilder.() -> Unit): TimeLimiter {
    val builder = TimeLimiterConfigBuilder()
    builder.configure()
    return TimeLimiter.create(builder.build())
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
 * val registry = TimeLimiterRegistry.create()
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
public class TimeLimiterRegistry private constructor(
    private val limiters: TVar<Map<String, TimeLimiter>>,
) {
    public companion object {
        /**
         * Creates a new [TimeLimiterRegistry] instance.
         */
        public suspend fun create(): TimeLimiterRegistry {
            val limiters = TVar.new(emptyMap<String, TimeLimiter>())
            return TimeLimiterRegistry(limiters)
        }
    }

    /**
     * Gets an existing time limiter or creates a new one.
     */
    public suspend fun getOrCreate(
        name: String,
        configure: (TimeLimiterConfigBuilder.() -> Unit)? = null,
    ): TimeLimiter {
        // First check if it already exists
        val existing = atomically { limiters.read()[name] }
        if (existing != null) return existing

        // Create outside the transaction, then atomically insert if still absent
        val newLimiter = if (configure != null) {
            timeLimiter(configure)
        } else {
            TimeLimiter.create()
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
     * Gets an existing time limiter by name.
     */
    public suspend fun get(name: String): TimeLimiter? = atomically { limiters.read()[name] }

    /**
     * Removes a time limiter from the registry.
     */
    public suspend fun remove(name: String): TimeLimiter? = atomically {
        val current = limiters.read()
        val removed = current[name]
        if (removed != null) {
            limiters.write(current - name)
        }
        removed
    }

    /**
     * Resets statistics for all time limiters in the registry.
     */
    public suspend fun resetAllStatistics() {
        val snapshot = atomically { limiters.read().values.toList() }
        snapshot.forEach { it.resetStatistics() }
    }

    /**
     * Gets all time limiter names in the registry.
     */
    public suspend fun getNames(): Set<String> = atomically { limiters.read().keys }

    /**
     * Gets statistics for all time limiters.
     */
    public suspend fun getAllStatistics(): Map<String, TimeLimiterStatistics> {
        val snapshot = atomically { limiters.read() }
        return snapshot.mapValues { (_, limiter) ->
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
    return TimeLimiter.create(TimeLimiterConfig(timeout = timeout)).execute(block = block)
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
    return TimeLimiter.create(TimeLimiterConfig(timeout = timeout))
        .executeOrDefault(default = default, block = block)
}
