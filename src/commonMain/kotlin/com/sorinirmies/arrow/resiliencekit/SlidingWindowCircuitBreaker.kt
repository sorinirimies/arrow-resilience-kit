// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package com.sorinirmies.arrow.resiliencekit

import arrow.fx.stm.TVar
import arrow.fx.stm.atomically
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Sliding-window circuit breaker: trips on a *failure rate* over the last N calls, rather than
 * [CircuitBreaker]'s consecutive-failure count.
 *
 * Consecutive-failure tripping is sensitive to call ordering and blind to low-but-nonzero,
 * steady-state error rates: with [CircuitBreaker]'s default `failureThreshold = 5`, a service
 * failing 1 call in 3 (a serious 33% error rate) never trips at all as long as it never strings
 * together 5 failures *in a row*. A sliding window instead asks "what fraction of the last N
 * calls failed?", which is what you usually actually want to protect against -- the same
 * approach resilience4j's `CircuitBreaker` uses by default.
 *
 * Otherwise behaves like [CircuitBreaker]: **Closed** (normal) -> **Open** (failing fast) ->
 * **Half-Open** (testing recovery) -> back to Closed or Open, with the same reset-timeout and
 * half-open-success-threshold semantics.
 *
 * **Basic usage:**
 * ```kotlin
 * val breaker = SlidingWindowCircuitBreaker.create(config = SlidingWindowCircuitBreakerConfig(
 *     slidingWindowSize = 20,
 *     minimumNumberOfCalls = 10, // don't trip on the first few calls before the window fills
 *     failureRateThreshold = 0.5, // trip once >= 50% of the last 20 calls failed
 * ))
 *
 * val result = breaker.execute {
 *     callExternalService()
 * }
 * ```
 *
 * **Inspecting the current failure rate:**
 * ```kotlin
 * val stats = breaker.statistics()
 * println("${stats.callsInWindow} calls in window, ${stats.failureRate * 100}% failing")
 * ```
 */
