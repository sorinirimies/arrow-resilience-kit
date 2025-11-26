// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies


import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.io.IOException

class SagaTest {

    @Test
    fun `saga executes all steps successfully`() = runTest {
        var step1Executed = false
        var step2Executed = false
        var step3Executed = false

        val saga = saga<String> {
            step("Step 1", { step1Executed = true; "result1" })
            step("Step 2", { step2Executed = true; "result2" })
            step("Step 3", { step3Executed = true; "result3" })
        }

        val result = saga.execute()

        result.shouldBeInstanceOf<SagaResult.Success<String>>()
        result as SagaResult.Success
        result.executedSteps shouldBe 3
        step1Executed shouldBe true
        step2Executed shouldBe true
        step3Executed shouldBe true
    }

    @Test
    fun `saga compensates on failure`() = runTest {
        var step1Compensated = false
        var step2Compensated = false

        val saga = saga<String> {
            step(
                name = "Step 1",
                action = { "result1" },
                compensation = { step1Compensated = true }
            )
            step(
                name = "Step 2",
                action = { "result2" },
                compensation = { step2Compensated = true }
            )
            step(
                name = "Step 3",
                action = { throw IOException("Step 3 failed") }
            )
        }

        val result = saga.execute()

        result.shouldBeInstanceOf<SagaResult.Failure<String>>()
        result as SagaResult.Failure
        result.compensatedSteps shouldBe 2
        step1Compensated shouldBe true
        step2Compensated shouldBe true
    }

    @Test
    fun `saga compensates in reverse order`() = runTest {
        val compensationOrder = mutableListOf<String>()

        val saga = saga<String> {
            step(
                name = "Step 1",
                action = { "result1" },
                compensation = { compensationOrder.add("Step 1") }
            )
            step(
                name = "Step 2",
                action = { "result2" },
                compensation = { compensationOrder.add("Step 2") }
            )
            step(
                name = "Step 3",
                action = { "result3" },
                compensation = { compensationOrder.add("Step 3") }
            )
            step(
                name = "Step 4",
                action = { throw IOException("Fail") }
            )
        }

        saga.execute()

        compensationOrder shouldBe listOf("Step 3", "Step 2", "Step 1")
    }

    @Test
    fun `saga skips compensation for steps without compensation defined`() = runTest {
        var step1Compensated = false
        var step3Compensated = false

        val saga = saga<String> {
            step(
                name = "Step 1",
                action = { "result1" },
                compensation = { step1Compensated = true }
            )
            step(
                name = "Step 2",
                action = { "result2" }
                // No compensation
            )
            step(
                name = "Step 3",
                action = { "result3" },
                compensation = { step3Compensated = true }
            )
            step(
                name = "Step 4",
                action = { throw IOException("Fail") }
            )
        }

        val result = saga.execute()

        result.shouldBeInstanceOf<SagaResult.Failure<String>>()
        result as SagaResult.Failure
        result.compensatedSteps shouldBe 2 // Only steps 1 and 3
        step1Compensated shouldBe true
        step3Compensated shouldBe true
    }

    @Test
    fun `saga continues compensation on failure by default`() = runTest {
        var step1Compensated = false
        var step2Compensated = false
        var step3Compensated = false

        val saga = saga<String> {
            configure {
                copy(continueOnCompensationFailure = true)
            }

            step(
                name = "Step 1",
                action = { "result1" },
                compensation = { step1Compensated = true }
            )
            step(
                name = "Step 2",
                action = { "result2" },
                compensation = {
                    step2Compensated = true
                    throw IOException("Compensation failed")
                }
            )
            step(
                name = "Step 3",
                action = { "result3" },
                compensation = { step3Compensated = true }
            )
            step(
                name = "Step 4",
                action = { throw IOException("Step failed") }
            )
        }

        val result = saga.execute()

        result.shouldBeInstanceOf<SagaResult.Failure<String>>()
        result as SagaResult.Failure
        result.hasCompensationErrors shouldBe true
        result.compensationErrors shouldHaveSize 1
        // All steps should have attempted compensation
        step1Compensated shouldBe true
        step2Compensated shouldBe true
        step3Compensated shouldBe true
    }

