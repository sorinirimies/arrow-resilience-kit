// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies


import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import mu.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Saga pattern implementation for managing distributed transactions.
 *
 * A Saga coordinates a sequence of local transactions where each transaction has a
 * compensating action to undo its effects. If any step fails, all previously completed
 * steps are compensated (rolled back) in reverse order.
 *
 * **Pattern:**
 * ```
 * Step 1 -> Step 2 -> Step 3 -> ... -> Success
 *    ↓        ↓        ↓
 * Comp 1  Comp 2  Comp 3  (executed in reverse on failure)
 * ```
 *
 * Example usage:
 * ```
 * val saga = saga {
 *     step(
 *         name = "Reserve inventory",
 *         action = { inventoryService.reserve(items) },
 *         compensation = { result -> inventoryService.release(result.reservationId) }
 *     )
 *
 *     step(
 *         name = "Charge payment",
 *         action = { paymentService.charge(amount) },
 *         compensation = { result -> paymentService.refund(result.transactionId) }
 *     )
 *
 *     step(
 *         name = "Create order",
 *         action = { orderService.create(orderDetails) },
 *         compensation = { result -> orderService.cancel(result.orderId) }
 *     )
 * }
 *
 * val result = saga.execute()
 * ```
 */
class Saga<T> private constructor(
    private val steps: List<SagaStep<*, *>>,
    private val config: SagaConfig,
) {
    private val executedSteps = mutableListOf<ExecutedStep<*>>()

    /**
     * Executes the saga, running all steps in sequence.
     *
     * If any step fails, automatically compensates all previously executed steps in reverse order.
     *
     * @return [SagaResult] containing the final result or compensation details
     */
    suspend fun execute(): SagaResult<T> {
        logger.info { "Starting saga execution with ${steps.size} steps" }
        val startTime = kotlinx.datetime.Clock.System.now()

        try {
            // Execute all steps forward
            steps.forEachIndexed { index, step ->
                logger.debug { "Executing step ${index + 1}/${steps.size}: ${step.name}" }
                val stepResult = executeStep(step)
                @Suppress("UNCHECKED_CAST")
                executedSteps.add(
                    ExecutedStep(
                        step = step as SagaStep<Any?, *>,
                        result = stepResult,
                        index = index,
                    ) as ExecutedStep<*>
                )
            }

            val duration = kotlinx.datetime.Clock.System.now() - startTime
            logger.info { "Saga completed successfully in $duration" }

            @Suppress("UNCHECKED_CAST")
            val finalResult = executedSteps.lastOrNull()?.result as? T
            return SagaResult.Success<T>(
                result = finalResult,
                executedSteps = executedSteps.size,
                duration = duration,
            )
        } catch (e: Exception) {
            logger.error(e) { "Saga failed at step ${executedSteps.size + 1}/${steps.size}, starting compensation" }
            return compensate(e, startTime)
        }
    }

    private suspend fun <R> executeStep(step: SagaStep<R, *>): R {
        return try {
            step.action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Step '${step.name}' failed: ${e.message}" }
            throw SagaStepException(step.name, e)
        }
    }

    private suspend fun compensate(
        originalError: Exception,
        startTime: kotlinx.datetime.Instant,
    ): SagaResult<T> {
        val compensationErrors = mutableListOf<CompensationError>()

        // Compensate in reverse order
        executedSteps.asReversed().forEachIndexed { reverseIndex, executedStep ->
            val step = executedStep.step
            val stepIndex = executedSteps.size - reverseIndex

            if (step.compensation == null) {
                logger.warn { "No compensation defined for step $stepIndex: ${step.name}, skipping" }
                return@forEachIndexed
            }

            logger.info { "Compensating step $stepIndex/${executedSteps.size}: ${step.name}" }

            try {
                @Suppress("UNCHECKED_CAST")
                (step as SagaStep<Any?, Any?>).compensation?.invoke(executedStep.result)
                logger.debug { "Successfully compensated step: ${step.name}" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Compensation failed for step '${step.name}': ${e.message}" }
                compensationErrors.add(
                    CompensationError(
                        stepName = step.name,
                        stepIndex = executedStep.index,
                        error = e,
                    )
                )

                if (!config.continueOnCompensationFailure) {
                    logger.error { "Stopping compensation due to failure in step: ${step.name}" }
                    // Stop compensation on failure
                }
            }
        }

        val duration = kotlinx.datetime.Clock.System.now() - startTime

        return SagaResult.Failure<T>(
            error = originalError,
            compensatedSteps = executedSteps.size - compensationErrors.size,
            compensationErrors = compensationErrors,
            duration = duration,
        )
    }

    companion object {
        /**
         * Creates a new Saga instance with the given steps and configuration.
         */
        fun <T> create(steps: List<SagaStep<*, *>>, config: SagaConfig = SagaConfig()): Saga<T> {
            require(steps.isNotEmpty()) { "Saga must have at least one step" }
            return Saga(steps, config)
        }

        /**
         * Creates a new saga builder.
         */
        fun <T> builder(): SagaBuilder<T> = SagaBuilder()
    }
}

/**
 * Builder for constructing a saga with multiple steps.
 */
class SagaBuilder<T> {
    private val steps = mutableListOf<SagaStep<*, *>>()
    private var config = SagaConfig()

    /**
     * Adds a step to the saga.
     *
     * @param name Human-readable name for the step
     * @param action The action to execute
     * @param compensation The compensation action to execute on failure (optional)
     */
    fun <R> step(
        name: String,
        action: suspend () -> R,
        compensation: (suspend (R) -> Unit)? = null,
    ): SagaBuilder<T> {
        steps.add(
            SagaStep<R, T>(
                name = name,
                action = action,
                compensation = compensation,
            )
        )
        return this
    }

    /**
     * Adds a step with timeout.
     *
     * @param name Human-readable name for the step
     * @param timeout Maximum time allowed for the action
     * @param action The action to execute
     * @param compensation The compensation action to execute on failure (optional)
     */
    fun <R> stepWithTimeout(
        name: String,
        timeout: kotlin.time.Duration,
        action: suspend () -> R,
        compensation: (suspend (R) -> Unit)? = null,
    ): SagaBuilder<T> {
        steps.add(
            SagaStep<R, T>(
                name = name,
                action = {
                    kotlinx.coroutines.withTimeout(timeout) {
                        action()
                    }
                },
                compensation = compensation,
            )
        )
        return this
    }

    /**
     * Adds a step with retry capability.
     *
     * @param name Human-readable name for the step
     * @param retries Number of retry attempts
     * @param action The action to execute
     * @param compensation The compensation action to execute on failure (optional)
     */
    fun <R> stepWithRetry(
        name: String,
        retries: Long = 3,
        action: suspend () -> R,
        compensation: (suspend (R) -> Unit)? = null,
    ): SagaBuilder<T> {
        steps.add(
            SagaStep<R, T>(
                name = name,
                action = {
                    retryWithExponentialBackoff(retries = retries) {
                        action()
                    }
                },
                compensation = compensation,
            )
        )
        return this
    }

    /**
     * Configures saga behavior.
     *
     * @param configure Configuration block
     */
    fun configure(configure: SagaConfig.() -> SagaConfig): SagaBuilder<T> {
        config = configure(config)
        return this
    }

    /**
     * Builds the saga.
     */
    fun build(): Saga<T> {
        return Saga.create(steps, config)
    }
}

/**
 * DSL function for creating a saga.
 *
 * Example:
 * ```
 * val saga = saga<OrderResult> {
 *     step(
 *         name = "Reserve inventory",
 *         action = { inventoryService.reserve(items) },
 *         compensation = { result -> inventoryService.release(result) }
 *     )
 *
 *     step(
 *         name = "Process payment",
 *         action = { paymentService.charge(amount) },
 *         compensation = { result -> paymentService.refund(result) }
 *     )
 * }
 * ```
 */
fun <T> saga(configure: SagaBuilder<T>.() -> Unit): Saga<T> {
    val builder = Saga.builder<T>()
    builder.configure()
    return builder.build()
}

/**
 * Executes a saga and returns the result.
 *
 * Convenience function that builds and executes a saga in one call.
 */
suspend fun <T> executeSaga(configure: SagaBuilder<T>.() -> Unit): SagaResult<T> {
    return saga(configure).execute()
}

/**
 * Configuration for saga execution behavior.
 *
 * @property continueOnCompensationFailure If true, continues compensating remaining steps even if one fails
 * @property compensationTimeout Maximum time allowed for all compensations
 */
data class SagaConfig(
    val continueOnCompensationFailure: Boolean = true,
    val compensationTimeout: kotlin.time.Duration? = null,
)

/**
 * Represents a single step in a saga.
 *
 * @param R The result type of the step action
 * @param name Human-readable name for the step
 * @param action The action to execute
 * @param compensation The compensation action to execute on failure
 */
data class SagaStep<R, T>(
    val name: String,
    val action: suspend () -> R,
    val compensation: (suspend (R) -> Unit)? = null,
)

/**
 * Represents a step that has been executed.
 */
private data class ExecutedStep<R>(
    val step: SagaStep<R, *>,
    val result: R,
    val index: Int,
)

/**
 * Result of saga execution.
 */
sealed class SagaResult<T> {
    /**
     * Saga completed successfully.
     *
     * @property result The final result of the last step
     * @property executedSteps Number of steps executed
     * @property duration Total execution time
     */
    data class Success<T>(
        val result: T?,
        val executedSteps: Int,
        val duration: Duration,
    ) : SagaResult<T>() {
        val isSuccess: Boolean = true
    }

    /**
     * Saga failed and was compensated.
     *
     * @property error The original error that caused the failure
     * @property compensatedSteps Number of steps successfully compensated
     * @property compensationErrors List of errors that occurred during compensation
     * @property duration Total execution time including compensation
     */
    data class Failure<T>(
        val error: Exception,
        val compensatedSteps: Int,
        val compensationErrors: List<CompensationError>,
        val duration: Duration,
    ) : SagaResult<T>() {
        val isSuccess: Boolean = false
        val hasCompensationErrors: Boolean = compensationErrors.isNotEmpty()
    }
}

/**
 * Information about a compensation error.
 *
 * @property stepName Name of the step that failed to compensate
 * @property stepIndex Index of the step in the saga
 * @property error The error that occurred during compensation
 */
data class CompensationError(
    val stepName: String,
    val stepIndex: Int,
    val error: Exception,
)

/**
 * Exception thrown when a saga step fails.
 */
class SagaStepException(
    val stepName: String,
    cause: Throwable,
) : Exception("Saga step '$stepName' failed", cause)

/**
 * Parallel saga coordinator for executing multiple sagas concurrently.
 *
 * Useful for scenarios where multiple independent sagas need to run in parallel,
 * such as processing a batch of orders.
 *
 * Example:
 * ```
 * val coordinator = ParallelSagaCoordinator()
 *
 * val results = coordinator.executeAll(
 *     orders.map { order ->
 *         saga {
 *             step("Process", { processOrder(order) }, { cancelOrder(it) })
 *         }
 *     }
 * )
 * ```
 */
class ParallelSagaCoordinator {
    /**
     * Executes multiple sagas in parallel.
     *
     * @param sagas List of sagas to execute
     * @return List of results, one for each saga
     */
    suspend fun <T> executeAll(sagas: List<Saga<T>>): List<SagaResult<T>> {
        return coroutineScope {
            sagas.map { saga ->
                async {
                    saga.execute()
                }
            }.map { it.await() }
        }
    }

    /**
     * Executes multiple sagas in parallel and collects statistics.
     *
     * @param sagas List of sagas to execute
     * @return [ParallelSagaResult] with results and statistics
     */
    suspend fun <T> executeWithStats(sagas: List<Saga<T>>): ParallelSagaResult<T> {
        val startTime = kotlinx.datetime.Clock.System.now()
        val results = executeAll(sagas)
        val duration = kotlinx.datetime.Clock.System.now() - startTime

        val successful = results.count { it is SagaResult.Success }
        val failed = results.count { it is SagaResult.Failure }

        return ParallelSagaResult(
            results = results,
            totalSagas = sagas.size,
            successfulSagas = successful,
            failedSagas = failed,
            duration = duration,
        )
    }
}

/**
 * Result of parallel saga execution.
 */
data class ParallelSagaResult<T>(
    val results: List<SagaResult<T>>,
    val totalSagas: Int,
    val successfulSagas: Int,
    val failedSagas: Int,
    val duration: Duration,
) {
    val successRate: Double = if (totalSagas > 0) successfulSagas.toDouble() / totalSagas else 0.0
    val allSuccessful: Boolean = failedSagas == 0
}