// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package com.sorinirmies.arrow.resiliencekit

import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class MicrometerBridgeTest {

    @Test
    fun `bindBulkhead exposes total and rejected call gauges`() = runTest {
        val bulkhead = Bulkhead.create(BulkheadConfig(maxConcurrentCalls = 1, maxWaitingCalls = 0))
        bulkhead.execute { "ok" }
        bulkhead.execute { "ok again" }

        val registry = SimpleMeterRegistry()
        MicrometerBridge.bindBulkhead(registry, "test-bulkhead", bulkhead)

        registry.get("resilience.bulkhead.calls.total").tag("name", "test-bulkhead").gauge().value() shouldBe 2.0
    }

    @Test
    fun `bindCircuitBreaker exposes state as a numeric gauge`() = runTest {
        val circuitBreaker = CircuitBreaker.create(CircuitBreakerConfig(failureThreshold = 1))
        val registry = SimpleMeterRegistry()
        MicrometerBridge.bindCircuitBreaker(registry, "test-cb", circuitBreaker)

        registry.get("resilience.circuit_breaker.state").tag("name", "test-cb").gauge().value() shouldBe 0.0

        try {
            circuitBreaker.execute { throw IllegalStateException("boom") }
        } catch (_: IllegalStateException) {
            // expected, opens the breaker
        }

        registry.get("resilience.circuit_breaker.state").tag("name", "test-cb").gauge().value() shouldBe 2.0
    }

    @Test
    fun `bindAdaptiveLimiter exposes the current dynamic limit`() = runTest {
        val limiter = AdaptiveLimiter.create(AdaptiveLimiterConfig(initialLimit = 4))
        val registry = SimpleMeterRegistry()
        MicrometerBridge.bindAdaptiveLimiter(registry, "test-limiter", limiter)

        registry.get("resilience.adaptive_limiter.limit").tag("name", "test-limiter").gauge().value() shouldBe 4.0
    }

    @Test
    fun `bindCache exposes hit and miss counts`() = runTest {
        val cache = Cache.create<String, String>(CacheConfig(maxSize = 10))
        cache.put("a", "1")
        cache.get("a")
        cache.get("missing")

        val registry = SimpleMeterRegistry()
        MicrometerBridge.bindCache(registry, "test-cache", cache)

        registry.get("resilience.cache.hits").tag("name", "test-cache").gauge().value() shouldBe 1.0
        registry.get("resilience.cache.misses").tag("name", "test-cache").gauge().value() shouldBe 1.0
    }

    @Test
    fun `bindTimeLimiter exposes total call and success rate gauges`() = runTest {
        val timeLimiter = TimeLimiter.create()
        timeLimiter.execute { "ok" }

        val registry = SimpleMeterRegistry()
        MicrometerBridge.bindTimeLimiter(registry, "test-time-limiter", timeLimiter)

        registry.get("resilience.time_limiter.calls.total").tag("name", "test-time-limiter").gauge().value() shouldBe 1.0
        registry.get("resilience.time_limiter.success_rate").tag("name", "test-time-limiter").gauge().value() shouldBe 1.0
    }

    @Test
    fun `bindFailover exposes per-provider success and state gauges`() = runTest {
        val transport = failover<String> {
            provider("websocket") { "ws" }
            provider("mqtt") { "mqtt" }
        }
        transport.execute()

        val registry = SimpleMeterRegistry()
        MicrometerBridge.bindFailover(registry, "test-transport", transport)

        registry.get("resilience.failover.successes")
            .tag("name", "test-transport").tag("provider", "websocket").gauge().value() shouldBe 1.0
        registry.get("resilience.failover.state")
            .tag("name", "test-transport").tag("provider", "websocket").gauge().value() shouldBe 0.0
    }
}
