// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies


import arrow.core.Either
import arrow.resilience.Schedule
import arrow.resilience.repeat
import arrow.resilience.repeatOrElse
import arrow.resilience.retry
import arrow.resilience.retryOrElse
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Applies jitter to a delay duration to prevent thundering herd problems.
 *
 * @param delay The base delay duration
 * @param jitterFactor The jitter factor (default 0.1 = 10%)
 * @return The delay with jitter applied
 */
private fun applyJitter(delay: Duration, jitterFactor: Double = 0.1): Duration {
    val jitter = (kotlin.random.Random.nextDouble() * 2 * jitterFactor) - jitterFactor
    return delay * (1.0 + jitter)
}

// ============================================================================
// RETRY OPERATIONS
// ============================================================================

/**
 * Executes a suspend operation with retry capabilities using exponential backoff and jitter.
 *
 * This is the primary retry function for most use cases. It uses Arrow's Schedule library with
 * an exponential backoff strategy and automatic jitter (10%) to prevent thundering herd problems.
 *
 * **Retry Behavior:**
 * - Total attempts = initial attempt + [retries]
 * - Delay grows exponentially: base * factor^attempt
 * - Jitter (±10%) randomizes delays to avoid synchronized retries
 * - All exceptions are retried (except CancellationException)
 *
 * @param R The return type of the suspend operation
 * @param retries The maximum number of retry attempts after the initial attempt (defaults to 3)
 * @param base The base duration for exponential backoff (defaults to 200 milliseconds)
 * @param factor The multiplication factor for exponential backoff (defaults to 2.2)
 * @param block The suspend operation to execute with retry logic
 *
 * @return The result of the successful operation
 * @throws Exception if all retry attempts fail
 */
suspend fun <R> retryWithExponentialBackoff(
    retries: Long = 3,
    base: Duration = 200.milliseconds,
    factor: Double = 2.2,
    block: suspend () -> R,
): R {
    require(retries >= 0) { "retries must be >= 0, but was $retries" }
    require(base >= Duration.ZERO) { "base delay must be >= 0, but was $base" }
    require(factor > 0) { "factor must be > 0, but was $factor" }

    val retrySchedule = Schedule.exponential<Throwable>(
        base = base,
        factor = factor,
    ).jittered() and Schedule.recurs(retries)
    return retrySchedule.retry(block)
}

/**
 * Executes a suspend operation with retry capabilities using capped exponential backoff.
 *
 * Like [retryWithExponentialBackoff] but with a maximum delay cap to prevent extremely long waits.
 * This is useful in production to ensure reasonable retry times.
 *
 * @param R The return type of the suspend operation
 * @param retries The maximum number of retry attempts (defaults to 5)
 * @param base The base duration for exponential backoff (defaults to 200 milliseconds)
 * @param maxDelay The maximum delay between retries (defaults to 30 seconds)
 * @param factor The multiplication factor for exponential backoff (defaults to 2.2)
 * @param block The suspend operation to execute with retry logic
 *
 * @return The result of the successful operation
 * @throws Exception if all retry attempts fail
 */
suspend fun <R> retryWithCappedBackoff(
    retries: Long = 5,
    base: Duration = 200.milliseconds,
    maxDelay: Duration = 30.seconds,
    factor: Double = 2.2,
    block: suspend () -> R,
): R {
    require(retries >= 0) { "retries must be >= 0, but was $retries" }
    require(base >= Duration.ZERO) { "base delay must be >= 0, but was $base" }
    require(maxDelay > Duration.ZERO) { "maxDelay must be > 0, but was $maxDelay" }
    require(factor > 0) { "factor must be > 0, but was $factor" }

    var attempt = 0L
    var currentDelay = base

    while (true) {
        try {
            return block()
        } catch (exception: Exception) {
            if (attempt >= retries) {
                logger.warn(exception) { "All retry attempts exhausted after $attempt attempts" }
                throw exception
            }

            logger.debug {
                "Retrying after ${exception::class.simpleName}: ${exception.message} (attempt ${attempt + 1}/$retries)"
            }

            val cappedDelay = minOf(currentDelay, maxDelay)
            delay(applyJitter(cappedDelay))

            currentDelay *= factor
            attempt++
        }
    }
}

/**
 * Executes a suspend operation with Fibonacci backoff strategy.
 *
 * Uses Fibonacci sequence for delays: 1, 1, 2, 3, 5, 8, 13, 21...
 * This provides a middle ground between linear and exponential backoff.
 *
 * @param R The return type of the suspend operation
 * @param retries The maximum number of retry attempts (defaults to 5)
 * @param base The base duration multiplier (defaults to 200 milliseconds)
 * @param block The suspend operation to execute with retry logic
 *
 * @return The result of the successful operation
 * @throws Exception if all retry attempts fail
 */
