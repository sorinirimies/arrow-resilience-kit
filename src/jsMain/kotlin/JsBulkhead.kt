// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

import com.sorinirmies.arrow.resiliencekit.BulkheadConfig
import kotlin.js.Promise

/**
 * JS/TS-friendly facade over [com.sorinirmies.arrow.resiliencekit.Bulkhead].
 *
 * ```ts
 * import { Bulkhead } from "arrow-resilience-kit";
 *
 * const bh = await Bulkhead.create({ maxConcurrentCalls: 10, maxWaitingCalls: 20 });
 * const result = await bh.execute(() => dbPool.query());
 * ```
 */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
public class Bulkhead internal constructor(
    private val delegate: com.sorinirmies.arrow.resiliencekit.Bulkhead,
) {
    public companion object {
        /** Creates a new bulkhead. [maxWaitDurationMillis] of `undefined`/omitted means no wait-time limit. */
        public fun create(
            maxConcurrentCalls: Int = 10,
            maxWaitingCalls: Int = 10,
            maxWaitDurationMillis: Double? = null,
        ): Promise<Bulkhead> = runAsPromise {
            Bulkhead(
                com.sorinirmies.arrow.resiliencekit.Bulkhead.create(
                    BulkheadConfig(
                        maxConcurrentCalls = maxConcurrentCalls,
                        maxWaitingCalls = maxWaitingCalls,
                        maxWaitDuration = maxWaitDurationMillis?.millisAsDuration,
                    )
                )
            )
        }
    }

    /** Number of calls currently holding a permit. */
    public fun activeCalls(): Promise<Int> = runAsPromise { delegate.activeCalls() }

    /** Current statistics snapshot. */
    public fun statistics(): Promise<BulkheadStatistics> = runAsPromise { delegate.statistics().toJs() }

    /** Resets all statistics counters. */
    public fun resetStatistics(): Promise<Unit> = runAsPromise { delegate.resetStatistics() }

    /** Executes [block], waiting for a permit. Rejects if the bulkhead is full (or times out waiting). */
    public fun <T> execute(block: () -> Promise<T>): Promise<T> = runAsPromise {
        delegate.execute { awaitBlock(block) }
    }

    /** Executes [block] only if a permit is immediately available, resolving `null` otherwise. */
    public fun <T> tryExecute(block: () -> Promise<T>): Promise<T?> = runAsPromise {
        delegate.tryExecute { awaitBlock(block) }
    }

    /** Executes [block], falling back to [fallback] if the bulkhead is full. */
    public fun <T> executeOrFallback(fallback: () -> Promise<T>, block: () -> Promise<T>): Promise<T> = runAsPromise {
        delegate.executeOrFallback(fallback = { awaitBlock(fallback) }, block = { awaitBlock(block) })
    }
}

/** JS/TS-friendly snapshot of [com.sorinirmies.arrow.resiliencekit.BulkheadStatistics]. */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
public data class BulkheadStatistics internal constructor(
    public val totalCalls: Double,
    public val successfulCalls: Double,
    public val failedCalls: Double,
    public val rejectedCalls: Double,
    public val availableCapacity: Int,
    public val utilizationRate: Double,
)

private fun com.sorinirmies.arrow.resiliencekit.BulkheadStatistics.toJs() = BulkheadStatistics(
    totalCalls = totalCalls.toDouble(),
    successfulCalls = successfulCalls.toDouble(),
    failedCalls = failedCalls.toDouble(),
    rejectedCalls = rejectedCalls.toDouble(),
    availableCapacity = availableCapacity,
    utilizationRate = utilizationRate,
)
