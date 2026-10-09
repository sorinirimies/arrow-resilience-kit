// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package com.sorinirmies.arrow.resiliencekit

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class SlidingWindowCircuitBreakerTest {

    @JsName("slidingWindowCircuitBreakerStartsInClosedState")
    @Test
    fun `sliding window circuit breaker starts in closed state`() = runTest {
        val breaker = SlidingWindowCircuitBreaker.create()
        breaker.currentState() shouldBe CircuitBreakerState.Closed
    }

    @JsName("slidingWindowCircuitBreakerStaysClosedBelowMinimumNumberOfCalls")
    @Test
    fun `sliding window circuit breaker stays closed below minimumNumberOfCalls`() = runTest {
        val breaker = SlidingWindowCircuitBreaker.create(
            SlidingWindowCircuitBreakerConfig(
                slidingWindowSize = 10,
                minimumNumberOfCalls = 5,
                failureRateThreshold = 0.5,
            )
        )

        // 3 failures in a row, but minimumNumberOfCalls (5) hasn't been reached yet -- unlike
        // CircuitBreaker's consecutive-count trip, this must stay closed.
        repeat(3) {
            shouldThrow<RuntimeException> {
                breaker.execute { throw RuntimeException("boom") }
            }
        }

        breaker.currentState() shouldBe CircuitBreakerState.Closed
    }

    @JsName("slidingWindowCircuitBreakerTripsOnFailureRateNotConsecutiveFailures")
    @Test
    fun `sliding window circuit breaker trips on failure rate not consecutive failures`() = runTest {
        val breaker = SlidingWindowCircuitBreaker.create(
            SlidingWindowCircuitBreakerConfig(
                slidingWindowSize = 10,
                minimumNumberOfCalls = 10,
                failureRateThreshold = 0.5,
            )
        )

        // Alternating success/failure -- never 2 consecutive failures, but a steady 50% error
        // rate once the window fills, which is exactly what this circuit breaker protects
        // against and CircuitBreaker's consecutive-count trip would never catch.
        repeat(5) {
            breaker.execute { "ok" }
            shouldThrow<RuntimeException> { breaker.execute { throw RuntimeException("boom") } }
        }

        breaker.currentState() shouldBe CircuitBreakerState.Open
        val stats = breaker.statistics()
        stats.callsInWindow shouldBe 10
        stats.failuresInWindow shouldBe 5
        stats.failureRate shouldBe 0.5
    }

    @JsName("slidingWindowCircuitBreakerRejectsCallsWhenOpen")
    @Test
    fun `sliding window circuit breaker rejects calls when open`() = runTest {
        val breaker = SlidingWindowCircuitBreaker.create(
            SlidingWindowCircuitBreakerConfig(slidingWindowSize = 2, minimumNumberOfCalls = 2, failureRateThreshold = 0.5)
        )

        repeat(2) {
            shouldThrow<RuntimeException> { breaker.execute { throw RuntimeException("boom") } }
        }
        breaker.currentState() shouldBe CircuitBreakerState.Open

        var called = false
        shouldThrow<CircuitBreakerOpenException> {
            breaker.execute { called = true }
        }
        called shouldBe false
    }

    @JsName("slidingWindowCircuitBreakerTransitionsToHalfOpenAfterResetTimeout")
    @Test
    fun `sliding window circuit breaker transitions to half open after reset timeout`() = runTest {
        val clock = TestClock()
        val breaker = SlidingWindowCircuitBreaker.create(
            config = SlidingWindowCircuitBreakerConfig(
                slidingWindowSize = 2,
                minimumNumberOfCalls = 2,
                failureRateThreshold = 0.5,
                resetTimeout = 1.seconds,
            ),
            clock = clock,
        )

        repeat(2) {
            shouldThrow<RuntimeException> { breaker.execute { throw RuntimeException("boom") } }
        }
        breaker.currentState() shouldBe CircuitBreakerState.Open

        clock.advance(2.seconds)
        val result = breaker.execute { "recovered" }

        result shouldBe "recovered"
        breaker.currentState() shouldBe CircuitBreakerState.HalfOpen
    }

    @JsName("slidingWindowCircuitBreakerClosesAfterHalfOpenSuccessThreshold")
    @Test
    fun `sliding window circuit breaker closes after half open success threshold`() = runTest {
        val clock = TestClock()
        val breaker = SlidingWindowCircuitBreaker.create(
            config = SlidingWindowCircuitBreakerConfig(
                slidingWindowSize = 2,
                minimumNumberOfCalls = 2,
                failureRateThreshold = 0.5,
                resetTimeout = 1.seconds,
                halfOpenSuccessThreshold = 2,
            ),
            clock = clock,
        )

        repeat(2) {
            shouldThrow<RuntimeException> { breaker.execute { throw RuntimeException("boom") } }
        }
        clock.advance(2.seconds)

        breaker.execute { "ok" } // 1st half-open success
        breaker.currentState() shouldBe CircuitBreakerState.HalfOpen
        breaker.execute { "ok" } // 2nd half-open success -- closes
        breaker.currentState() shouldBe CircuitBreakerState.Closed

        // Closing clears the window -- old failures don't count against the fresh Closed state.
        breaker.statistics().callsInWindow shouldBe 0
    }

    @JsName("slidingWindowCircuitBreakerReopensOnFailureInHalfOpen")
    @Test
    fun `sliding window circuit breaker reopens on failure in half open`() = runTest {
        val clock = TestClock()
        val breaker = SlidingWindowCircuitBreaker.create(
            config = SlidingWindowCircuitBreakerConfig(
                slidingWindowSize = 2,
                minimumNumberOfCalls = 2,
                failureRateThreshold = 0.5,
                resetTimeout = 1.seconds,
            ),
            clock = clock,
        )

        repeat(2) {
            shouldThrow<RuntimeException> { breaker.execute { throw RuntimeException("boom") } }
        }
        clock.advance(2.seconds)

        shouldThrow<RuntimeException> {
            breaker.execute { throw RuntimeException("still broken") }
        }
        breaker.currentState() shouldBe CircuitBreakerState.Open
    }

    @JsName("slidingWindowCircuitBreakerExecuteOrFallbackUsesFallbackWhenOpen")
    @Test
    fun `sliding window circuit breaker executeOrFallback uses fallback when open`() = runTest {
        val breaker = SlidingWindowCircuitBreaker.create(
            SlidingWindowCircuitBreakerConfig(slidingWindowSize = 1, minimumNumberOfCalls = 1, failureRateThreshold = 0.5)
        )
        shouldThrow<RuntimeException> { breaker.execute { throw RuntimeException("boom") } }
        breaker.currentState() shouldBe CircuitBreakerState.Open

        val result = breaker.executeOrFallback(fallback = { "fallback" }) { "primary" }
        result shouldBe "fallback"
    }

    @JsName("slidingWindowCircuitBreakerResetClearsWindowAndState")
    @Test
    fun `sliding window circuit breaker reset clears window and state`() = runTest {
        val breaker = SlidingWindowCircuitBreaker.create(
            SlidingWindowCircuitBreakerConfig(slidingWindowSize = 1, minimumNumberOfCalls = 1, failureRateThreshold = 0.5)
        )
        shouldThrow<RuntimeException> { breaker.execute { throw RuntimeException("boom") } }
        breaker.currentState() shouldBe CircuitBreakerState.Open

        breaker.reset()

        breaker.currentState() shouldBe CircuitBreakerState.Closed
        breaker.statistics().callsInWindow shouldBe 0
    }

    @JsName("slidingWindowCircuitBreakerAsPolicyDelegatesToExecute")
    @Test
    fun `sliding window circuit breaker asPolicy delegates to execute`() = runTest {
        val breaker = SlidingWindowCircuitBreaker.create()
        val policy = breaker.asPolicy()

        policy.apply { "ok" } shouldBe "ok"
    }

    @JsName("slidingWindowCircuitBreakerRegistryReusesInstanceByName")
    @Test
    fun `SlidingWindowCircuitBreakerRegistry reuses instance by name`() = runTest {
        val registry = SlidingWindowCircuitBreakerRegistry.create()

        val first = registry.getOrCreate("orders-api") { slidingWindowSize = 20 }
        val second = registry.getOrCreate("orders-api") { slidingWindowSize = 99 }

        first shouldBe second
        registry.getNames() shouldBe setOf("orders-api")
    }

    @JsName("slidingWindowCircuitBreakerRegistryGetAndRemove")
    @Test
    fun `SlidingWindowCircuitBreakerRegistry get and remove`() = runTest {
        val registry = SlidingWindowCircuitBreakerRegistry.create()
        registry.get("orders-api") shouldBe null

        val created = registry.getOrCreate("orders-api")
        registry.get("orders-api") shouldBe created

        val removed = registry.remove("orders-api")
        removed shouldBe created
        registry.get("orders-api") shouldBe null
    }

    @JsName("slidingWindowCircuitBreakerConfigRejectsInvalidValues")
    @Test
    fun `sliding window circuit breaker config rejects invalid values`() {
        shouldThrow<IllegalArgumentException> {
            SlidingWindowCircuitBreakerConfig(slidingWindowSize = 0)
        }
        shouldThrow<IllegalArgumentException> {
            SlidingWindowCircuitBreakerConfig(minimumNumberOfCalls = 20, slidingWindowSize = 10)
        }
        shouldThrow<IllegalArgumentException> {
            SlidingWindowCircuitBreakerConfig(failureRateThreshold = 0.0)
        }
        shouldThrow<IllegalArgumentException> {
            SlidingWindowCircuitBreakerConfig(failureRateThreshold = 1.5)
        }
    }
}
