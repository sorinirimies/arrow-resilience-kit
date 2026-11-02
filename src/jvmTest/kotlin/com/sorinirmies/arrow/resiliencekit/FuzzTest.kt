// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package com.sorinirmies.arrow.resiliencekit

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

/**
 * Seeded, randomised robustness tests (the Kotlin counterpart of cargo-fuzz / the TS fuzz suite).
 *
 * Each property drives a component with random operations and checks it against a trivial model
 * or an invariant. A failure prints the seed that reproduces it.
 *
 *     FUZZ_ITERS=200000 FUZZ_SEED=42 ./gradlew jvmTest --tests '*FuzzTest'   # or: just fuzz 200000 42
 *
 * Without env vars it runs a short smoke pass so the regular `jvmTest` stays fast.
 */
class FuzzTest {
    private val iterations = System.getenv("FUZZ_ITERS")?.toIntOrNull() ?: DEFAULT_ITERS
    private val seed = System.getenv("FUZZ_SEED")?.toLongOrNull() ?: Random.nextLong()

    private inline fun <T> reproducible(property: String, block: () -> T): T =
        try {
            block()
        } catch (e: Throwable) {
            fail("$property failed — reproduce with FUZZ_SEED=$seed FUZZ_ITERS=$iterations: ${e.message}", e)
        }

    @Test
    fun `retry budget stays within bounds and matches a model`() = runBlocking {
        reproducible("RetryBudget") {
            val rnd = Random(seed)
            val max = 1.0 + rnd.nextInt(20)
            val ratio = (1 + rnd.nextInt(10)) / 10.0
            val budget = RetryBudget.create(RetryBudgetConfig(max, ratio))
            var model = max
            run {
                repeat(iterations) {
                    if (rnd.nextBoolean()) {
                        budget.recordSuccess()
                        model = minOf(max, model + ratio)
                    } else {
                        val expected = model >= 1.0
                        if (expected) model -= 1.0
                        check(budget.tryWithdraw() == expected) { "tryWithdraw diverged from model" }
                    }
                    val tokens = budget.availableTokens()
                    check(tokens in 0.0..max) { "tokens $tokens outside 0..$max" }
                    check(kotlin.math.abs(tokens - model) < 1e-9) { "tokens $tokens != model $model" }
                }
            }
        }
    }

    @Test
    fun `cache never exceeds maxSize and never returns a stale value`() = runBlocking {
        reproducible("Cache") {
            val rnd = Random(seed)
            val maxSize = 1 + rnd.nextInt(16)
            val strategies = EvictionStrategy.entries
            val cache = Cache.create<Int, Int>(CacheConfig(maxSize, null, strategies[rnd.nextInt(strategies.size)]))
            val model = HashMap<Int, Int>()
            run {
                repeat(iterations) { i ->
                    val key = rnd.nextInt(maxSize * 3)
                    when (rnd.nextInt(4)) {
                        0, 1 -> { cache.put(key, i); model[key] = i }
                        2 -> {
                            val got = cache.get(key)
                            // Eviction may drop a key, but a hit must be the latest value written.
                            if (got != null) check(got == model[key]) { "stale value for $key: $got vs ${model[key]}" }
                        }
                        else -> { cache.remove(key); model.remove(key) }
                    }
                    check(cache.size() <= maxSize) { "size ${cache.size()} > maxSize $maxSize" }
                }
            }
        }
    }

    @Test
    fun `circuit breaker opens at the threshold and rejects without running the block`() = runBlocking {
        reproducible("CircuitBreaker") {
            val rnd = Random(seed)
            run {
                repeat(maxOf(1, iterations / 20)) {
                    val threshold = 1 + rnd.nextInt(8)
                    val timeout = (1 + rnd.nextInt(5000)).milliseconds
                    val clock = TestClock()
                    val breaker = CircuitBreaker.create(CircuitBreakerConfig(threshold, timeout), clock)

                    repeat(threshold) {
                        runCatching { breaker.execute { throw IllegalStateException("boom") } }
                    }
                    breaker.currentState() shouldBe CircuitBreakerState.Open

                    var ran = false
                    clock.advance(timeout / 2)
                    val rejected = runCatching { breaker.execute { ran = true } }.exceptionOrNull()
                    check(rejected is CircuitBreakerOpenException) { "expected rejection, got $rejected" }
                    check(!ran) { "block ran while the circuit was open" }

                    clock.advance(timeout)
                    breaker.execute { ran = true }
                    check(ran) { "block did not run after resetTimeout" }
                    check(breaker.currentState() != CircuitBreakerState.Open) { "still open after a success" }
                }
            }
        }
    }

    @Test
    fun `circuit breaker survives random call sequences with a valid state`() = runBlocking {
        reproducible("CircuitBreaker sequence") {
            val rnd = Random(seed)
            run {
                val clock = TestClock()
                val config = CircuitBreakerConfig(
                    failureThreshold = 1 + rnd.nextInt(6),
                    resetTimeout = (1 + rnd.nextInt(1000)).milliseconds,
                    halfOpenSuccessThreshold = 1 + rnd.nextInt(3),
                    halfOpenMaxCalls = 1 + rnd.nextInt(4),
                )
                val breaker = CircuitBreaker.create(config, clock)
                repeat(iterations) {
                    when (rnd.nextInt(5)) {
                        0 -> clock.advance(rnd.nextInt(2000).milliseconds)
                        1 -> breaker.reset()
                        2 -> breaker.trip()
                        3 -> runCatching { breaker.execute { throw IllegalStateException("boom") } }
                            .onSuccess { fail("failing block reported success") }
                        else -> {
                            val state = breaker.currentState()
                            val result = runCatching { breaker.execute { 7 } }
                            // A rejected call must come from an open circuit; anything else must pass the value through.
                            result.exceptionOrNull()?.let { check(it is CircuitBreakerOpenException) { "unexpected $it in $state" } }
                                ?: check(result.getOrNull() == 7)
                        }
                    }
                    check(breaker.failures() >= 0 && breaker.successes() >= 0) { "negative counters" }
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_ITERS = 2_000
    }
}
