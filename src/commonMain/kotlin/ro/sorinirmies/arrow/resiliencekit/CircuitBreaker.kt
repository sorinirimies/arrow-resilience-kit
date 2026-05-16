// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package ro.sorinirmies.arrow.resiliencekit

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * val breaker = CircuitBreaker(config = CircuitBreakerConfig(
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
public class CircuitBreaker(
    private val config: CircuitBreakerConfig = CircuitBreakerConfig(),
    internal val clock: Clock = Clock.System,
) {
    private val mutex = Mutex()
    private var state: CircuitBreakerState = CircuitBreakerState.Closed
    private var failureCount: Int = 0
    private var successCount: Int = 0
    private var lastFailureTime: Instant? = null
    private val listeners = mutableListOf<CircuitBreakerListener>()

    /**
     * Adds a listener for circuit breaker state changes.
     */
    public fun addListener(listener: CircuitBreakerListener) {
        listeners.add(listener)
    }

    /**
     * Gets the current state of the circuit breaker.
     */
    public suspend fun currentState(): CircuitBreakerState = mutex.withLock { state }

    /**
     * Gets the current failure count.
     */
    public suspend fun failures(): Int = mutex.withLock { failureCount }

    /**
     * Gets the current success count (in half-open state).
     */
    public suspend fun successes(): Int = mutex.withLock { successCount }

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

        val currentState = mutex.withLock { state }

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
        val oldState = mutex.withLock {
            val old = state
            failureCount = 0
            successCount = 0
            lastFailureTime = null
            state = CircuitBreakerState.Closed
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
        val oldState = mutex.withLock {
            val old = state
            state = CircuitBreakerState.Open
            lastFailureTime = clock.now()
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
        val (shouldTransition, lastFailure) = mutex.withLock {
            Pair(state == CircuitBreakerState.Open && lastFailureTime != null, lastFailureTime)
        }

        if (shouldTransition && lastFailure != null) {
            val timeSinceLastFailure = clock.now() - lastFailure
            if (timeSinceLastFailure >= config.resetTimeout) {
                val oldState = mutex.withLock {
                    if (state == CircuitBreakerState.Open) {
                        val old = state
                        state = CircuitBreakerState.HalfOpen
                        successCount = 0
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
        val previousFailures = mutex.withLock {
            val failures = failureCount
            if (failures > 0) {
                failureCount = 0
            }
            failures
        }

        if (previousFailures > 0) {
            logger.debug { "Success in CLOSED state, reset failure count from $previousFailures to 0" }
        }
    }

    private suspend fun onFailure(exception: Exception) {
        val (newFailures, shouldOpen) = mutex.withLock {
            val failures = failureCount + 1
            failureCount = failures
            lastFailureTime = clock.now()
            Pair(failures, failures >= config.failureThreshold)
        }

        logger.warn(exception) { "Failure in CLOSED state, count: $newFailures/${config.failureThreshold}" }

        if (shouldOpen) {
            val oldState = mutex.withLock {
                if (state == CircuitBreakerState.Closed) {
                    val old = state
                    state = CircuitBreakerState.Open
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
        val (newSuccesses, shouldClose) = mutex.withLock {
            val successes = successCount + 1
            successCount = successes
            Pair(successes, successes >= config.halfOpenSuccessThreshold)
        }

        logger.info { "Success in HALF_OPEN state, count: $newSuccesses/${config.halfOpenSuccessThreshold}" }

        if (shouldClose) {
            val oldState = mutex.withLock {
                if (state == CircuitBreakerState.HalfOpen) {
                    val old = state
                    failureCount = 0
                    successCount = 0
                    state = CircuitBreakerState.Closed
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
        val oldState = mutex.withLock {
            if (state == CircuitBreakerState.HalfOpen) {
                val old = state
                successCount = 0
                lastFailureTime = clock.now()
                state = CircuitBreakerState.Open
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
        listeners.forEach { it.onStateChange(oldState, newState) }
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
public fun circuitBreaker(configure: CircuitBreakerConfigBuilder.() -> Unit): CircuitBreaker {
    val builder = CircuitBreakerConfigBuilder()
    builder.configure()
    return CircuitBreaker(builder.build())
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
public class CircuitBreakerRegistry {
    private val breakers = mutableMapOf<String, CircuitBreaker>()

    /**
     * Gets or creates a circuit breaker with the given name.
     * @param name unique identifier for the circuit breaker
     * @param configure optional configuration block
     * @return the existing or newly created circuit breaker
     */
    public fun getOrCreate(
        name: String,
        configure: (CircuitBreakerConfigBuilder.() -> Unit)? = null,
    ): CircuitBreaker {
        return breakers.getOrPut(name) {
            if (configure != null) {
                circuitBreaker(configure)
            } else {
                CircuitBreaker()
            }
        }
    }

    /**
     * Gets an existing circuit breaker by name.
     * @param name the circuit breaker name
     * @return the circuit breaker or null if not found
     */
    public fun get(name: String): CircuitBreaker? {
        return breakers[name]
    }

    /**
     * Removes a circuit breaker from the registry.
     * @param name the circuit breaker name
     * @return the removed circuit breaker or null if not found
     */
    public fun remove(name: String): CircuitBreaker? {
        return breakers.remove(name)
    }

    /** Resets all circuit breakers in the registry to closed state. */
    public suspend fun resetAll() {
        breakers.values.forEach { it.reset() }
    }

    /** Returns the names of all registered circuit breakers. */
    public fun getNames(): Set<String> {
        return breakers.keys.toSet()
    }

    /** Returns statistics for all registered circuit breakers, keyed by name. */
    public suspend fun getStatistics(): Map<String, CircuitBreakerStats> {
        return breakers.mapValues { (_, breaker) ->
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
