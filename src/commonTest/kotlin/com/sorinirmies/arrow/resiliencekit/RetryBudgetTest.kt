// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package com.sorinirmies.arrow.resiliencekit

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

class RetryBudgetTest {

    @JsName("retryBudgetStartsFullyToppedUp")
    @Test
    fun `retry budget starts fully topped up`() = runTest {
        val budget = RetryBudget.create(RetryBudgetConfig(maxTokens = 5.0))
        budget.availableTokens() shouldBe 5.0
    }

    @JsName("retryBudgetTryWithdrawConsumesOneToken")
    @Test
    fun `retry budget tryWithdraw consumes one token`() = runTest {
        val budget = RetryBudget.create(RetryBudgetConfig(maxTokens = 5.0))

        budget.tryWithdraw() shouldBe true
        budget.availableTokens() shouldBe 4.0
    }

    @JsName("retryBudgetTryWithdrawFailsWhenExhausted")
    @Test
    fun `retry budget tryWithdraw fails when exhausted`() = runTest {
        val budget = RetryBudget.create(RetryBudgetConfig(maxTokens = 1.0))

        budget.tryWithdraw() shouldBe true
        budget.tryWithdraw() shouldBe false
        budget.availableTokens() shouldBe 0.0
    }

    @JsName("retryBudgetRecordSuccessDepositsTokenRatioCappedAtMaxTokens")
    @Test
    fun `retry budget recordSuccess deposits tokenRatio capped at maxTokens`() = runTest {
        val budget = RetryBudget.create(RetryBudgetConfig(maxTokens = 1.0, tokenRatio = 0.5))

        budget.tryWithdraw() shouldBe true // balance now 0.0
        budget.recordSuccess() // +0.5 -> 0.5
        budget.availableTokens() shouldBe 0.5

        budget.recordSuccess() // +0.5 -> 1.0
        budget.recordSuccess() // +0.5, capped at maxTokens -> still 1.0
        budget.availableTokens() shouldBe 1.0
    }

    @JsName("retryWithBudgetSucceedsOnFirstAttemptWithoutTouchingTheBudget")
    @Test
    fun `retryWithBudget succeeds on first attempt without touching the budget`() = runTest {
        val budget = RetryBudget.create(RetryBudgetConfig(maxTokens = 3.0))

        val result = retryWithBudget(budget, retries = 3, initialDelay = 1.milliseconds) { "ok" }

        result shouldBe "ok"
        // A successful call deposits a token (capped at maxTokens), it doesn't withdraw one.
        budget.availableTokens() shouldBe 3.0
    }

    @JsName("retryWithBudgetWithdrawsOneTokenPerRetryAttempt")
    @Test
    fun `retryWithBudget withdraws one token per retry attempt`() = runTest {
        val budget = RetryBudget.create(RetryBudgetConfig(maxTokens = 5.0, tokenRatio = 1.0))
        var calls = 0

        val result = retryWithBudget(budget, retries = 3, initialDelay = 1.milliseconds) {
            calls++
            if (calls < 3) throw RuntimeException("flaky") else "ok"
        }

        result shouldBe "ok"
        calls shouldBe 3
        // 2 failed attempts before the 3rd succeeded -> 2 tokens withdrawn, then +1 deposited on success.
        budget.availableTokens() shouldBe 4.0
    }

    @JsName("retryWithBudgetThrowsOriginalExceptionWhenRetriesExhaustedWithBudgetToSpare")
    @Test
    fun `retryWithBudget throws original exception when retries exhausted with budget to spare`() = runTest {
        val budget = RetryBudget.create(RetryBudgetConfig(maxTokens = 10.0))

        val exception = shouldThrow<RuntimeException> {
            retryWithBudget(budget, retries = 2, initialDelay = 1.milliseconds) {
                throw RuntimeException("always fails")
            }
        }

        exception.message shouldBe "always fails"
    }

    @JsName("retryWithBudgetFailsFastWithExhaustedExceptionWhenBudgetRunsOut")
    @Test
    fun `retryWithBudget fails fast with exhausted exception when budget runs out`() = runTest {
        val budget = RetryBudget.create(RetryBudgetConfig(maxTokens = 1.0))
        var calls = 0

        val exception = shouldThrow<RetryBudgetExhaustedException> {
            retryWithBudget(budget, retries = 10, initialDelay = 1.milliseconds) {
                calls++
                throw RuntimeException("always fails")
            }
        }

        // 1 token available: the 1st attempt fails, withdraws the only token for attempt 2,
        // attempt 2 also fails, and attempt 3 can't withdraw -- budget exhausted.
        exception.attempt shouldBe 2L
        calls shouldBe 2
        budget.availableTokens() shouldBe 0.0
    }

    @JsName("retryWithBudgetSharedAcrossMultipleCallSitesThrottlesTotalRetryVolume")
    @Test
    fun `retryWithBudget shared across multiple call sites throttles total retry volume`() = runTest {
        val budget = RetryBudget.create(RetryBudgetConfig(maxTokens = 2.0))
        var totalCalls = 0

        suspend fun alwaysFails(): String {
            totalCalls++
            throw RuntimeException("downstream is down")
        }

        // Three independent callers all hammering the same failing downstream, sharing one budget.
        repeat(3) {
            shouldThrow<Exception> {
                retryWithBudget(budget, retries = 5, initialDelay = 1.milliseconds) { alwaysFails() }
            }
        }

        // Budget caps total retries at 2 (maxTokens), regardless of how many independent callers
        // or how many retries each one requested -- this is the retry-storm protection itself.
        // 3 initial attempts + at most 2 budget-approved retries = at most 5 total calls.
        (totalCalls <= 5) shouldBe true
        budget.availableTokens() shouldBe 0.0
    }
}
