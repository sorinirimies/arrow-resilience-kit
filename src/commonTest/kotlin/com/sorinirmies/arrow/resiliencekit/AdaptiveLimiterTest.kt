// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package com.sorinirmies.arrow.resiliencekit

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

        repeat(3) { limiter.execute<String> { "ok" } }

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

        repeat(5) { limiter.execute<String> { "ok" } }

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

    @JsName("adaptiveLimiterRegistryReusesInstanceByName")
    @Test
    fun `AdaptiveLimiterRegistry reuses instance by name`() = runTest {
        val registry = AdaptiveLimiterRegistry.create()

        val first = registry.getOrCreate("downstream-api") { initialLimit = 20 }
        val second = registry.getOrCreate("downstream-api") { initialLimit = 99 }

        first shouldBe second
        second.statistics().currentLimit shouldBe 20
        registry.getNames() shouldBe setOf("downstream-api")
    }

    @JsName("adaptiveLimiterRegistryGetAndRemove")
    @Test
    fun `AdaptiveLimiterRegistry get and remove`() = runTest {
        val registry = AdaptiveLimiterRegistry.create()
        registry.get("downstream-api") shouldBe null

        registry.getOrCreate("downstream-api") { initialLimit = 20 }
        registry.get("downstream-api")?.statistics()?.currentLimit shouldBe 20

        val removed = registry.remove("downstream-api")
        removed?.statistics()?.currentLimit shouldBe 20
        registry.get("downstream-api") shouldBe null
    }

    @JsName("adaptiveLimiterRegistryGetAllStatisticsReturnsSnapshotForEachLimiter")
    @Test
    fun `AdaptiveLimiterRegistry getAllStatistics returns snapshot for each limiter`() = runTest {
        val registry = AdaptiveLimiterRegistry.create()
        registry.getOrCreate("a") { initialLimit = 10 }
        registry.getOrCreate("b") { initialLimit = 20 }

        val stats = registry.getAllStatistics()
        stats.keys shouldBe setOf("a", "b")
        stats.getValue("a").currentLimit shouldBe 10
        stats.getValue("b").currentLimit shouldBe 20
    }
}
