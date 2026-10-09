// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

/**
 * A resilience-pattern combinator: wraps [Bulkhead], [CircuitBreaker], [RateLimiter],
 * [TimeLimiter], retry, and any other `suspend () -> T` decorator into a single,
 * reusable, order-explicit chain.
 *
 * Each existing pattern already exposes `suspend fun <T> execute(block: suspend () -> T): T`.
 * [Policy] is the same shape, so every pattern converts to a [Policy] via
 * `asPolicy()`, and policies compose with [plus]:
 *
 * ```kotlin
 * val policy = retryPolicy(retries = 3) + circuitBreaker.asPolicy() + bulkhead.asPolicy()
 * val result = policy.apply { api.fetchData() }
 * ```
 *
 * **Composition order:** `a + b` means *a wraps b* — the leftmost policy is
 * outermost. `retryPolicy() + circuitBreaker.asPolicy() + bulkhead.asPolicy()`
 * executes as `retry { circuitBreaker.execute { bulkhead.execute { block() } } }`,
 * which mirrors the common recommendation: retry outermost (it re-attempts the
 * *whole* protected call), circuit breaker in the middle (fails fast without
 * touching the bulkhead once open), bulkhead innermost (bounds concurrency on
 * the actual resource).
 */
package com.sorinirmies.arrow.resiliencekit

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A composable resilience decorator: applies some cross-cutting behavior
 * (retry, circuit breaking, rate limiting, ...) around a suspend operation.
 *
 * Any of [Bulkhead], [CircuitBreaker], [RateLimiter], [TimeLimiter] can be
 * converted to a [Policy] via their `asPolicy()` extension, and policies
 * compose with [plus].
 *
 * Implemented as a plain interface (not a `fun interface`): [apply] is
 * generic per call, which a stored lambda cannot represent, so instances are
 * created via the factory functions in this file rather than SAM conversion.
 */
public interface Policy {
    /** Runs [block] through this policy's protective behavior. */
    public suspend fun <T> apply(block: suspend () -> T): T

    /** Factory helpers for combining multiple [Policy] instances. */
    public companion object {
        /** A no-op policy that just invokes the block directly. Useful as a fold seed. */
        public val identity: Policy = object : Policy {
            override suspend fun <T> apply(block: suspend () -> T): T = block()
        }

        /** Composes [policies] left-to-right; see [Policy] composition-order docs. */
        public fun combine(vararg policies: Policy): Policy =
            policies.fold(identity) { acc, policy -> acc + policy }
    }
}

/**
 * Composes two policies so that [this] wraps [other]: `(a + b).apply(block)`
 * runs as `a.apply { b.apply(block) }`.
 */
public operator fun Policy.plus(other: Policy): Policy {
    val outer = this
    return object : Policy {
        override suspend fun <T> apply(block: suspend () -> T): T =
            outer.apply { other.apply(block) }
    }
}

/** Adapts this [Bulkhead] into a [Policy]. */
public fun Bulkhead.asPolicy(): Policy {
    val bulkhead = this
    return object : Policy {
        override suspend fun <T> apply(block: suspend () -> T): T = bulkhead.execute(block)
    }
}

/** Adapts this [CircuitBreaker] into a [Policy]. */
public fun CircuitBreaker.asPolicy(): Policy {
    val circuitBreaker = this
    return object : Policy {
        override suspend fun <T> apply(block: suspend () -> T): T = circuitBreaker.execute(block)
    }
}

/** Adapts this [SlidingWindowCircuitBreaker] into a [Policy]. */
public fun SlidingWindowCircuitBreaker.asPolicy(): Policy {
    val circuitBreaker = this
    return object : Policy {
        override suspend fun <T> apply(block: suspend () -> T): T = circuitBreaker.execute(block)
    }
}

/** Adapts this [RateLimiter] into a [Policy]. */
public fun RateLimiter.asPolicy(): Policy {
    val rateLimiter = this
    return object : Policy {
        override suspend fun <T> apply(block: suspend () -> T): T = rateLimiter.execute(block = block)
    }
}

/** Adapts this [SlidingWindowRateLimiter] into a [Policy]. */
public fun SlidingWindowRateLimiter.asPolicy(): Policy {
    val rateLimiter = this
    return object : Policy {
        override suspend fun <T> apply(block: suspend () -> T): T = rateLimiter.execute(block)
    }
}

/** Adapts this [TimeLimiter] into a [Policy]. */
public fun TimeLimiter.asPolicy(): Policy {
    val timeLimiter = this
    return object : Policy {
        override suspend fun <T> apply(block: suspend () -> T): T = timeLimiter.execute(block = block)
    }
}

/** Adapts this [AdaptiveLimiter] into a [Policy]. */
public fun AdaptiveLimiter.asPolicy(): Policy {
    val adaptiveLimiter = this
    return object : Policy {
        override suspend fun <T> apply(block: suspend () -> T): T = adaptiveLimiter.execute(block)
    }
}

/**
 * A [Policy] that runs the hedged-request pattern (see [hedge]): [block] may be started more than
 * once, concurrently, after [hedgeDelay] (and again every [hedgeDelay] up to [maxHedges] times) if
 * the earlier attempt(s) haven't completed yet, resolving to whichever attempt succeeds first.
 * [block] must be safe to run multiple times concurrently (e.g. an idempotent GET).
 */
public fun hedgePolicy(
    hedgeDelay: Duration = 100.milliseconds,
    maxHedges: Int = 1,
): Policy = object : Policy {
    override suspend fun <T> apply(block: suspend () -> T): T = hedge(hedgeDelay, maxHedges, block)
}

/**
 * A [Policy] that retries with exponential backoff and jitter, backed by
 * [retryWithExponentialBackoff].
 */
public fun retryPolicy(
    retries: Long = 3,
    base: Duration = 200.milliseconds,
    factor: Double = 2.0,
): Policy = object : Policy {
    override suspend fun <T> apply(block: suspend () -> T): T =
        retryWithExponentialBackoff(retries, base, factor, block)
}

/**
 * A [Policy] that retries with exponential backoff and jitter like [retryPolicy], but only for
 * exceptions matching [shouldRetry], backed by [retryIfWithExponentialBackoff]. Prefer this over
 * [retryPolicy] whenever some failures are permanent (e.g. a 4xx response, a validation error)
 * and shouldn't be retried at all.
 */
public fun retryIfPolicy(
    retries: Long = 3,
    base: Duration = 200.milliseconds,
    factor: Double = 2.0,
    shouldRetry: (Throwable) -> Boolean,
): Policy = object : Policy {
    override suspend fun <T> apply(block: suspend () -> T): T =
        retryIfWithExponentialBackoff(retries, base, factor, shouldRetry, block)
}
