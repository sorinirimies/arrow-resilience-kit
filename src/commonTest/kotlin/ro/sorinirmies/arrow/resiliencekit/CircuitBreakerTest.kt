// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package ro.sorinirmies.arrow.resiliencekit

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds


class CircuitBreakerTest {

    @JsName("circuitBreakerStartsInClosedState")
    @Test
    fun `circuit breaker starts in closed state`() = runTest {
        val breaker = CircuitBreaker()
        breaker.currentState() shouldBe CircuitBreakerState.Closed
    }

    @JsName("circuitBreakerAllowsCallsInClosedState")
    @Test
    fun `circuit breaker allows calls in closed state`() = runTest {
        val breaker = CircuitBreaker()
        var executed = false

        val result = breaker.execute {
            executed = true
            "success"
        }

        result shouldBe "success"
        executed shouldBe true
        breaker.currentState() shouldBe CircuitBreakerState.Closed
    }

    @JsName("circuitBreakerOpensAfterThresholdFailures")
    @Test
    fun `circuit breaker opens after threshold failures`() = runTest {
        val breaker = CircuitBreaker(
            config = CircuitBreakerConfig(
                failureThreshold = 3,
                resetTimeout = 1.seconds
            )
        )

        // Cause 3 failures to open the circuit
        repeat(3) {
            shouldThrow<RuntimeException> {
                breaker.execute {
                    throw RuntimeException("Failure ${it + 1}")
                }
            }
        }

        breaker.currentState() shouldBe CircuitBreakerState.Open
        breaker.failures() shouldBe 3
    }

    @JsName("circuitBreakerRejectsCallsWhenOpen")
    @Test
    fun `circuit breaker rejects calls when open`() = runTest {
        val breaker = CircuitBreaker(
            config = CircuitBreakerConfig(failureThreshold = 1)
        )

        // Open the circuit
        shouldThrow<RuntimeException> {
            breaker.execute { throw RuntimeException("Fail") }
        }

        breaker.currentState() shouldBe CircuitBreakerState.Open

        // Next call should be rejected immediately
        shouldThrow<CircuitBreakerOpenException> {
            breaker.execute { "should not execute" }
        }
    }

    @JsName("circuitBreakerTransitionsToHalfOpenAfterResetTimeout")
    @Test
    fun `circuit breaker transitions to half-open after reset timeout`() = runTest {
        val testClock = TestClock()
        val breaker = CircuitBreaker(
            config = CircuitBreakerConfig(
                failureThreshold = 1,
                resetTimeout = 50.milliseconds,
                halfOpenSuccessThreshold = 1
            ),
            clock = testClock
        )

        // Open the circuit
        shouldThrow<RuntimeException> {
            breaker.execute { throw RuntimeException("Fail") }
        }

        breaker.currentState() shouldBe CircuitBreakerState.Open

        // Advance past reset timeout
        testClock.advance(60.milliseconds)

        // Try to execute - should transition to half-open then close
        var executed = false
        val result = breaker.execute {
            executed = true
            "success"
        }

        result shouldBe "success"
        executed shouldBe true
        breaker.currentState() shouldBe CircuitBreakerState.Closed
    }

    @JsName("circuitBreakerClosesFromHalfOpenAfterSuccessThreshold")
    @Test
    fun `circuit breaker closes from half-open after success threshold`() = runTest {
        val testClock = TestClock()
        val breaker = CircuitBreaker(
            config = CircuitBreakerConfig(
                failureThreshold = 1,
                resetTimeout = 50.milliseconds,
                halfOpenSuccessThreshold = 2
            ),
            clock = testClock
        )

        // Open the circuit
        shouldThrow<RuntimeException> {
            breaker.execute { throw RuntimeException("Fail") }
        }

        testClock.advance(60.milliseconds)

        // First success in half-open
        breaker.execute { "success 1" }
        breaker.currentState() shouldBe CircuitBreakerState.HalfOpen

        // Second success should close the circuit
        breaker.execute { "success 2" }
        breaker.currentState() shouldBe CircuitBreakerState.Closed
    }

    @JsName("circuitBreakerReopensFromHalfOpenOnFailure")
    @Test
    fun `circuit breaker reopens from half-open on failure`() = runTest {
        val testClock = TestClock()
        val breaker = CircuitBreaker(
            config = CircuitBreakerConfig(
                failureThreshold = 1,
                resetTimeout = 50.milliseconds,
                halfOpenSuccessThreshold = 2
            ),
            clock = testClock
        )

        // Open the circuit
        shouldThrow<RuntimeException> {
            breaker.execute { throw RuntimeException("Initial failure") }
        }

        testClock.advance(60.milliseconds)

        // Fail in half-open state
        shouldThrow<RuntimeException> {
            breaker.execute { throw RuntimeException("Half-open failure") }
        }

        breaker.currentState() shouldBe CircuitBreakerState.Open
    }