public class SlidingWindowCircuitBreaker private constructor(
    private val config: SlidingWindowCircuitBreakerConfig,
    private val clock: Clock,
    private val state: TVar<CircuitBreakerState>,
    private val outcomes: TVar<List<Boolean>>,
    private val halfOpenSuccesses: TVar<Int>,
    private val lastTransitionTime: TVar<Instant?>,
) {
    private val listeners = mutableListOf<CircuitBreakerListener>()

    /** Factory for [SlidingWindowCircuitBreaker]. */
    public companion object {
        /** Creates a new [SlidingWindowCircuitBreaker] instance. */
        public suspend fun create(
            config: SlidingWindowCircuitBreakerConfig = SlidingWindowCircuitBreakerConfig(),
            clock: Clock = Clock.System,
        ): SlidingWindowCircuitBreaker = SlidingWindowCircuitBreaker(
            config = config,
            clock = clock,
            state = TVar.new(CircuitBreakerState.Closed),
            outcomes = TVar.new(emptyList()),
            halfOpenSuccesses = TVar.new(0),
            lastTransitionTime = TVar.new(null),
        )
    }

    /** Adds a listener for circuit breaker state changes. */
    public fun addListener(listener: CircuitBreakerListener) {
        listeners.add(listener)
    }

    /** Gets the current state of the circuit breaker. */
    public suspend fun currentState(): CircuitBreakerState = atomically { state.read() }

    /** Returns a snapshot of the current window's statistics. */
    public suspend fun statistics(): SlidingWindowCircuitBreakerStatistics = atomically {
        val snapshot = outcomes.read()
        val failures = snapshot.count { !it }
        SlidingWindowCircuitBreakerStatistics(
            state = state.read(),
            callsInWindow = snapshot.size,
            failuresInWindow = failures,
            failureRate = if (snapshot.isNotEmpty()) failures.toDouble() / snapshot.size else 0.0,
        )
    }

    /**
     * Executes an operation through the circuit breaker.
     *
     * @param block The operation to execute
     * @return The result of the operation
     * @throws CircuitBreakerOpenException if the circuit is open
     * @throws Exception if the operation fails
     */
    public suspend fun <T> execute(block: suspend () -> T): T {
        checkAndUpdateState()

        return when (atomically { state.read() }) {
            CircuitBreakerState.Open -> {
                logger.debug { "Sliding-window circuit breaker is OPEN, rejecting call" }
                throw CircuitBreakerOpenException("Circuit breaker is OPEN")
            }
            CircuitBreakerState.HalfOpen -> executeInHalfOpenState(block)
            CircuitBreakerState.Closed -> executeInClosedState(block)
        }
    }

    /**
     * Executes an operation with a fallback if the circuit is open.
     *
     * @param fallback The fallback function to call if circuit is open
     * @param block The primary operation to execute
     * @return The result of the operation or fallback
     */
    public suspend fun <T> executeOrFallback(
        fallback: suspend (CircuitBreakerOpenException) -> T,
        block: suspend () -> T,
    ): T {
        return try {
            execute(block)
        } catch (e: CircuitBreakerOpenException) {
            logger.debug { "Sliding-window circuit breaker is OPEN, using fallback" }
            fallback(e)
        }
    }

    /** Manually resets the circuit breaker to closed state and clears its window. */
    public suspend fun reset() {
        val oldState = atomically {
            val old = state.read()
            outcomes.write(emptyList())
            halfOpenSuccesses.write(0)
            lastTransitionTime.write(null)
            state.write(CircuitBreakerState.Closed)
            old
        }
        if (oldState != CircuitBreakerState.Closed) {
            logger.info { "Manually resetting sliding-window circuit breaker from $oldState to Closed" }
            notifyListeners(oldState, CircuitBreakerState.Closed)
        }
    }

    private suspend fun <T> executeInClosedState(block: suspend () -> T): T {
        return try {
            val result = block()
            recordOutcome(success = true)
            result
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            recordOutcome(success = false)
            throw e
        }
    }

    private suspend fun <T> executeInHalfOpenState(block: suspend () -> T): T {
        return try {
            val result = block()
            onSuccessInHalfOpen()
            result
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            onFailureInHalfOpen(e)
            throw e
        }
    }

    private suspend fun recordOutcome(success: Boolean) {
        val shouldOpen = atomically {
            val updated = (outcomes.read() + success).takeLast(config.slidingWindowSize)
            outcomes.write(updated)

            if (updated.size >= config.minimumNumberOfCalls) {
                val failureRate = updated.count { !it }.toDouble() / updated.size
                failureRate >= config.failureRateThreshold
            } else {
                false
            }
        }

        if (!success) {
            logger.debug { "Failure recorded in CLOSED state" }
        }

        if (shouldOpen) {
            val oldState = atomically {
                if (state.read() == CircuitBreakerState.Closed) {
                    val old = state.read()
                    lastTransitionTime.write(clock.now())
                    state.write(CircuitBreakerState.Open)
                    old
                } else {
                    null
                }
            }
            if (oldState != null) {
                logger.error {
                    "Failure rate threshold reached, opening sliding-window circuit breaker from $oldState"
                }
                notifyListeners(oldState, CircuitBreakerState.Open)
            }
        }
    }

    private suspend fun checkAndUpdateState() {
        val (shouldTransition, lastTransition) = atomically {
            Pair(state.read() == CircuitBreakerState.Open && lastTransitionTime.read() != null, lastTransitionTime.read())
        }

        if (shouldTransition && lastTransition != null) {
            val timeSinceTransition = clock.now() - lastTransition
            if (timeSinceTransition >= config.resetTimeout) {
                val oldState = atomically {
                    if (state.read() == CircuitBreakerState.Open) {
                        val old = state.read()
                        state.write(CircuitBreakerState.HalfOpen)
                        halfOpenSuccesses.write(0)
                        old
                    } else {
                        null
                    }
                }
                if (oldState != null) {
                    logger.info { "Reset timeout expired, transitioning from $oldState to HALF_OPEN" }
                    notifyListeners(oldState, CircuitBreakerState.HalfOpen)
                }
            }
        }
    }

    private suspend fun onSuccessInHalfOpen() {
        val (newSuccesses, shouldClose) = atomically {
            val successes = halfOpenSuccesses.read() + 1
            halfOpenSuccesses.write(successes)
            Pair(successes, successes >= config.halfOpenSuccessThreshold)
        }

        logger.info { "Success in HALF_OPEN state, count: $newSuccesses/${config.halfOpenSuccessThreshold}" }

        if (shouldClose) {
            val oldState = atomically {
                if (state.read() == CircuitBreakerState.HalfOpen) {
                    val old = state.read()
                    outcomes.write(emptyList())
                    halfOpenSuccesses.write(0)
                    state.write(CircuitBreakerState.Closed)
                    old
                } else {
                    null
                }
            }
            if (oldState != null) {
                logger.info { "Success threshold reached, closing sliding-window circuit breaker from $oldState" }
                notifyListeners(oldState, CircuitBreakerState.Closed)
            }
        }
    }

    private suspend fun onFailureInHalfOpen(exception: Exception) {
        val oldState = atomically {
            if (state.read() == CircuitBreakerState.HalfOpen) {
                val old = state.read()
                halfOpenSuccesses.write(0)
                lastTransitionTime.write(clock.now())
                state.write(CircuitBreakerState.Open)
                old
            } else {
                null
            }
        }
        if (oldState != null) {
            logger.error(exception) { "Failure in HALF_OPEN state, re-opening sliding-window circuit breaker from $oldState" }
            notifyListeners(oldState, CircuitBreakerState.Open)
        }
    }

    private fun notifyListeners(oldState: CircuitBreakerState, newState: CircuitBreakerState) {
        listeners.toList().forEach {
            try {
                it.onStateChange(oldState, newState)
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * Configuration for [SlidingWindowCircuitBreaker].
 *
 * @property slidingWindowSize Number of most-recent call outcomes tracked (default: 100)
 * @property minimumNumberOfCalls Minimum outcomes recorded before the failure rate is evaluated at
 *   all -- avoids tripping on a handful of unlucky early calls before the window has enough
 *   samples to be meaningful (default: 10)
 * @property failureRateThreshold Fraction of calls in the window that must fail to trip the
 *   circuit open, from `0.0` to `1.0` (default: 0.5, i.e. 50%)
 * @property resetTimeout Duration to wait before transitioning from Open to Half-Open (default: 30 seconds)
 * @property halfOpenSuccessThreshold Number of successes needed in Half-Open state to close circuit (default: 2)
 */
public data class SlidingWindowCircuitBreakerConfig(
    public val slidingWindowSize: Int = 100,
    public val minimumNumberOfCalls: Int = 10,
    public val failureRateThreshold: Double = 0.5,
    public val resetTimeout: Duration = 30.seconds,
    public val halfOpenSuccessThreshold: Int = 2,
) {
    init {
        require(slidingWindowSize > 0) { "slidingWindowSize must be > 0, but was $slidingWindowSize" }
        require(minimumNumberOfCalls > 0) { "minimumNumberOfCalls must be > 0, but was $minimumNumberOfCalls" }
        require(minimumNumberOfCalls <= slidingWindowSize) {
            "minimumNumberOfCalls ($minimumNumberOfCalls) must be <= slidingWindowSize ($slidingWindowSize)"
        }
        require(failureRateThreshold > 0.0 && failureRateThreshold <= 1.0) {
            "failureRateThreshold must be in (0.0, 1.0], but was $failureRateThreshold"
        }
        require(resetTimeout > Duration.ZERO) { "resetTimeout must be > 0, but was $resetTimeout" }
        require(halfOpenSuccessThreshold > 0) { "halfOpenSuccessThreshold must be > 0, but was $halfOpenSuccessThreshold" }
    }
}

/**
 * Statistics for a [SlidingWindowCircuitBreaker].
 *
 * @property state Current state of the circuit breaker
 * @property callsInWindow Number of call outcomes currently tracked (up to `slidingWindowSize`)
 * @property failuresInWindow Number of those outcomes that were failures
 * @property failureRate [failuresInWindow] / [callsInWindow], or `0.0` if the window is empty
 */
@Serializable
public data class SlidingWindowCircuitBreakerStatistics(
    public val state: CircuitBreakerState,
    public val callsInWindow: Int,
    public val failuresInWindow: Int,
    public val failureRate: Double,
)

/**
 * Creates a sliding-window circuit breaker with DSL-style configuration.
 */
public suspend fun slidingWindowCircuitBreaker(
    configure: SlidingWindowCircuitBreakerConfigBuilder.() -> Unit,
): SlidingWindowCircuitBreaker {
    val builder = SlidingWindowCircuitBreakerConfigBuilder()
    builder.configure()
    return SlidingWindowCircuitBreaker.create(builder.build())
}

/**
 * Builder for [SlidingWindowCircuitBreakerConfig].
 */
public class SlidingWindowCircuitBreakerConfigBuilder {
    /** Number of most-recent call outcomes tracked. */
    public var slidingWindowSize: Int = 100

    /** Minimum outcomes recorded before the failure rate is evaluated at all. */
    public var minimumNumberOfCalls: Int = 10

    /** Fraction of calls in the window that must fail to trip the circuit open. */
    public var failureRateThreshold: Double = 0.5

    /** Duration to wait before transitioning from Open to Half-Open. */
    public var resetTimeout: Duration = 30.seconds

    /** Number of successes needed in Half-Open state to close the circuit. */
    public var halfOpenSuccessThreshold: Int = 2

    /** Builds the [SlidingWindowCircuitBreakerConfig] from the current builder state. */
    public fun build(): SlidingWindowCircuitBreakerConfig = SlidingWindowCircuitBreakerConfig(
        slidingWindowSize = slidingWindowSize,
        minimumNumberOfCalls = minimumNumberOfCalls,
        failureRateThreshold = failureRateThreshold,
        resetTimeout = resetTimeout,
        halfOpenSuccessThreshold = halfOpenSuccessThreshold,
    )
}

/**
 * Registry for managing multiple named sliding-window circuit breakers.
 */
public class SlidingWindowCircuitBreakerRegistry private constructor(
    private val breakers: TVar<Map<String, SlidingWindowCircuitBreaker>>,
) {
    /** Factory for [SlidingWindowCircuitBreakerRegistry]. */
    public companion object {
        /** Creates a new, empty [SlidingWindowCircuitBreakerRegistry]. */
        public suspend fun create(): SlidingWindowCircuitBreakerRegistry {
            val breakers = TVar.new(emptyMap<String, SlidingWindowCircuitBreaker>())
            return SlidingWindowCircuitBreakerRegistry(breakers)
        }
    }

    /** Gets an existing circuit breaker or creates a new one. */
    public suspend fun getOrCreate(
        name: String,
        configure: (SlidingWindowCircuitBreakerConfigBuilder.() -> Unit)? = null,
    ): SlidingWindowCircuitBreaker {
        val existing = atomically { breakers.read()[name] }
        if (existing != null) return existing

        val newBreaker = if (configure != null) {
            slidingWindowCircuitBreaker(configure)
        } else {
            SlidingWindowCircuitBreaker.create()
        }

        return atomically {
            val current = breakers.read()
            val existingInTx = current[name]
            if (existingInTx != null) {
                existingInTx
            } else {
                breakers.write(current + (name to newBreaker))
                newBreaker
            }
        }
    }

    /** Gets an existing circuit breaker by name. */
    public suspend fun get(name: String): SlidingWindowCircuitBreaker? = atomically { breakers.read()[name] }

    /** Removes a circuit breaker from the registry. */
    public suspend fun remove(name: String): SlidingWindowCircuitBreaker? = atomically {
        val current = breakers.read()
        val removed = current[name]
        if (removed != null) {
            breakers.write(current - name)
        }
        removed
    }

    /** Gets all circuit breaker names in the registry. */
    public suspend fun getNames(): Set<String> = atomically { breakers.read().keys }

    /** Gets statistics for all circuit breakers. */
    public suspend fun getAllStatistics(): Map<String, SlidingWindowCircuitBreakerStatistics> {
        val snapshot = atomically { breakers.read() }
        return snapshot.mapValues { (_, breaker) -> breaker.statistics() }
    }
}
