// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.js.Promise
import kotlin.test.Test

/**
 * Exercises the JS/TS-facing facades (src/jsMain/kotlin) through their actual `Promise`-based
 * API, the same way a real JS/TS consumer would -- not the underlying Kotlin suspend API these
 * wrap. This is where a bug like the one these tests guard against (a rejected JS Promise's
 * error surfacing as a plain Throwable rather than an Exception, silently bypassing every
 * pattern's internal `catch (e: Exception)` failure accounting -- found by smoke-testing the
 * compiled output directly in Node before this test existed) would actually show up.
 */
class JsFacadeTest {

    @Test
    fun circuitBreakerAccountsForAFailureThrownFromAJsCallback() = runTest {
        val breaker = CircuitBreaker.create(failureThreshold = 1).await()

        breaker.execute({ Promise.resolve("ok") }).await() shouldBe "ok"

        var threw = false
        try {
            breaker.execute({ Promise.reject(Throwable("boom")) }).await()
        } catch (e: Throwable) {
            threw = true
        }
        threw shouldBe true

        // The whole point of the regression test: a failure thrown from the JS-facing block
        // must actually be recorded by the circuit breaker's own internal accounting, not just
        // propagate back out to the (JS) caller while silently bypassing it.
        breaker.failures().await() shouldBe 1
        breaker.currentState().await() shouldBe "Open"
    }

    @Test
    fun bulkheadAccountsForAFailureThrownFromAJsCallback() = runTest {
        val bulkhead = Bulkhead.create(maxConcurrentCalls = 2, maxWaitingCalls = 0).await()

        try {
            bulkhead.execute<String>({ Promise.reject(Throwable("boom")) }).await()
        } catch (e: Throwable) {
            // expected
        }

        bulkhead.statistics().await().failedCalls shouldBe 1.0
    }

    @Test
    fun retryBudgetWithdrawsOneTokenPerRetryAttemptThroughJsCallback() = runTest {
        val budget = RetryBudget.create(maxTokens = 5.0, tokenRatio = 1.0).await()
        var calls = 0

        val result = budget.retry(
            block = {
                calls++
                if (calls < 3) Promise.reject(Throwable("flaky")) else Promise.resolve("ok")
            },
            retries = 5,
            initialDelayMillis = 1.0,
        ).await()

        result shouldBe "ok"
        calls shouldBe 3
        // 2 failed attempts before success -> 2 tokens withdrawn, +1 deposited on success.
        budget.availableTokens().await() shouldBe 4.0
    }

    @Test
    fun slidingWindowCircuitBreakerTripsOnFailureRateThroughJsCallbacks() = runTest {
        val breaker = SlidingWindowCircuitBreaker.create(
            slidingWindowSize = 2,
            minimumNumberOfCalls = 2,
            failureRateThreshold = 0.5,
        ).await()

        repeat(2) {
            try {
                breaker.execute({ Promise.reject(Throwable("boom")) }).await()
            } catch (e: Throwable) {
                // expected
            }
        }

        breaker.currentState().await() shouldBe "Open"
        breaker.statistics().await().failuresInWindow shouldBe 2
    }

    @Test
    fun failoverFallsThroughToNextProviderWhenFirstFailsThroughJsCallback() = runTest {
        var mqttCalls = 0
        val transport = Failover.create(
            arrayOf(
                FailoverProviderSpec<String>("websocket", { Promise.reject(Throwable("ws down")) }, failureThreshold = 1),
                FailoverProviderSpec<String>("mqtt", { mqttCalls++; Promise.resolve("mqtt-ok") }),
            )
        ).await()

        transport.execute().await() shouldBe "mqtt-ok"
        mqttCalls shouldBe 1

        val stats = transport.statistics().await().associateBy { it.name }
        stats.getValue("websocket").failures shouldBe 1.0
        stats.getValue("mqtt").successes shouldBe 1.0
    }

    @Test
    fun cacheGetOrPutComputesThroughJsCallbackOnlyOnce() = runTest {
        val cache = Cache.create<String>(maxSize = 10).await()
        var loaderCalls = 0

        val first = cache.getOrPut("key") { loaderCalls++; Promise.resolve("value") }.await()
        val second = cache.getOrPut("key") { loaderCalls++; Promise.resolve("value-2") }.await()

        first shouldBe "value"
        second shouldBe "value"
        loaderCalls shouldBe 1
    }

    @Test
    fun hedgeResolvesWithFirstSuccessfulJsCallback() = runTest {
        val result = hedge<String>(
            block = { Promise.resolve("ok") },
            hedgeDelayMillis = 10.0,
            maxHedges = 1,
        ).await()

        result shouldBe "ok"
    }

    // ---- Errors reach the JS caller unwrapped ------------------------------------------------

    @Test
    fun rejectionsCarryTheCallersOwnErrorNotTheInternalWrapper() = runTest {
        val original = Throwable("boom")
        val breaker = CircuitBreaker.create(failureThreshold = 5).await()
        val seen = try {
            breaker.execute<String>({ Promise.reject(original) }).await()
            null
        } catch (e: Throwable) {
            e
        }
        (seen === original) shouldBe true
        breaker.failures().await() shouldBe 1 // still accounted for internally
    }

    // ---- Retry.withBackoff -----------------------------------------------------------------------

    @Test
    fun withBackoffPassesTheOriginalErrorAndRetryNumberAndHonorsDelayOverride() = runTest {
        val first = Throwable("rate limited")
        val second = Throwable("not found")
        var calls = 0
        val seen = mutableListOf<Pair<Any?, Int>>()
        val waits = mutableListOf<Double>()
        val options: RetryOptions = js("({})")
        options.asDynamic().retries = 5
        options.asDynamic().shouldRetry = { e: Any?, retry: Int -> seen += e to retry; e === first }
        options.asDynamic().delayMillis = { e: Any?, _: Int -> if (e === first) 7.0 else null }
        options.asDynamic().onRetry = { _: Any?, _: Int, delay: Double -> waits += delay }

        val error = try {
            Retry.withBackoff<String>({ calls++; Promise.reject(if (calls == 1) first else second) }, options).await()
            null
        } catch (e: Throwable) {
            e
        }
        (error === second) shouldBe true
        calls shouldBe 2
        seen.map { it.second } shouldBe listOf(1, 2)
        (seen[0].first === first) shouldBe true
        waits shouldBe listOf(7.0)
    }

    @Test
    fun withBackoffWorksWithoutOptions() = runTest {
        Retry.withBackoff<String>({ Promise.resolve("ok") }).await() shouldBe "ok"
    }

    // ---- Logging ---------------------------------------------------------------------------------

    @Test
    fun loggingIsOffByDefaultAndConfigurable() = runTest {
        CircuitBreaker.create().await() // any facade call applies the JS defaults
        Logging.level() shouldBe "OFF"
        val events = mutableListOf<String>()
        Logging.setLevel("debug")
        Logging.setHandler { level, _, _ -> events += level }
        try {
            Retry.ifMatches<String>({ Promise.reject(Throwable("x")) }, { false }, 1, 1.0).await()
        } catch (_: Throwable) {
            // expected
        }
        events.contains("WARN") shouldBe true
        Logging.setHandler(null)
        Logging.setLevel("OFF")
        Logging.level() shouldBe "OFF"
        val rejected = try {
            Logging.setLevel("LOUD")
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        rejected shouldBe true
    }
}
