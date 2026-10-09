// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

import com.sorinirmies.arrow.resiliencekit.CircuitBreakerConfig
import kotlin.js.Promise
import kotlin.time.Duration.Companion.seconds

/**
 * JS/TS-friendly facade over [com.sorinirmies.arrow.resiliencekit.CircuitBreaker].
 *
 * ```ts
 * import { CircuitBreaker } from "arrow-resilience-kit";
 *
 * const breaker = await CircuitBreaker.create({ failureThreshold: 5, resetTimeoutMillis: 30_000 });
 * const result = await breaker.execute(() => callExternalService());
 * ```
 */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
public class CircuitBreaker internal constructor(
    private val delegate: com.sorinirmies.arrow.resiliencekit.CircuitBreaker,
) {
    public companion object {
        /** Creates a new circuit breaker. */
        public fun create(
            failureThreshold: Int = 5,
            resetTimeoutMillis: Double = 30.seconds.inWholeMilliseconds.toDouble(),
            halfOpenSuccessThreshold: Int = 2,
            halfOpenMaxCalls: Int = 3,
        ): Promise<CircuitBreaker> = runAsPromise {
            CircuitBreaker(
                com.sorinirmies.arrow.resiliencekit.CircuitBreaker.create(
                    CircuitBreakerConfig(
                        failureThreshold = failureThreshold,
                        resetTimeout = resetTimeoutMillis.millisAsDuration,
                        halfOpenSuccessThreshold = halfOpenSuccessThreshold,
                        halfOpenMaxCalls = halfOpenMaxCalls,
                    )
                )
            )
        }
    }

    /** Current state: `"Closed"`, `"Open"`, or `"HalfOpen"`. */
    public fun currentState(): Promise<String> = runAsPromise { delegate.currentState().name }

    /** Current consecutive-failure count. */
    public fun failures(): Promise<Int> = runAsPromise { delegate.failures() }

    /** Current half-open success count. */
    public fun successes(): Promise<Int> = runAsPromise { delegate.successes() }

    /** Executes [block] through the circuit breaker. Rejects with an error if the circuit is open. */
    public fun <T> execute(block: () -> Promise<T>): Promise<T> = runAsPromise {
        delegate.execute { awaitBlock(block) }
    }

    /** Executes [block], falling back to [fallback] if the circuit is open. */
    public fun <T> executeOrFallback(fallback: () -> Promise<T>, block: () -> Promise<T>): Promise<T> = runAsPromise {
        delegate.executeOrFallback(fallback = { awaitBlock(fallback) }, block = { awaitBlock(block) })
    }

    /** Manually resets the circuit breaker to closed state. */
    public fun reset(): Promise<Unit> = runAsPromise { delegate.reset() }

    /** Manually opens the circuit breaker. */
    public fun trip(): Promise<Unit> = runAsPromise { delegate.trip() }

    /** Registers [listener] to be called (with the old and new state names) on every state change. */
    public fun onStateChange(listener: (oldState: String, newState: String) -> Unit) {
        delegate.addListener { oldState, newState -> listener(oldState.name, newState.name) }
    }
}

