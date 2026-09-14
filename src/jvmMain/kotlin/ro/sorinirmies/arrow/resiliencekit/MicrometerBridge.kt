// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

/**
 * Optional JVM-only [Micrometer](https://micrometer.io/) bridge.
 *
 * Exports the existing `*Statistics`/`*Stats` snapshots as Micrometer gauges,
 * so they show up in whatever backend your `MeterRegistry` is wired to
 * (Prometheus, Datadog, CloudWatch, ...) without this library taking a hard
 * dependency on Micrometer for every consumer.
 *
 * Micrometer is `compileOnly` on the JVM target: add
 * `io.micrometer:micrometer-core` to your own project to use this bridge.
 *
 * ```kotlin
 * val registry = SimpleMeterRegistry()
 * MicrometerBridge.bindBulkhead(registry, "orders-api", bulkhead)
 * MicrometerBridge.bindCircuitBreaker(registry, "orders-api", circuitBreaker)
 * ```
 */
package ro.sorinirmies.arrow.resiliencekit

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Binds resilience-pattern statistics to a Micrometer [MeterRegistry] as gauges.
 *
 * Each `bind*` function registers a gauge that reads the pattern's *current*
 * statistics snapshot on every scrape (via [MeterRegistry.gauge] semantics) —
 * there is no background polling loop to manage or shut down.
 */
public object MicrometerBridge {

    /** Binds [BulkheadStatistics] gauges for [bulkhead] under metric prefix `resilience.bulkhead`. */
    public fun bindBulkhead(registry: MeterRegistry, name: String, bulkhead: Bulkhead) {
        val tags = Tags.of("name", name)
        gauge(registry, "resilience.bulkhead.calls.total", tags) { bulkhead.blockingStatistics().totalCalls.toDouble() }
        gauge(registry, "resilience.bulkhead.calls.rejected", tags) {
            bulkhead.blockingStatistics().rejectedCalls.toDouble()
        }
        gauge(registry, "resilience.bulkhead.utilization", tags) {
            bulkhead.blockingStatistics().utilizationRate
        }
    }

    /** Binds circuit breaker state/failure gauges for [circuitBreaker] under metric prefix `resilience.circuit_breaker`. */
    public fun bindCircuitBreaker(registry: MeterRegistry, name: String, circuitBreaker: CircuitBreaker) {
        val tags = Tags.of("name", name)
        gauge(registry, "resilience.circuit_breaker.state", tags) {
            when (circuitBreaker.blockingState()) {
                CircuitBreakerState.Closed -> 0.0
                CircuitBreakerState.HalfOpen -> 1.0
                CircuitBreakerState.Open -> 2.0
            }
        }
        gauge(registry, "resilience.circuit_breaker.failures", tags) {
            circuitBreaker.blockingFailures().toDouble()
        }
        gauge(registry, "resilience.circuit_breaker.successes", tags) {
            circuitBreaker.blockingSuccesses().toDouble()
        }
    }

    /** Binds [RateLimiterStatistics] gauges for [rateLimiter] under metric prefix `resilience.rate_limiter`. */
    public fun bindRateLimiter(registry: MeterRegistry, name: String, rateLimiter: RateLimiter) {
        val tags = Tags.of("name", name)
        gauge(registry, "resilience.rate_limiter.requests.total", tags) {
            rateLimiter.blockingStatistics().totalRequests.toDouble()
        }
        gauge(registry, "resilience.rate_limiter.requests.rejected", tags) {
            rateLimiter.blockingStatistics().rejectedRequests.toDouble()
        }
        gauge(registry, "resilience.rate_limiter.acceptance_rate", tags) {
            rateLimiter.blockingStatistics().acceptanceRate
        }
    }

    /** Binds [AdaptiveLimiterStatistics] gauges for [limiter] under metric prefix `resilience.adaptive_limiter`. */
    public fun bindAdaptiveLimiter(registry: MeterRegistry, name: String, limiter: AdaptiveLimiter) {
        val tags = Tags.of("name", name)
        gauge(registry, "resilience.adaptive_limiter.limit", tags) {
            limiter.blockingStatistics().currentLimit.toDouble()
        }
        gauge(registry, "resilience.adaptive_limiter.in_flight", tags) {
            limiter.blockingStatistics().inFlight.toDouble()
        }
        gauge(registry, "resilience.adaptive_limiter.overload_events", tags) {
            limiter.blockingStatistics().overloadEvents.toDouble()
        }
    }

    private fun gauge(registry: MeterRegistry, metricName: String, tags: Tags, value: () -> Double) {
        registry.gauge(metricName, tags, Unit) { value() }
    }
}

// Statistics accessors are `suspend`; bridging to Micrometer's synchronous gauge
// callbacks needs a blocking hop. These run a tiny STM read, so blocking here is
// bounded and safe — no lock contention or I/O is involved.
private fun Bulkhead.blockingStatistics(): BulkheadStatistics = runBlocking(Dispatchers.Unconfined) { statistics() }
private fun CircuitBreaker.blockingState(): CircuitBreakerState = runBlocking(Dispatchers.Unconfined) { currentState() }
private fun CircuitBreaker.blockingFailures(): Int = runBlocking(Dispatchers.Unconfined) { failures() }
private fun CircuitBreaker.blockingSuccesses(): Int = runBlocking(Dispatchers.Unconfined) { successes() }
private fun RateLimiter.blockingStatistics(): RateLimiterStatistics =
    runBlocking(Dispatchers.Unconfined) { statistics() }
private fun AdaptiveLimiter.blockingStatistics(): AdaptiveLimiterStatistics =
    runBlocking(Dispatchers.Unconfined) { statistics() }
