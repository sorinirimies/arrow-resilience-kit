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
package com.sorinirmies.arrow.resiliencekit

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

    /** Binds [CacheStatistics] gauges for [cache] under metric prefix `resilience.cache`. */
    public fun bindCache(registry: MeterRegistry, name: String, cache: Cache<*, *>) {
        val tags = Tags.of("name", name)
        gauge(registry, "resilience.cache.hits", tags) { cache.blockingStatistics().hits.toDouble() }
        gauge(registry, "resilience.cache.misses", tags) { cache.blockingStatistics().misses.toDouble() }
        gauge(registry, "resilience.cache.evictions", tags) { cache.blockingStatistics().evictions.toDouble() }
        gauge(registry, "resilience.cache.size", tags) { cache.blockingStatistics().size.toDouble() }
        gauge(registry, "resilience.cache.hit_rate", tags) { cache.blockingStatistics().hitRate }
    }

    /** Binds [TimeLimiterStatistics] gauges for [timeLimiter] under metric prefix `resilience.time_limiter`. */
    public fun bindTimeLimiter(registry: MeterRegistry, name: String, timeLimiter: TimeLimiter) {
        val tags = Tags.of("name", name)
        gauge(registry, "resilience.time_limiter.calls.total", tags) {
            timeLimiter.blockingStatistics().totalCalls.toDouble()
        }
        gauge(registry, "resilience.time_limiter.calls.timed_out", tags) {
            timeLimiter.blockingStatistics().timedOutCalls.toDouble()
        }
        gauge(registry, "resilience.time_limiter.calls.failed", tags) {
            timeLimiter.blockingStatistics().failedCalls.toDouble()
        }
        gauge(registry, "resilience.time_limiter.success_rate", tags) {
            timeLimiter.blockingStatistics().successRate
        }
    }

    /**
     * Binds [FailoverProviderStatistics] gauges for every provider in [failover], under metric
     * prefix `resilience.failover`, tagged by both `name` (the failover chain) and `provider`.
     */
    public fun <T> bindFailover(registry: MeterRegistry, name: String, failover: Failover<T>) {
        failover.providerNames().forEach { providerName ->
            val tags = Tags.of("name", name, "provider", providerName)
            gauge(registry, "resilience.failover.successes", tags) {
                failover.blockingProviderStatistics(providerName)?.successes?.toDouble() ?: 0.0
            }
            gauge(registry, "resilience.failover.failures", tags) {
                failover.blockingProviderStatistics(providerName)?.failures?.toDouble() ?: 0.0
            }
            gauge(registry, "resilience.failover.skipped", tags) {
                failover.blockingProviderStatistics(providerName)?.skipped?.toDouble() ?: 0.0
            }
            gauge(registry, "resilience.failover.state", tags) {
                when (failover.blockingProviderStatistics(providerName)?.state) {
                    CircuitBreakerState.Closed -> 0.0
                    CircuitBreakerState.HalfOpen -> 1.0
                    CircuitBreakerState.Open -> 2.0
                    null -> -1.0
                }
            }
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
private fun Cache<*, *>.blockingStatistics(): CacheStatistics = runBlocking(Dispatchers.Unconfined) { statistics() }
private fun TimeLimiter.blockingStatistics(): TimeLimiterStatistics =
    runBlocking(Dispatchers.Unconfined) { statistics() }
private fun <T> Failover<T>.blockingProviderStatistics(providerName: String): FailoverProviderStatistics? =
    runBlocking(Dispatchers.Unconfined) { statistics().find { it.name == providerName } }
