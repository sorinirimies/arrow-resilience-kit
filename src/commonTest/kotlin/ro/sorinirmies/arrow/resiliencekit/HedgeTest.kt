// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package ro.sorinirmies.arrow.resiliencekit

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HedgeTest {

    @JsName("hedgeReturnsPrimaryResultWhenItIsFastEnough")
    @Test
    fun `hedge returns primary result when it is fast enough`() = runTest {
        var hedgeCalls = 0
        val result = hedge(hedgeDelay = 200.milliseconds, maxHedges = 1) {
            hedgeCalls++
            "primary"
        }

        result shouldBe "primary"
        hedgeCalls shouldBe 1
    }

    @JsName("hedgeFiresBackupAttemptWhenPrimaryIsSlow")
    @Test
    fun `hedge fires backup attempt when primary is slow`() = runTest {
        var attemptCount = 0
        val result = hedge(hedgeDelay = 10.milliseconds, maxHedges = 1) {
            attemptCount++
            val attempt = attemptCount
            if (attempt == 1) {
                delay(1.seconds)
                "slow-primary"
            } else {
                "fast-hedge"
            }
        }

        result shouldBe "fast-hedge"
        attemptCount shouldBe 2
    }

    @JsName("hedgeThrowsLastFailureWhenAllAttemptsFail")
    @Test
    fun `hedge throws last failure when all attempts fail`() = runTest {
        shouldThrow<IllegalStateException> {
            hedge(hedgeDelay = 5.milliseconds, maxHedges = 2) {
                throw IllegalStateException("boom")
            }
        }
    }

    @JsName("hedgeSucceedsIfAnyAttemptSucceedsEvenAfterEarlierFailures")
    @Test
    fun `hedge succeeds if any attempt succeeds even after earlier failures`() = runTest {
        var attemptCount = 0
        val result = hedge(hedgeDelay = 10.milliseconds, maxHedges = 2) {
            attemptCount++
            val attempt = attemptCount
            if (attempt <= 2) {
                throw IllegalStateException("fails-$attempt")
            }
            "eventually-succeeds"
        }

        result shouldBe "eventually-succeeds"
    }
}
