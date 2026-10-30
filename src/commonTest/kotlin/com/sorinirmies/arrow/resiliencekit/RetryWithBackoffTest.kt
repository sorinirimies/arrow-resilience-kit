// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package com.sorinirmies.arrow.resiliencekit

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private class HttpException(val status: Int, val retryAfter: Duration? = null) : Exception("HTTP $status")

class RetryWithBackoffTest {

    @JsName("backoffGrowsByFactorAndIsCapped")
    @Test
    fun `backoff grows by factor and is capped at maxDelay`() = runTest {
        val waits = mutableListOf<Duration>()
        var calls = 0
        shouldThrow<HttpException> {
            retryWithBackoff(
                retries = 4,
                base = 100.milliseconds,
                factor = 3.0,
                maxDelay = 500.milliseconds,
                jitterFactor = 0.0,
                onRetry = { _, _, wait -> waits += wait },
            ) {
                calls++
                throw HttpException(503)
            }
        }
        calls shouldBe 5
        waits shouldBe listOf(100.milliseconds, 300.milliseconds, 500.milliseconds, 500.milliseconds)
        testScheduler.currentTime shouldBe 1_400L
    }

    @JsName("onlyRetriesWhatShouldRetryAccepts")
    @Test
    fun `only retries failures shouldRetry accepts with 1-based retry numbers`() = runTest {
        val seen = mutableListOf<Pair<Int, Long>>()
        var calls = 0
        val error = shouldThrow<HttpException> {
            retryWithBackoff(
                retries = 5,
                jitterFactor = 0.0,
                shouldRetry = { e, retry -> seen += (e as HttpException).status to retry; e.status >= 500 },
            ) {
                calls++
                throw HttpException(if (calls == 1) 503 else 404)
            }
        }
        error.status shouldBe 404
        calls shouldBe 2
        seen shouldBe listOf(503 to 1L, 404 to 2L)
    }

    @JsName("delayForOverridesBackoff")
    @Test
    fun `delayFor overrides the backoff exactly and negative delays count as zero`() = runTest {
        val waits = mutableListOf<Duration>()
        var calls = 0
        val result = retryWithBackoff(
            retries = 3,
            base = 100.milliseconds,
            maxDelay = 1.seconds,
            delayFor = { e, _ -> (e as HttpException).retryAfter },
            onRetry = { _, _, wait -> waits += wait },
        ) {
            calls++
            when (calls) {
                1 -> throw HttpException(429, retryAfter = 5.seconds) // above maxDelay: still honored
                2 -> throw HttpException(429, retryAfter = (-1).seconds)
                else -> "ok"
            }
        }
        result shouldBe "ok"
        waits shouldBe listOf(5.seconds, Duration.ZERO)
        testScheduler.currentTime shouldBe 5_000L
    }

    @JsName("jitterStaysWithinBounds")
    @Test
    fun `jitterFactor keeps the computed delay within plus-minus jitter`() = runTest {
        val waits = mutableListOf<Duration>()
        shouldThrow<HttpException> {
            retryWithBackoff(retries = 20, base = 1.seconds, factor = 1.0, jitterFactor = 0.5, onRetry = { _, _, w -> waits += w }) {
                throw HttpException(500)
            }
        }
        waits.forEach { check(it >= 500.milliseconds && it <= 1_500.milliseconds) { "out of range: $it" } }
    }

    @JsName("zeroRetriesRunsOnce")
    @Test
    fun `zero retries runs once and success needs no waiting`() = runTest {
        var calls = 0
        shouldThrow<HttpException> { retryWithBackoff(retries = 0) { calls++; throw HttpException(500) } }
        calls shouldBe 1
        retryWithBackoff { "ok" } shouldBe "ok"
        testScheduler.currentTime shouldBe 0L
    }

    @JsName("cancellationIsNotRetried")
    @Test
    fun `cancellation is never retried and cancels the wait`() = runTest {
        var calls = 0
        val job = launch {
            retryWithBackoff(retries = 10, base = 10.seconds, jitterFactor = 0.0) { calls++; throw HttpException(500) }
        }
        runCurrent()
        advanceTimeBy(1.seconds)
        job.cancel()
        runCurrent()
        job.isCancelled shouldBe true
        calls shouldBe 1

        var cancelCalls = 0
        shouldThrow<CancellationException> {
            retryWithBackoff(retries = 3) { cancelCalls++; throw CancellationException("stop") }
        }
        cancelCalls shouldBe 1
    }

    @JsName("rejectsInvalidArguments")
    @Test
    fun `rejects invalid arguments`() = runTest {
        shouldThrow<IllegalArgumentException> { retryWithBackoff(retries = -1) { 1 } }
        shouldThrow<IllegalArgumentException> { retryWithBackoff(base = (-1).milliseconds) { 1 } }
        shouldThrow<IllegalArgumentException> { retryWithBackoff(factor = 0.0) { 1 } }
        shouldThrow<IllegalArgumentException> { retryWithBackoff(maxDelay = Duration.ZERO) { 1 } }
        shouldThrow<IllegalArgumentException> { retryWithBackoff(jitterFactor = 1.5) { 1 } }
    }
}
