// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package ro.sorinirmies.arrow.resiliencekit

import io.kotest.matchers.shouldBe
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

class StatisticsSerializationTest {

    private val json = Json { prettyPrint = false }

    @JsName("bulkheadStatisticsRoundTripsThroughJson")
    @Test
    fun `bulkhead statistics round-trips through json`() {
        val stats = BulkheadStatistics(
            totalCalls = 10,
            successfulCalls = 8,
            failedCalls = 1,
            rejectedCalls = 1,
            availableCapacity = 3,
            utilizationRate = 0.7,
        )

        val encoded = json.encodeToString(stats)
        val decoded = json.decodeFromString<BulkheadStatistics>(encoded)

        decoded shouldBe stats
    }

    @JsName("circuitBreakerStatsRoundTripsThroughJsonIncludingState")
    @Test
    fun `circuit breaker stats round-trips through json including state`() {
        val stats = CircuitBreakerStats(state = CircuitBreakerState.HalfOpen, failures = 2, successes = 1)

        val encoded = json.encodeToString(stats)
        val decoded = json.decodeFromString<CircuitBreakerStats>(encoded)

        decoded shouldBe stats
    }

    @JsName("timeLimiterStatisticsRoundTripsDurationAsMilliseconds")
    @Test
    fun `time limiter statistics round-trips duration as milliseconds`() {
        val stats = TimeLimiterStatistics(
            totalCalls = 5,
            successfulCalls = 4,
            timedOutCalls = 1,
            failedCalls = 0,
            averageTimeoutDuration = 250.milliseconds,
        )

        val encoded = json.encodeToString(stats)
        encoded shouldBe """{"totalCalls":5,"successfulCalls":4,"timedOutCalls":1,"failedCalls":0,"averageTimeoutDuration":250}"""

        val decoded = json.decodeFromString<TimeLimiterStatistics>(encoded)
        decoded shouldBe stats
    }

    @JsName("adaptiveLimiterStatisticsRoundTripsThroughJson")
    @Test
    fun `adaptive limiter statistics round-trips through json`() {
        val stats = AdaptiveLimiterStatistics(currentLimit = 12, inFlight = 3, totalCalls = 100, overloadEvents = 2)

        val encoded = json.encodeToString(stats)
        val decoded = json.decodeFromString<AdaptiveLimiterStatistics>(encoded)

        decoded shouldBe stats
    }
}