    @Test
    fun `saga stops compensation on failure when configured`() = runTest {
        var step1Compensated = false
        var step2Compensated = false
        var step3Compensated = false

        val saga = saga<String> {
            configure {
                copy(continueOnCompensationFailure = false)
            }

            step(
                name = "Step 1",
                action = { "result1" },
                compensation = { step1Compensated = true }
            )
            step(
                name = "Step 2",
                action = { "result2" },
                compensation = {
                    step2Compensated = true
                    throw IOException("Compensation failed")
                }
            )
            step(
                name = "Step 3",
                action = { "result3" },
                compensation = { step3Compensated = true }
            )
            step(
                name = "Step 4",
                action = { throw IOException("Step failed") }
            )
        }

        val result = saga.execute()

        result.shouldBeInstanceOf<SagaResult.Failure<String>>()
        result as SagaResult.Failure
        result.hasCompensationErrors shouldBe true
        // Step 1 should not have been compensated due to step 2 failure
        step1Compensated shouldBe false
        step2Compensated shouldBe true
        step3Compensated shouldBe true
    }

    @Test
    fun `saga passes results to compensation actions`() = runTest {
        var compensatedValue: String? = null

        val saga = saga<String> {
            step(
                name = "Create resource",
                action = { "resource-123" },
                compensation = { result ->
                    compensatedValue = result
                }
            )
            step(
                name = "Fail step",
                action = { throw IOException("Fail") }
            )
        }

        saga.execute()

        compensatedValue shouldBe "resource-123"
    }

    @Test
    fun `saga failure result contains original error`() = runTest {
        val expectedError = IOException("Original error")

        val saga = saga<String> {
            step("Step 1", { "result" })
            step("Step 2", { throw expectedError })
        }

        val result = saga.execute()

        result.shouldBeInstanceOf<SagaResult.Failure<String>>()
        result as SagaResult.Failure
        result.error.cause shouldBe expectedError
    }

    @Test
    fun `saga failure result includes compensation errors`() = runTest {
        val saga = saga<String> {
            step(
                name = "Step 1",
                action = { "result" },
                compensation = { throw IOException("Compensation error") }
            )
            step(
                name = "Step 2",
                action = { throw IOException("Step error") }
            )
        }

        val result = saga.execute()

        result.shouldBeInstanceOf<SagaResult.Failure<String>>()
        result as SagaResult.Failure
        result.compensationErrors shouldHaveSize 1
        result.compensationErrors[0].stepName shouldBe "Step 1"
    }

    @Test
    fun `stepWithTimeout enforces timeout`() = runTest {
        val saga = saga<String> {
            stepWithTimeout(
                name = "Slow step",
                timeout = 100.milliseconds,
                action = {
                    kotlinx.coroutines.delay(500.milliseconds)
                    "result"
                }
            )
        }

        val result = saga.execute()

        result.shouldBeInstanceOf<SagaResult.Failure<String>>()
    }

    @Test
    fun `stepWithRetry retries on failure`() = runTest {
        var attempts = 0

        val saga = saga<String> {
            stepWithRetry(
                name = "Flaky step",
                retries = 3,
                action = {
                    attempts++
                    if (attempts < 3) throw IOException("Fail")
                    "success"
                }
            )
        }

        val result = saga.execute()

        result.shouldBeInstanceOf<SagaResult.Success<String>>()
        attempts shouldBe 3
    }

    @Test
    fun `executeSaga is a convenience function`() = runTest {
        var executed = false

        val result = executeSaga<String> {
            step("Test", { executed = true; "done" })
        }

        result.shouldBeInstanceOf<SagaResult.Success<String>>()
        executed shouldBe true
    }

    @Test
    fun `saga builder requires at least one step`() {
        shouldThrow<IllegalArgumentException> {
            Saga.builder<String>().build()
        }
    }

    @Test
    fun `saga success result provides execution metrics`() = runTest {
        val saga = saga<String> {
            step("Step 1", { kotlinx.coroutines.delay(10.milliseconds); "r1" })
            step("Step 2", { kotlinx.coroutines.delay(10.milliseconds); "r2" })
            step("Step 3", { kotlinx.coroutines.delay(10.milliseconds); "r3" })
        }

        val result = saga.execute()

        result.shouldBeInstanceOf<SagaResult.Success<String>>()
        result as SagaResult.Success
        result.isSuccess shouldBe true
        result.executedSteps shouldBe 3
        result.duration.inWholeMilliseconds shouldBe 30L // At least 30ms
    }

    @Test
    fun `saga failure result provides compensation metrics`() = runTest {
        val saga = saga<String> {
            step("Step 1", { "r1" }, {})
            step("Step 2", { "r2" }, {})
            step("Step 3", { "r3" }, {})
            step("Step 4", { throw IOException("Fail") })
        }

        val result = saga.execute()

        result.shouldBeInstanceOf<SagaResult.Failure<String>>()
        result as SagaResult.Failure
        result.isSuccess shouldBe false
        result.compensatedSteps shouldBe 3
        result.compensationErrors shouldHaveSize 0
    }

