// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

import com.sorinirmies.arrow.resiliencekit.TimeLimiterConfig
import kotlin.js.Promise

/**
 * JS/TS-friendly facade over [com.sorinirmies.arrow.resiliencekit.TimeLimiter].
 *
 * ```ts
 * import { TimeLimiter } from "arrow-resilience-kit";
 *
 * const tl = await TimeLimiter.create({ timeoutMillis: 5_000 });
 * const result = await tl.execute(() => slowOp());
 * ```
 */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
public class TimeLimiter internal constructor(
    private val delegate: com.sorinirmies.arrow.resiliencekit.TimeLimiter,
) {
    public companion object {
        /** Creates a new time limiter. */
        public fun create(timeoutMillis: Double = 30_000.0): Promise<TimeLimiter> = runAsPromise {
            TimeLimiter(
                com.sorinirmies.arrow.resiliencekit.TimeLimiter.create(
                    TimeLimiterConfig(timeout = timeoutMillis.millisAsDuration)
                )
            )
        }
    }

    /** Current statistics snapshot. */
    public fun statistics(): Promise<TimeLimiterStatistics> = runAsPromise { delegate.statistics().toJs() }

    /** Resets all statistics counters. */
    public fun resetStatistics(): Promise<Unit> = runAsPromise { delegate.resetStatistics() }

    /** Executes [block], rejecting with a timeout error if it exceeds [timeoutMillis] (or this limiter's default). */
    public fun <T> execute(timeoutMillis: Double? = null, block: () -> Promise<T>): Promise<T> = runAsPromise {
        delegate.execute(timeoutMillis?.millisAsDuration) { awaitBlock(block) }
    }

    /** Like [execute], but resolves `null` on timeout instead of rejecting. */
    public fun <T> executeOrNull(timeoutMillis: Double? = null, block: () -> Promise<T>): Promise<T?> = runAsPromise {
        delegate.executeOrNull(timeoutMillis?.millisAsDuration) { awaitBlock(block) }
    }

    /** Like [execute], but resolves [default] on timeout instead of rejecting. */
    public fun <T> executeOrDefault(default: T, timeoutMillis: Double? = null, block: () -> Promise<T>): Promise<T> =
        runAsPromise {
            delegate.executeOrDefault(timeoutMillis?.millisAsDuration, default) { awaitBlock(block) }
        }

    /** Like [execute], retrying up to [retries] times on timeout. */
    public fun <T> executeWithRetry(
        timeoutMillis: Double? = null,
        retries: Int = 3,
        block: () -> Promise<T>,
    ): Promise<T> = runAsPromise {
        delegate.executeWithRetry(timeoutMillis?.millisAsDuration, retries) { awaitBlock(block) }
    }
}

/** JS/TS-friendly snapshot of [com.sorinirmies.arrow.resiliencekit.TimeLimiterStatistics]. */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
public data class TimeLimiterStatistics internal constructor(
    public val totalCalls: Double,
    public val successfulCalls: Double,
    public val timedOutCalls: Double,
    public val failedCalls: Double,
    public val successRate: Double,
)

private fun com.sorinirmies.arrow.resiliencekit.TimeLimiterStatistics.toJs() = TimeLimiterStatistics(
    totalCalls = totalCalls.toDouble(),
    successfulCalls = successfulCalls.toDouble(),
    timedOutCalls = timedOutCalls.toDouble(),
    failedCalls = failedCalls.toDouble(),
    successRate = successRate,
)
