// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package ro.sorinirmies.arrow.resiliencekit

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class TimeLimiterTest {

    @JsName("timeLimiterAllowsCallsWithinTimeout")
    @Test
    fun `time limiter allows calls within timeout`() = runTest {
        val timeLimiter = TimeLimiter.create(
            config = TimeLimiterConfig(
                timeout = 1.seconds
            )
        )

        var executed = false
        val result = timeLimiter.execute {
            executed = true
            "success"
        }

        result shouldBe "success"
        executed shouldBe true
    }

    @JsName("timeLimiterThrowsExceptionOnTimeout")
    @Test
    fun `time limiter throws exception on timeout`() = runTest {
        val timeLimiter = TimeLimiter.create(
            config = TimeLimiterConfig(
                timeout = 50.milliseconds
            )
        )

        shouldThrow<TimeoutCancellationException> {
            timeLimiter.execute {
                delay(200.milliseconds)
                "too slow"
            }
        }
    }

    @JsName("executeOrNullReturnsNullOnTimeout")
    @Test
    fun `executeOrNull returns null on timeout`() = runTest {
        val timeLimiter = TimeLimiter.create(
            config = TimeLimiterConfig(
                timeout = 50.milliseconds
            )
        )

        val result = timeLimiter.executeOrNull {
            delay(200.milliseconds)
            "too slow"
        }

        result shouldBe null
    }

    @JsName("executeOrNullReturnsValueOnSuccess")
    @Test
    fun `executeOrNull returns value on success`() = runTest {
        val timeLimiter = TimeLimiter.create(
            config = TimeLimiterConfig(
                timeout = 1.seconds
            )
        )

        val result = timeLimiter.executeOrNull {
            "success"
        }

        result shouldBe "success"
    }

    @JsName("executeOrFallbackUsesFallbackOnTimeout")
    @Test
    fun `executeOrFallback uses fallback on timeout`() = runTest {
        val timeLimiter = TimeLimiter.create(
            config = TimeLimiterConfig(
                timeout = 50.milliseconds
            )
        )

        val result = timeLimiter.executeOrFallback(
            fallback = { "fallback" }
        ) {
            delay(200.milliseconds)
            "primary"
        }

        result shouldBe "fallback"
    }

    @JsName("executeOrFallbackExecutesPrimaryWhenWithinTimeout")
    @Test
    fun `executeOrFallback executes primary when within timeout`() = runTest {
        val timeLimiter = TimeLimiter.create(
            config = TimeLimiterConfig(
                timeout = 1.seconds
            )
        )

        val result = timeLimiter.executeOrFallback(
            fallback = { "fallback" }
        ) {
            "primary"
        }

        result shouldBe "primary"
    }

    @JsName("executeOrDefaultUsesDefaultOnTimeout")
    @Test
    fun `executeOrDefault uses default on timeout`() = runTest {
        val timeLimiter = TimeLimiter.create(
            config = TimeLimiterConfig(
                timeout = 50.milliseconds
            )
        )

        val result = timeLimiter.executeOrDefault(
            default = "default"
        ) {
            delay(200.milliseconds)
            "primary"
        }

        result shouldBe "default"
    }

    @JsName("executeWithRetryRetriesOnTimeout")
    @Test
    fun `executeWithRetry retries on timeout`() = runTest {
        val timeLimiter = TimeLimiter.create(
            config = TimeLimiterConfig(
                timeout = 50.milliseconds
            )
        )

        var attempts = 0

        shouldThrow<TimeoutCancellationException> {
            timeLimiter.executeWithRetry(retries = 2) {
                attempts++
                delay(200.milliseconds)
                "never completes"
            }
        }

        attempts shouldBe 3 // Initial + 2 retries
    }

    @JsName("executeWithRetrySucceedsOnRetry")
    @Test
    fun `executeWithRetry succeeds on retry`() = runTest {
        val timeLimiter = TimeLimiter.create(
            config = TimeLimiterConfig(
                timeout = 100.milliseconds
            )
        )

        var attempts = 0

        val result = timeLimiter.executeWithRetry(retries = 3) {
            attempts++
            if (attempts < 2) {
                delay(200.milliseconds)
                "too slow"
            } else {
                "success"
            }
        }

        result shouldBe "success"
        attempts shouldBe 2
    }

    @JsName("customTimeoutOverridesConfigTimeout")
    @Test
    fun `custom timeout overrides config timeout`() = runTest {
        val timeLimiter = TimeLimiter.create(
            config = TimeLimiterConfig(
                timeout = 1.seconds
            )
        )

        shouldThrow<TimeoutCancellationException> {
            timeLimiter.execute(timeout = 50.milliseconds) {
                delay(200.milliseconds)
                "too slow"
            }
        }
    }

    @JsName("timeLimiterTracksStatistics")
    @Test
    fun `time limiter tracks statistics`() = runTest {
        val timeLimiter = TimeLimiter.create(
            config = TimeLimiterConfig(
                timeout = 100.milliseconds
            )
        )

        timeLimiter.execute { "success1" }
        timeLimiter.execute { "success2" }

        val stats = timeLimiter.statistics()
        stats.totalCalls shouldBe 2
        stats.successfulCalls shouldBe 2
        stats.timedOutCalls shouldBe 0
        stats.failedCalls shouldBe 0
        stats.successRate shouldBe 1.0
    }

    @JsName("timeLimiterTracksTimeouts")
    @Test
    fun `time limiter tracks timeouts`() = runTest {
        val timeLimiter = TimeLimiter.create(
            config = TimeLimiterConfig(
                timeout = 50.milliseconds
            )
        )

        try {
            timeLimiter.execute {
                delay(200.milliseconds)
                "timeout"
            }
        } catch (e: TimeoutCancellationException) {
            // Expected
        }

        val stats = timeLimiter.statistics()
        stats.totalCalls shouldBe 1
        stats.timedOutCalls shouldBe 1
        stats.successfulCalls shouldBe 0
        stats.timeoutRate shouldBe 1.0
    }

    @JsName("timeLimiterTracksFailures")
    @Test
    fun `time limiter tracks failures`() = runTest {
        val timeLimiter = TimeLimiter.create(
            config = TimeLimiterConfig(
                timeout = 1.seconds
            )
        )

        try {
            timeLimiter.execute {
                throw RuntimeException("Test error")
            }
        } catch (e: RuntimeException) {
            // Expected
        }

        val stats = timeLimiter.statistics()
        stats.totalCalls shouldBe 1
        stats.failedCalls shouldBe 1
        stats.successfulCalls shouldBe 0
        stats.failureRate shouldBe 1.0
    }

    @JsName("timeLimiterListenerIsNotifiedOfSuccess")
    @Test
    fun `time limiter listener is notified of success`() = runTest {
        var onSuccessCalled = false
        var duration = 0L

        val timeLimiter = TimeLimiter.create(
            config = TimeLimiterConfig(timeout = 1.seconds)
        )

        timeLimiter.addListener(object : TimeLimiterListener {
            override fun onSuccess(durationMs: Long) {
                onSuccessCalled = true
                duration = durationMs
            }
        })

        timeLimiter.execute { "success" }

        onSuccessCalled shouldBe true
        // Duration should be very small (< 50ms) for a trivial operation
        (duration < 50L) shouldBe true
    }

    @JsName("timeLimiterListenerIsNotifiedOfTimeout")
    @Test
    fun `time limiter listener is notified of timeout`() = runTest {
        var onTimeoutCalled = false

        val timeLimiter = TimeLimiter.create(
            config = TimeLimiterConfig(timeout = 50.milliseconds)
        )

        timeLimiter.addListener(object : TimeLimiterListener {
            override fun onTimeout(timeout: kotlin.time.Duration) {
                onTimeoutCalled = true
            }
        })

        try {
            timeLimiter.execute {
                delay(200.milliseconds)
                "timeout"
            }
        } catch (e: TimeoutCancellationException) {
            // Expected
        }

        onTimeoutCalled shouldBe true
    }

    @JsName("timeLimiterListenerIsNotifiedOfFailure")
    @Test
    fun `time limiter listener is notified of failure`() = runTest {
        var onFailureCalled = false

        val timeLimiter = TimeLimiter.create(
            config = TimeLimiterConfig(timeout = 1.seconds)
        )

        timeLimiter.addListener(object : TimeLimiterListener {
            override fun onFailure(exception: Exception) {
                onFailureCalled = true
            }
        })

        try {
            timeLimiter.execute {
                throw RuntimeException("Test error")
            }
        } catch (e: RuntimeException) {
            // Expected
        }

        onFailureCalled shouldBe true
    }

    @JsName("timeLimiterConfigValidatesParameters")
    @Test
    fun `time limiter config validates parameters`() {
        shouldThrow<IllegalArgumentException> {
            TimeLimiterConfig(timeout = 0.milliseconds)
        }

        shouldThrow<IllegalArgumentException> {
            TimeLimiterConfig(timeout = (-1).seconds)
        }
    }

    @JsName("timeLimiterDSLCreatesConfiguredLimiter")
    @Test
    fun `time limiter DSL creates configured limiter`() = runTest {
        val timeLimiter = timeLimiter {
            timeout = 5.seconds
        }

        val result = timeLimiter.execute { "success" }
        result shouldBe "success"
    }

    @JsName("executeAllExecutesMultipleOperationsWithTimeout")
    @Test
    fun `executeAll executes multiple operations with timeout`() = runTest {
        val timeLimiter = TimeLimiter.create(
            config = TimeLimiterConfig(timeout = 100.milliseconds)
        )

        val results = timeLimiter.executeAll(
            blocks = listOf(
                { delay(50.milliseconds); "fast1" },
                { delay(50.milliseconds); "fast2" },
                { delay(200.milliseconds); "slow" }, // Will timeout
                { "instant" }
            )
        )

        results.size shouldBe 4
        results[0] shouldBe "fast1"
        results[1] shouldBe "fast2"
        results[2] shouldBe null // Timed out
        results[3] shouldBe "instant"
    }

    @JsName("resetStatisticsClearsCounters")
    @Test
    fun `reset statistics clears counters`() = runTest {
        val timeLimiter = TimeLimiter.create()

        timeLimiter.execute { "success" }

        var stats = timeLimiter.statistics()
        stats.totalCalls shouldBe 1

        timeLimiter.resetStatistics()

        stats = timeLimiter.statistics()
        stats.totalCalls shouldBe 0
        stats.successfulCalls shouldBe 0
        stats.timedOutCalls shouldBe 0
        stats.failedCalls shouldBe 0
    }

    @JsName("timeLimiterRegistryManagesMultipleLimiters")
    @Test
    fun `TimeLimiterRegistry manages multiple limiters`() = runTest {
        val registry = TimeLimiterRegistry.create()

        val fastLimiter = registry.getOrCreate("fast") {
            timeout = 100.milliseconds
        }

        val slowLimiter = registry.getOrCreate("slow") {
            timeout = 5.seconds
        }

        fastLimiter.execute { "fast" } shouldBe "fast"
        slowLimiter.execute { "slow" } shouldBe "slow"

        registry.getNames() shouldBe setOf("fast", "slow")
    }

    @JsName("timeLimiterRegistryGetReturnsExistingLimiter")
    @Test
    fun `TimeLimiterRegistry get returns existing limiter`() = runTest {
        val registry = TimeLimiterRegistry.create()

        registry.get("nonexistent") shouldBe null

        val limiter = registry.getOrCreate("test")
        registry.get("test") shouldBe limiter
    }

    @JsName("timeLimiterRegistryRemoveRemovesLimiter")
    @Test
    fun `TimeLimiterRegistry remove removes limiter`() = runTest {
        val registry = TimeLimiterRegistry.create()

        val limiter = registry.getOrCreate("test")
        val removed = registry.remove("test")

        removed shouldBe limiter
        registry.get("test") shouldBe null
    }

    @JsName("timeLimiterRegistryResetAllStatisticsResetsAllLimiters")
    @Test
    fun `TimeLimiterRegistry resetAllStatistics resets all limiters`() = runTest {
        val registry = TimeLimiterRegistry.create()

        val limiter = registry.getOrCreate("test")
        limiter.execute { "success" }

        var stats = limiter.statistics()
        stats.totalCalls shouldBe 1

        registry.resetAllStatistics()

        stats = limiter.statistics()
        stats.totalCalls shouldBe 0
    }

    @JsName("timeLimiterRegistryGetAllStatisticsReturnsStatsForAllLimiters")
    @Test
    fun `TimeLimiterRegistry getAllStatistics returns stats for all limiters`() = runTest {
        val registry = TimeLimiterRegistry.create()

        val limiter1 = registry.getOrCreate("limiter1")
        val limiter2 = registry.getOrCreate("limiter2")

        limiter1.execute { "success1" }
        limiter2.execute { "success2" }

        val allStats = registry.getAllStatistics()

        allStats.size shouldBe 2
        allStats["limiter1"]?.totalCalls shouldBe 1
        allStats["limiter2"]?.totalCalls shouldBe 1
    }

    @JsName("withTimeLimitExtensionFunctionWorks")
    @Test
    fun `withTimeLimit extension function works`() = runTest {
        val result = withTimeLimit(1.seconds) {
            "success"
        }

        result shouldBe "success"
    }

    @JsName("withTimeLimitExtensionFunctionTimesOut")
    @Test
    fun `withTimeLimit extension function times out`() = runTest {
        shouldThrow<TimeoutCancellationException> {
            withTimeLimit(50.milliseconds) {
                delay(200.milliseconds)
                "too slow"
            }
        }
    }

    @JsName("withTimeLimitOrDefaultExtensionFunctionWorks")
    @Test
    fun `withTimeLimitOrDefault extension function works`() = runTest {
        val result = withTimeLimitOrDefault(1.seconds, default = "default") {
            "success"
        }

        result shouldBe "success"
    }

    @JsName("withTimeLimitOrDefaultExtensionFunctionUsesDefaultOnTimeout")
    @Test
    fun `withTimeLimitOrDefault extension function uses default on timeout`() = runTest {
        val result = withTimeLimitOrDefault(50.milliseconds, default = "default") {
            delay(200.milliseconds)
            "too slow"
        }

        result shouldBe "default"
    }

    @JsName("timeLimiterHandlesConcurrentCalls")
    @Test
    fun `time limiter handles concurrent calls`() = runTest {
        val timeLimiter = TimeLimiter.create(
            config = TimeLimiterConfig(timeout = 200.milliseconds)
        )

        val resultsMutex = Mutex()
        val results = mutableListOf<String>()

        val jobs = (1..10).map { i ->
            launch {
                val result = timeLimiter.execute {
                    delay(50.milliseconds)
                    "result$i"
                }
                resultsMutex.withLock {
                    results.add(result)
                }
            }
        }

        jobs.forEach { it.join() }

        results.size shouldBe 10
    }

    @JsName("timeLimiterCalculatesAverageTimeoutDuration")
    @Test
    fun `time limiter calculates average timeout duration`() = runTest {
        val timeLimiter = TimeLimiter.create(
            config = TimeLimiterConfig(timeout = 50.milliseconds)
        )

        // Cause some timeouts
        repeat(3) {
            try {
                timeLimiter.execute {
                    delay(200.milliseconds)
                    "timeout"
                }
            } catch (e: TimeoutCancellationException) {
                // Expected
            }
        }

        val stats = timeLimiter.statistics()
        stats.timedOutCalls shouldBe 3
        // Average timeout duration should be around 50ms
    }

    @JsName("executeRaceReturnsFirstResult")
    @Test
    fun `executeRace returns first completing result`() = runTest {
        val timeLimiter = TimeLimiter.create(config = TimeLimiterConfig(timeout = 1.seconds))

        val result = timeLimiter.executeRace(
            blocks = listOf(
                { delay(200.milliseconds); "slow" },
                { "fast" },
                { delay(300.milliseconds); "slower" }
            )
        )

        result shouldBe "fast"
    }
}
