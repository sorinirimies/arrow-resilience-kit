// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies


import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BulkheadTest {

    @Test
    fun `bulkhead allows calls within capacity`() = runTest {
        val bulkhead = Bulkhead(
            config = BulkheadConfig(
                maxConcurrentCalls = 5,
                maxWaitingCalls = 0
            )
        )

        var executed = false
        val result = bulkhead.execute {
            executed = true
            "success"
        }

        result shouldBe "success"
        executed shouldBe true
    }

    @Test
    fun `bulkhead tracks active calls`() = runTest {
        val bulkhead = Bulkhead(
            config = BulkheadConfig(
                maxConcurrentCalls = 2,
                maxWaitingCalls = 5
            )
        )

        bulkhead.activeCalls() shouldBe 0

        val job1 = launch {
            bulkhead.execute {
                delay(100.milliseconds)
                "done"
            }
        }

        delay(10.milliseconds) // Let job1 start
        bulkhead.activeCalls() shouldBe 1

        job1.join()
        bulkhead.activeCalls() shouldBe 0
    }

    @Test
    fun `bulkhead rejects calls when full`() = runTest {
        val bulkhead = Bulkhead(
            config = BulkheadConfig(
                maxConcurrentCalls = 2,
                maxWaitingCalls = 0
            )
        )

        val job1 = launch {
            bulkhead.execute {
                delay(200.milliseconds)
                "job1"
            }
        }

        val job2 = launch {
            bulkhead.execute {
                delay(200.milliseconds)
                "job2"
            }
        }

        delay(20.milliseconds) // Let jobs start

        // Third call should be rejected
        shouldThrow<BulkheadFullException> {
            bulkhead.execute {
                "job3"
            }
        }

        job1.cancel()
        job2.cancel()
    }

    @Test
    fun `bulkhead allows waiting calls up to limit`() = runTest {
        val bulkhead = Bulkhead(
            config = BulkheadConfig(
                maxConcurrentCalls = 1,
                maxWaitingCalls = 2
            )
        )

        val results = mutableListOf<String>()

        val job1 = launch {
            bulkhead.execute {
                delay(50.milliseconds)
                results.add("job1")
            }
        }

        delay(10.milliseconds)

        val job2 = launch {
            bulkhead.execute {
                results.add("job2")
            }
        }

        val job3 = launch {
            bulkhead.execute {
                results.add("job3")
            }
        }

        delay(10.milliseconds)

        // Fourth call should be rejected (max waiting = 2)
        shouldThrow<BulkheadFullException> {
            bulkhead.execute {
                "job4"
            }
        }

        job1.join()
        job2.join()
        job3.join()

        results.size shouldBe 3
    }

    @Test
    fun `bulkhead with timeout rejects calls after wait timeout`() = runTest {
        val bulkhead = Bulkhead(
            config = BulkheadConfig(
                maxConcurrentCalls = 1,
                maxWaitingCalls = 5,
                maxWaitDuration = 50.milliseconds
            )
        )

        val job1 = launch {
            bulkhead.execute {
                delay(200.milliseconds)
                "job1"
            }
        }

        delay(10.milliseconds)

        // This should timeout waiting for permit
        shouldThrow<BulkheadTimeoutException> {
            bulkhead.execute {
                "job2"
            }
        }

        job1.cancel()
    }

    @Test
    fun `executeOrFallback uses fallback when full`() = runTest {
        val bulkhead = Bulkhead(
            config = BulkheadConfig(
                maxConcurrentCalls = 1,
                maxWaitingCalls = 0
            )
        )

        val job1 = launch {
            bulkhead.execute {
                delay(100.milliseconds)
                "primary"
            }
        }

        delay(10.milliseconds)

        val result = bulkhead.executeOrFallback(
            fallback = { "fallback" }
        ) {
            "primary"
        }

        result shouldBe "fallback"
        job1.cancel()
    }

    @Test
    fun `tryExecute returns null when full`() = runTest {
        val bulkhead = Bulkhead(
            config = BulkheadConfig(
                maxConcurrentCalls = 1,
                maxWaitingCalls = 0
            )
        )

        val job1 = launch {
            bulkhead.execute {
                delay(100.milliseconds)
                "primary"
            }
        }

        delay(10.milliseconds)

        val result = bulkhead.tryExecute {
            "should not execute"
        }

        result shouldBe null
        job1.cancel()
    }

    @Test
    fun `bulkhead tracks statistics`() = runTest {
        val bulkhead = Bulkhead(
            config = BulkheadConfig(
                maxConcurrentCalls = 2,
                maxWaitingCalls = 1
            )
        )

        // Successful call
        bulkhead.execute { "success" }

        val stats = bulkhead.statistics()
        stats.totalCalls shouldBe 1
        stats.successfulCalls shouldBe 1
        stats.rejectedCalls shouldBe 0
        stats.failedCalls shouldBe 0
    }

    @Test
    fun `bulkhead tracks failed calls`() = runTest {
        val bulkhead = Bulkhead(
            config = BulkheadConfig(
                maxConcurrentCalls = 5,
                maxWaitingCalls = 0
            )
        )

        try {
            bulkhead.execute {
                throw RuntimeException("Test error")
            }
        } catch (e: Exception) {
            // Expected
        }

        val stats = bulkhead.statistics()
        stats.totalCalls shouldBe 1
        stats.failedCalls shouldBe 1
        stats.successfulCalls shouldBe 0
    }

    @Test
    fun `bulkhead tracks rejected calls`() = runTest {
        val bulkhead = Bulkhead(
            config = BulkheadConfig(
                maxConcurrentCalls = 1,
                maxWaitingCalls = 0
            )
        )

        val job1 = launch {
            bulkhead.execute {
                delay(100.milliseconds)
                "job1"
            }
        }

        delay(10.milliseconds)

        try {
            bulkhead.execute { "job2" }
        } catch (e: BulkheadFullException) {
            // Expected
        }

        val stats = bulkhead.statistics()
        stats.rejectedCalls shouldBe 1

        job1.cancel()
    }

    @Test
    fun `bulkhead listener is notified of events`() = runTest {
        var callEntered = false
        var callExited = false
        var callSucceeded = false

        val bulkhead = Bulkhead(
            config = BulkheadConfig(maxConcurrentCalls = 5)
        )

        bulkhead.addListener(object : BulkheadListener {
            override fun onCallEntered() {
                callEntered = true
            }

            override fun onCallExited() {
                callExited = true
            }

            override fun onCallSucceeded() {
                callSucceeded = true
            }
        })

        bulkhead.execute { "success" }

        callEntered shouldBe true
        callExited shouldBe true
        callSucceeded shouldBe true
    }

    @Test
    fun `bulkhead listener is notified of rejection`() = runTest {
        var callRejected = false

        val bulkhead = Bulkhead(
            config = BulkheadConfig(
                maxConcurrentCalls = 1,
                maxWaitingCalls = 0
            )
        )

        bulkhead.addListener(object : BulkheadListener {
            override fun onCallRejected() {
                callRejected = true
            }
        })

        val job1 = launch {
            bulkhead.execute {
                delay(100.milliseconds)
                "job1"
            }
        }

        delay(10.milliseconds)

        try {
            bulkhead.execute { "job2" }
        } catch (e: BulkheadFullException) {
            // Expected
        }

        callRejected shouldBe true
        job1.cancel()
    }

    @Test
    fun `bulkhead config validates parameters`() {
        shouldThrow<IllegalArgumentException> {
            BulkheadConfig(maxConcurrentCalls = 0)
        }

        shouldThrow<IllegalArgumentException> {
            BulkheadConfig(maxWaitingCalls = -1)
        }

        shouldThrow<IllegalArgumentException> {
            BulkheadConfig(maxWaitDuration = 0.milliseconds)
        }
    }

    @Test
    fun `bulkhead DSL creates configured bulkhead`() = runTest {
        val bulkhead = bulkhead {
            maxConcurrentCalls = 3
            maxWaitingCalls = 2
            maxWaitDuration = 1.seconds
        }

        val result = bulkhead.execute { "success" }
        result shouldBe "success"
    }

    @Test
    fun `BulkheadRegistry manages multiple bulkheads`() = runTest {
        val registry = BulkheadRegistry()

        val bulkhead1 = registry.getOrCreate("service1") {
            maxConcurrentCalls = 5
        }

        val bulkhead2 = registry.getOrCreate("service2") {
            maxConcurrentCalls = 10
        }

        bulkhead1.execute { "service1" } shouldBe "service1"
        bulkhead2.execute { "service2" } shouldBe "service2"

        registry.getNames() shouldBe setOf("service1", "service2")
    }

    @Test
    fun `BulkheadRegistry get returns existing bulkhead`() = runTest {
        val registry = BulkheadRegistry()

        registry.get("nonexistent") shouldBe null

        val bulkhead = registry.getOrCreate("test")
        registry.get("test") shouldBe bulkhead
    }

    @Test
    fun `BulkheadRegistry remove removes bulkhead`() = runTest {
        val registry = BulkheadRegistry()

        val bulkhead = registry.getOrCreate("test")
        val removed = registry.remove("test")

        removed shouldBe bulkhead
        registry.get("test") shouldBe null
    }

    @Test
    fun `BulkheadRegistry resetAllStatistics resets all bulkheads`() = runTest {
        val registry = BulkheadRegistry()

        val bulkhead = registry.getOrCreate("test")
        bulkhead.execute { "success" }

        var stats = bulkhead.statistics()
        stats.totalCalls shouldBe 1

        registry.resetAllStatistics()

        stats = bulkhead.statistics()
        stats.totalCalls shouldBe 0
    }

    @Test
    fun `BulkheadRegistry getAllStatistics returns stats for all bulkheads`() = runTest {
        val registry = BulkheadRegistry()

        val bulkhead1 = registry.getOrCreate("service1")
        val bulkhead2 = registry.getOrCreate("service2")

        bulkhead1.execute { "success1" }
        bulkhead2.execute { "success2" }

        val allStats = registry.getAllStatistics()

        allStats.size shouldBe 2
        allStats["service1"]?.totalCalls shouldBe 1
        allStats["service2"]?.totalCalls shouldBe 1
    }

    @Test
    fun `bulkhead statistics calculates utilization rate`() = runTest {
        val bulkhead = Bulkhead(
            config = BulkheadConfig(
                maxConcurrentCalls = 10,
                maxWaitingCalls = 5
            )
        )

        bulkhead.execute { "success" }

        val stats = bulkhead.statistics()
        stats.utilizationRate shouldBe 0.0 // No active calls after execution completes
    }

    @Test
    fun `bulkhead handles concurrent access correctly`() = runTest {
        val bulkhead = Bulkhead(
            config = BulkheadConfig(
                maxConcurrentCalls = 5,
                maxWaitingCalls = 10
            )
        )

        val results = mutableListOf<Int>()

        val jobs = (1..10).map { i ->
            launch {
                bulkhead.execute {
                    delay(50.milliseconds)
                    synchronized(results) {
                        results.add(i)
                    }
                }
            }
        }

        jobs.forEach { it.join() }

        results.size shouldBe 10
    }

    @Test
    fun `bulkhead available capacity is correct`() = runTest {
        val bulkhead = Bulkhead(
            config = BulkheadConfig(
                maxConcurrentCalls = 5,
                maxWaitingCalls = 0
            )
        )

        val stats = bulkhead.statistics()
        stats.availableCapacity shouldBe 5

        val job = launch {
            bulkhead.execute {
                delay(100.milliseconds)
                "running"
            }
        }

        delay(20.milliseconds)
        
        val activeStats = bulkhead.statistics()
        activeStats.availableCapacity shouldBe 4

        job.join()

        val finalStats = bulkhead.statistics()
        finalStats.availableCapacity shouldBe 5
    }

    @Test
    fun `bulkhead reset statistics clears counters`() = runTest {
        val bulkhead = Bulkhead()

        bulkhead.execute { "success" }
        
        var stats = bulkhead.statistics()
        stats.totalCalls shouldBe 1

        bulkhead.resetStatistics()

        stats = bulkhead.statistics()
        stats.totalCalls shouldBe 0
        stats.successfulCalls shouldBe 0
    }
}