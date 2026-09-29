// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package ro.sorinirmies.arrow.resiliencekit

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds


class SagaTest {

    @JsName("sagaExecutesAllStepsSuccessfully")
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
        result.executedSteps shouldBe 3
        step1Executed shouldBe true
        step2Executed shouldBe true
        step3Executed shouldBe true
    }

    @JsName("sagaCompensatesOnFailure")
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
                action = { throw RuntimeException("Step 3 failed") }
            )
        }

        val result = saga.execute()

        result.shouldBeInstanceOf<SagaResult.Failure<String>>()
        result.compensatedSteps shouldBe 2
        step1Compensated shouldBe true
        step2Compensated shouldBe true
    }

    @JsName("sagaCompensatesInReverseOrder")
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
                action = { throw RuntimeException("Fail") }
            )
        }

        saga.execute()

        compensationOrder shouldBe listOf("Step 3", "Step 2", "Step 1")
    }

    @JsName("sagaSkipsCompensationForStepsWithoutCompensationDefined")
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
                action = { throw RuntimeException("Fail") }
            )
        }

        val result = saga.execute()

        result.shouldBeInstanceOf<SagaResult.Failure<String>>()
        result.compensatedSteps shouldBe 2 // Only steps 1 and 3
        step1Compensated shouldBe true
        step3Compensated shouldBe true
    }

    @JsName("sagaContinuesCompensationOnFailureByDefault")
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
                    throw RuntimeException("Compensation failed")
                }
            )
            step(
                name = "Step 3",
                action = { "result3" },
                compensation = { step3Compensated = true }
            )
            step(
                name = "Step 4",
                action = { throw RuntimeException("Step failed") }
            )
        }

        val result = saga.execute()

        result.shouldBeInstanceOf<SagaResult.Failure<String>>()
        result.hasCompensationErrors shouldBe true
        result.compensationErrors shouldHaveSize 1
        // All steps should have attempted compensation
        step1Compensated shouldBe true
        step2Compensated shouldBe true
        step3Compensated shouldBe true
    }

    @JsName("sagaStopsCompensationOnFailureWhenConfigured")
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
                    throw RuntimeException("Compensation failed")
                }
            )
            step(
                name = "Step 3",
                action = { "result3" },
                compensation = { step3Compensated = true }
            )
            step(
                name = "Step 4",
                action = { throw RuntimeException("Step failed") }
            )
        }

        val result = saga.execute()

        result.shouldBeInstanceOf<SagaResult.Failure<String>>()
        result.hasCompensationErrors shouldBe true
        // Step 1 should not have been compensated due to step 2 failure
        step1Compensated shouldBe false
        step2Compensated shouldBe true
        step3Compensated shouldBe true
    }

    @JsName("sagaPassesResultsToCompensationActions")
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
                action = { throw RuntimeException("Fail") }
            )
        }

        saga.execute()

        compensatedValue shouldBe "resource-123"
    }

    @JsName("sagaFailureResultContainsOriginalError")
    @Test
    fun `saga failure result contains original error`() = runTest {
        val expectedError = RuntimeException("Original error")

        val saga = saga<String> {
            step("Step 1", { "result" })
            step("Step 2", { throw expectedError })
        }

        val result = saga.execute()

        result.shouldBeInstanceOf<SagaResult.Failure<String>>()
        result.error.cause shouldBe expectedError
    }

    @JsName("sagaFailureResultIncludesCompensationErrors")
    @Test
    fun `saga failure result includes compensation errors`() = runTest {
        val saga = saga<String> {
            step(
                name = "Step 1",
                action = { "result" },
                compensation = { throw RuntimeException("Compensation error") }
            )
            step(
                name = "Step 2",
                action = { throw RuntimeException("Step error") }
            )
        }

        val result = saga.execute()

        result.shouldBeInstanceOf<SagaResult.Failure<String>>()
        result.compensationErrors shouldHaveSize 1
        result.compensationErrors[0].stepName shouldBe "Step 1"
    }

    @JsName("stepWithTimeoutEnforcesTimeout")
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

    @JsName("stepWithRetryRetriesOnFailure")
    @Test
    fun `stepWithRetry retries on failure`() = runTest {
        var attempts = 0

        val saga = saga<String> {
            stepWithRetry(
                name = "Flaky step",
                retries = 3,
                action = {
                    attempts++
                    if (attempts < 3) throw RuntimeException("Fail")
                    "success"
                }
            )
        }

        val result = saga.execute()

        result.shouldBeInstanceOf<SagaResult.Success<String>>()
        attempts shouldBe 3
    }

    @JsName("executeSagaIsAConvenienceFunction")
    @Test
    fun `executeSaga is a convenience function`() = runTest {
        var executed = false

        val result = executeSaga<String> {
            step("Test", { executed = true; "done" })
        }

        result.shouldBeInstanceOf<SagaResult.Success<String>>()
        executed shouldBe true
    }

    @JsName("sagaBuilderRequiresAtLeastOneStep")
    @Test
    fun `saga builder requires at least one step`() {
        shouldThrow<IllegalArgumentException> {
            Saga.builder<String>().build()
        }
    }

    @JsName("sagaSuccessResultProvidesExecutionMetrics")
    @Test
    fun `saga success result provides execution metrics`() = runTest {
        val testClock = TestClock()
        val saga = saga<String>(clock = testClock) {
            step("Step 1", { testClock.advance(10.milliseconds); "r1" })
            step("Step 2", { testClock.advance(10.milliseconds); "r2" })
            step("Step 3", { testClock.advance(10.milliseconds); "r3" })
        }

        val result = saga.execute()

        result.shouldBeInstanceOf<SagaResult.Success<String>>()
        result.isSuccess shouldBe true
        result.executedSteps shouldBe 3
        result.duration.inWholeMilliseconds shouldBeGreaterThan 0L
    }

    @JsName("sagaFailureResultProvidesCompensationMetrics")
    @Test
    fun `saga failure result provides compensation metrics`() = runTest {
        val saga = saga<String> {
            step("Step 1", { "r1" }, {})
            step("Step 2", { "r2" }, {})
            step("Step 3", { "r3" }, {})
            step("Step 4", { throw RuntimeException("Fail") })
        }

        val result = saga.execute()

        result.shouldBeInstanceOf<SagaResult.Failure<String>>()
        result.isSuccess shouldBe false
        result.compensatedSteps shouldBe 3
        result.compensationErrors shouldHaveSize 0
    }

    @JsName("parallelSagaCoordinatorExecutesSagasInParallel")
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

    @JsName("parallelSagaCoordinatorHandlesMixedSuccessAndFailure")
    @Test
    fun `ParallelSagaCoordinator handles mixed success and failure`() = runTest {
        val coordinator = ParallelSagaCoordinator()

        val sagas = listOf(
            saga<String> { step("Success", { "ok" }) },
            saga<String> { step("Fail", { throw RuntimeException("Error") }) },
            saga<String> { step("Success 2", { "ok2" }) }
        )

        val results = coordinator.executeAll(sagas)

        results shouldHaveSize 3
        results.count { it is SagaResult.Success } shouldBe 2
        results.count { it is SagaResult.Failure } shouldBe 1
    }

    @JsName("parallelSagaCoordinatorExecuteWithStatsProvidesStatistics")
    @Test
    fun `ParallelSagaCoordinator executeWithStats provides statistics`() = runTest {
        val coordinator = ParallelSagaCoordinator()

        val sagas = listOf(
            saga<String> { step("S1", { "r1" }) },
            saga<String> { step("S2", { throw RuntimeException("Fail") }) },
            saga<String> { step("S3", { "r3" }) }
        )

        val result = coordinator.executeWithStats(sagas)

        result.totalSagas shouldBe 3
        result.successfulSagas shouldBe 2
        result.failedSagas shouldBe 1
        result.successRate shouldBeGreaterThan 0.6
        result.successRate shouldBeLessThan 0.7 // 2/3 (approximately)
        result.allSuccessful shouldBe false
    }

    @JsName("parallelSagaCoordinatorAllSuccessfulCase")
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

    @JsName("complexSagaScenarioWithInventoryAndPaymentAndOrder")
    @Test
    fun `complex saga scenario with inventory and payment and order`() = runTest {
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
                    throw RuntimeException("Order creation failed")
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

    @JsName("sagaWithRetryAndTimeoutSteps")
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
                    if (retriedStepAttempts < 2) throw RuntimeException("Temporary failure")
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

    @JsName("sagaBuilderCompanionCreatesBuilder")
    @Test
    fun `Saga builder companion creates builder`() {
        val builder = Saga.builder<String>()
        // Just verify it doesn't throw
        shouldThrow<IllegalArgumentException> {
            builder.build() // Should throw because no steps added
        }
    }
}