suspend fun <R> retryWithFibonacciBackoff(
    retries: Long = 5,
    base: Duration = 200.milliseconds,
    block: suspend () -> R,
): R {
    require(retries >= 0) { "retries must be >= 0, but was $retries" }
    require(base >= Duration.ZERO) { "base delay must be >= 0, but was $base" }

    var attempt = 0L
    var fib1 = 1L
    var fib2 = 1L

    while (true) {
        try {
            return block()
        } catch (exception: Exception) {
            if (attempt >= retries) {
                logger.warn(exception) { "All retry attempts exhausted after $attempt attempts" }
                throw exception
            }

            logger.debug {
                "Retrying after ${exception::class.simpleName} (Fibonacci attempt ${attempt + 1}/$retries)"
            }

            val fibDelay = base * fib1
            delay(applyJitter(fibDelay))

            val nextFib = fib1 + fib2
            fib2 = fib1
            fib1 = nextFib
            attempt++
        }
    }
}

/**
 * Executes a suspend operation with retry and returns a fallback value if all retries fail.
 *
 * @param R The return type of the suspend operation
 * @param retries The maximum number of retry attempts (defaults to 3)
 * @param base The base duration for exponential backoff (defaults to 200 milliseconds)
 * @param factor The multiplication factor for exponential backoff (defaults to 2.2)
 * @param fallback The fallback value to return if all retries fail
 * @param block The suspend operation to execute with retry logic
 *
 * @return The result of the successful operation or the fallback value
 */
suspend fun <R> retryOrDefault(
    retries: Long = 3,
    base: Duration = 200.milliseconds,
    factor: Double = 2.2,
    fallback: R,
    block: suspend () -> R,
): R {
    require(retries >= 0) { "retries must be >= 0, but was $retries" }
    require(base >= Duration.ZERO) { "base delay must be >= 0, but was $base" }
    require(factor > 0) { "factor must be > 0, but was $factor" }

    val retrySchedule = Schedule.exponential<Throwable>(
        base = base,
        factor = factor,
    ).jittered() and Schedule.recurs(retries)

    return retrySchedule.retryOrElse(
        action = block,
        orElse = { error, _ ->
            logger.warn(error) { "All retry attempts failed, returning fallback value" }
            fallback
        },
    )
}

/**
 * Executes a suspend operation with retry based on a custom predicate.
 *
 * @param R The return type of the suspend operation
 * @param retries The maximum number of retry attempts (defaults to 3)
 * @param base The base duration for exponential backoff (defaults to 200 milliseconds)
 * @param factor The multiplication factor for exponential backoff (defaults to 2.2)
 * @param shouldRetry Predicate that determines whether to retry based on the exception
 * @param block The suspend operation to execute with retry logic
 *
 * @return The result of the successful operation
 * @throws Exception if the predicate returns false or all retry attempts fail
 */
@Suppress("TooGenericExceptionCaught")
suspend fun <R> retryIf(
    retries: Long = 3,
    base: Duration = 200.milliseconds,
    factor: Double = 2.2,
    shouldRetry: (Throwable) -> Boolean,
    block: suspend () -> R,
): R {
    require(retries >= 0) { "retries must be >= 0, but was $retries" }
    require(base >= Duration.ZERO) { "base delay must be >= 0, but was $base" }
    require(factor > 0) { "factor must be > 0, but was $factor" }

    var attempt = 0L
    var currentDelay = base

    while (true) {
        try {
            return block()
        } catch (exception: Exception) {
            val shouldRetryResult = shouldRetry(exception)

            if (!shouldRetryResult || attempt >= retries) {
                if (!shouldRetryResult) {
                    logger.debug {
                        "Not retrying exception ${exception::class.simpleName}: predicate returned false"
                    }
                } else {
                    logger.warn(exception) { "All retry attempts exhausted after $attempt attempts" }
                }
                throw exception
            }

            logger.debug {
                "Retrying after ${exception::class.simpleName}: ${exception.message} (attempt ${attempt + 1}/$retries)"
            }

            delay(applyJitter(currentDelay))
            currentDelay *= factor
            attempt++
        }
    }
}

