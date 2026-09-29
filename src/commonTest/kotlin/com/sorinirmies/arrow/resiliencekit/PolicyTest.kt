// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package com.sorinirmies.arrow.resiliencekit

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

class PolicyTest {

    @JsName("identityPolicyJustRunsTheBlock")
    @Test
    fun `identity policy just runs the block`() = runTest {
        Policy.identity.apply { "value" } shouldBe "value"
    }

    @JsName("plusComposesOuterWrapsInnerInOrder")
    @Test
    fun `plus composes outer wraps inner in order`() = runTest {
        val trace = mutableListOf<String>()
        val outer = object : Policy {
            override suspend fun <T> apply(block: suspend () -> T): T {
                trace += "outer-enter"
                val result = block()
                trace += "outer-exit"
                return result
            }
        }
        val inner = object : Policy {
            override suspend fun <T> apply(block: suspend () -> T): T {
                trace += "inner-enter"
                val result = block()
                trace += "inner-exit"
                return result
            }
        }

        (outer + inner).apply { trace += "block"; "value" }

        trace shouldBe listOf("outer-enter", "inner-enter", "block", "inner-exit", "outer-exit")
    }

    @JsName("combineFoldsPoliciesLeftToRight")
    @Test
    fun `combine folds policies left to right`() = runTest {
        val trace = mutableListOf<String>()
        fun tracing(name: String) = object : Policy {
            override suspend fun <T> apply(block: suspend () -> T): T {
                trace += "$name-enter"
                val result = block()
                trace += "$name-exit"
                return result
            }
        }

        Policy.combine(tracing("a"), tracing("b"), tracing("c")).apply { trace += "block" }

        trace shouldBe listOf("a-enter", "b-enter", "c-enter", "block", "c-exit", "b-exit", "a-exit")
    }

    @JsName("bulkheadCircuitBreakerAndRetryComposeAsPolicies")
    @Test
    fun `bulkhead circuit breaker and retry compose as policies`() = runTest {
        val bulkhead = Bulkhead.create(BulkheadConfig(maxConcurrentCalls = 2))
        val circuitBreaker = CircuitBreaker.create(CircuitBreakerConfig(failureThreshold = 5))
        val policy = retryPolicy(retries = 2) + circuitBreaker.asPolicy() + bulkhead.asPolicy()

        var attempts = 0
        val result = policy.apply<String> {
            attempts++
            if (attempts < 2) throw IllegalStateException("flaky") else "success"
        }

        result shouldBe "success"
        attempts shouldBe 2
    }

    @JsName("retryIfPolicyRetriesOnlyMatchingExceptions")
    @Test
    fun `retryIfPolicy retries only matching exceptions`() = runTest {
        val policy = retryIfPolicy(
            retries = 3,
            base = 10.milliseconds,
            shouldRetry = { it is IllegalStateException },
        )

        var attempts = 0
        val result = policy.apply {
            attempts++
            if (attempts < 2) throw IllegalStateException("flaky") else "success"
        }

        result shouldBe "success"
        attempts shouldBe 2
    }

    @JsName("retryIfPolicyDoesNotRetryNonMatchingExceptions")
    @Test
    fun `retryIfPolicy does not retry non-matching exceptions`() = runTest {
        val policy = retryIfPolicy(
            retries = 3,
            base = 10.milliseconds,
            shouldRetry = { it is IllegalStateException },
        )

        var attempts = 0
        val exception = shouldThrow<RuntimeException> {
            policy.apply<String> {
                attempts++
                throw RuntimeException("permanent")
            }
        }

        exception.message shouldBe "permanent"
        attempts shouldBe 1
    }

    @JsName("retryIfPolicyComposesWithOtherPolicies")
    @Test
    fun `retryIfPolicy composes with other policies`() = runTest {
        val bulkhead = Bulkhead.create(BulkheadConfig(maxConcurrentCalls = 2))
        val policy = retryIfPolicy(
            retries = 2,
            base = 10.milliseconds,
            shouldRetry = { it is IllegalStateException },
        ) + bulkhead.asPolicy()

        var attempts = 0
        val result = policy.apply<String> {
            attempts++
            if (attempts < 2) throw IllegalStateException("flaky") else "success"
        }

        result shouldBe "success"
        attempts shouldBe 2
    }


    @Test
    fun `adaptive limiter composes as policy`() = runTest {
        val limiter = AdaptiveLimiter.create()
        val policy = limiter.asPolicy()

        val result = policy.apply { "value" }

        result shouldBe "value"
        limiter.statistics().totalCalls shouldBe 1L
    }

    @JsName("hedgePolicyResolvesToFirstSuccessfulAttempt")
    @Test
    fun `hedge policy resolves to first successful attempt`() = runTest {
        val policy = hedgePolicy(hedgeDelay = 10.milliseconds, maxHedges = 2)

        val result = policy.apply { "value" }

        result shouldBe "value"
    }
}