    @Test
    fun `ParallelSagaCoordinator executes sagas in parallel`() = runTest {
        val coordinator = ParallelSagaCoordinator()

        val sagas = listOf(
            saga<String> { step("S1", { "result1" }) },
            saga<String> { step("S2", { "result2" }) },
            saga<String> { step("S3", { "result3" }) }
        )

        val results = coordinator.executeAll(sagas)

        results shouldHaveSize 3
        results.all { it is SagaResult.Success } shouldBe true
    }

    @Test
    fun `ParallelSagaCoordinator handles mixed success and failure`() = runTest {
        val coordinator = ParallelSagaCoordinator()

        val sagas = listOf(
            saga<String> { step("Success", { "ok" }) },
            saga<String> { step("Fail", { throw IOException("Error") }) },
            saga<String> { step("Success 2", { "ok2" }) }
        )

        val results = coordinator.executeAll(sagas)

        results shouldHaveSize 3
        results.count { it is SagaResult.Success } shouldBe 2
        results.count { it is SagaResult.Failure } shouldBe 1
    }

    @Test
    fun `ParallelSagaCoordinator executeWithStats provides statistics`() = runTest {
        val coordinator = ParallelSagaCoordinator()

        val sagas = listOf(
            saga<String> { step("S1", { "r1" }) },
            saga<String> { step("S2", { throw IOException("Fail") }) },
            saga<String> { step("S3", { "r3" }) }
        )

        val result = coordinator.executeWithStats(sagas)

        result.totalSagas shouldBe 3
        result.successfulSagas shouldBe 2
        result.failedSagas shouldBe 1
        result.successRate shouldBe 0.666 // 2/3 (approximately)
        result.allSuccessful shouldBe false
    }

    @Test
    fun `ParallelSagaCoordinator all successful case`() = runTest {
        val coordinator = ParallelSagaCoordinator()

        val sagas = listOf(
            saga<String> { step("S1", { "r1" }) },
            saga<String> { step("S2", { "r2" }) }
        )

        val result = coordinator.executeWithStats(sagas)

        result.allSuccessful shouldBe true
        result.successRate shouldBe 1.0
    }

    @Test
    fun `complex saga scenario with inventory, payment, and order`() = runTest {
        data class ReservationId(val id: String)
        data class PaymentId(val id: String)
        data class OrderId(val id: String)

        var inventoryReserved = false
        var inventoryReleased = false
        var paymentCharged = false
        var paymentRefunded = false
        var orderCreated = false
        var orderCancelled = false

        val saga = saga<OrderId> {
            step(
                name = "Reserve inventory",
                action = {
                    inventoryReserved = true
                    ReservationId("RES-123")
                },
                compensation = {
                    inventoryReleased = true
                }
            )

            step(
                name = "Charge payment",
                action = {
                    paymentCharged = true
                    PaymentId("PAY-456")
                },
                compensation = {
                    paymentRefunded = true
                }
            )

            step(
                name = "Create order",
                action = {
                    orderCreated = true
                    // Simulate failure
                    throw IOException("Order creation failed")
                },
                compensation = {
                    orderCancelled = true
                }
            )
        }

        val result = saga.execute()

        result.shouldBeInstanceOf<SagaResult.Failure<OrderId>>()

        // All forward steps executed
        inventoryReserved shouldBe true
        paymentCharged shouldBe true
        orderCreated shouldBe true

        // All compensations executed in reverse
        inventoryReleased shouldBe true
        paymentRefunded shouldBe true
        orderCancelled shouldBe false // Never created, so no compensation
    }

    @Test
    fun `saga with retry and timeout steps`() = runTest {
        var retriedStepAttempts = 0
        var timedStepExecuted = false

        val saga = saga<String> {
            stepWithRetry(
                name = "Flaky API call",
                retries = 2,
                action = {
                    retriedStepAttempts++
                    if (retriedStepAttempts < 2) throw IOException("Temporary failure")
                    "api-result"
                }
            )

            stepWithTimeout(
                name = "Fast operation",
                timeout = 1.seconds,
                action = {
                    timedStepExecuted = true
                    kotlinx.coroutines.delay(50.milliseconds)
                    "timed-result"
                }
            )

            step(
                name = "Final step",
                action = { "final-result" }
            )
        }

        val result = saga.execute()

        result.shouldBeInstanceOf<SagaResult.Success<String>>()
        retriedStepAttempts shouldBe 2
        timedStepExecuted shouldBe true
    }
}