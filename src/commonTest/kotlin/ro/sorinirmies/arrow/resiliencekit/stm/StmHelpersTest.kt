// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package ro.sorinirmies.arrow.resiliencekit.stm

import arrow.fx.stm.atomically
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.js.JsName
import kotlin.test.Test

class StmHelpersTest {

    // ── StmCounter ────────────────────────────────────────────────

    @JsName("counterStartsAtInitialValue")
    @Test
    fun `counter starts at initial value`() = runTest {
        val counter = StmCounter.create(0L)
        counter.value() shouldBe 0L
    }

    @JsName("counterStartsAtCustomInitial")
    @Test
    fun `counter starts at custom initial value`() = runTest {
        val counter = StmCounter.create(42L)
        counter.value() shouldBe 42L
    }

    @JsName("counterIncrements")
    @Test
    fun `counter increments atomically`() = runTest {
        val counter = StmCounter.create(0L)
        val result = atomically { with(counter) { increment() } }
        result shouldBe 1L
        counter.value() shouldBe 1L
    }

    @JsName("counterDecrements")
    @Test
    fun `counter decrements atomically`() = runTest {
        val counter = StmCounter.create(5L)
        val result = atomically { with(counter) { decrement() } }
        result shouldBe 4L
        counter.value() shouldBe 4L
    }

    @JsName("counterAdds")
    @Test
    fun `counter adds amount`() = runTest {
        val counter = StmCounter.create(10L)
        atomically { with(counter) { add(5) } }
        counter.value() shouldBe 15L
    }

    @JsName("counterSetOverwrites")
    @Test
    fun `counter set overwrites value`() = runTest {
        val counter = StmCounter.create(0L)
        atomically { with(counter) { set(99L) } }
        counter.value() shouldBe 99L
    }

    @JsName("counterGetReadsCurrentValue")
    @Test
    fun `counter get reads current value`() = runTest {
        val counter = StmCounter.create(7L)
        val value = atomically { with(counter) { get() } }
        value shouldBe 7L
    }

    // ── StmGauge ──────────────────────────────────────────────────

    @JsName("gaugeStartsAtInitial")
    @Test
    fun `gauge starts at initial value`() = runTest {
        val gauge = StmGauge.create(5.0)
        gauge.value() shouldBe 5.0
    }

    @JsName("gaugeTracksMinMax")
    @Test
    fun `gauge tracks min and max`() = runTest {
        val gauge = StmGauge.create(5.0)
        atomically { with(gauge) { set(10.0) } }
        atomically { with(gauge) { set(2.0) } }
        atomically { with(gauge) { set(7.0) } }

        val min = atomically { with(gauge) { min() } }
        val max = atomically { with(gauge) { max() } }
        min shouldBe 2.0
        max shouldBe 10.0
    }

    // ── StmStateMachine ───────────────────────────────────────────

    @JsName("stateMachineStartsInInitialState")
    @Test
    fun `state machine starts in initial state`() = runTest {
        val sm = StmStateMachine.create("idle")
        sm.currentState() shouldBe "idle"
    }

    @JsName("stateMachineTransitions")
    @Test
    fun `state machine transitions`() = runTest {
        val sm = StmStateMachine.create("idle")
        atomically { with(sm) { transition("running") } }
        sm.currentState() shouldBe "running"
    }

    @JsName("stateMachineTransitionIfSucceeds")
    @Test
    fun `state machine transitionIf succeeds when state matches`() = runTest {
        val sm = StmStateMachine.create("idle")
        val result = atomically { with(sm) { transitionIf("idle", "running") } }
        result shouldBe true
        sm.currentState() shouldBe "running"
    }

    @JsName("stateMachineTransitionIfFailsOnMismatch")
    @Test
    fun `state machine transitionIf fails on mismatch`() = runTest {
        val sm = StmStateMachine.create("idle")
        val result = atomically { with(sm) { transitionIf("running", "stopped") } }
        result shouldBe false
        sm.currentState() shouldBe "idle"
    }

    // ── StmSemaphore ──────────────────────────────────────────────

    @JsName("semaphoreStartsWithPermits")
    @Test
    fun `semaphore starts with full permits`() = runTest {
        val sem = StmSemaphore.create(3)
        sem.availablePermits() shouldBe 3
    }

    @JsName("semaphoreAcquireDecrementsPermits")
    @Test
    fun `semaphore acquire decrements permits`() = runTest {
        val sem = StmSemaphore.create(3)
        val acquired = atomically { with(sem) { acquire() } }
        acquired shouldBe true
        sem.availablePermits() shouldBe 2
    }

    @JsName("semaphoreAcquireFailsWhenEmpty")
    @Test
    fun `semaphore acquire fails when no permits`() = runTest {
        val sem = StmSemaphore.create(1)
        atomically { with(sem) { acquire() } }
        val acquired = atomically { with(sem) { acquire() } }
        acquired shouldBe false
    }

    @JsName("semaphoreReleaseIncrementsPermits")
    @Test
    fun `semaphore release increments permits`() = runTest {
        val sem = StmSemaphore.create(2)
        atomically { with(sem) { acquire() } }
        atomically { with(sem) { release() } }
        sem.availablePermits() shouldBe 2
    }

    @JsName("semaphoreReleaseDoesNotExceedMax")
    @Test
    fun `semaphore release does not exceed max permits`() = runTest {
        val sem = StmSemaphore.create(2)
        atomically { with(sem) { release() } } // try to release without acquiring
        sem.availablePermits() shouldBe 2 // should not go above max
    }

    // ── StmRateWindow ─────────────────────────────────────────────

    @JsName("rateWindowAllowsWithinLimit")
    @Test
    fun `rate window allows requests within limit`() = runTest {
        val window = StmRateWindow.create(1000L)
        val allowed = atomically { with(window) { tryAcquire(5, 100L) } }
        allowed shouldBe true
    }

    @JsName("rateWindowRejectsOverLimit")
    @Test
    fun `rate window rejects when over limit`() = runTest {
        val window = StmRateWindow.create(1000L)
        // Fill up
        repeat(3) { i ->
            atomically { with(window) { tryAcquire(3, (100 + i).toLong()) } }
        }
        val rejected = atomically { with(window) { tryAcquire(3, 200L) } }
        rejected shouldBe false
    }

    @JsName("rateWindowResetsCount")
    @Test
    fun `rate window reset clears all requests`() = runTest {
        val window = StmRateWindow.create(1000L)
        atomically { with(window) { tryAcquire(5, 100L) } }
        atomically { with(window) { reset() } }
        val count = atomically { with(window) { currentCount(200L) } }
        count shouldBe 0
    }
}