/**
 * Executes a suspend operation with retry and collects all results including failures.
 *
 * @param R The return type of the suspend operation
 * @param retries The maximum number of retry attempts (defaults to 3)
 * @param base The base duration for exponential backoff (defaults to 200 milliseconds)
 * @param factor The multiplication factor for exponential backoff (defaults to 2.2)
 * @param block The suspend operation to execute with retry logic
 *
 * @return [RetryResult] containing the final result and all attempt history
 */
suspend fun <R> retryAndCollect(
    retries: Long = 3,
    base: Duration = 200.milliseconds,
    factor: Double = 2.2,
    block: suspend () -> R,
): RetryResult<R> {
    require(retries >= 0) { "retries must be >= 0, but was $retries" }
    require(base >= Duration.ZERO) { "base delay must be >= 0, but was $base" }
    require(factor > 0) { "factor must be > 0, but was $factor" }

    val attempts = mutableListOf<AttemptResult<R>>()
    val startTime = kotlinx.datetime.Clock.System.now()

    var attempt = 0L
    var currentDelay = base
    var lastException: Exception? = null

    while (attempt <= retries) {
        val attemptStart = kotlinx.datetime.Clock.System.now()
        try {
            val result = block()
            val attemptDuration = kotlinx.datetime.Clock.System.now() - attemptStart
            attempts.add(
                AttemptResult(
                    attemptNumber = (attempt + 1).toInt(),
                    result = Result.success(result),
                    duration = attemptDuration,
                )
            )
            val totalDuration = kotlinx.datetime.Clock.System.now() - startTime
            return RetryResult(
                finalResult = Result.success(result),
                attempts = attempts,
                totalDuration = totalDuration,
            )
        } catch (exception: Exception) {
            lastException = exception
            val attemptDuration = kotlinx.datetime.Clock.System.now() - attemptStart
            attempts.add(
                AttemptResult(
                    attemptNumber = (attempt + 1).toInt(),
                    result = Result.failure(exception),
                    duration = attemptDuration,
                )
            )

            if (attempt < retries) {
                delay(applyJitter(currentDelay))
                currentDelay *= factor
            }
            attempt++
        }
    }

    val totalDuration = kotlinx.datetime.Clock.System.now() - startTime
    return RetryResult(
        finalResult = Result.failure(lastException!!),
        attempts = attempts,
        totalDuration = totalDuration,
    )
}

// ============================================================================
// REPEAT OPERATIONS
// ============================================================================

/**
 * Repeats a successful operation multiple times with exponential backoff between repetitions.
 *
 * Unlike retry which continues on failure, repeat continues on success and stops on failure.
 * This is useful for polling, collecting multiple results, or periodic tasks.
 *
 * **Repeat Behavior:**
 * - Executes the operation, if it succeeds, waits and repeats
 * - Stops on first failure or when repetition limit is reached
 * - Returns the last successful result
 *
 * @param R The return type of the suspend operation
 * @param times The number of times to repeat after the initial execution (defaults to 3, total 4 executions)
 * @param base The base duration for exponential backoff (defaults to 200 milliseconds)
 * @param factor The multiplication factor for exponential backoff (defaults to 2.2)
 * @param block The suspend operation to repeat
 *
 * @return The result of the last successful execution
 * @throws Exception if any execution fails
 *
 * Example usage:
 * ```
 * // Poll an API 5 times with increasing delays
 * val lastStatus = repeatWithExponentialBackoff(times = 4) {
 *     apiClient.fetchStatus()
 * }
 * ```
 */
suspend fun <R> repeatWithExponentialBackoff(
    times: Long = 3,
    base: Duration = 200.milliseconds,
    factor: Double = 2.2,
    block: suspend () -> R,
): R {
    require(times >= 0) { "times must be >= 0, but was $times" }
    require(base >= Duration.ZERO) { "base delay must be >= 0, but was $base" }
    require(factor > 0) { "factor must be > 0, but was $factor" }

    val repeatSchedule = Schedule.exponential<R>(
        base = base,
        factor = factor,
    ).jittered() and Schedule.recurs(times)

    return repeatSchedule.repeat(block)
}

/**
 * Repeats a successful operation with constant delay between repetitions.
 *
 * @param R The return type of the suspend operation
 * @param times The number of times to repeat after the initial execution (defaults to 3)
 * @param delay The fixed delay between repetitions (defaults to 500 milliseconds)
 * @param block The suspend operation to repeat
 *
 * @return The result of the last successful execution
 * @throws Exception if any execution fails
 *
 * Example usage:
 * ```
 * // Health check every 2 seconds
 * val lastCheck = repeatWithFixedDelay(times = 10, delay = 2.seconds) {
 *     healthService.check()
 * }
 * ```
 */
