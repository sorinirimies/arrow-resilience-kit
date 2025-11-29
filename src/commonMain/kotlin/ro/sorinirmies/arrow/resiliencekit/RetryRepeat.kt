// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies


import arrow.core.Either
import arrow.resilience.Schedule
import arrow.resilience.retry
import arrow.resilience.retryOrElse
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
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
 * This is the primary retry function for most use cases. It uses exponential backoff
 * strategy with automatic jitter (10%) to prevent thundering herd problems.
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
 * @param factor The exponential factor (defaults to 2.0)
 * @param block The suspend operation to execute
 *
 * @return The result of the successful execution
 * @throws Exception if all retry attempts fail
 *
 * Example usage:
 * ```
 * val user = retryWithExponentialBackoff(retries = 5) {
 *     userService.fetchUser("user123")
 * }
 * ```
 */
suspend fun <R> retryWithExponentialBackoff(
    retries: Long = 3,
    base: Duration = 200.milliseconds,
    factor: Double = 2.0,
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
 * Executes a suspend operation with retry using constant delay between attempts.
 *
 * This is useful when you want predictable retry intervals, such as polling an API
 * or waiting for a resource to become available.
 *
 * @param R The return type of the suspend operation
 * @param retries The maximum number of retry attempts (defaults to 3)
 * @param delay The fixed delay between retry attempts (defaults to 1 second)
 * @param block The suspend operation to execute
 *
 * @return The result of the successful execution
 * @throws Exception if all retry attempts fail
 *
 * Example usage:
 * ```
 * val status = retryWithConstantDelay(retries = 10, delay = 500.milliseconds) {
 *     healthService.checkStatus()
 * }
 * ```
 */
suspend fun <R> retryWithConstantDelay(
    retries: Long = 3,
    delay: Duration = 1.seconds,
    block: suspend () -> R,
): R {
    require(retries >= 0) { "retries must be >= 0, but was $retries" }
    require(delay >= Duration.ZERO) { "delay must be >= 0, but was $delay" }

    val retrySchedule = Schedule.spaced<Throwable>(delay) and Schedule.recurs(retries)
    return retrySchedule.retry(block)
}

/**
 * Executes a suspend operation with retry using Fibonacci sequence for delays.
 *
 * The Fibonacci sequence provides a middle ground between constant and exponential backoff,
 * growing more gradually than exponential but faster than constant delays.
 *
 * @param R The return type of the suspend operation
 * @param retries The maximum number of retry attempts (defaults to 5)
 * @param base The base delay unit (defaults to 100 milliseconds)
 * @param block The suspend operation to execute
 *
 * @return The result of the successful execution
 * @throws Exception if all retry attempts fail
 *
 * Example usage:
 * ```
 * val data = retryWithFibonacci(retries = 8) {
 *     apiClient.fetchData()
 * }
 * ```
 */
suspend fun <R> retryWithFibonacci(
    retries: Long = 5,
    base: Duration = 100.milliseconds,
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

            val fibDelay = base * fib1.toInt()
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
 * @param factor The exponential factor (defaults to 2.0)
 * @param fallback The fallback value provider that receives the last exception
 * @param block The suspend operation to execute
 *
 * @return The result of successful execution or the fallback value
 *
 * Example usage:
 * ```
 * val user = retryOrDefault(retries = 3, fallback = { User.anonymous() }) {
 *     userService.fetchUser("user123")
 * }
 * ```
 */
suspend fun <R> retryOrDefault(
    retries: Long = 3,
    base: Duration = 200.milliseconds,
    factor: Double = 2.0,
    fallback: suspend (Throwable) -> R,
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
            logger.warn(error) { "All retry attempts failed, using fallback" }
            fallback(error)
        },
    )
}

