// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package com.sorinirmies.arrow.resiliencekit

import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread-pool-isolation bulkhead (JVM). Admitted calls run on a dedicated
 * fixed-size thread pool of [BulkheadConfig.maxConcurrentCalls] threads, so
 * slow or blocking calls can never consume threads outside the pool.
 *
 * Must be [close]d to release its threads.
 *
 * ```kotlin
 * ThreadPoolBulkhead.create("payments", BulkheadConfig(maxConcurrentCalls = 8)).use { b ->
 *     b.execute { blockingJdbcCall() }
 * }
 * ```
 */
public class ThreadPoolBulkhead private constructor(
    private val executor: ExecutorService,
    private val delegate: DispatcherBulkhead,
) : AutoCloseable {
    /** The configuration of this bulkhead. */
    public val config: BulkheadConfig get() = delegate.config

    /** Factory for [ThreadPoolBulkhead]. */
    public companion object {
        /**
         * Creates a [ThreadPoolBulkhead] with its own daemon thread pool.
         * @param name thread-name prefix
         * @param config permit/queue limits; pool size is `maxConcurrentCalls`
         */
        public suspend fun create(
            name: String = "bulkhead",
            config: BulkheadConfig = BulkheadConfig(),
        ): ThreadPoolBulkhead {
            val counter = AtomicInteger()
            val factory = ThreadFactory { r ->
                Thread(r, "$name-${counter.incrementAndGet()}").apply { isDaemon = true }
            }
            val executor = Executors.newFixedThreadPool(config.maxConcurrentCalls, factory)
            return ThreadPoolBulkhead(
                executor,
                DispatcherBulkhead.create(executor.asCoroutineDispatcher(), config),
            )
        }
    }

    /** Adds a listener for bulkhead events. */
    public fun addListener(listener: BulkheadListener) {
        delegate.addListener(listener)
    }

    /** Returns the current number of active calls. */
    public suspend fun activeCalls(): Int = delegate.activeCalls()

    /** Returns a snapshot of the current statistics. */
    public suspend fun statistics(): BulkheadStatistics = delegate.statistics()

    /** Executes [block] on the dedicated pool; see [DispatcherBulkhead.execute]. */
    public suspend fun <T> execute(block: suspend () -> T): T = delegate.execute(block)

    /** Like [execute] but returns `null` when rejected. */
    public suspend fun <T> tryExecute(block: suspend () -> T): T? = delegate.tryExecute(block)

    /** Like [execute] but runs [fallback] when rejected. */
    public suspend fun <T> executeOrFallback(fallback: suspend () -> T, block: suspend () -> T): T =
        delegate.executeOrFallback(fallback, block)

    /** Adapts this bulkhead into a [Policy]. */
    public fun asPolicy(): Policy = delegate.asPolicy()

    /** Shuts the thread pool down. */
    override fun close() {
        executor.shutdown()
    }
}
