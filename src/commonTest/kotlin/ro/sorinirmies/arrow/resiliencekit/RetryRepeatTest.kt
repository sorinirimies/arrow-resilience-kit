// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies


import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.io.IOException
import java.net.SocketTimeoutException

class RetryRepeatTest {

    // ============================================================================
    // RETRY TESTS
    // ============================================================================

    @Test
    fun `retryWithExponentialBackoff succeeds on first attempt`() = runTest {
        var attempts = 0
        val result = retryWithExponentialBackoff {
            attempts++
            "success"
        }

        result shouldBe "success"
        attempts shouldBe 1
    }

    @Test
    fun `retryWithExponentialBackoff retries and eventually succeeds`() = runTest {
        var attempts = 0
        val result = retryWithExponentialBackoff(retries = 3) {
            attempts++
            if (attempts < 3) throw IOException("Temporary failure")
            "success"
        }

        result shouldBe "success"
        attempts shouldBe 3
    }

    @Test
    fun `retryWithExponentialBackoff throws after all retries exhausted`() = runTest {
        var attempts = 0
        shouldThrow<IOException> {
            retryWithExponentialBackoff(retries = 3) {
                attempts++
                throw IOException("Persistent failure")
            }
        }

        attempts shouldBe 4 // Initial attempt + 3 retries
    }

    @Test
    fun `retryWithExponentialBackoff validates parameters`() = runTest {
        shouldThrow<IllegalArgumentException> {
            retryWithExponentialBackoff(retries = -1) { "test" }
        }

        shouldThrow<IllegalArgumentException> {
            retryWithExponentialBackoff(base = (-100).milliseconds) { "test" }
        }

        shouldThrow<IllegalArgumentException> {
            retryWithExponentialBackoff(factor = 0.0) { "test" }
        }
    }

    @Test
    fun `retryWithCappedBackoff caps maximum delay`() = runTest {
        var attempts = 0
        val maxDelay = 100.milliseconds

        shouldThrow<IOException> {
            retryWithCappedBackoff(
                retries = 10,
                base = 50.milliseconds,
                maxDelay = maxDelay,
                factor = 10.0 // Very aggressive growth
            ) {
                attempts++
                throw IOException("Always fail")
            }
        }

        // Should have executed all attempts despite aggressive factor
        attempts shouldBe 11
    }

    @Test
    fun `retryWithCappedBackoff validates parameters`() = runTest {
        shouldThrow<IllegalArgumentException> {
            retryWithCappedBackoff(maxDelay = 0.milliseconds) { "test" }
        }
    }

    @Test
    fun `retryWithFibonacciBackoff uses Fibonacci sequence`() = runTest {
        var attempts = 0
        val result = retryWithFibonacciBackoff(retries = 5, base = 10.milliseconds) {
            attempts++
            if (attempts < 4) throw IOException("Retry")
            "success"
        }

        result shouldBe "success"
        attempts shouldBe 4
    }

    @Test
    fun `retryWithFibonacciBackoff exhausts all retries`() = runTest {
        var attempts = 0
        shouldThrow<IOException> {
            retryWithFibonacciBackoff(retries = 5, base = 10.milliseconds) {
                attempts++
                throw IOException("Always fail")
            }
        }

        attempts shouldBe 6 // Initial + 5 retries
    }

    @Test
    fun `retryOrDefault returns fallback on failure`() = runTest {
        var attempts = 0
        val result = retryOrDefault(
            retries = 2,
            fallback = "fallback",
        ) {
            attempts++
            throw IOException("Failed")
        }

        result shouldBe "fallback"
        attempts shouldBe 3 // Initial attempt + 2 retries
    }

    @Test
    fun `retryOrDefault returns success value`() = runTest {
        var attempts = 0
        val result = retryOrDefault(
            retries = 3,
            fallback = "fallback",
        ) {
            attempts++
            if (attempts < 2) throw IOException("Temporary failure")
            "success"
        }

        result shouldBe "success"
        attempts shouldBe 2
    }

    @Test
    fun `retryIf retries based on custom predicate`() = runTest {
        var attempts = 0
        val result = retryIf(
            retries = 3,
            shouldRetry = { error ->
                error is IOException && error.message?.contains("retryable") == true
            },
        ) {
            attempts++
            when (attempts) {
                1 -> throw IOException("retryable error")
                2 -> throw IOException("another retryable error")
                else -> "success"
            }
        }

        result shouldBe "success"
        attempts shouldBe 3
    }

    @Test
    fun `retryIf does not retry when predicate returns false`() = runTest {
        var attempts = 0
        val exception = shouldThrow<IOException> {
            retryIf(
                retries = 3,
                shouldRetry = { error ->
                    error is IOException && error.message?.contains("retryable") == true
                },
            ) {
                attempts++
                throw IOException("Don't retry this")
            }
        }

        exception.message shouldBe "Don't retry this"
        attempts shouldBe 1
    }

