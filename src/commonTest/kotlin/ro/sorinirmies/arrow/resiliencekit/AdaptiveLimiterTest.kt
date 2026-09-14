// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package ro.sorinirmies.arrow.resiliencekit

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

class AdaptiveLimiterTest {

    @JsName("adaptiveLimiterAllowsCallsWithinTheInitialLimit")
    @Test
    fun `adaptive limiter allows calls within the initial limit`() = runTest {
        val limiter = AdaptiveLimiter.create(AdaptiveLimiterConfig(initialLimit = 5))

        val result = limiter.execute { "ok" }

        result shouldBe "ok"
        limiter.statistics().totalCalls shouldBe 1L
    }

    @JsName("adaptiveLimiterGrowsTheLimitAfterSuccessfulCalls")
    @Test
    fun `adaptive limiter grows the limit after successful calls`() = runTest {
        val limiter = AdaptiveLimiter.create(
            AdaptiveLimiterConfig(initialLimit = 2, maxLimit = 10, increaseStep = 1),
        )

        repeat(3) { limiter.execute { "ok" } }

        limiter.currentLimit() shouldBe 5
    }

    @JsName("adaptiveLimiterShrinksTheLimitOnFailure")
    @Test
    fun `adaptive limiter shrinks the limit on failure`() = runTest {
        val limiter = AdaptiveLimiter.create(
            AdaptiveLimiterConfig(initialLimit = 10, minLimit = 1, decreaseFactor = 0.5),
        )

        try {
            limiter.execute { throw IllegalStateException("boom") }
        } catch (_: IllegalStateException) {
            // expected
        }

        limiter.currentLimit() shouldBe 5
        limiter.statistics().overloadEvents shouldBe 1L
    }

    @JsName("adaptiveLimiterNeverShrinksBelowMinLimit")
    @Test
    fun `adaptive limiter never shrinks below minLimit`() = runTest {
        val limiter = AdaptiveLimiter.create(
            AdaptiveLimiterConfig(initialLimit = 2, minLimit = 2, decreaseFactor = 0.1),
        )

        repeat(5) {
            try {
                limiter.execute { throw IllegalStateException("boom") }
            } catch (_: IllegalStateException) {
                // expected
            }
        }

        limiter.currentLimit() shouldBe 2
    }

    @JsName("adaptiveLimiterNeverGrowsAboveMaxLimit")
    @Test
    fun `adaptive limiter never grows above maxLimit`() = runTest {
        val limiter = AdaptiveLimiter.create(
            AdaptiveLimiterConfig(initialLimit = 9, maxLimit = 10, increaseStep = 5),
        )

        repeat(5) { limiter.execute { "ok" } }

        limiter.currentLimit() shouldBe 10
    }

    @JsName("adaptiveLimiterBlocksAdmissionUntilASlotFreesUp")
    @Test
    fun `adaptive limiter blocks admission until a slot frees up`() = runTest {
        val limiter = AdaptiveLimiter.create(
            AdaptiveLimiterConfig(initialLimit = 1, increaseStep = 1),
        )
        var concurrentPeak = 0
        var active = 0

        val jobs = (1..3).map {
            async {
                limiter.execute {
                    active++
                    concurrentPeak = maxOf(concurrentPeak, active)
                    kotlinx.coroutines.yield()
                    active--
                    "done"
                }
            }
        }
        jobs.awaitAll()

        (concurrentPeak <= 2) shouldBe true
        limiter.statistics().totalCalls shouldBe 3L
    }

    @JsName("adaptiveLimiterTreatsSlowCallsAsOverloadWhenLatencyThresholdIsSet")
    @Test
    fun `adaptive limiter treats slow calls as overload when latency threshold is set`() = runTest {
        val clock = TestClock()
        val limiter = AdaptiveLimiter.create(
            AdaptiveLimiterConfig(
                initialLimit = 10,
                decreaseFactor = 0.5,
                latencyThreshold = 50.milliseconds,
            ),
            clock = clock,
        )

        limiter.execute {
            clock.advance(100.milliseconds)
            "slow-but-successful"
        }

        limiter.currentLimit() shouldBe 5
        limiter.statistics().overloadEvents shouldBe 1L
    }
}
