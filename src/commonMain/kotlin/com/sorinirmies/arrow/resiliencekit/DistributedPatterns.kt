// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package com.sorinirmies.arrow.resiliencekit

import kotlin.time.Clock
import kotlin.time.Duration

/**
 * Applies [transform] to the value stored at [key] using a compare-and-set
 * loop. [transform] returns `null` to leave the value untouched, otherwise
 * `Pair(newValue, result)`. Returns the produced result.
 */
private suspend fun <R> SharedStateStore.casLoop(
    key: String,
    transform: (current: String?) -> Pair<String, R>?,
    unchanged: (current: String?) -> R,
): R {
    while (true) {
        val current = get(key)
        val next = transform(current) ?: return unchanged(current)
        if (compareAndSet(key, current, next.first)) return next.second
    }
}

/**
 * Cross-instance fixed-window rate limiter whose counter lives in a
 * [SharedStateStore] (e.g. a Redis-backed one), so every process sharing the
 * store shares one budget of [maxRequests] per [window].
 *
 * ```kotlin
 * val limiter = DistributedRateLimiter(redisStore, "api", maxRequests = 100, window = 1.minutes)
 * limiter.execute { callApi() }   // throws RateLimitExceededException when over budget
 * ```
 */
public class DistributedRateLimiter(
    private val store: SharedStateStore,
    private val key: String,
    private val maxRequests: Int,
    private val window: Duration,
    private val clock: Clock = Clock.System,
) {
    init {
        require(maxRequests > 0) { "maxRequests must be > 0, but was $maxRequests" }
        require(window > Duration.ZERO) { "window must be > 0, but was $window" }
    }

    /** Tries to take one permit. Returns `true` if granted. */
    public suspend fun tryAcquire(): Boolean {
        val now = clock.now().toEpochMilliseconds()
        return store.casLoop(
            key = "rl:$key",
            transform = { current ->
                val parts = current?.split(':')
                val start = parts?.getOrNull(0)?.toLongOrNull()
                val count = parts?.getOrNull(1)?.toIntOrNull()
                when {
                    start == null || count == null || now - start >= window.inWholeMilliseconds ->
                        "$now:1" to true
                    count >= maxRequests -> null
                    else -> "$start:${count + 1}" to true
                }
            },
            unchanged = { false },
        )
    }

    /**
     * Runs [block] if a permit is available.
     * @throws RateLimitExceededException when the shared budget is exhausted
     */
    public suspend fun <T> execute(block: suspend () -> T): T {
        if (!tryAcquire()) throw RateLimitExceededException("Distributed rate limit exceeded for '$key'")
        return block()
    }

    /** Like [execute] but returns `null` when over budget. */
    public suspend fun <T> tryExecute(block: suspend () -> T): T? =
        if (tryAcquire()) block() else null

    /** Adapts this limiter into a [Policy]. */
    public fun asPolicy(): Policy {
        val limiter = this
        return object : Policy {
            override suspend fun <T> apply(block: suspend () -> T): T = limiter.execute(block)
        }
    }
}

/**
 * Cross-instance circuit breaker whose state lives in a [SharedStateStore]:
 * when one instance trips the breaker, every instance sharing the store
 * fails fast. Semantics follow [CircuitBreaker] (Closed → Open → HalfOpen),
 * with [CircuitBreakerConfig.halfOpenMaxCalls] not enforced across processes.
 *
 * ```kotlin
 * val breaker = DistributedCircuitBreaker(redisStore, "payments", CircuitBreakerConfig())
 * breaker.execute { callPayments() }
 * ```
 */
public class DistributedCircuitBreaker(
    private val store: SharedStateStore,
    private val key: String,
    private val config: CircuitBreakerConfig = CircuitBreakerConfig(),
    private val clock: Clock = Clock.System,
) {
    /** Immutable state snapshot encoded as `STATE:failures:openedAt:halfOpenSuccesses`. */
    private data class Snapshot(
        val state: CircuitBreakerState,
        val failures: Int = 0,
        val openedAt: Long = 0,
        val successes: Int = 0,
    ) {
        fun encode(): String = "${state.name}:$failures:$openedAt:$successes"

        companion object {
            fun decode(raw: String?): Snapshot {
                val p = raw?.split(':') ?: return Snapshot(CircuitBreakerState.Closed)
                val state = CircuitBreakerState.entries.firstOrNull { it.name == p.getOrNull(0) }
                    ?: return Snapshot(CircuitBreakerState.Closed)
                return Snapshot(
                    state,
                    p.getOrNull(1)?.toIntOrNull() ?: 0,
                    p.getOrNull(2)?.toLongOrNull() ?: 0,
                    p.getOrNull(3)?.toIntOrNull() ?: 0,
                )
            }
        }
    }

    private val storeKey = "cb:$key"

    private suspend fun update(transform: (Snapshot) -> Snapshot?) {
        store.casLoop(
            key = storeKey,
            transform = { raw ->
                val before = Snapshot.decode(raw)
                transform(before)?.takeIf { it != before }?.let { it.encode() to Unit }
            },
            unchanged = {},
        )
    }

    /** Returns the current shared state, applying the Open → HalfOpen timeout transition. */
    public suspend fun currentState(): CircuitBreakerState {
        val snap = Snapshot.decode(store.get(storeKey))
        return if (snap.state == CircuitBreakerState.Open && resetElapsed(snap)) {
            CircuitBreakerState.HalfOpen
        } else {
            snap.state
        }
    }

    private fun resetElapsed(snap: Snapshot): Boolean =
        clock.now().toEpochMilliseconds() - snap.openedAt >= config.resetTimeout.inWholeMilliseconds

    /**
     * Runs [block] through the shared breaker.
     * @throws CircuitBreakerOpenException while the breaker is open
     */
    public suspend fun <T> execute(block: suspend () -> T): T {
        update { s ->
            if (s.state == CircuitBreakerState.Open && resetElapsed(s)) {
                Snapshot(CircuitBreakerState.HalfOpen)
            } else {
                null
            }
        }
        if (Snapshot.decode(store.get(storeKey)).state == CircuitBreakerState.Open) {
            throw CircuitBreakerOpenException("Distributed circuit breaker '$key' is open")
        }
        val result = try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            recordFailure()
            throw e
        }
        recordSuccess()
        return result
    }

    private suspend fun recordSuccess() {
        update { s ->
            when (s.state) {
                CircuitBreakerState.Closed -> if (s.failures > 0) Snapshot(CircuitBreakerState.Closed) else null
                CircuitBreakerState.HalfOpen ->
                    if (s.successes + 1 >= config.halfOpenSuccessThreshold) {
                        Snapshot(CircuitBreakerState.Closed)
                    } else {
                        s.copy(successes = s.successes + 1)
                    }
                CircuitBreakerState.Open -> null
            }
        }
    }

    private suspend fun recordFailure() {
        val now = clock.now().toEpochMilliseconds()
        update { s ->
            when (s.state) {
                CircuitBreakerState.Closed ->
                    if (s.failures + 1 >= config.failureThreshold) {
                        Snapshot(CircuitBreakerState.Open, openedAt = now)
                    } else {
                        s.copy(failures = s.failures + 1)
                    }
                CircuitBreakerState.HalfOpen -> Snapshot(CircuitBreakerState.Open, openedAt = now)
                CircuitBreakerState.Open -> null
            }
        }
    }

    /** Adapts this breaker into a [Policy]. */
    public fun asPolicy(): Policy {
        val breaker = this
        return object : Policy {
            override suspend fun <T> apply(block: suspend () -> T): T = breaker.execute(block)
        }
    }
}
