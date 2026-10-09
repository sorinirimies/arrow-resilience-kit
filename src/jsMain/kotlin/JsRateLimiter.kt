// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

import com.sorinirmies.arrow.resiliencekit.RateLimiterConfig
import com.sorinirmies.arrow.resiliencekit.SlidingWindowConfig
import kotlin.js.Promise

/**
 * JS/TS-friendly facade over [com.sorinirmies.arrow.resiliencekit.RateLimiter] (token bucket).
 *
 * ```ts
 * import { RateLimiter } from "arrow-resilience-kit";
 *
 * const rl = await RateLimiter.create({ permitsPerSecond: 10, burstCapacity: 20 });
 * const result = await rl.execute(() => api.request());
 * ```
 */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
public class RateLimiter internal constructor(
    private val delegate: com.sorinirmies.arrow.resiliencekit.RateLimiter,
) {
    public companion object {
        /** Creates a new token-bucket rate limiter. */
        public fun create(permitsPerSecond: Double = 10.0, burstCapacity: Int = 10): Promise<RateLimiter> =
            runAsPromise {
                RateLimiter(
                    com.sorinirmies.arrow.resiliencekit.RateLimiter.create(
                        RateLimiterConfig(permitsPerSecond = permitsPerSecond, burstCapacity = burstCapacity)
                    )
                )
            }
    }

    /** Current number of available tokens. */
    public fun availableTokens(): Promise<Double> = runAsPromise { delegate.availableTokens() }

    /** Current statistics snapshot. */
    public fun statistics(): Promise<RateLimiterStatistics> = runAsPromise { delegate.statistics().toJs() }

    /** Tries to acquire [permits] tokens without executing anything. Resolves `true` if acquired. */
    public fun tryAcquire(permits: Int): Promise<Boolean> = runAsPromise { delegate.tryAcquire(permits) }

    /** Executes [block] if a token is available; rejects otherwise. */
    public fun <T> execute(block: () -> Promise<T>): Promise<T> = runAsPromise {
        delegate.execute { awaitBlock(block) }
    }

    /** Executes [block] only if a token is immediately available, resolving `null` otherwise. */
    public fun <T> tryExecute(block: () -> Promise<T>): Promise<T?> = runAsPromise {
        delegate.tryExecute { awaitBlock(block) }
    }

    /** Executes [block], falling back to [fallback] if no token is available. */
    public fun <T> executeOrFallback(fallback: () -> Promise<T>, block: () -> Promise<T>): Promise<T> = runAsPromise {
        delegate.executeOrFallback(fallback = { awaitBlock(fallback) }, block = { awaitBlock(block) })
    }

    /** Resets statistics counters (not the available-token balance). */
    public fun resetStatistics(): Promise<Unit> = runAsPromise { delegate.resetStatistics() }

    /** Resets the rate limiter to a full bucket and clears statistics. */
    public fun reset(): Promise<Unit> = runAsPromise { delegate.reset() }
}

/** JS/TS-friendly snapshot of [com.sorinirmies.arrow.resiliencekit.RateLimiterStatistics]. */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
public data class RateLimiterStatistics internal constructor(
    public val availableTokens: Double,
    public val totalRequests: Double,
    public val acceptedRequests: Double,
    public val rejectedRequests: Double,
    public val acceptanceRate: Double,
    public val rejectionRate: Double,
)

private fun com.sorinirmies.arrow.resiliencekit.RateLimiterStatistics.toJs() = RateLimiterStatistics(
    availableTokens = availableTokens,
    totalRequests = totalRequests.toDouble(),
    acceptedRequests = acceptedRequests.toDouble(),
    rejectedRequests = rejectedRequests.toDouble(),
    acceptanceRate = acceptanceRate,
    rejectionRate = rejectionRate,
)

/**
 * JS/TS-friendly facade over [com.sorinirmies.arrow.resiliencekit.SlidingWindowRateLimiter].
 *
 * ```ts
 * import { SlidingWindowRateLimiter } from "arrow-resilience-kit";
 *
 * const sw = await SlidingWindowRateLimiter.create({ maxRequests: 100, windowDurationMillis: 60_000 });
 * const result = await sw.execute(() => api.request());
 * ```
 */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
public class SlidingWindowRateLimiter internal constructor(
    private val delegate: com.sorinirmies.arrow.resiliencekit.SlidingWindowRateLimiter,
) {
    public companion object {
        /** Creates a new sliding-window rate limiter. */
        public fun create(maxRequests: Int = 100, windowDurationMillis: Double = 60_000.0): Promise<SlidingWindowRateLimiter> =
            runAsPromise {
                SlidingWindowRateLimiter(
                    com.sorinirmies.arrow.resiliencekit.SlidingWindowRateLimiter.create(
                        SlidingWindowConfig(
                            maxRequests = maxRequests,
                            windowDuration = windowDurationMillis.millisAsDuration,
                        )
                    )
                )
            }
    }

    /** Current number of requests counted within the window. */
    public fun currentRequests(): Promise<Int> = runAsPromise { delegate.currentRequests() }

    /** Current statistics snapshot. */
    public fun statistics(): Promise<SlidingWindowStatistics> = runAsPromise { delegate.statistics().toJs() }

    /** Tries to acquire a slot without executing anything. Resolves `true` if acquired. */
    public fun tryAcquire(): Promise<Boolean> = runAsPromise { delegate.tryAcquire() }

    /** Executes [block] if within the window's limit; rejects otherwise. */
    public fun <T> execute(block: () -> Promise<T>): Promise<T> = runAsPromise {
        delegate.execute { awaitBlock(block) }
    }

    /** Executes [block] only if a slot is immediately available, resolving `null` otherwise. */
    public fun <T> tryExecute(block: () -> Promise<T>): Promise<T?> = runAsPromise {
        delegate.tryExecute { awaitBlock(block) }
    }

    /** Resets the window and clears statistics. */
    public fun reset(): Promise<Unit> = runAsPromise { delegate.reset() }
}

/** JS/TS-friendly snapshot of [com.sorinirmies.arrow.resiliencekit.SlidingWindowStatistics]. */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
public data class SlidingWindowStatistics internal constructor(
    public val currentRequests: Int,
    public val totalRequests: Double,
    public val acceptedRequests: Double,
    public val rejectedRequests: Double,
)

private fun com.sorinirmies.arrow.resiliencekit.SlidingWindowStatistics.toJs() = SlidingWindowStatistics(
    currentRequests = currentRequests,
    totalRequests = totalRequests.toDouble(),
    acceptedRequests = acceptedRequests.toDouble(),
    rejectedRequests = rejectedRequests.toDouble(),
)
