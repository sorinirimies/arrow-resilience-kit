// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package ro.sorinirmies.arrow.resiliencekit

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import kotlin.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Configuration for bulkhead behavior.
 */
public data class BulkheadConfig(
    public val maxConcurrentCalls: Int = 10,
    public val maxWaitingCalls: Int = 10,
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
    public val totalCalls: Long,
    public val successfulCalls: Long,
    public val failedCalls: Long,
    public val rejectedCalls: Long,
    public val availableCapacity: Int,
    public val utilizationRate: Double,
)

/**
 * Listener interface for bulkhead events.
 */
public interface BulkheadListener {
    public fun onCallEntered() {}
    public fun onCallExited() {}
    public fun onCallSucceeded() {}
    public fun onCallFailed(throwable: Throwable) {}
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
 * val bulkhead = Bulkhead(config = BulkheadConfig(maxConcurrentCalls = 5))
 *
 * val result = bulkhead.execute {
 *     callExternalService()
 * }
 * ```
 *
 * **With waiting queue:**
 * ```kotlin
 * val bulkhead = Bulkhead(config = BulkheadConfig(
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
public class Bulkhead(public val config: BulkheadConfig = BulkheadConfig()) {

    private val semaphore = Semaphore(config.maxConcurrentCalls)
    private val mutex = Mutex()
    private var activeCalls = 0
    private var waitingCalls = 0
    private var totalCalls = 0L
    private var successfulCalls = 0L
    private var failedCalls = 0L
    private var rejectedCalls = 0L
    private val listeners = mutableListOf<BulkheadListener>()

    public fun addListener(listener: BulkheadListener) {
        listeners.add(listener)
    }

    public suspend fun activeCalls(): Int = mutex.withLock { activeCalls }

    public suspend fun statistics(): BulkheadStatistics = mutex.withLock {
        BulkheadStatistics(
            totalCalls = totalCalls,
            successfulCalls = successfulCalls,
            failedCalls = failedCalls,
            rejectedCalls = rejectedCalls,
            availableCapacity = config.maxConcurrentCalls - activeCalls,
            utilizationRate = activeCalls.toDouble() / config.maxConcurrentCalls,
        )
    }

    public suspend fun resetStatistics(): Unit = mutex.withLock {
        totalCalls = 0L
        successfulCalls = 0L
        failedCalls = 0L
        rejectedCalls = 0L
    }

    public suspend fun <T> execute(block: suspend () -> T): T {
        // Check if we can accept this call (waiting queue check)
        val canAccept = mutex.withLock {
            if (activeCalls < config.maxConcurrentCalls || waitingCalls < config.maxWaitingCalls) {
                waitingCalls++
                true
            } else {
                false
            }
        }

        if (!canAccept) {
            mutex.withLock {
                totalCalls++
                rejectedCalls++
            }
            listeners.forEach { it.onCallRejected() }
            throw BulkheadFullException("Bulkhead is full. Max concurrent: ${config.maxConcurrentCalls}, Max waiting: ${config.maxWaitingCalls}")
        }

        try {
            // Acquire semaphore permit (with optional timeout)
            if (config.maxWaitDuration != null) {
                val acquired = kotlinx.coroutines.withTimeoutOrNull(config.maxWaitDuration!!) {
                    semaphore.acquire()
                    true
                } ?: false
                if (!acquired) {
                    mutex.withLock {
                        waitingCalls--
                        totalCalls++
                        rejectedCalls++
                    }
                    listeners.forEach { it.onCallRejected() }
                    throw BulkheadTimeoutException("Timeout waiting for bulkhead permit after ${config.maxWaitDuration}")
                }
            } else {
                semaphore.acquire()
            }

            // Move from waiting to active
            mutex.withLock {
                waitingCalls--
                activeCalls++
                totalCalls++
            }

            listeners.forEach { it.onCallEntered() }

            return try {
                val result = block()
                mutex.withLock { successfulCalls++ }
                listeners.forEach { it.onCallSucceeded() }
                result
            } catch (e: Throwable) {
                mutex.withLock { failedCalls++ }
                listeners.forEach { it.onCallFailed(e) }
                throw e
            } finally {
                mutex.withLock {
                    if (activeCalls > 0) activeCalls--
                }
                semaphore.release()
                listeners.forEach { it.onCallExited() }
            }
        } catch (e: BulkheadFullException) {
            throw e
        } catch (e: BulkheadTimeoutException) {
            throw e
        } catch (e: Throwable) {
            // If we failed before acquiring semaphore, decrement waiting
            // But the above logic handles this in the timeout case already
            throw e
        }
    }

    public suspend fun <T> tryExecute(block: suspend () -> T): T? {
        return try {
            execute(block)
        } catch (e: BulkheadFullException) {
            null
        } catch (e: BulkheadTimeoutException) {
            null
        }
    }

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
public class BulkheadRegistry {
    private val bulkheads = mutableMapOf<String, Bulkhead>()

    public fun getOrCreate(name: String, configure: BulkheadConfigBuilder.() -> Unit = {}): Bulkhead {
        return bulkheads.getOrPut(name) {
            val config = BulkheadConfigBuilder().apply(configure).build()
            Bulkhead(config)
        }
    }

    public fun get(name: String): Bulkhead? = bulkheads[name]

    public fun remove(name: String): Bulkhead? = bulkheads.remove(name)

    public fun getNames(): Set<String> = bulkheads.keys.toSet()

    public suspend fun resetAllStatistics() {
        bulkheads.values.forEach { it.resetStatistics() }
    }

    public suspend fun getAllStatistics(): Map<String, BulkheadStatistics> {
        return bulkheads.mapValues { (_, bulkhead) -> bulkhead.statistics() }
    }
}

// --- Free functions (kept for backward compatibility) ---

public suspend fun <T> withBulkhead(
    config: BulkheadConfig = BulkheadConfig(),
    block: suspend () -> T,
): T {
    return Bulkhead(config).execute(block)
}

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
public fun bulkhead(configure: BulkheadConfigBuilder.() -> Unit): Bulkhead {
    val config = BulkheadConfigBuilder().apply(configure).build()
    return Bulkhead(config)
}

/**
 * Builder for bulkhead configuration.
 */
public class BulkheadConfigBuilder {
    public var maxConcurrentCalls: Int = 10
    public var maxWaitingCalls: Int = 10
    public var maxWaitDuration: Duration? = null

    public fun build(): BulkheadConfig {
        return BulkheadConfig(
            maxConcurrentCalls = maxConcurrentCalls,
            maxWaitingCalls = maxWaitingCalls,
            maxWaitDuration = maxWaitDuration,
        )
    }
}
