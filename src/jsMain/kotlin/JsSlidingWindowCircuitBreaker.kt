// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

import com.sorinirmies.arrow.resiliencekit.SlidingWindowCircuitBreakerConfig
import kotlin.js.Promise

/**
 * JS/TS-friendly facade over [com.sorinirmies.arrow.resiliencekit.SlidingWindowCircuitBreaker].
 *
 * ```ts
 * import { SlidingWindowCircuitBreaker } from "arrow-resilience-kit";
 *
 * const cb = await SlidingWindowCircuitBreaker.create({ slidingWindowSize: 20, failureRateThreshold: 0.5 });
 * const result = await cb.execute(() => externalService.call());
 * ```
 */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
public class SlidingWindowCircuitBreaker internal constructor(
    private val delegate: com.sorinirmies.arrow.resiliencekit.SlidingWindowCircuitBreaker,
) {
    public companion object {
        /** Creates a new sliding-window circuit breaker. */
        public fun create(
            slidingWindowSize: Int = 100,
            minimumNumberOfCalls: Int = 10,
            failureRateThreshold: Double = 0.5,
            resetTimeoutMillis: Double = 30_000.0,
            halfOpenSuccessThreshold: Int = 2,
        ): Promise<SlidingWindowCircuitBreaker> = runAsPromise {
            SlidingWindowCircuitBreaker(
                com.sorinirmies.arrow.resiliencekit.SlidingWindowCircuitBreaker.create(
                    SlidingWindowCircuitBreakerConfig(
                        slidingWindowSize = slidingWindowSize,
                        minimumNumberOfCalls = minimumNumberOfCalls,
                        failureRateThreshold = failureRateThreshold,
                        resetTimeout = resetTimeoutMillis.millisAsDuration,
                        halfOpenSuccessThreshold = halfOpenSuccessThreshold,
                    )
                )
            )
        }
    }

    /** Current state: `"Closed"`, `"Open"`, or `"HalfOpen"`. */
    public fun currentState(): Promise<String> = runAsPromise { delegate.currentState().name }

    /** Current window statistics snapshot. */
    public fun statistics(): Promise<SlidingWindowCircuitBreakerStatistics> = runAsPromise {
        delegate.statistics().toJs()
    }

    /** Executes [block] through the circuit breaker. Rejects with an error if the circuit is open. */
    public fun <T> execute(block: () -> Promise<T>): Promise<T> = runAsPromise {
        delegate.execute { awaitBlock(block) }
    }

    /** Executes [block], falling back to [fallback] if the circuit is open. */
    public fun <T> executeOrFallback(fallback: () -> Promise<T>, block: () -> Promise<T>): Promise<T> = runAsPromise {
        delegate.executeOrFallback(fallback = { awaitBlock(fallback) }, block = { awaitBlock(block) })
    }

    /** Manually resets the circuit breaker to closed state and clears its window. */
    public fun reset(): Promise<Unit> = runAsPromise { delegate.reset() }

    /** Registers [listener] to be called (with the old and new state names) on every state change. */
    public fun onStateChange(listener: (oldState: String, newState: String) -> Unit) {
        delegate.addListener { oldState, newState -> listener(oldState.name, newState.name) }
    }
}

/** JS/TS-friendly snapshot of [com.sorinirmies.arrow.resiliencekit.SlidingWindowCircuitBreakerStatistics]. */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
public data class SlidingWindowCircuitBreakerStatistics internal constructor(
    public val state: String,
    public val callsInWindow: Int,
    public val failuresInWindow: Int,
    public val failureRate: Double,
)

private fun com.sorinirmies.arrow.resiliencekit.SlidingWindowCircuitBreakerStatistics.toJs() =
    SlidingWindowCircuitBreakerStatistics(
        state = state.name,
        callsInWindow = callsInWindow,
        failuresInWindow = failuresInWindow,
        failureRate = failureRate,
    )