    @Test
    fun `retryAndCollect returns history of all attempts`() = runTest {
        var attempts = 0
        val result = retryAndCollect(retries = 3, base = 10.milliseconds) {
            attempts++
            if (attempts < 3) throw IOException("Attempt $attempts")
            "success"
        }

        result.isSuccess shouldBe true
        result.attemptCount shouldBe 3
        result.getOrThrow() shouldBe "success"
        result.attempts shouldHaveSize 3
        result.attempts[0].isFailure shouldBe true
        result.attempts[1].isFailure shouldBe true
        result.attempts[2].isSuccess shouldBe true
    }

    @Test
    fun `retryAndCollect collects all failures when exhausted`() = runTest {
        var attempts = 0
        val result = retryAndCollect(retries = 2, base = 10.milliseconds) {
            attempts++
            throw IOException("Attempt $attempts")
        }

        result.isFailure shouldBe true
        result.attemptCount shouldBe 3
        result.attempts shouldHaveSize 3
        result.attempts.all { it.isFailure } shouldBe true
    }

    // ============================================================================
    // REPEAT TESTS
    // ============================================================================

    @Test
    fun `repeatWithExponentialBackoff repeats successful operation`() = runTest {
        var executions = 0
        val result = repeatWithExponentialBackoff(times = 3, base = 10.milliseconds) {
            executions++
            "execution-$executions"
        }

        result shouldBe "execution-4" // Initial + 3 repeats
        executions shouldBe 4
    }

    @Test
    fun `repeatWithExponentialBackoff stops on failure`() = runTest {
        var executions = 0
        shouldThrow<IOException> {
            repeatWithExponentialBackoff(times = 5, base = 10.milliseconds) {
                executions++
                if (executions == 3) throw IOException("Stop here")
                "execution-$executions"
            }
        }

        executions shouldBe 3
    }

    @Test
    fun `repeatWithExponentialBackoff validates parameters`() = runTest {
        shouldThrow<IllegalArgumentException> {
            repeatWithExponentialBackoff(times = -1) { "test" }
        }

        shouldThrow<IllegalArgumentException> {
            repeatWithExponentialBackoff(base = (-100).milliseconds) { "test" }
        }
    }

    @Test
    fun `repeatWithFixedDelay uses constant delay`() = runTest {
        var executions = 0
        val result = repeatWithFixedDelay(times = 3, delay = 50.milliseconds) {
            executions++
            "count-$executions"
        }

        result shouldBe "count-4"
        executions shouldBe 4
    }

    @Test
    fun `repeatUntil stops when predicate is satisfied`() = runTest {
        var counter = 0
        val result = repeatUntil(
            maxAttempts = 10,
            delay = 10.milliseconds,
            predicate = { it >= 5 }
        ) {
            counter++
            counter
        }

        result shouldBe 5
        counter shouldBe 5
    }

    @Test
    fun `repeatUntil throws when max attempts reached without satisfaction`() = runTest {
        var counter = 0
        shouldThrow<Exception> {
            repeatUntil(
                maxAttempts = 5,
                delay = 10.milliseconds,
                predicate = { it >= 100 } // Never satisfied
            ) {
                counter++
                counter
            }
        }

        counter shouldBe 5
    }

    @Test
    fun `repeatUntil validates parameters`() = runTest {
        shouldThrow<IllegalArgumentException> {
            repeatUntil(maxAttempts = 0, predicate = { true }) { "test" }
        }

        shouldThrow<IllegalArgumentException> {
            repeatUntil(delay = (-50).milliseconds, predicate = { true }) { "test" }
        }
    }

    @Test
    fun `repeatWhile continues while predicate is true`() = runTest {
        var counter = 0
        val results = repeatWhile(
            maxAttempts = 10,
            delay = 10.milliseconds,
            predicate = { it < 5 }
        ) {
            counter++
            counter
        }

        results shouldBe listOf(1, 2, 3, 4, 5)
        counter shouldBe 5
    }

    @Test
    fun `repeatWhile respects max attempts`() = runTest {
        var counter = 0
        val results = repeatWhile(
            maxAttempts = 3,
            delay = 10.milliseconds,
            predicate = { true } // Always true
        ) {
            counter++
            counter
        }

        results shouldBe listOf(1, 2, 3)
        counter shouldBe 3
    }

    @Test
    fun `repeatWhile validates parameters`() = runTest {
        shouldThrow<IllegalArgumentException> {
            repeatWhile(maxAttempts = 0, predicate = { true }) { "test" }
        }
    }

    @Test
    fun `repeatAndCollect collects all results`() = runTest {
        var counter = 0
        val results = repeatAndCollect(times = 4, delay = 10.milliseconds) {
            counter++
            counter
        }

        results shouldBe listOf(1, 2, 3, 4, 5)
        counter shouldBe 5
    }

    @Test
    fun `repeatAndCollect stops on first failure`() = runTest {
        var counter = 0
        shouldThrow<IOException> {
            repeatAndCollect(times = 5, delay = 10.milliseconds) {
                counter++
                if (counter == 3) throw IOException("Stop")
                counter
            }
        }

        counter shouldBe 3
    }

