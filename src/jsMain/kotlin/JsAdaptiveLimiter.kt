// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

import com.sorinirmies.arrow.resiliencekit.AdaptiveLimiterConfig
import kotlin.js.Promise

/**
 * JS/TS-friendly facade over [com.sorinirmies.arrow.resiliencekit.AdaptiveLimiter].
 *
 * ```ts
 * import { AdaptiveLimiter } from "arrow-resilience-kit";
 *
 * const limiter = await AdaptiveLimiter.create({ initialLimit: 20 });
 * const result = await limiter.execute(() => downstream.call());
 * ```
 */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
public class AdaptiveLimiter internal constructor(
    private val delegate: com.sorinirmies.arrow.resiliencekit.AdaptiveLimiter,
) {
    public companion object {
        /** Creates a new AIMD adaptive concurrency limiter. [latencyThresholdMillis] of `undefined` disables latency-based back-off. */
        public fun create(
            initialLimit: Int = 20,
            minLimit: Int = 1,
            maxLimit: Int = 200,
            increaseStep: Int = 1,
            decreaseFactor: Double = 0.5,
            latencyThresholdMillis: Double? = null,
        ): Promise<AdaptiveLimiter> = runAsPromise {
            AdaptiveLimiter(
                com.sorinirmies.arrow.resiliencekit.AdaptiveLimiter.create(
                    AdaptiveLimiterConfig(
                        initialLimit = initialLimit,
                        minLimit = minLimit,
                        maxLimit = maxLimit,
                        increaseStep = increaseStep,
                        decreaseFactor = decreaseFactor,
                        latencyThreshold = latencyThresholdMillis?.millisAsDuration,
                    )
                )
            )
        }
    }

    /** Current statistics snapshot. */
    public fun statistics(): Promise<AdaptiveLimiterStatistics> = runAsPromise { delegate.statistics().toJs() }

    /** Current concurrency limit. */
    public fun currentLimit(): Promise<Int> = runAsPromise { delegate.currentLimit() }

    /** Executes [block], counting it toward the current concurrency limit and adapting the limit based on its outcome/latency. */
    public fun <T> execute(block: () -> Promise<T>): Promise<T> = runAsPromise {
        delegate.execute { awaitBlock(block) }
    }
}

/** JS/TS-friendly snapshot of [com.sorinirmies.arrow.resiliencekit.AdaptiveLimiterStatistics]. */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
public data class AdaptiveLimiterStatistics internal constructor(
    public val currentLimit: Int,
    public val inFlight: Int,
    public val totalCalls: Double,
    public val overloadEvents: Double,
    public val utilizationRate: Double,
)

private fun com.sorinirmies.arrow.resiliencekit.AdaptiveLimiterStatistics.toJs() = AdaptiveLimiterStatistics(
    currentLimit = currentLimit,
    inFlight = inFlight,
    totalCalls = totalCalls.toDouble(),
    overloadEvents = overloadEvents.toDouble(),
    utilizationRate = utilizationRate,
)
