// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package ro.sorinirmies.arrow.resiliencekit

import arrow.fx.stm.TVar
import arrow.fx.stm.atomically
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import mu.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Circuit breaker pattern to prevent cascading failures.
 *
 * The circuit breaker prevents cascading failures by stopping calls to failing services.
 * It has three states: Closed (normal), Open (failing), and Half-Open (testing recovery).
 *
 * **States:**
 * - **Closed**: Normal operation, calls are allowed
 * - **Open**: Circuit is broken, calls fail immediately
 * - **Half-Open**: Testing if service has recovered
 *
 * **State transitions:**
 * ```
 * Closed -> Open: When failure threshold is exceeded
 * Open -> Half-Open: After reset timeout expires
 * Half-Open -> Closed: When success threshold is reached
 * Half-Open -> Open: When any failure occurs
 * ```
 *
 * **Basic usage:**
 * ```kotlin
 * val breaker = CircuitBreaker.create(config = CircuitBreakerConfig(
 *     failureThreshold = 5,
 *     resetTimeout = 30.seconds
 * ))
 *
 * val result = breaker.execute {
 *     callExternalService()
 * }
 * ```
 *
 * **With fallback:**
 * ```kotlin
 * val result = breaker.executeOrFallback(
 *     fallback = { cachedValue() }
 * ) {
 *     callExternalService()
 * }
 * ```
 *
 * **State monitoring:**
 * ```kotlin
 * when (breaker.currentState()) {
 *     CircuitBreakerState.Closed -> println("Service healthy")
 *     CircuitBreakerState.Open -> println("Service unavailable")
 *     CircuitBreakerState.HalfOpen -> println("Testing service...")
 * }
 * ```
 *
 * **DSL builder:**
 * ```kotlin
 * val breaker = circuitBreaker {
 *     failureThreshold = 5
 *     resetTimeout = 30.seconds
 *     halfOpenSuccessThreshold = 3
 * }
 * ```
 *
 * @param config Configuration for circuit breaker behavior
 */