suspend fun <R> repeatWithFixedDelay(
    times: Long = 3,
    delay: Duration = 500.milliseconds,
    block: suspend () -> R,
): R {
    require(times >= 0) { "times must be >= 0, but was $times" }
    require(delay >= Duration.ZERO) { "delay must be >= 0, but was $delay" }

    val repeatSchedule = Schedule.recurs<R>(times) and Schedule.spaced(delay)
    return repeatSchedule.repeat(block)
}

/**
 * Repeats an operation until a predicate condition is met.
 *
 * Continues executing the operation and checking the result until the predicate returns true
 * or the maximum number of attempts is reached.
 *
 * @param R The return type of the suspend operation
 * @param maxAttempts The maximum number of execution attempts (defaults to 10)
 * @param delay The delay between repetitions (defaults to 500 milliseconds)
 * @param predicate Function that determines if the condition is met based on the result
 * @param block The suspend operation to repeat
 *
 * @return The result that satisfies the predicate
 * @throws IllegalStateException if max attempts reached without satisfying predicate
 * @throws Exception if any execution fails
 *
 * Example usage:
 * ```
 * // Poll until job is complete
 * val completedJob = repeatUntil(
 *     maxAttempts = 20,
 *     delay = 1.seconds,
 *     predicate = { job -> job.status == JobStatus.COMPLETED }
 * ) {
 *     jobService.getJobStatus(jobId)
 * }
 * ```
 */
suspend fun <R> repeatUntil(
    maxAttempts: Long = 10,
    delay: Duration = 500.milliseconds,
    predicate: (R) -> Boolean,
    block: suspend () -> R,
): R {
    require(maxAttempts > 0) { "maxAttempts must be > 0, but was $maxAttempts" }
    require(delay >= Duration.ZERO) { "delay must be >= 0, but was $delay" }

    val repeatSchedule = Schedule.doUntil<R> { result, _ ->
        predicate(result)
    } and Schedule.recurs(maxAttempts - 1) and Schedule.spaced(delay)

    return repeatSchedule.repeat(block)
}

/**
 * Repeats an operation while a predicate condition remains true.
 *
 * Continues executing the operation as long as the predicate returns true and collects all results.
 * Stops when the predicate returns false or maximum attempts is reached.
 *
 * @param R The return type of the suspend operation
 * @param maxAttempts The maximum number of execution attempts (defaults to 10)
 * @param delay The delay between repetitions (defaults to 500 milliseconds)
 * @param predicate Function that determines if repetition should continue based on the result
 * @param block The suspend operation to repeat
 *
 * @return List of all collected results
 * @throws Exception if any execution fails
 *
 * Example usage:
 * ```
 * // Fetch paginated data while there are more pages
 * val allPages = repeatWhile(
 *     maxAttempts = 100,
 *     delay = 100.milliseconds,
 *     predicate = { response -> response.hasNextPage }
 * ) {
 *     apiClient.fetchNextPage()
 * }
 * ```
 */
suspend fun <R> repeatWhile(
    maxAttempts: Long = 10,
    delay: Duration = 500.milliseconds,
    predicate: (R) -> Boolean,
    block: suspend () -> R,
): List<R> {
    require(maxAttempts > 0) { "maxAttempts must be > 0, but was $maxAttempts" }
    require(delay >= Duration.ZERO) { "delay must be >= 0, but was $delay" }

    val results = mutableListOf<R>()
    var attempts = 0L

    while (attempts < maxAttempts) {
        val result = block()
        results.add(result)

        if (!predicate(result)) {
            logger.debug { "Repeat stopped after ${results.size} iterations - predicate returned false" }
            break
        }

        if (attempts < maxAttempts - 1) {
            kotlinx.coroutines.delay(delay)
        }
        attempts++
    }

    return results
}

/**
 * Repeats an operation and collects all results.
 *
 * @param R The return type of the suspend operation
 * @param times The number of times to repeat after initial execution (defaults to 3)
 * @param delay The delay between repetitions (defaults to 500 milliseconds)
 * @param block The suspend operation to repeat
 *
 * @return List of all results from each execution
 * @throws Exception if any execution fails
 *
 * Example usage:
 * ```
 * // Collect 5 samples
 * val samples = repeatAndCollect(times = 4, delay = 1.seconds) {
 *     sensor.readValue()
 * }
 * ```
 */
