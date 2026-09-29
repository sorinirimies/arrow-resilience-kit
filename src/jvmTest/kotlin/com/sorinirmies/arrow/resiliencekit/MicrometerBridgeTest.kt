// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package ro.sorinirmies.arrow.resiliencekit

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
}