    @JsName("executeOrFallbackUsesFallbackWhenCircuitIsOpen")
    @Test
    fun `executeOrFallback uses fallback when circuit is open`() = runTest {
        val breaker = CircuitBreaker(
            config = CircuitBreakerConfig(failureThreshold = 1)
        )

        // Open the circuit
        shouldThrow<RuntimeException> {
            breaker.execute { throw RuntimeException("Fail") }
        }

        var fallbackUsed = false
        val result = breaker.executeOrFallback(
            fallback = {
                fallbackUsed = true
                "fallback value"
            }
        ) {
            "primary value"
        }

        result shouldBe "fallback value"
        fallbackUsed shouldBe true
    }

    @JsName("executeOrFallbackExecutesPrimaryWhenCircuitIsClosed")
    @Test
    fun `executeOrFallback executes primary when circuit is closed`() = runTest {
        val breaker = CircuitBreaker()

        var fallbackUsed = false
        val result = breaker.executeOrFallback(
            fallback = {
                fallbackUsed = true
                "fallback value"
            }
        ) {
            "primary value"
        }

        result shouldBe "primary value"
        fallbackUsed shouldBe false
    }

    @JsName("manualResetClosesTheCircuit")
    @Test
    fun `manual reset closes the circuit`() = runTest {
        val breaker = CircuitBreaker(
            config = CircuitBreakerConfig(failureThreshold = 1)
        )

        // Open the circuit
        shouldThrow<RuntimeException> {
            breaker.execute { throw RuntimeException("Fail") }
        }

        breaker.currentState() shouldBe CircuitBreakerState.Open

        // Manually reset
        breaker.reset()

        breaker.currentState() shouldBe CircuitBreakerState.Closed
        breaker.failures() shouldBe 0
    }

    @JsName("manualTripOpensTheCircuit")
    @Test
    fun `manual trip opens the circuit`() = runTest {
        val breaker = CircuitBreaker()
        breaker.currentState() shouldBe CircuitBreakerState.Closed

        breaker.trip()

        breaker.currentState() shouldBe CircuitBreakerState.Open
    }

    @JsName("circuitBreakerResetsFailureCountOnSuccessInClosedState")
    @Test
    fun `circuit breaker resets failure count on success in closed state`() = runTest {
        val breaker = CircuitBreaker(
            config = CircuitBreakerConfig(failureThreshold = 3)
        )

        // Cause 2 failures
        repeat(2) {
            shouldThrow<RuntimeException> {
                breaker.execute { throw RuntimeException("Fail") }
            }
        }

        breaker.failures() shouldBe 2

        // One success should reset the count
        breaker.execute { "success" }

        breaker.failures() shouldBe 0
        breaker.currentState() shouldBe CircuitBreakerState.Closed
    }

    @JsName("circuitBreakerListenerIsNotifiedOfStateChanges")
    @Test
    fun `circuit breaker listener is notified of state changes`() = runTest {
        val stateChanges = mutableListOf<Pair<CircuitBreakerState, CircuitBreakerState>>()

        val breaker = CircuitBreaker(
            config = CircuitBreakerConfig(failureThreshold = 1)
        )

        breaker.addListener { oldState, newState ->
            stateChanges.add(oldState to newState)
        }

        // Open the circuit
        shouldThrow<RuntimeException> {
            breaker.execute { throw RuntimeException("Fail") }
        }

        stateChanges.size shouldBe 1
        stateChanges[0] shouldBe (CircuitBreakerState.Closed to CircuitBreakerState.Open)
    }

    @JsName("circuitBreakerConfigValidatesParameters")
    @Test
    fun `circuit breaker config validates parameters`() {
        shouldThrow<IllegalArgumentException> {
            CircuitBreakerConfig(failureThreshold = 0)
        }

        shouldThrow<IllegalArgumentException> {
            CircuitBreakerConfig(resetTimeout = 0.milliseconds)
        }

        shouldThrow<IllegalArgumentException> {
            CircuitBreakerConfig(halfOpenSuccessThreshold = 0)
        }

        shouldThrow<IllegalArgumentException> {
            CircuitBreakerConfig(halfOpenMaxCalls = 0)
        }
    }

