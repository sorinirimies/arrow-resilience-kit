// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package ro.sorinirmies.arrow.resiliencekit

import arrow.fx.stm.TVar
import arrow.fx.stm.atomically
import kotlinx.coroutines.sync.Semaphore
import kotlin.time.Duration

/**
 * Configuration for bulkhead behavior.
 */
public data class BulkheadConfig(
    /** Maximum number of concurrent calls allowed. */
    public val maxConcurrentCalls: Int = 10,
    /** Maximum number of calls waiting for a permit. */
    public val maxWaitingCalls: Int = 10,
    /** Maximum duration to wait for a permit, or null for no limit. */
    public val maxWaitDuration: Duration? = null,
) {
    init {
        require(maxConcurrentCalls > 0) {
            "maxConcurrentCalls must be > 0, but was $maxConcurrentCalls"
        }
        require(maxWaitingCalls >= 0) {
            "maxWaitingCalls must be >= 0, but was $maxWaitingCalls"
        }
        require(maxWaitDuration == null || maxWaitDuration > Duration.ZERO) {
            "maxWaitDuration must be > 0 or null, but was $maxWaitDuration"
        }
    }
}

/**
 * Exception thrown when the bulkhead is full.
 */
public class BulkheadFullException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Exception thrown when waiting for a bulkhead permit times out.
 */
public class BulkheadTimeoutException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Statistics tracked by a bulkhead.
 */
public data class BulkheadStatistics(
    /** Total number of calls attempted. */
    public val totalCalls: Long,
    /** Total number of calls that completed successfully. */
    public val successfulCalls: Long,
    /** Total number of calls that failed with an exception. */
    public val failedCalls: Long,
    /** Total number of calls rejected due to capacity limits. */
    public val rejectedCalls: Long,
    /** Number of permits currently available. */
    public val availableCapacity: Int,
    /** Current utilization as a ratio from 0.0 to 1.0. */
    public val utilizationRate: Double,
)

/**
 * Listener interface for bulkhead events.
 */
public interface BulkheadListener {
    /** Called when a call enters the bulkhead. */
    public fun onCallEntered() {}

    /** Called when a call exits the bulkhead. */
    public fun onCallExited() {}

    /** Called when a call completes successfully. */
    public fun onCallSucceeded() {}

    /** Called when a call fails with an exception. */
    public fun onCallFailed(throwable: Throwable) {}

    /** Called when a call is rejected due to capacity limits. */
    public fun onCallRejected() {}
}

/**
 * Bulkhead pattern implementation for concurrency isolation.
 *
 * Limits the number of concurrent calls to protect shared resources from overload.
 * When the maximum number of concurrent calls is reached, additional calls are either
 * queued (up to [BulkheadConfig.maxWaitingCalls]) or rejected immediately.
 *
 * **Basic usage:**
 * ```kotlin
 * val bulkhead = Bulkhead.create(config = BulkheadConfig(maxConcurrentCalls = 5))
 *
 * val result = bulkhead.execute {
 *     callExternalService()
 * }
 * ```
 *
 * **With waiting queue:**
 * ```kotlin
 * val bulkhead = Bulkhead.create(config = BulkheadConfig(
 *     maxConcurrentCalls = 10,
 *     maxWaitingCalls = 20,
 *     maxWaitDuration = 5.seconds
 * ))
 * ```
 *
 * **With fallback:**
 * ```kotlin
 * val result = bulkhead.executeOrFallback(
 *     fallback = { cachedResponse() }
 * ) {
 *     callExternalService()
 * }
 * ```
 *
 * **DSL builder:**
 * ```kotlin
 * val bulkhead = bulkhead {
 *     maxConcurrentCalls = 5
 *     maxWaitingCalls = 10
 *     maxWaitDuration = 3.seconds
 * }
 * ```
 *
 * **With listener:**
 * ```kotlin
 * bulkhead.addListener(object : BulkheadListener {
 *     override fun onCallRejected() {
 *         metrics.incrementRejected()
 *     }
 * })
 * ```
 *
 * **Statistics:**
 * ```kotlin
 * val stats = bulkhead.statistics()
 * println("Total: ${stats.totalCalls}, Rejected: ${stats.rejectedCalls}")
 * println("Utilization: ${stats.utilizationRate * 100}%")
 * ```
 */
