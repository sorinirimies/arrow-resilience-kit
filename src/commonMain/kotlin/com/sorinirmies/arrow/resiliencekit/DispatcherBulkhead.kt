// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package com.sorinirmies.arrow.resiliencekit

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Isolation-by-dispatcher bulkhead: like [Bulkhead], but every admitted call
 * runs on a dedicated [CoroutineDispatcher] instead of the caller's context.
 *
 * A semaphore-only [Bulkhead] bounds concurrency but a slow/blocking call still
 * occupies the caller's threads. Running the protected work on its own
 * dispatcher (e.g. a fixed thread pool, see `ThreadPoolBulkhead` on the JVM)
 * keeps a slow dependency from starving the rest of the application.
 *
 * ```kotlin
 * val bulkhead = DispatcherBulkhead.create(Dispatchers.IO.limitedParallelism(4))
 * bulkhead.execute { blockingCall() }
 * ```
 */
public class DispatcherBulkhead private constructor(
    private val delegate: Bulkhead,
    /** Dispatcher on which admitted calls run. */
    public val dispatcher: CoroutineDispatcher,
) {
    /** The configuration of the underlying permit/queue bulkhead. */
    public val config: BulkheadConfig get() = delegate.config

    /** Factory for [DispatcherBulkhead]. */
    public companion object {
        /**
         * Creates a [DispatcherBulkhead] running admitted calls on [dispatcher].
         * @param dispatcher dispatcher the protected work runs on
         * @param config permit/queue limits
         */
        public suspend fun create(
            dispatcher: CoroutineDispatcher,
            config: BulkheadConfig = BulkheadConfig(),
        ): DispatcherBulkhead = DispatcherBulkhead(Bulkhead.create(config), dispatcher)
    }

    /** Adds a listener for bulkhead events. */
    public fun addListener(listener: BulkheadListener) {
        delegate.addListener(listener)
    }

    /** Returns the current number of active calls. */
    public suspend fun activeCalls(): Int = delegate.activeCalls()

    /** Returns a snapshot of the current statistics. */
    public suspend fun statistics(): BulkheadStatistics = delegate.statistics()

    /** Resets all statistics counters. */
    public suspend fun resetStatistics() {
        delegate.resetStatistics()
    }

    /**
     * Executes [block] on [dispatcher] once a permit is available.
     * @throws BulkheadFullException if the bulkhead is full
     * @throws BulkheadTimeoutException if waiting for a permit times out
     */
    public suspend fun <T> execute(block: suspend () -> T): T =
        delegate.execute { withContext(dispatcher) { block() } }

    /** Like [execute] but returns `null` when rejected. */
    public suspend fun <T> tryExecute(block: suspend () -> T): T? =
        delegate.tryExecute { withContext(dispatcher) { block() } }

    /** Like [execute] but runs [fallback] when rejected. */
    public suspend fun <T> executeOrFallback(fallback: suspend () -> T, block: suspend () -> T): T =
        delegate.executeOrFallback(fallback) { withContext(dispatcher) { block() } }
}

/** Adapts this [DispatcherBulkhead] into a [Policy]. */
public fun DispatcherBulkhead.asPolicy(): Policy {
    val bulkhead = this
    return object : Policy {
        override suspend fun <T> apply(block: suspend () -> T): T = bulkhead.execute(block)
    }
}