/**
 * Executes a suspend operation with retry using a custom predicate to decide retries.
 *
 * @param R The return type of the suspend operation
 * @param retries The maximum number of retry attempts (defaults to 3)
 * @param delay The delay between retry attempts (defaults to 1 second)
 * @param shouldRetry Predicate that receives the exception and returns true to retry
 * @param block The suspend operation to execute
 *
 * @return The result of the successful execution
 * @throws Exception if shouldRetry returns false or all retries are exhausted
 *
 * Example usage:
 * ```
 * val response = retryIf(
 *     retries = 5,
 *     shouldRetry = { it is TimeoutException || it is IOException }
 * ) {
 *     httpClient.get("/api/data")
 * }
 * ```
 */
suspend fun <R> retryIf(
    retries: Long = 3,
    delay: Duration = 1.seconds,
    shouldRetry: (Throwable) -> Boolean,
    block: suspend () -> R,
): R {
    require(retries >= 0) { "retries must be >= 0, but was $retries" }
    require(delay >= Duration.ZERO) { "delay must be >= 0, but was $delay" }

    var attempt = 0L
    var lastException: Throwable? = null

    while (attempt <= retries) {
        try {
            return block()
        } catch (exception: Exception) {
            lastException = exception

            if (attempt >= retries || !shouldRetry(exception)) {
                logger.warn(exception) {
                    "Retry condition not met or attempts exhausted (attempt ${attempt + 1}/${retries + 1})"
                }
                throw exception
            }

            logger.debug {
                "Retrying after ${exception::class.simpleName} (attempt ${attempt + 1}/${retries + 1})"
            }

            delay(applyJitter(delay))
            attempt++
        }
    }

    throw lastException ?: IllegalStateException("Retry failed without exception")
}

/**
 * Executes a suspend operation with retry and collects detailed history of all attempts.
 *
 * @param R The return type of the suspend operation
 * @param retries The maximum number of retry attempts (defaults to 3)
 * @param base The base duration for exponential backoff (defaults to 200 milliseconds)
 * @param factor The exponential factor (defaults to 2.0)
 * @param block The suspend operation to execute
 *
 * @return A [RetryResult] containing the final result and detailed attempt history
 *
 * Example usage:
 * ```
 * val result = retryWithHistory(retries = 5) {
 *     apiClient.fetchData()
 * }
 * println("Took ${result.attemptCount} attempts")
 * println("Total duration: ${result.totalDuration}")
 * ```
 */
suspend fun <R> retryWithHistory(
    retries: Long = 3,
    base: Duration = 200.milliseconds,
    factor: Double = 2.0,
    block: suspend () -> R,
): RetryResult<R> {
    require(retries >= 0) { "retries must be >= 0, but was $retries" }
    require(base >= Duration.ZERO) { "base delay must be >= 0, but was $base" }
    require(factor > 0) { "factor must be > 0, but was $factor" }

    val attempts = mutableListOf<AttemptResult<R>>()
    val startTime = Clock.System.now()
    var attempt = 0L
    var currentDelay = base

    while (attempt <= retries) {
        val attemptStart = Clock.System.now()
        val attemptResult = try {
            val result = block()
            Result.success(result)
        } catch (exception: Exception) {
            Result.failure(exception)
        }
        val attemptEnd = Clock.System.now()
        val attemptDuration = attemptEnd - attemptStart

        attempts.add(
            AttemptResult(
                attemptNumber = (attempt + 1).toInt(),
                result = attemptResult,
                duration = attemptDuration,
            )
        )

        if (attemptResult.isSuccess) {
            val totalDuration = Clock.System.now() - startTime
            return RetryResult(
                finalResult = attemptResult,
                attempts = attempts,
                totalDuration = totalDuration,
            )
        }

        if (attempt >= retries) {
            val totalDuration = Clock.System.now() - startTime
            return RetryResult(
                finalResult = attemptResult,
                attempts = attempts,
                totalDuration = totalDuration,
            )
        }

        logger.debug { "Attempt ${attempt + 1} failed, retrying after $currentDelay" }
        delay(applyJitter(currentDelay))
        currentDelay = currentDelay * factor
        attempt++
    }

    throw IllegalStateException("Retry loop terminated unexpectedly")
}

// ============================================================================
// REPEAT OPERATIONS
// ============================================================================