public class Bulkhead private constructor(
    public val config: BulkheadConfig,
    private val semaphore: Semaphore,
    private val activeCallsVar: TVar<Int>,
    private val waitingCallsVar: TVar<Int>,
    private val totalCallsVar: TVar<Long>,
    private val successfulCallsVar: TVar<Long>,
    private val failedCallsVar: TVar<Long>,
    private val rejectedCallsVar: TVar<Long>,
    internal val clock: kotlinx.datetime.Clock = kotlinx.datetime.Clock.System,
) {

    private val listeners = mutableListOf<BulkheadListener>()

    public companion object {
        /**
         * Creates a new [Bulkhead] instance with the given configuration.
         * @param config bulkhead configuration
         * @param clock clock used for timing (defaults to system clock)
         * @return a new [Bulkhead] instance
         */
        public suspend fun create(
            config: BulkheadConfig = BulkheadConfig(),
            clock: kotlinx.datetime.Clock = kotlinx.datetime.Clock.System,
        ): Bulkhead = Bulkhead(
            config = config,
            semaphore = Semaphore(config.maxConcurrentCalls),
            activeCallsVar = TVar.new(0),
            waitingCallsVar = TVar.new(0),
            totalCallsVar = TVar.new(0L),
            successfulCallsVar = TVar.new(0L),
            failedCallsVar = TVar.new(0L),
            rejectedCallsVar = TVar.new(0L),
            clock = clock,
        )
    }

    /**
     * Adds a listener for bulkhead events.
     * @param listener the listener to add
     */
    public fun addListener(listener: BulkheadListener) {
        listeners.add(listener)
    }

    /** Returns the current number of active calls. */
    public suspend fun activeCalls(): Int = atomically { activeCallsVar.read() }

    /** Returns a snapshot of the current bulkhead statistics. */
    public suspend fun statistics(): BulkheadStatistics = atomically {
        val active = activeCallsVar.read()
        BulkheadStatistics(
            totalCalls = totalCallsVar.read(),
            successfulCalls = successfulCallsVar.read(),
            failedCalls = failedCallsVar.read(),
            rejectedCalls = rejectedCallsVar.read(),
            availableCapacity = config.maxConcurrentCalls - active,
            utilizationRate = active.toDouble() / config.maxConcurrentCalls,
        )
    }

    /** Resets all statistics counters to zero. */
    public suspend fun resetStatistics(): Unit = atomically {
        totalCallsVar.write(0L)
        successfulCallsVar.write(0L)
        failedCallsVar.write(0L)
        rejectedCallsVar.write(0L)
    }

    /**
     * Executes an operation through the bulkhead.
     * @param block the operation to execute
     * @return the result of the operation
     * @throws BulkheadFullException if the bulkhead is full and cannot accept the call
     * @throws BulkheadTimeoutException if waiting for a permit times out
     */
    public suspend fun <T> execute(block: suspend () -> T): T {
        // Check if we can accept this call (waiting queue check)
        val canAccept = atomically {
            val active = activeCallsVar.read()
            val waiting = waitingCallsVar.read()
            if (active < config.maxConcurrentCalls || waiting < config.maxWaitingCalls) {
                waitingCallsVar.write(waiting + 1)
                true
            } else {
                false
            }
        }

        if (!canAccept) {
            atomically {
                totalCallsVar.write(totalCallsVar.read() + 1)
                rejectedCallsVar.write(rejectedCallsVar.read() + 1)
            }
            listeners.toList().forEach {
                try {
                    it.onCallRejected()
                } catch (_: Exception) {
                }
            }
            throw BulkheadFullException("Bulkhead is full. Max concurrent: ${config.maxConcurrentCalls}, Max waiting: ${config.maxWaitingCalls}")
        }

        // Acquire semaphore permit (with optional timeout)
        if (config.maxWaitDuration != null) {
            val acquired = kotlinx.coroutines.withTimeoutOrNull(config.maxWaitDuration) {
                semaphore.acquire()
                true
            } ?: false
            if (!acquired) {
                atomically {
                    waitingCallsVar.write(waitingCallsVar.read() - 1)
                    totalCallsVar.write(totalCallsVar.read() + 1)
                    rejectedCallsVar.write(rejectedCallsVar.read() + 1)
                }
                listeners.toList().forEach {
                    try {
                        it.onCallRejected()
                    } catch (_: Exception) {
                    }
                }
                throw BulkheadTimeoutException("Timeout waiting for bulkhead permit after ${config.maxWaitDuration}")
            }
        } else {
            semaphore.acquire()
        }

        // Move from waiting to active
        atomically {
            waitingCallsVar.write(waitingCallsVar.read() - 1)
            activeCallsVar.write(activeCallsVar.read() + 1)
            totalCallsVar.write(totalCallsVar.read() + 1)
        }

        listeners.toList().forEach {
            try {
                it.onCallEntered()
            } catch (_: Exception) {
            }
        }

        return try {
            val result = block()
            atomically { successfulCallsVar.write(successfulCallsVar.read() + 1) }
            listeners.toList().forEach {
                try {
                    it.onCallSucceeded()
                } catch (_: Exception) {
                }
            }
            result
        } catch (e: Throwable) {
            atomically { failedCallsVar.write(failedCallsVar.read() + 1) }
            listeners.toList().forEach {
                try {
                    it.onCallFailed(e)
                } catch (_: Exception) {
                }
            }
            throw e
        } finally {
            atomically {
                val active = activeCallsVar.read()
                if (active > 0) activeCallsVar.write(active - 1)
            }
            semaphore.release()
            listeners.toList().forEach {
                try {
                    it.onCallExited()
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Tries to execute an operation, returning null if the bulkhead is full or times out.
     * @param block the operation to execute
     * @return the result of the operation or null if rejected
     */
    public suspend fun <T> tryExecute(block: suspend () -> T): T? {
        return try {
            execute(block)
        } catch (e: BulkheadFullException) {
            null
        } catch (e: BulkheadTimeoutException) {
            null
        }
    }

    /**
     * Executes an operation with a fallback if the bulkhead is full or times out.
     * @param fallback the fallback function to call if rejected
     * @param block the primary operation to execute
     * @return the result of the operation or fallback
     */
    public suspend fun <T> executeOrFallback(fallback: suspend () -> T, block: suspend () -> T): T {
        return try {
            execute(block)
        } catch (e: BulkheadFullException) {
            fallback()
        } catch (e: BulkheadTimeoutException) {
            fallback()
        }
    }
}

/**
 * Registry for managing named bulkhead instances.
 */
public class BulkheadRegistry private constructor(
    private val bulkheadsVar: TVar<Map<String, Bulkhead>>,
) {

    public companion object {
        /**
         * Creates a new empty [BulkheadRegistry].
         */
        public suspend fun create(): BulkheadRegistry = BulkheadRegistry(TVar.new(emptyMap()))
    }

    /**
     * Gets or creates a bulkhead with the given name.
     * @param name unique identifier for the bulkhead
     * @param configure optional configuration block
     * @return the existing or newly created bulkhead
     */
    public suspend fun getOrCreate(name: String, configure: BulkheadConfigBuilder.() -> Unit = {}): Bulkhead {
        val existing = atomically { bulkheadsVar.read()[name] }
        if (existing != null) return existing
        val config = BulkheadConfigBuilder().apply(configure).build()
        val bulkhead = Bulkhead.create(config)
        atomically { bulkheadsVar.write(bulkheadsVar.read() + (name to bulkhead)) }
        return bulkhead
    }

    /**
     * Gets an existing bulkhead by name.
     * @param name the bulkhead name
     * @return the bulkhead or null if not found
     */
    public suspend fun get(name: String): Bulkhead? = atomically { bulkheadsVar.read()[name] }

    /**
     * Removes a bulkhead from the registry.
     * @param name the bulkhead name
     * @return the removed bulkhead or null if not found
     */
    public suspend fun remove(name: String): Bulkhead? = atomically {
        val map = bulkheadsVar.read()
        val removed = map[name]
        bulkheadsVar.write(map - name)
        removed
    }

    /** Returns the names of all registered bulkheads. */
    public suspend fun getNames(): Set<String> = atomically { bulkheadsVar.read().keys }

    /** Resets statistics for all registered bulkheads. */
    public suspend fun resetAllStatistics() {
        val snapshot = atomically { bulkheadsVar.read().values.toList() }
        snapshot.forEach { it.resetStatistics() }
    }

    /** Returns statistics for all registered bulkheads, keyed by name. */
    public suspend fun getAllStatistics(): Map<String, BulkheadStatistics> {
        val snapshot = atomically { bulkheadsVar.read().toMap() }
        return snapshot.mapValues { (_, bulkhead) -> bulkhead.statistics() }
    }
}

// --- Free functions (kept for backward compatibility) ---

/**
 * Executes an operation with bulkhead protection.
 * @param config bulkhead configuration
 * @param block the operation to execute
 * @return the result of the operation
 */
public suspend fun <T> withBulkhead(
    config: BulkheadConfig = BulkheadConfig(),
    block: suspend () -> T,
): T {
    return Bulkhead.create(config).execute(block)
}

/**
 * Tries to execute an operation with bulkhead protection, returning null if rejected.
 * @param config bulkhead configuration
 * @param block the operation to execute
 * @return the result of the operation or null if rejected
 */
public suspend fun <T> tryWithBulkhead(
    config: BulkheadConfig = BulkheadConfig(),
    block: suspend () -> T,
): T? {
    return try {
        withBulkhead(config, block)
    } catch (e: BulkheadFullException) {
        null
    } catch (e: BulkheadTimeoutException) {
        null
    }
}

/**
 * Executes an operation with bulkhead protection and a fallback for rejection.
 * @param config bulkhead configuration
 * @param fallback the fallback function called with the rejection exception
 * @param block the primary operation to execute
 * @return the result of the operation or fallback
 */
public suspend fun <T> withBulkheadOrFallback(
    config: BulkheadConfig = BulkheadConfig(),
    fallback: suspend (Exception) -> T,
    block: suspend () -> T,
): T {
    return try {
        withBulkhead(config, block)
    } catch (e: BulkheadFullException) {
        fallback(e)
    } catch (e: BulkheadTimeoutException) {
        fallback(e)
    }
}

/**
 * DSL for creating a configured Bulkhead instance.
 */
public suspend fun bulkhead(configure: BulkheadConfigBuilder.() -> Unit): Bulkhead {
    val config = BulkheadConfigBuilder().apply(configure).build()
    return Bulkhead.create(config)
}

/**
 * Builder for bulkhead configuration.
 */
public class BulkheadConfigBuilder {
    /** Maximum number of concurrent calls allowed. */
    public var maxConcurrentCalls: Int = 10

    /** Maximum number of calls waiting for a permit. */
    public var maxWaitingCalls: Int = 10

    /** Maximum duration to wait for a permit, or null for no limit. */
    public var maxWaitDuration: Duration? = null

    /** Builds the [BulkheadConfig] from the current builder state. */
    public fun build(): BulkheadConfig {
        return BulkheadConfig(
            maxConcurrentCalls = maxConcurrentCalls,
            maxWaitingCalls = maxWaitingCalls,
            maxWaitDuration = maxWaitDuration,
        )
    }
}
