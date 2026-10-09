// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package com.sorinirmies.arrow.resiliencekit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import arrow.fx.stm.TVar
import arrow.fx.stm.atomically
import kotlin.time.Clock
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Saga pattern for distributed transactions with compensation.
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
 * **Basic usage:**
 * ```kotlin
 * val result = executeSaga<OrderId> {
 *     step("Reserve inventory",
 *         action = { inventoryService.reserve(items) },
 *         compensation = { inventoryService.release(it) }
 *     )
 *     step("Charge payment",
 *         action = { paymentService.charge(amount) },
 *         compensation = { paymentService.refund(it) }
 *     )
 *     step("Create order",
 *         action = { orderService.create(order) },
 *         compensation = { orderService.cancel(it) }
 *     )
 * }
 *
 * when (result) {
 *     is SagaResult.Success -> println("Order ${result.result} created")
 *     is SagaResult.Failure -> println("Failed: ${result.error}")
 * }
 * ```
 *
 * **With retry and timeout:**
 * ```kotlin
 * val saga = saga<String> {
 *     stepWithRetry("Flaky API", retries = 3, action = { callApi() })
 *     stepWithTimeout("Slow service", timeout = 5.seconds, action = { callSlow() })
 * }
 * ```
 *
 * **DSL builder:**
 * ```kotlin
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
public class Saga<T> private constructor(
    private val steps: List<SagaStep<*, *>>,
    private val config: SagaConfig,
    private val clock: Clock = Clock.System,
) {
    private val executedSteps = mutableListOf<ExecutedStep<*>>()

    /**
     * Executes the saga, running all steps in sequence.
     *
     * If any step fails, automatically compensates all previously executed steps in reverse order.
     *
     * @return [SagaResult] containing the final result or compensation details
     */
    public suspend fun execute(): SagaResult<T> {
        executedSteps.clear()
        logger.info { "Starting saga execution with ${steps.size} steps" }
        val startTime = clock.now()

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

            val duration = clock.now() - startTime
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
        startTime: kotlin.time.Instant,
    ): SagaResult<T> {
        val compensationErrors = mutableListOf<CompensationError>()
        var compensatedCount = 0

        // Compensate in reverse order
        for ((reverseIndex, executedStep) in executedSteps.asReversed().withIndex()) {
            val step = executedStep.step
            val stepIndex = executedSteps.size - reverseIndex

            if (step.compensation == null) {
                logger.warn { "No compensation defined for step $stepIndex: ${step.name}, skipping" }
                continue
            }

            logger.info { "Compensating step $stepIndex/${executedSteps.size}: ${step.name}" }

            try {
                @Suppress("UNCHECKED_CAST")
                (step as SagaStep<Any?, Any?>).compensation?.invoke(executedStep.result)
                logger.debug { "Successfully compensated step: ${step.name}" }
                compensatedCount++
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
                    break
                }
            }
        }

        val duration = clock.now() - startTime

        return SagaResult.Failure<T>(
            error = originalError,
            compensatedSteps = compensatedCount,
            compensationErrors = compensationErrors,
            duration = duration,
        )
    }

    /** Factory for [Saga]. */
    public companion object {
        /**
         * Creates a new Saga instance with the given steps and configuration.
         */
        public fun <T> create(
            steps: List<SagaStep<*, *>>,
            config: SagaConfig = SagaConfig(),
            clock: Clock = Clock.System
        ): Saga<T> {
            require(steps.isNotEmpty()) { "Saga must have at least one step" }
            return Saga(steps, config, clock)
        }

        /**
         * Creates a new saga builder.
         */
        public fun <T> builder(): SagaBuilder<T> = SagaBuilder()
    }
}

/**
 * Builder for constructing a saga with multiple steps.
 */
public class SagaBuilder<T>(private val clock: Clock = Clock.System) {
    private val steps = mutableListOf<SagaStep<*, *>>()
    private var config = SagaConfig()

    /**
     * Adds a step to the saga.
     *
     * @param name Human-readable name for the step
     * @param action The action to execute
     * @param compensation The compensation action to execute on failure (optional)
     */
    public fun <R> step(
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
    public fun <R> stepWithTimeout(
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
    public fun <R> stepWithRetry(
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
    public fun configure(configure: SagaConfig.() -> SagaConfig): SagaBuilder<T> {
        config = configure(config)
        return this
    }

    /**
     * Builds the saga.
     */
    public fun build(): Saga<T> {
        return Saga.create(steps, config, clock)
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
public fun <T> saga(clock: Clock = Clock.System, configure: SagaBuilder<T>.() -> Unit): Saga<T> {
    val builder = SagaBuilder<T>(clock)
    builder.configure()
    return builder.build()
}

/**
 * Executes a saga and returns the result.
 *
 * Convenience function that builds and executes a saga in one call.
 */
public suspend fun <T> executeSaga(clock: Clock = Clock.System, configure: SagaBuilder<T>.() -> Unit): SagaResult<T> {
    return saga(clock, configure).execute()
}

/**
 * Configuration for saga execution behavior.
 *
 * @property continueOnCompensationFailure If true, continues compensating remaining steps even if one fails
 * @property compensationTimeout Maximum time allowed for all compensations
 */
public data class SagaConfig(
    public val continueOnCompensationFailure: Boolean = true,
    public val compensationTimeout: kotlin.time.Duration? = null,
)

/**
 * Represents a single step in a saga.
 *
 * @param R The result type of the step action
 * @param name Human-readable name for the step
 * @param action The action to execute
 * @param compensation The compensation action to execute on failure
 */
public data class SagaStep<R, T>(
    public val name: String,
    public val action: suspend () -> R,
    public val compensation: (suspend (R) -> Unit)? = null,
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
public sealed class SagaResult<T> {
    /**
     * Saga completed successfully.
     *
     * @property result The final result of the last step
     * @property executedSteps Number of steps executed
     * @property duration Total execution time
     */
    public data class Success<T>(
        public val result: T?,
        public val executedSteps: Int,
        public val duration: Duration,
    ) : SagaResult<T>() {
        /** Always `true` for [Success]. */
        public val isSuccess: Boolean = true
    }

    /**
     * Saga failed and was compensated.
     *
     * @property error The original error that caused the failure
     * @property compensatedSteps Number of steps successfully compensated
     * @property compensationErrors List of errors that occurred during compensation
     * @property duration Total execution time including compensation
     */
    public data class Failure<T>(
        public val error: Exception,
        public val compensatedSteps: Int,
        public val compensationErrors: List<CompensationError>,
        public val duration: Duration,
    ) : SagaResult<T>() {
        /** Always `false` for [Failure]. */
        public val isSuccess: Boolean = false
        /** Whether any compensation (rollback) step itself also failed. */
        public val hasCompensationErrors: Boolean = compensationErrors.isNotEmpty()
    }
}

/**
 * Information about a compensation error.
 *
 * @property stepName Name of the step that failed to compensate
 * @property stepIndex Index of the step in the saga
 * @property error The error that occurred during compensation
 */
public data class CompensationError(
    public val stepName: String,
    public val stepIndex: Int,
    public val error: Exception,
)

/**
 * Exception thrown when a saga step fails.
 */
public class SagaStepException(
    /** Name of the step that failed. */
    public val stepName: String,
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
public class ParallelSagaCoordinator {
    /**
     * Executes multiple sagas in parallel.
     *
     * @param sagas List of sagas to execute
     * @return List of results, one for each saga
     */
    public suspend fun <T> executeAll(sagas: List<Saga<T>>): List<SagaResult<T>> {
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
    public suspend fun <T> executeWithStats(sagas: List<Saga<T>>): ParallelSagaResult<T> {
        val startTime = Clock.System.now()
        val results = executeAll(sagas)
        val duration = Clock.System.now() - startTime

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
public data class ParallelSagaResult<T>(
    /** The result of each saga, in the same order they were submitted. */
    public val results: List<SagaResult<T>>,
    /** Total number of sagas executed. */
    public val totalSagas: Int,
    /** Number of sagas that completed successfully. */
    public val successfulSagas: Int,
    /** Number of sagas that failed (and were compensated). */
    public val failedSagas: Int,
    /** Total wall-clock time to run every saga. */
    public val duration: Duration,
) {
    /** Ratio of successful sagas from 0.0 to 1.0. */
    public val successRate: Double = if (totalSagas > 0) successfulSagas.toDouble() / totalSagas else 0.0

    /** Whether all sagas completed successfully. */
    public val allSuccessful: Boolean = failedSagas == 0
}

/**
 * Registry for managing multiple named, reusable saga templates.
 *
 * A [Saga] instance is safe to [Saga.execute] more than once (its per-run
 * tracking state is reset at the start of every execution), so storing one by
 * name and re-running it is the main use case for this registry -- e.g. an
 * "order-checkout" saga built once at startup and executed per order.
 *
 * Example usage:
 * ```
 * val registry = SagaRegistry.create()
 *
 * val checkout = registry.getOrCreate<OrderResult>("order-checkout") {
 *     step("Reserve inventory", action = { inventoryService.reserve(items) })
 *     step("Charge payment", action = { paymentService.charge(amount) })
 * }
 *
 * val result = checkout.execute()
 * ```
 */
public class SagaRegistry private constructor(
    private val sagas: TVar<Map<String, Saga<*>>>,
) {
    /** Factory for [SagaRegistry]. */
    public companion object {
        /** Creates a new, empty [SagaRegistry]. */
        public suspend fun create(): SagaRegistry {
            val sagas = TVar.new(emptyMap<String, Saga<*>>())
            return SagaRegistry(sagas)
        }
    }

    /**
     * Gets an existing saga template or builds a new one from [configure].
     *
     * If a saga is already registered under [name], [configure] is ignored
     * and the existing instance is returned as-is -- same caveat as
     * [CacheRegistry.getOrCreate]: calling this with a different `T` for an
     * already-registered name is a programmer error, not something this API
     * can check at compile time.
     */
    @Suppress("UNCHECKED_CAST")
    public suspend fun <T> getOrCreate(name: String, configure: SagaBuilder<T>.() -> Unit): Saga<T> {
        val existing = atomically { sagas.read()[name] }
        if (existing != null) return existing as Saga<T>

        val newSaga = saga(configure = configure)
        return atomically {
            val current = sagas.read()
            val existingInTx = current[name]
            if (existingInTx != null) {
                existingInTx as Saga<T>
            } else {
                sagas.write(current + (name to newSaga))
                newSaga
            }
        }
    }

    /**
     * Gets an existing saga template by name.
     */
    @Suppress("UNCHECKED_CAST")
    public suspend fun <T> get(name: String): Saga<T>? = atomically { sagas.read()[name] as? Saga<T> }

    /**
     * Removes a saga template from the registry.
     */
    public suspend fun remove(name: String): Saga<*>? = atomically {
        val current = sagas.read()
        val removed = current[name]
        if (removed != null) {
            sagas.write(current - name)
        }
        removed
    }

    /**
     * Gets all saga template names in the registry.
     */
    public suspend fun getNames(): Set<String> = atomically { sagas.read().keys }
}