/**
 * Repeats a successful operation with exponential backoff delay between repetitions.
 *
 * This is useful for operations that need to be executed periodically with increasing
 * intervals, such as periodic polling or gradual backoff scenarios.
 *
 * @param R The return type of the suspend operation
 * @param times The number of times to repeat after the initial execution (defaults to 3)
 * @param base The base duration for exponential backoff (defaults to 1 second)
 * @param factor The exponential factor (defaults to 2.0)
 * @param block The suspend operation to repeat
 *
 * @return The result of the last successful execution
 * @throws Exception if any execution fails
 *
 * Example usage:
 * ```
 * val lastPoll = repeatWithExponentialBackoff(times = 5) {
 *     pollService.check()
 * }
 * ```
 */
suspend fun <R> repeatWithExponentialBackoff(
    times: Long = 3,
    base: Duration = 1.seconds,
    factor: Double = 2.0,
    block: suspend () -> R,
): R {
    require(times >= 0) { "times must be >= 0, but was $times" }
    require(base >= Duration.ZERO) { "base delay must be >= 0, but was $base" }
    require(factor > 0) { "factor must be > 0, but was $factor" }

    var result = block()
    var currentDelay = base

    repeat(times.toInt()) {
        delay(applyJitter(currentDelay))
        result = block()
        currentDelay = currentDelay * factor
    }

    return result
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

    var result = block()

    repeat(times.toInt()) {
        delay(applyJitter(delay))
        result = block()
    }

    return result
}

/**
 * Repeats an operation until a condition is met or max attempts are reached.
 *
 * @param R The return type of the suspend operation
 * @param maxAttempts The maximum number of attempts (defaults to 10)
 * @param delay The delay between attempts (defaults to 500 milliseconds)
 * @param condition Predicate to test the result; returns true to stop repeating
 * @param block The suspend operation to repeat
 *
 * @return The result that satisfied the condition
 * @throws IllegalStateException if max attempts reached without satisfying condition
 *
 * Example usage:
 * ```
 * val ready = repeatUntil(maxAttempts = 20, delay = 1.seconds, condition = { it.isReady }) {
 *     serviceStatus.check()
 * }
 * ```
 */
suspend fun <R> repeatUntil(
    maxAttempts: Long = 10,
    delay: Duration = 500.milliseconds,
    condition: (R) -> Boolean,
    block: suspend () -> R,
): R {
    require(maxAttempts > 0) { "maxAttempts must be > 0, but was $maxAttempts" }
    require(delay >= Duration.ZERO) { "delay must be >= 0, but was $delay" }

    repeat(maxAttempts.toInt()) { attempt ->
        val result = block()
        if (condition(result)) {
            logger.debug { "Condition met after ${attempt + 1} attempts" }
            return result
        }

        if (attempt < maxAttempts - 1) {
            delay(applyJitter(delay))
        }
    }

    throw IllegalStateException("Condition not met after $maxAttempts attempts")
}

/**
 * Repeats an operation and invokes an error handler if any execution fails.
 *
 * @param R The return type of the suspend operation
 * @param times The number of times to repeat after initial execution (defaults to 3)
 * @param delay The delay between repetitions (defaults to 500 milliseconds)
 * @param onError Error handler that receives the exception and attempt number
 * @param block The suspend operation to repeat
 *
 * @return The result of the last successful execution
 *
 * Example usage:
 * ```
 * val result = repeatOrElse(
 *     times = 5,
 *     onError = { error, attempt -> logError(error, attempt) }
 * ) {
 *     dataService.process()
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

    var result = block()
    var attempt = 0L

    repeat(times.toInt()) {
        attempt++
        try {
            delay(applyJitter(delay))
            result = block()
        } catch (error: Exception) {
            logger.warn(error) { "Repeat operation failed, invoking error handler" }
            return onError(error, attempt)
        }
    }

    return result
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
    val totalDuration: Duration,
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
    val duration: Duration,
) {
    val isSuccess: Boolean get() = result.isSuccess
    val isFailure: Boolean get() = result.isFailure

    fun getOrNull(): R? = result.getOrNull()
    fun exceptionOrNull(): Throwable? = result.exceptionOrNull()
}