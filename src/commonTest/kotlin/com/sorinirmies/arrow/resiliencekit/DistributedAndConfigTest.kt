// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package com.sorinirmies.arrow.resiliencekit

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class DistributedAndConfigTest {

    @Test
    fun distributedRateLimiterSharesBudgetAcrossInstances() = runTest {
        val store = InMemorySharedStateStore.create()
        val clock = TestClock()
        val a = DistributedRateLimiter(store, "k", 2, 10.seconds, clock)
        val b = DistributedRateLimiter(store, "k", 2, 10.seconds, clock)
        a.tryAcquire() shouldBe true
        b.tryAcquire() shouldBe true
        a.tryAcquire() shouldBe false
        assertFailsWith<RateLimitExceededException> { b.execute { 1 } }
        clock.advance(11.seconds)
        b.tryAcquire() shouldBe true
    }

    @Test
    fun distributedCircuitBreakerOpensForAllInstancesAndRecovers() = runTest {
        val store = InMemorySharedStateStore.create()
        val clock = TestClock()
        val cfg = CircuitBreakerConfig(failureThreshold = 2, resetTimeout = 5.seconds, halfOpenSuccessThreshold = 1)
        val a = DistributedCircuitBreaker(store, "svc", cfg, clock)
        val b = DistributedCircuitBreaker(store, "svc", cfg, clock)
        repeat(2) { assertFailsWith<IllegalStateException> { a.execute { error("boom") } } }
        b.currentState() shouldBe CircuitBreakerState.Open
        assertFailsWith<CircuitBreakerOpenException> { b.execute { 1 } }
        clock.advance(6.seconds)
        b.execute { 42 } shouldBe 42
        a.currentState() shouldBe CircuitBreakerState.Closed
    }

    @Test
    fun dispatcherBulkheadRunsAndRejects() = runTest {
        val bh = DispatcherBulkhead.create(Dispatchers.Default, BulkheadConfig(maxConcurrentCalls = 1))
        bh.execute { 7 } shouldBe 7
        bh.statistics().successfulCalls shouldBe 1
    }

    @Test
    fun configFromJsonAndProperties() = runTest {
        val json = ResilienceConfig.fromJson(
            """{"circuitBreakers":{"p":{"failureThreshold":3,"resetTimeout":"PT10S"}},"bulkheads":{"b":{"maxConcurrentCalls":2}}}""",
        )
        json.circuitBreakers.getValue("p").toConfig().failureThreshold shouldBe 3
        val props = ResilienceConfig.fromProperties(
            mapOf(
                "circuitBreaker.p.failureThreshold" to "4",
                "circuitBreaker.p.resetTimeout" to "PT20S",
                "timeLimiter.t.timeout" to "2s",
            ),
        )
        props.circuitBreakers.getValue("p").resetTimeout shouldBe 20.seconds
        val inst = ResilienceInstances.create(json + props)
        inst.circuitBreakerNames shouldBe setOf("p")
        inst.bulkhead("b").config.maxConcurrentCalls shouldBe 2
        inst.timeLimiterNames shouldBe setOf("t")
    }
}

private operator fun ResilienceConfig.plus(o: ResilienceConfig) = ResilienceConfig(
    circuitBreakers + o.circuitBreakers, bulkheads + o.bulkheads, rateLimiters + o.rateLimiters, timeLimiters + o.timeLimiters,
)
