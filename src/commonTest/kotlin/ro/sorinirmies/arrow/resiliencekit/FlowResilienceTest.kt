// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package ro.sorinirmies.arrow.resiliencekit

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

class FlowResilienceTest {

    @JsName("retryWithBackoffRetriesAFailingFlowUntilItSucceeds")
    @Test
    fun `retryWithBackoff retries a failing flow until it succeeds`() = runTest {
        var attempts = 0
        val values = flow {
            attempts++
            if (attempts < 3) throw IllegalStateException("flaky")
            emit(1)
            emit(2)
        }.retryWithBackoff(retries = 5, base = 1.milliseconds)
            .toList()

        values shouldBe listOf(1, 2)
        attempts shouldBe 3
    }

    @JsName("retryWithBackoffGivesUpAfterExhaustingRetries")
    @Test
    fun `retryWithBackoff gives up after exhausting retries`() = runTest {
        var attempts = 0
        val flowUnderTest = flow<Int> {
            attempts++
            throw IllegalStateException("always fails")
        }.retryWithBackoff(retries = 2, base = 1.milliseconds)

        shouldThrow<IllegalStateException> { flowUnderTest.toList() }
        attempts shouldBe 3
    }

    @JsName("throughBulkheadProtectsFlowCollectionAsASingleCall")
    @Test
    fun `throughBulkhead protects flow collection as a single call`() = runTest {
        val bulkhead = Bulkhead.create(BulkheadConfig(maxConcurrentCalls = 1))

        val values = flow {
            emit(1)
            emit(2)
        }.throughBulkhead(bulkhead).toList()

        values shouldBe listOf(1, 2)
        bulkhead.statistics().totalCalls shouldBe 1L
    }

    @JsName("throughCircuitBreakerFailsFastWhenTheBreakerIsOpen")
    @Test
    fun `throughCircuitBreaker fails fast when the breaker is open`() = runTest {
        val circuitBreaker = CircuitBreaker.create(CircuitBreakerConfig(failureThreshold = 1))
        try {
            flow<Int> { throw IllegalStateException("boom") }
                .throughCircuitBreaker(circuitBreaker)
                .toList()
        } catch (_: IllegalStateException) {
            // opens the breaker
        }

        shouldThrow<CircuitBreakerOpenException> {
            flow { emit(1) }.throughCircuitBreaker(circuitBreaker).toList()
        }
    }
}