public class CircuitBreaker private constructor(
    private val config: CircuitBreakerConfig,
    internal val clock: Clock,
    private val state: TVar<CircuitBreakerState>,
    private val failureCount: TVar<Int>,
    private val successCount: TVar<Int>,
    private val lastFailureTime: TVar<Instant?>,
) {
    private val listeners = mutableListOf<CircuitBreakerListener>()

    public companion object {
        /**
         * Creates a new [CircuitBreaker] instance.
         *
         * @param config Configuration for circuit breaker behavior
         * @param clock Clock used for timing (defaults to [Clock.System])
         * @return a new [CircuitBreaker]
         */
        public suspend fun create(
            config: CircuitBreakerConfig = CircuitBreakerConfig(),
            clock: Clock = Clock.System,
        ): CircuitBreaker = CircuitBreaker(
            config = config,
            clock = clock,
            state = TVar.new(CircuitBreakerState.Closed),
            failureCount = TVar.new(0),
            successCount = TVar.new(0),
            lastFailureTime = TVar.new(null),
        )
    }

    /**
     * Adds a listener for circuit breaker state changes.
     */
    public fun addListener(listener: CircuitBreakerListener) {
        listeners.add(listener)
    }

    /**
     * Gets the current state of the circuit breaker.
     */
    public suspend fun currentState(): CircuitBreakerState = atomically { state.read() }

    /**
     * Gets the current failure count.
     */
    public suspend fun failures(): Int = atomically { failureCount.read() }

    /**
     * Gets the current success count (in half-open state).
     */
    public suspend fun successes(): Int = atomically { successCount.read() }

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

        val currentState = atomically { state.read() }

        return when (currentState) {
            CircuitBreakerState.Open -> {
                logger.debug { "Circuit breaker is OPEN, rejecting call" }
                throw CircuitBreakerOpenException("Circuit breaker is OPEN")
            }

            CircuitBreakerState.HalfOpen -> {
                executeInHalfOpenState(block)
            }

            CircuitBreakerState.Closed -> {
                executeInClosedState(block)
            }
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
            logger.debug { "Circuit breaker is OPEN, using fallback" }
            fallback(e)
        }
    }

    /**
     * Manually resets the circuit breaker to closed state.
     */
    public suspend fun reset() {
        val oldState = atomically {
            val old = state.read()
            failureCount.write(0)
            successCount.write(0)
            lastFailureTime.write(null)
            state.write(CircuitBreakerState.Closed)
            old
        }
        if (oldState != CircuitBreakerState.Closed) {
            logger.info { "Manually resetting circuit breaker from $oldState to Closed" }
            notifyListeners(oldState, CircuitBreakerState.Closed)
        }
    }

    /**
     * Manually opens the circuit breaker.
     */
    public suspend fun trip() {
        val oldState = atomically {
            val old = state.read()
            state.write(CircuitBreakerState.Open)
            lastFailureTime.write(clock.now())
            old
        }
        if (oldState != CircuitBreakerState.Open) {
            logger.warn { "Manually tripping circuit breaker from $oldState to Open" }
            notifyListeners(oldState, CircuitBreakerState.Open)
        }
    }

    private suspend fun <T> executeInClosedState(block: suspend () -> T): T {
        return try {
            val result = block()
            onSuccess()
            result
        } catch (e: Exception) {
            onFailure(e)
            throw e
        }
    }

    private suspend fun <T> executeInHalfOpenState(block: suspend () -> T): T {
        return try {
            val result = block()
            onSuccessInHalfOpen()
            result
        } catch (e: Exception) {
            onFailureInHalfOpen(e)
            throw e
        }
    }

    private suspend fun checkAndUpdateState() {
        val (shouldTransition, lastFailure) = atomically {
            Pair(state.read() == CircuitBreakerState.Open && lastFailureTime.read() != null, lastFailureTime.read())
        }

        if (shouldTransition && lastFailure != null) {
            val timeSinceLastFailure = clock.now() - lastFailure
            if (timeSinceLastFailure >= config.resetTimeout) {
                val oldState = atomically {
                    if (state.read() == CircuitBreakerState.Open) {
                        val old = state.read()
                        state.write(CircuitBreakerState.HalfOpen)
                        successCount.write(0)
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

    private suspend fun onSuccess() {
        val previousFailures = atomically {
            val failures = failureCount.read()
            if (failures > 0) {
                failureCount.write(0)
            }
            failures
        }

        if (previousFailures > 0) {
            logger.debug { "Success in CLOSED state, reset failure count from $previousFailures to 0" }
        }
    }

    private suspend fun onFailure(exception: Exception) {
        val (newFailures, shouldOpen) = atomically {
            val failures = failureCount.read() + 1
            failureCount.write(failures)
            lastFailureTime.write(clock.now())
            Pair(failures, failures >= config.failureThreshold)
        }

        logger.warn(exception) { "Failure in CLOSED state, count: $newFailures/${config.failureThreshold}" }

        if (shouldOpen) {
            val oldState = atomically {
                if (state.read() == CircuitBreakerState.Closed) {
                    val old = state.read()
                    state.write(CircuitBreakerState.Open)
                    old
                } else {
                    null
                }
            }
            if (oldState != null) {
                logger.error { "Failure threshold reached ($newFailures), opening circuit breaker from $oldState" }
                notifyListeners(oldState, CircuitBreakerState.Open)
            }
        }
    }

    private suspend fun onSuccessInHalfOpen() {
        val (newSuccesses, shouldClose) = atomically {
            val successes = successCount.read() + 1
            successCount.write(successes)
            Pair(successes, successes >= config.halfOpenSuccessThreshold)
        }

        logger.info { "Success in HALF_OPEN state, count: $newSuccesses/${config.halfOpenSuccessThreshold}" }

        if (shouldClose) {
            val oldState = atomically {
                if (state.read() == CircuitBreakerState.HalfOpen) {
                    val old = state.read()
                    failureCount.write(0)
                    successCount.write(0)
                    state.write(CircuitBreakerState.Closed)
                    old
                } else {
                    null
                }
            }
            if (oldState != null) {
                logger.info { "Success threshold reached, closing circuit breaker from $oldState" }
                notifyListeners(oldState, CircuitBreakerState.Closed)
            }
        }
    }

    private suspend fun onFailureInHalfOpen(exception: Exception) {
        val oldState = atomically {
            if (state.read() == CircuitBreakerState.HalfOpen) {
                val old = state.read()
                successCount.write(0)
                lastFailureTime.write(clock.now())
                state.write(CircuitBreakerState.Open)
                old
            } else {
                null
            }
        }
        if (oldState != null) {
            logger.error(exception) { "Failure in HALF_OPEN state, re-opening circuit breaker from $oldState" }
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
 * Configuration for circuit breaker behavior.
 *
 * @property failureThreshold Number of consecutive failures before opening the circuit (default: 5)
 * @property resetTimeout Duration to wait before transitioning from Open to Half-Open (default: 30 seconds)
 * @property halfOpenSuccessThreshold Number of successes needed in Half-Open state to close circuit (default: 2)
 * @property halfOpenMaxCalls Maximum number of calls allowed in Half-Open state (default: 3)
 */
public data class CircuitBreakerConfig(
    public val failureThreshold: Int = 5,
    public val resetTimeout: Duration = 30.seconds,
    public val halfOpenSuccessThreshold: Int = 2,
    public val halfOpenMaxCalls: Int = 3,
) {
    init {
        require(failureThreshold > 0) { "failureThreshold must be > 0, but was $failureThreshold" }
        require(resetTimeout > Duration.ZERO) { "resetTimeout must be > 0, but was $resetTimeout" }
        require(halfOpenSuccessThreshold > 0) { "halfOpenSuccessThreshold must be > 0, but was $halfOpenSuccessThreshold" }
        require(halfOpenMaxCalls > 0) { "halfOpenMaxCalls must be > 0, but was $halfOpenMaxCalls" }
    }
}

/**
 * Circuit breaker state.
 */
public enum class CircuitBreakerState {
    /** Normal operation — calls are allowed through. */
    Closed,

    /** Circuit is broken — calls fail immediately without execution. */
    Open,

    /** Testing recovery — a limited number of calls are allowed through. */
    HalfOpen
}

/**
 * Exception thrown when circuit breaker is open.
 */
public class CircuitBreakerOpenException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Listener for circuit breaker state changes.
 */
public fun interface CircuitBreakerListener {
    public fun onStateChange(oldState: CircuitBreakerState, newState: CircuitBreakerState)
}

/**
 * Creates a circuit breaker with DSL-style configuration.
 */
public suspend fun circuitBreaker(configure: CircuitBreakerConfigBuilder.() -> Unit): CircuitBreaker {
    val builder = CircuitBreakerConfigBuilder()
    builder.configure()
    return CircuitBreaker.create(builder.build())
}

/**
 * Builder for circuit breaker configuration.
 */
public class CircuitBreakerConfigBuilder {
    /** Number of consecutive failures before opening the circuit. */
    public var failureThreshold: Int = 5

    /** Duration to wait before transitioning from Open to Half-Open. */
    public var resetTimeout: Duration = 30.seconds

    /** Number of successes needed in Half-Open state to close the circuit. */
    public var halfOpenSuccessThreshold: Int = 2

    /** Maximum number of calls allowed in Half-Open state. */
    public var halfOpenMaxCalls: Int = 3

    /** Builds the [CircuitBreakerConfig] from the current builder state. */
    public fun build(): CircuitBreakerConfig {
        return CircuitBreakerConfig(
            failureThreshold = failureThreshold,
            resetTimeout = resetTimeout,
            halfOpenSuccessThreshold = halfOpenSuccessThreshold,
            halfOpenMaxCalls = halfOpenMaxCalls,
        )
    }
}

/**
 * Registry for managing multiple named circuit breakers.
 */
public class CircuitBreakerRegistry private constructor(
    private val breakers: TVar<Map<String, CircuitBreaker>>,
) {
    public companion object {
        /**
         * Creates a new [CircuitBreakerRegistry].
         */
        public suspend fun create(): CircuitBreakerRegistry =
            CircuitBreakerRegistry(TVar.new(emptyMap()))
    }

    /**
     * Gets or creates a circuit breaker with the given name.
     * @param name unique identifier for the circuit breaker
     * @param configure optional configuration block
     * @return the existing or newly created circuit breaker
     */
    public suspend fun getOrCreate(
        name: String,
        configure: (CircuitBreakerConfigBuilder.() -> Unit)? = null,
    ): CircuitBreaker {
        // Fast path: check if already exists
        val existing = atomically { breakers.read() }[name]
        if (existing != null) return existing

        // Slow path: create and insert atomically
        val newBreaker = if (configure != null) {
            circuitBreaker(configure)
        } else {
            CircuitBreaker.create()
        }
        return atomically {
            val current = breakers.read()
            val alreadyExists = current[name]
            if (alreadyExists != null) {
                alreadyExists
            } else {
                breakers.write(current + (name to newBreaker))
                newBreaker
            }
        }
    }

    /**
     * Gets an existing circuit breaker by name.
     * @param name the circuit breaker name
     * @return the circuit breaker or null if not found
     */
    public suspend fun get(name: String): CircuitBreaker? = atomically { breakers.read() }[name]

    /**
     * Removes a circuit breaker from the registry.
     * @param name the circuit breaker name
     * @return the removed circuit breaker or null if not found
     */
    public suspend fun remove(name: String): CircuitBreaker? = atomically {
        val current = breakers.read()
        val removed = current[name]
        if (removed != null) {
            breakers.write(current - name)
        }
        removed
    }

    /** Resets all circuit breakers in the registry to closed state. */
    public suspend fun resetAll() {
        val snapshot = atomically { breakers.read() }.values.toList()
        snapshot.forEach { it.reset() }
    }

    /** Returns the names of all registered circuit breakers. */
    public suspend fun getNames(): Set<String> = atomically { breakers.read() }.keys

    /** Returns statistics for all registered circuit breakers, keyed by name. */
    public suspend fun getStatistics(): Map<String, CircuitBreakerStats> {
        val snapshot = atomically { breakers.read() }
        return snapshot.mapValues { (_, breaker) ->
            CircuitBreakerStats(
                state = breaker.currentState(),
                failures = breaker.failures(),
                successes = breaker.successes(),
            )
        }
    }
}

/**
 * Statistics for a circuit breaker.
 */
public data class CircuitBreakerStats(
    /** Current state of the circuit breaker. */
    public val state: CircuitBreakerState,
    /** Current failure count. */
    public val failures: Int,
    /** Current success count (relevant in half-open state). */
    public val successes: Int,
)