suspend fun <R> repeatAndCollect(
    times: Long = 3,
    delay: Duration = 500.milliseconds,
    block: suspend () -> R,
): List<R> {
    require(times >= 0) { "times must be >= 0, but was $times" }
    require(delay >= Duration.ZERO) { "delay must be >= 0, but was $delay" }

    val results = mutableListOf<R>()

    repeat((times + 1).toInt()) { index ->
        val result = block()
        results.add(result)

        if (index < times.toInt()) {
            kotlinx.coroutines.delay(delay)
        }
    }

    return results
}

/**
 * Repeats an operation with error handling via a fallback function.
 *
 * If any repetition fails, invokes the error handler to determine the fallback value.
 *
 * @param R The return type of the suspend operation
 * @param times The number of times to repeat after initial execution (defaults to 3)
 * @param delay The delay between repetitions (defaults to 500 milliseconds)
 * @param onError Function to handle errors and provide fallback values
 * @param block The suspend operation to repeat
 *
 * @return The result of the last successful execution or the fallback value
 *
 * Example usage:
 * ```
 * val result = repeatOrElse(
 *     times = 5,
 *     onError = { error, attempt ->
 *         logger.warn { "Repeat failed at attempt $attempt: ${error.message}" }
 *         emptyList() // fallback value
 *     }
 * ) {
 *     dataCollector.collect()
 * }
 * ```
 */
suspend fun <R> repeatOrElse(
    times: Long = 3,
    delay: Duration = 500.milliseconds,
    onError: suspend (Throwable, Long) -> R,
    block: suspend () -> R,
): R {
    require(times >= 0) { "times must be >= 0, but was $times" }
    require(delay >= Duration.ZERO) { "delay must be >= 0, but was $delay" }

    val repeatSchedule = Schedule.recurs<R>(times) and Schedule.spaced(delay)

    return repeatSchedule.repeatOrElse(
        action = block,
        orElse = { error, decision ->
            logger.warn(error) { "Repeat operation failed, invoking error handler" }
            onError(error, decision.first)
        },
    )
}

/**
 * Repeats an operation with a timeout for the entire repeat cycle.
 *
 * @param R The return type of the suspend operation
 * @param timeout The maximum total time for all repetitions
 * @param times The number of times to repeat after initial execution (defaults to 3)
 * @param delay The delay between repetitions (defaults to 500 milliseconds)
 * @param block The suspend operation to repeat
 *
 * @return The result of the last successful execution
 * @throws kotlinx.coroutines.TimeoutCancellationException if timeout is exceeded
 * @throws Exception if any execution fails
 */
suspend fun <R> repeatWithTimeout(
    timeout: Duration,
    times: Long = 3,
    delay: Duration = 500.milliseconds,
    block: suspend () -> R,
): R {
    require(timeout > Duration.ZERO) { "timeout must be > 0, but was $timeout" }
    require(times >= 0) { "times must be >= 0, but was $times" }
    require(delay >= Duration.ZERO) { "delay must be >= 0, but was $delay" }

    return withTimeout(timeout) {
        repeatWithFixedDelay(times = times, delay = delay, block = block)
    }
}

// ============================================================================
// DATA CLASSES
// ============================================================================

/**
 * Result of a retry operation with history.
 *
 * @param R The return type of the operation
 * @property finalResult The final result (success or failure)
 * @property attempts List of all attempts made
 * @property totalDuration Total time spent on all attempts
 */
data class RetryResult<R>(
    val finalResult: Result<R>,
    val attempts: List<AttemptResult<R>>,
    val totalDuration: kotlinx.datetime.Duration,
) {
    val isSuccess: Boolean get() = finalResult.isSuccess
    val isFailure: Boolean get() = finalResult.isFailure
    val attemptCount: Int get() = attempts.size

    fun getOrNull(): R? = finalResult.getOrNull()
    fun getOrThrow(): R = finalResult.getOrThrow()
    fun exceptionOrNull(): Throwable? = finalResult.exceptionOrNull()
}

/**
 * Result of a single attempt in a retry operation.
 *
 * @param R The return type of the operation
 * @property attemptNumber The attempt number (1-based)
 * @property result The result of this attempt
 * @property duration Time taken for this attempt
 */
data class AttemptResult<R>(
    val attemptNumber: Int,
    val result: Result<R>,
    val duration: kotlinx.datetime.Duration,
) {
    val isSuccess: Boolean get() = result.isSuccess
    val isFailure: Boolean get() = result.isFailure

    fun getOrNull(): R? = result.getOrNull()
    fun exceptionOrNull(): Throwable? = result.exceptionOrNull()
}