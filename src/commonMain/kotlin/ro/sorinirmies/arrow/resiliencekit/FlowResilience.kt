// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

/**
 * `Flow`-native resilience operators.
 *
 * The existing patterns protect a single suspend call; these operators apply
 * the same protection around an entire [Flow] collection.
 *
 * ```kotlin
 * flow { emit(api.stream()) }
 *     .throughCircuitBreaker(breaker)
 *     .throughBulkhead(bulkhead)
 *     .retryWithBackoff(retries = 3)
 *     .collect { println(it) }
 * ```
 */
package ro.sorinirmies.arrow.resiliencekit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Retries collection of this [Flow] using exponential backoff with jitter,
 * mirroring [retryWithExponentialBackoff] but for a whole flow instead of a
 * single suspend call.
 *
 * @param retries maximum number of retry attempts after the initial collection
 * @param base base delay for exponential backoff
 * @param factor exponential growth factor
 * @param jitterFactor randomization factor applied to each computed delay (0.0 disables jitter)
 * @param retryIf predicate deciding whether a given exception should trigger a retry
 */
public fun <T> Flow<T>.retryWithBackoff(
    retries: Long = 3,
    base: Duration = 200.milliseconds,
    factor: Double = 2.0,
    jitterFactor: Double = 0.1,
    retryIf: (Throwable) -> Boolean = { it !is CancellationException },
): Flow<T> {
    require(retries >= 0) { "retries must be >= 0, but was $retries" }
    require(base >= Duration.ZERO) { "base delay must be >= 0, but was $base" }
    require(factor > 0) { "factor must be > 0, but was $factor" }

    return retryWhen { cause, attempt ->
        if (attempt >= retries || !retryIf(cause)) {
            false
        } else {
            val raw = base * factor.pow(attempt.toInt())
            val jitter = if (jitterFactor > 0.0) {
                1.0 + ((Random.nextDouble() * 2 * jitterFactor) - jitterFactor)
            } else {
                1.0
            }
            delay(raw * jitter)
            true
        }
    }
}

/**
 * Protects collection of this [Flow] with a [CircuitBreaker]: if the breaker
 * is open, collecting fails fast with [CircuitBreakerOpenException] instead
 * of starting the upstream flow.
 */
public fun <T> Flow<T>.throughCircuitBreaker(circuitBreaker: CircuitBreaker): Flow<T> {
    val upstream = this
    return flow { circuitBreaker.execute { upstream.collect { value -> emit(value) } } }
}

/**
 * Protects collection of this [Flow] with a [Bulkhead]: the whole collection
 * counts as a single call against the bulkhead's concurrency limit.
 */
public fun <T> Flow<T>.throughBulkhead(bulkhead: Bulkhead): Flow<T> {
    val upstream = this
    return flow { bulkhead.execute { upstream.collect { value -> emit(value) } } }
}

/**
 * Protects collection of this [Flow] with a [RateLimiter]: acquiring [permits]
 * tokens gates when collection of the upstream flow is allowed to start.
 */
public fun <T> Flow<T>.throughRateLimiter(rateLimiter: RateLimiter, permits: Int = 1): Flow<T> {
    val upstream = this
    return flow { rateLimiter.execute(permits = permits) { upstream.collect { value -> emit(value) } } }
}

/**
 * Bounds collection of this [Flow] with a [TimeLimiter]: if the whole
 * collection doesn't complete within the timeout, it is cancelled.
 */
public fun <T> Flow<T>.throughTimeLimiter(timeLimiter: TimeLimiter, timeout: Duration? = null): Flow<T> {
    val upstream = this
    return flow { timeLimiter.execute(timeout = timeout) { upstream.collect { value -> emit(value) } } }
}

/**
 * Applies an arbitrary [Policy] around collection of this [Flow].
 */
public fun <T> Flow<T>.throughPolicy(policy: Policy): Flow<T> {
    val upstream = this
    return flow { policy.apply { upstream.collect { value -> emit(value) } } }
}