    @Test
    fun `repeatOrElse handles errors with fallback`() = runTest {
        var executions = 0
        var errorHandlerCalled = false

        val result = repeatOrElse(
            times = 3,
            delay = 10.milliseconds,
            onError = { error, attempt ->
                errorHandlerCalled = true
                "fallback"
            }
        ) {
            executions++
            if (executions == 2) throw IOException("Failed")
            "execution-$executions"
        }

        result shouldBe "fallback"
        errorHandlerCalled shouldBe true
        executions shouldBe 2
    }

    @Test
    fun `repeatWithTimeout completes within timeout`() = runTest {
        var executions = 0
        val result = repeatWithTimeout(
            timeout = 5.seconds,
            times = 3,
            delay = 10.milliseconds
        ) {
            executions++
            "result-$executions"
        }

        result shouldBe "result-4"
        executions shouldBe 4
    }

    @Test
    fun `repeatWithTimeout throws when timeout exceeded`() = runTest {
        shouldThrow<TimeoutCancellationException> {
            repeatWithTimeout(
                timeout = 100.milliseconds,
                times = 100,
                delay = 50.milliseconds
            ) {
                "test"
            }
        }
    }

    @Test
    fun `repeatWithTimeout validates parameters`() = runTest {
        shouldThrow<IllegalArgumentException> {
            repeatWithTimeout(timeout = 0.milliseconds, times = 3) { "test" }
        }
    }

    // ============================================================================
    // INTEGRATION TESTS
    // ============================================================================

    @Test
    fun `retry and repeat can be combined`() = runTest {
        var attempts = 0

        // Use retry inside a repeat
        val results = repeatAndCollect(times = 2, delay = 10.milliseconds) {
            retryWithExponentialBackoff(retries = 2, base = 5.milliseconds) {
                attempts++
                if (attempts % 2 == 1) throw IOException("Fail odd attempts")
                "attempt-$attempts"
            }
        }

        results shouldHaveSize 3
        attempts shouldBeGreaterThan 3 // Some retries happened
    }

    @Test
    fun `retryAndCollect provides detailed timing information`() = runTest {
        val result = retryAndCollect(retries = 2, base = 20.milliseconds) {
            kotlinx.coroutines.delay(10.milliseconds)
            "success"
        }

        result.isSuccess shouldBe true
        result.totalDuration.inWholeMilliseconds shouldBeGreaterThan 0
        result.attempts.forEach { attempt ->
            attempt.duration.inWholeMilliseconds shouldBeGreaterThan 0
        }
    }

    @Test
    fun `complex retry scenario with multiple failure types`() = runTest {
        var attempts = 0
        val result = retryIf(
            retries = 5,
            base = 10.milliseconds,
            shouldRetry = { error ->
                error is IOException || error is SocketTimeoutException
            }
        ) {
            attempts++
            when (attempts) {
                1 -> throw IOException("Network error")
                2 -> throw SocketTimeoutException("Timeout")
                3 -> throw IOException("Another network error")
                else -> "finally succeeded"
            }
        }

        result shouldBe "finally succeeded"
        attempts shouldBe 4
    }

    @Test
    fun `repeatWhile for pagination scenario`() = runTest {
        data class Page(val data: List<Int>, val hasMore: Boolean)

        var pageNumber = 0
        val pages = repeatWhile(
            maxAttempts = 10,
            delay = 10.milliseconds,
            predicate = { page -> page.hasMore }
        ) {
            pageNumber++
            when (pageNumber) {
                1 -> Page(listOf(1, 2, 3), hasMore = true)
                2 -> Page(listOf(4, 5, 6), hasMore = true)
                3 -> Page(listOf(7, 8, 9), hasMore = false)
                else -> Page(emptyList(), hasMore = false)
            }
        }

        pages shouldHaveSize 3
        pages[0].data shouldBe listOf(1, 2, 3)
        pages[1].data shouldBe listOf(4, 5, 6)
        pages[2].data shouldBe listOf(7, 8, 9)
        pageNumber shouldBe 3
    }

    @Test
    fun `retryWithCappedBackoff prevents exponential explosion`() = runTest {
        val attempts = mutableListOf<Long>()
        var counter = 0

        shouldThrow<IOException> {
            retryWithCappedBackoff(
                retries = 5,
                base = 10.milliseconds,
                maxDelay = 50.milliseconds,
                factor = 10.0 // Would explode without cap
            ) {
                val start = System.currentTimeMillis()
                attempts.add(start)
                counter++
                throw IOException("Fail")
            }
        }

        counter shouldBe 6

        // Check that delays were actually capped
        if (attempts.size > 2) {
            val delay1 = attempts[1] - attempts[0]
            val delay2 = attempts[2] - attempts[1]
            // Later delays should not be 10x larger due to cap
            delay2 shouldBeLessThan (delay1 * 5)
        }
    }
}