    @JsName("circuitBreakerDSLCreatesConfiguredBreaker")
    @Test
    fun `circuitBreaker DSL creates configured breaker`() = runTest {
        val breaker = circuitBreaker {
            failureThreshold = 10
            resetTimeout = 60.seconds
            halfOpenSuccessThreshold = 3
            halfOpenMaxCalls = 5
        }

        breaker.currentState() shouldBe CircuitBreakerState.Closed

        // Should take 10 failures to open
        repeat(9) {
            shouldThrow<RuntimeException> {
                breaker.execute { throw RuntimeException("Fail") }
            }
        }

        breaker.currentState() shouldBe CircuitBreakerState.Closed

        // 10th failure opens it
        shouldThrow<RuntimeException> {
            breaker.execute { throw RuntimeException("Fail") }
        }

        breaker.currentState() shouldBe CircuitBreakerState.Open
    }

    @JsName("circuitBreakerRegistryManagesMultipleBreakers")
    @Test
    fun `CircuitBreakerRegistry manages multiple breakers`() = runTest {
        val registry = CircuitBreakerRegistry()

        val breaker1 = registry.getOrCreate("service1") {
            failureThreshold = 5
        }

        val breaker2 = registry.getOrCreate("service2") {
            failureThreshold = 10
        }

        // Different configurations
        breaker1.currentState() shouldBe CircuitBreakerState.Closed
        breaker2.currentState() shouldBe CircuitBreakerState.Closed

        // Getting same name returns same instance
        val breaker1Again = registry.getOrCreate("service1")
        breaker1Again shouldBe breaker1

        registry.getNames() shouldBe setOf("service1", "service2")
    }

    @JsName("circuitBreakerRegistryGetReturnsExistingBreaker")
    @Test
    fun `CircuitBreakerRegistry get returns existing breaker`() = runTest {
        val registry = CircuitBreakerRegistry()

        registry.get("nonexistent") shouldBe null

        val breaker = registry.getOrCreate("test")
        registry.get("test") shouldBe breaker
    }

    @JsName("circuitBreakerRegistryRemoveRemovesBreaker")
    @Test
    fun `CircuitBreakerRegistry remove removes breaker`() = runTest {
        val registry = CircuitBreakerRegistry()

        val breaker = registry.getOrCreate("test")
        val removed = registry.remove("test")

        removed shouldBe breaker
        registry.get("test") shouldBe null
    }

    @JsName("circuitBreakerRegistryResetAllResetsAllBreakers")
    @Test
    fun `CircuitBreakerRegistry resetAll resets all breakers`() = runTest {
        val registry = CircuitBreakerRegistry()

        val breaker1 = registry.getOrCreate("service1") {
            failureThreshold = 1
        }

        val breaker2 = registry.getOrCreate("service2") {
            failureThreshold = 1
        }

        // Open both circuits
        shouldThrow<RuntimeException> {
            breaker1.execute { throw RuntimeException("Fail") }
        }
        shouldThrow<RuntimeException> {
            breaker2.execute { throw RuntimeException("Fail") }
        }

        breaker1.currentState() shouldBe CircuitBreakerState.Open
        breaker2.currentState() shouldBe CircuitBreakerState.Open

        // Reset all
        registry.resetAll()

        breaker1.currentState() shouldBe CircuitBreakerState.Closed
        breaker2.currentState() shouldBe CircuitBreakerState.Closed
    }

    @JsName("circuitBreakerRegistryGetStatisticsReturnsStatsForAllBreakers")
    @Test
    fun `CircuitBreakerRegistry getStatistics returns stats for all breakers`() = runTest {
        val registry = CircuitBreakerRegistry()

        val breaker1 = registry.getOrCreate("service1") {
            failureThreshold = 3
        }

        // Cause some failures
        repeat(2) {
            shouldThrow<RuntimeException> {
                breaker1.execute { throw RuntimeException("Fail") }
            }
        }

        val stats = registry.getStatistics()

        stats.containsKey("service1") shouldBe true
        stats["service1"]?.state shouldBe CircuitBreakerState.Closed
        stats["service1"]?.failures shouldBe 2
    }

    @JsName("circuitBreakerHandlesConcurrentCallsInHalfOpenState")
    @Test
    fun `circuit breaker handles concurrent calls in half-open state`() = runTest {
        val testClock = TestClock()
        val breaker = CircuitBreaker(
            config = CircuitBreakerConfig(
                failureThreshold = 1,
                resetTimeout = 50.milliseconds,
                halfOpenSuccessThreshold = 2,
                halfOpenMaxCalls = 3
            ),
            clock = testClock
        )

        // Open the circuit
        shouldThrow<RuntimeException> {
            breaker.execute { throw RuntimeException("Fail") }
        }

        testClock.advance(60.milliseconds)

        // Execute successfully in half-open
        breaker.execute { "success 1" }
        breaker.execute { "success 2" }

        breaker.currentState() shouldBe CircuitBreakerState.Closed
    }
}
