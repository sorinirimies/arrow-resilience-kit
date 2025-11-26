// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies


import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RateLimiterTest {

    @Test
    fun `rate limiter allows calls within rate`() = runTest {
        val rateLimiter = RateLimiter(
            config = RateLimiterConfig(
                permitsPerSecond = 10.0,
                burstCapacity = 10
            )
        )

        var executed = false
        val result = rateLimiter.tryExecute {
            executed = true
            "success"
        }

        result shouldBe "success"
        executed shouldBe true
    }

    @Test
    fun `rate limiter rejects calls when rate exceeded`() = runTest {
        val rateLimiter = RateLimiter(
            config = RateLimiterConfig(
                permitsPerSecond = 2.0,
                burstCapacity = 2
            )
        )

        // Use up all tokens
        rateLimiter.tryExecute { "call1" }
        rateLimiter.tryExecute { "call2" }

        // This should be rejected
        val result = rateLimiter.tryExecute {
            "call3"
        }

        result shouldBe null
    }

    @Test
    fun `rate limiter refills tokens over time`() = runTest {
        val rateLimiter = RateLimiter(
            config = RateLimiterConfig(
                permitsPerSecond = 10.0,
                burstCapacity = 2
            )
        )

        // Use up all tokens
        rateLimiter.tryExecute { "call1" }
        rateLimiter.tryExecute { "call2" }

        // Should be rejected immediately
        rateLimiter.tryExecute { "call3" } shouldBe null

        // Wait for tokens to refill (100ms = 1 token at 10/sec)
        delay(150.milliseconds)

        // Should succeed now
        val result = rateLimiter.tryExecute { "call4" }
        result shouldBe "call4"
    }

    @Test
    fun `rate limiter execute waits for tokens`() = runTest {
        val rateLimiter = RateLimiter(
            config = RateLimiterConfig(
                permitsPerSecond = 5.0,
                burstCapacity = 1
            )
        )

        // Use up the token
        rateLimiter.tryExecute { "call1" }

        // This should wait for token refill
        val result = rateLimiter.execute {
            "call2"
        }

        result shouldBe "call2"
    }

    @Test
    fun `rate limiter tracks available tokens`() = runTest {
        val rateLimiter = RateLimiter(
            config = RateLimiterConfig(
                permitsPerSecond = 10.0,
                burstCapacity = 5
            )
        )

        val initialTokens = rateLimiter.availableTokens()
        initialTokens shouldBe 5.0

        rateLimiter.tryExecute { "call1" }

        val afterCall = rateLimiter.availableTokens()
        afterCall shouldBe 4.0
    }

    @Test
    fun `rate limiter tracks statistics`() = runTest {
        val rateLimiter = RateLimiter(
            config = RateLimiterConfig(
                permitsPerSecond = 10.0,
                burstCapacity = 5
            )
        )

        rateLimiter.tryExecute { "call1" }
        rateLimiter.tryExecute { "call2" }

        val stats = rateLimiter.statistics()
        stats.totalRequests shouldBe 2
        stats.acceptedRequests shouldBe 2
        stats.rejectedRequests shouldBe 0
    }

    @Test
    fun `rate limiter tracks rejected requests`() = runTest {
        val rateLimiter = RateLimiter(
            config = RateLimiterConfig(
                permitsPerSecond = 1.0,
                burstCapacity = 1
            )
        )

        rateLimiter.tryExecute { "call1" }
        rateLimiter.tryExecute { "call2" } // Should be rejected

        val stats = rateLimiter.statistics()
        stats.totalRequests shouldBe 2
        stats.acceptedRequests shouldBe 1
        stats.rejectedRequests shouldBe 1
        stats.rejectionRate shouldBe 0.5
    }

    @Test
    fun `executeOrFallback uses fallback when rate limited`() = runTest {
        val rateLimiter = RateLimiter(
            config = RateLimiterConfig(
                permitsPerSecond = 1.0,
                burstCapacity = 1
            )
        )

        rateLimiter.tryExecute { "call1" }

        val result = rateLimiter.executeOrFallback(
            fallback = { "fallback" }
        ) {
            "primary"
        }

        result shouldBe "fallback"
    }

    @Test
    fun `rate limiter listener is notified of events`() = runTest {
        var requestAccepted = false
        var requestRejected = false

        val rateLimiter = RateLimiter(
            config = RateLimiterConfig(
                permitsPerSecond = 1.0,
                burstCapacity = 1
            )
        )

        rateLimiter.addListener(object : RateLimiterListener {
            override fun onRequestAccepted() {
                requestAccepted = true
            }

            override fun onRequestRejected() {
                requestRejected = true
            }
        })

        rateLimiter.tryExecute { "call1" }
        rateLimiter.tryExecute { "call2" }

        requestAccepted shouldBe true
        requestRejected shouldBe true
    }

    @Test
    fun `rate limiter config validates parameters`() {
        shouldThrow<IllegalArgumentException> {
            RateLimiterConfig(permitsPerSecond = 0.0)
        }

        shouldThrow<IllegalArgumentException> {
            RateLimiterConfig(permitsPerSecond = -1.0)
        }

        shouldThrow<IllegalArgumentException> {
            RateLimiterConfig(burstCapacity = 0)
        }

        shouldThrow<IllegalArgumentException> {
            RateLimiterConfig(burstCapacity = -1)
        }
    }

    @Test
    fun `rate limiter DSL creates configured limiter`() = runTest {
        val rateLimiter = rateLimiter {
            permitsPerSecond = 100.0
            burstCapacity = 200
        }

        val result = rateLimiter.tryExecute { "success" }
        result shouldBe "success"
    }

    @Test
    fun `rate limiter reset clears state`() = runTest {
        val rateLimiter = RateLimiter(
            config = RateLimiterConfig(
                permitsPerSecond = 1.0,
                burstCapacity = 1
            )
        )

        rateLimiter.tryExecute { "call1" }
        rateLimiter.availableTokens() shouldBe 0.0

        rateLimiter.reset()

        rateLimiter.availableTokens() shouldBe 1.0
    }

    @Test
    fun `rate limiter reset statistics clears counters`() = runTest {
        val rateLimiter = RateLimiter()

        rateLimiter.tryExecute { "call1" }

        var stats = rateLimiter.statistics()
        stats.totalRequests shouldBe 1

        rateLimiter.resetStatistics()

        stats = rateLimiter.statistics()
        stats.totalRequests shouldBe 0
    }

    @Test
    fun `rate limiter allows burst up to capacity`() = runTest {
        val rateLimiter = RateLimiter(
            config = RateLimiterConfig(
                permitsPerSecond = 1.0,
                burstCapacity = 5
            )
        )

        // Should be able to make 5 calls immediately
        repeat(5) { i ->
            val result = rateLimiter.tryExecute { "call$i" }
            result shouldBe "call$i"
        }

        // 6th call should be rejected
        rateLimiter.tryExecute { "call6" } shouldBe null
    }

    @Test
    fun `rate limiter handles multiple permits per request`() = runTest {
        val rateLimiter = RateLimiter(
            config = RateLimiterConfig(
                permitsPerSecond = 10.0,
                burstCapacity = 10
            )
        )

        // Consume 3 permits
        val result = rateLimiter.tryExecute(permits = 3) {
            "success"
        }

        result shouldBe "success"
        rateLimiter.availableTokens() shouldBe 7.0
    }

    @Test
    fun `rate limiter validates permits parameter`() = runTest {
        val rateLimiter = RateLimiter()

        shouldThrow<IllegalArgumentException> {
            rateLimiter.tryExecute(permits = 0) { "fail" }
        }

        shouldThrow<IllegalArgumentException> {
            rateLimiter.tryExecute(permits = -1) { "fail" }
        }
    }

    @Test
    fun `RateLimiterRegistry manages multiple limiters`() = runTest {
        val registry = RateLimiterRegistry()

        val limiter1 = registry.getOrCreate("api") {
            permitsPerSecond = 100.0
        }

        val limiter2 = registry.getOrCreate("database") {
            permitsPerSecond = 50.0
        }

        limiter1.tryExecute { "api" } shouldBe "api"
        limiter2.tryExecute { "db" } shouldBe "db"

        registry.getNames() shouldBe setOf("api", "database")
    }

    @Test
    fun `RateLimiterRegistry get returns existing limiter`() = runTest {
        val registry = RateLimiterRegistry()

        registry.get("nonexistent") shouldBe null

        val limiter = registry.getOrCreate("test")
        registry.get("test") shouldBe limiter
    }

    @Test
    fun `RateLimiterRegistry remove removes limiter`() = runTest {
        val registry = RateLimiterRegistry()

        val limiter = registry.getOrCreate("test")
        val removed = registry.remove("test")

        removed shouldBe limiter
        registry.get("test") shouldBe null
    }

    @Test
    fun `RateLimiterRegistry resetAll resets all limiters`() = runTest {
        val registry = RateLimiterRegistry()

        val limiter = registry.getOrCreate("test") {
            permitsPerSecond = 1.0
            burstCapacity = 1
        }

        limiter.tryExecute { "call" }
        limiter.availableTokens() shouldBe 0.0

        registry.resetAll()

        limiter.availableTokens() shouldBe 1.0
    }

    @Test
    fun `RateLimiterRegistry getAllStatistics returns stats for all limiters`() = runTest {
        val registry = RateLimiterRegistry()

        val limiter1 = registry.getOrCreate("api")
        val limiter2 = registry.getOrCreate("db")

        limiter1.tryExecute { "call1" }
        limiter2.tryExecute { "call2" }

        val allStats = registry.getAllStatistics()

        allStats.size shouldBe 2
        allStats["api"]?.totalRequests shouldBe 1
        allStats["db"]?.totalRequests shouldBe 1
    }

    // Sliding Window Rate Limiter Tests

    @Test
    fun `sliding window rate limiter allows calls within window`() = runTest {
        val rateLimiter = SlidingWindowRateLimiter(
            config = SlidingWindowConfig(
                maxRequests = 5,
                windowDuration = 1.seconds
            )
        )

        repeat(5) { i ->
            val result = rateLimiter.tryExecute { "call$i" }
            result shouldBe "call$i"
        }

        rateLimiter.currentRequests() shouldBe 5
    }

    @Test
    fun `sliding window rate limiter rejects calls when limit exceeded`() = runTest {
        val rateLimiter = SlidingWindowRateLimiter(
            config = SlidingWindowConfig(
                maxRequests = 3,
                windowDuration = 1.seconds
            )
        )

        repeat(3) { i ->
            rateLimiter.tryExecute { "call$i" }
        }

        // 4th call should be rejected
        val result = rateLimiter.tryExecute { "call4" }
        result shouldBe null
    }

    @Test
    fun `sliding window rate limiter allows calls after window expires`() = runTest {
        val rateLimiter = SlidingWindowRateLimiter(
            config = SlidingWindowConfig(
                maxRequests = 2,
                windowDuration = 100.milliseconds
            )
        )

        // Fill the window
        rateLimiter.tryExecute { "call1" }
        rateLimiter.tryExecute { "call2" }

        // Should be rejected
        rateLimiter.tryExecute { "call3" } shouldBe null

        // Wait for window to expire
        delay(150.milliseconds)

        // Should succeed now
        val result = rateLimiter.tryExecute { "call4" }
        result shouldBe "call4"
    }

    @Test
    fun `sliding window execute throws exception when rate exceeded`() = runTest {
        val rateLimiter = SlidingWindowRateLimiter(
            config = SlidingWindowConfig(
                maxRequests = 1,
                windowDuration = 1.seconds
            )
        )

        rateLimiter.execute { "call1" }

        shouldThrow<RateLimitExceededException> {
            rateLimiter.execute { "call2" }
        }
    }

    @Test
    fun `sliding window tracks current requests`() = runTest {
        val rateLimiter = SlidingWindowRateLimiter(
            config = SlidingWindowConfig(
                maxRequests = 5,
                windowDuration = 1.seconds
            )
        )

        rateLimiter.currentRequests() shouldBe 0

        rateLimiter.tryExecute { "call1" }
        rateLimiter.currentRequests() shouldBe 1

        rateLimiter.tryExecute { "call2" }
        rateLimiter.currentRequests() shouldBe 2
    }

    @Test
    fun `sliding window tracks statistics`() = runTest {
        val rateLimiter = SlidingWindowRateLimiter(
            config = SlidingWindowConfig(
                maxRequests = 3,
                windowDuration = 1.seconds
            )
        )

        rateLimiter.tryExecute { "call1" }
        rateLimiter.tryExecute { "call2" }
        rateLimiter.tryExecute { "call3" }
        rateLimiter.tryExecute { "call4" } // Rejected

        val stats = rateLimiter.statistics()
        stats.totalRequests shouldBe 4
        stats.acceptedRequests shouldBe 3
        stats.rejectedRequests shouldBe 1
        stats.acceptanceRate shouldBe 0.75
        stats.rejectionRate shouldBe 0.25
    }

    @Test
    fun `sliding window reset clears state`() = runTest {
        val rateLimiter = SlidingWindowRateLimiter(
            config = SlidingWindowConfig(
                maxRequests = 2,
                windowDuration = 1.seconds
            )
        )

        rateLimiter.tryExecute { "call1" }
        rateLimiter.tryExecute { "call2" }
        rateLimiter.currentRequests() shouldBe 2

        rateLimiter.reset()

        rateLimiter.currentRequests() shouldBe 0

        val stats = rateLimiter.statistics()
        stats.totalRequests shouldBe 0
    }

    @Test
    fun `sliding window config validates parameters`() {
        shouldThrow<IllegalArgumentException> {
            SlidingWindowConfig(maxRequests = 0)
        }

        shouldThrow<IllegalArgumentException> {
            SlidingWindowConfig(maxRequests = -1)
        }

        shouldThrow<IllegalArgumentException> {
            SlidingWindowConfig(windowDuration = 0.seconds)
        }
    }

    @Test
    fun `sliding window handles concurrent requests`() = runTest {
        val rateLimiter = SlidingWindowRateLimiter(
            config = SlidingWindowConfig(
                maxRequests = 10,
                windowDuration = 1.seconds
            )
        )

        val results = mutableListOf<String?>()

        val jobs = (1..10).map { i ->
            launch {
                val result = rateLimiter.tryExecute { "call$i" }
                synchronized(results) {
                    results.add(result)
                }
            }
        }

        jobs.forEach { it.join() }

        results.filterNotNull().size shouldBe 10
    }
}