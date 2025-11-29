// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies


import arrow.fx.stm.TVar
import arrow.fx.stm.atomically
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import mu.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Circuit Breaker pattern implementation for fault tolerance using Arrow STM.
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
 * @param config Configuration for circuit breaker behavior
 *
 * Example usage:
 * ```
 * val circuitBreaker = CircuitBreaker.create(
 *     config = CircuitBreakerConfig(
 *         failureThreshold = 5,
 *         successThreshold = 3,
 *         timeout = 60.seconds
 *     )
 * )
 *
 * val result = circuitBreaker.execute {
 *     externalService.call()
 * }
 * ```
 */
class CircuitBreaker private constructor(
    private val config: CircuitBreakerConfig,
    private val stateVar: TVar<CircuitBreakerState>,
    private val failureCountVar: TVar<Int>,
    private val successCountVar: TVar<Int>,
    private val lastFailureTimeVar: TVar<Instant?>
) {
    companion object {
        /**
         * Creates a new CircuitBreaker instance with the given configuration.
         */
        suspend fun create(config: CircuitBreakerConfig = CircuitBreakerConfig()): CircuitBreaker {
            return CircuitBreaker(
                config = config,
                stateVar = TVar.new(CircuitBreakerState.Closed),
                failureCountVar = TVar.new(0),
                successCountVar = TVar.new(0),
                lastFailureTimeVar = TVar.new(null)
            )
        }
    }

    /**
     * Gets the current state of the circuit breaker.
     */
    suspend fun currentState(): CircuitBreakerState = atomically {
        stateVar.read()
    }

    /**
     * Gets the current failure count.
     */
    suspend fun failures(): Int = atomically {
        failureCountVar.read()
    }

    /**
     * Gets the current success count (in half-open state).
     */
    suspend fun successes(): Int = atomically {
        successCountVar.read()
    }

    /**
     * Adds a listener to be notified of circuit breaker state changes.
     */
    suspend fun addListener(listener: CircuitBreakerListener) {
        // TODO: Implement listener registration
    }

    /**
     * Removes a listener.
     */
    suspend fun removeListener(listener: CircuitBreakerListener) {
        // TODO: Implement listener removal
    }

    /**
     * Executes an operation through the circuit breaker.
     *
     * @param block The operation to execute
     * @return The result of the operation
     * @throws CircuitBreakerOpenException if the circuit is open
     * @throws Exception if the operation fails
     */
    suspend fun <T> execute(block: suspend () -> T): T {
        checkAndUpdateState()

        val currentState = atomically { stateVar.read() }

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
    suspend fun <T> executeOrFallback(
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
    suspend fun reset() {
        atomically {
            failureCountVar.write(0)
            successCountVar.write(0)
            lastFailureTimeVar.write(null)
            val oldState = stateVar.read()
            stateVar.write(CircuitBreakerState.Closed)
            oldState
        }.also { oldState ->
            if (oldState != CircuitBreakerState.Closed) {
                logger.info { "Manually resetting circuit breaker" }
                notifyListeners(oldState, CircuitBreakerState.Closed)
            }
        }
    }

    /**
     * Manually opens the circuit breaker.
     */
    suspend fun trip() {
        atomically {
            val oldState = stateVar.read()
            stateVar.write(CircuitBreakerState.Open)
            lastFailureTimeVar.write(Clock.System.now())
            oldState
        }.also { oldState ->
            if (oldState != CircuitBreakerState.Open) {
                logger.warn { "Manually tripping circuit breaker" }
                notifyListeners(oldState, CircuitBreakerState.Open)
            }
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
            val currentState = stateVar.read()
            val lastFailure = lastFailureTimeVar.read()
            Pair(currentState == CircuitBreakerState.Open && lastFailure != null, lastFailure)
        }

        if (shouldTransition && lastFailure != null) {
            val timeSinceLastFailure = Clock.System.now() - lastFailure
            if (timeSinceLastFailure >= config.resetTimeout) {
                atomically {
                    val oldState = stateVar.read()
                    if (oldState == CircuitBreakerState.Open) {
                        stateVar.write(CircuitBreakerState.HalfOpen)
                        successCountVar.write(0)
                        oldState
                    } else {
                        null
                    }
                }?.also { oldState ->
                    logger.info { "Reset timeout expired, transitioning to HALF_OPEN" }
                    notifyListeners(oldState, CircuitBreakerState.HalfOpen)
                }
            }
        }
    }

    private suspend fun onSuccess() {
        val previousFailures = atomically {
            val failures = failureCountVar.read()
            if (failures > 0) {
                failureCountVar.write(0)
            }
            failures
        }

        if (previousFailures > 0) {
            logger.debug { "Success in CLOSED state, reset failure count from $previousFailures to 0" }
        }
    }

    private suspend fun onFailure(exception: Exception) {
        val (newFailures, shouldOpen) = atomically {
            val failures = failureCountVar.read() + 1
            failureCountVar.write(failures)
            lastFailureTimeVar.write(Clock.System.now())
            Pair(failures, failures >= config.failureThreshold)
        }

        logger.warn(exception) { "Failure in CLOSED state, count: $newFailures/${config.failureThreshold}" }

        if (shouldOpen) {
            atomically {
                val oldState = stateVar.read()
                if (oldState == CircuitBreakerState.Closed) {
                    stateVar.write(CircuitBreakerState.Open)
                    oldState
                } else {
                    null
                }
            }?.also { oldState ->
                logger.error { "Failure threshold reached ($newFailures), opening circuit breaker" }
                notifyListeners(oldState, CircuitBreakerState.Open)
            }
        }
    }

    private suspend fun onSuccessInHalfOpen() {
        val (newSuccesses, shouldClose) = atomically {
            val successes = successCountVar.read() + 1
            successCountVar.write(successes)
            Pair(successes, successes >= config.halfOpenSuccessThreshold)
        }

        logger.info { "Success in HALF_OPEN state, count: $newSuccesses/${config.halfOpenSuccessThreshold}" }

        if (shouldClose) {
            atomically {
                val oldState = stateVar.read()
                if (oldState == CircuitBreakerState.HalfOpen) {
                    failureCountVar.write(0)
                    successCountVar.write(0)
                    stateVar.write(CircuitBreakerState.Closed)
                    oldState
                } else {
                    null
                }
            }?.also { oldState ->
                logger.info { "Success threshold reached, closing circuit breaker" }
                notifyListeners(oldState, CircuitBreakerState.Closed)
            }
        }
    }

    private suspend fun onFailureInHalfOpen(exception: Exception) {
        atomically {
            val oldState = stateVar.read()
            if (oldState == CircuitBreakerState.HalfOpen) {
                successCountVar.write(0)
                lastFailureTimeVar.write(Clock.System.now())
                stateVar.write(CircuitBreakerState.Open)
                oldState
            } else {
                null
            }
        }?.also { oldState ->
            logger.error(exception) { "Failure in HALF_OPEN state, re-opening circuit breaker" }
            notifyListeners(oldState, CircuitBreakerState.Open)
        }
    }

    private fun notifyListeners(oldState: CircuitBreakerState, newState: CircuitBreakerState) {
        // TODO: Implement listener notifications
        logger.debug { "Circuit breaker state changed: $oldState -> $newState" }
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
data class CircuitBreakerConfig(
    val failureThreshold: Int = 5,
    val resetTimeout: Duration = 30.seconds,
    val halfOpenSuccessThreshold: Int = 2,
    val halfOpenMaxCalls: Int = 3,
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
enum class CircuitBreakerState {
    /**
     * Normal operation, all calls are allowed.
     */
    Closed,

    /**
     * Circuit is broken, all calls fail immediately without executing.
     */
    Open,

    /**
     * Testing recovery, limited calls are allowed to test if service has recovered.
     */
    HalfOpen
}

/**
 * Exception thrown when circuit breaker is open.
 */
class CircuitBreakerOpenException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Listener for circuit breaker state changes.
 */
fun interface CircuitBreakerListener {
    /**
     * Called when the circuit breaker state changes.
     *
     * @param oldState The previous state
     * @param newState The new state
     */
    fun onStateChange(oldState: CircuitBreakerState, newState: CircuitBreakerState)
}

/**
 * Creates a circuit breaker with DSL-style configuration.
 *
 * Example usage:
 * ```
 * val breaker = circuitBreaker {
 *     failureThreshold = 5
 *     resetTimeout = 30.seconds
 *     halfOpenSuccessThreshold = 2
 * }
 * ```
 */
suspend fun circuitBreaker(configure: CircuitBreakerConfigBuilder.() -> Unit): CircuitBreaker {
    val builder = CircuitBreakerConfigBuilder()
    builder.configure()
    return CircuitBreaker.create(builder.build())
}

/**
 * Builder for circuit breaker configuration.
 */
class CircuitBreakerConfigBuilder {
    var failureThreshold: Int = 5
    var resetTimeout: Duration = 30.seconds
    var halfOpenSuccessThreshold: Int = 2
    var halfOpenMaxCalls: Int = 3

    fun build(): CircuitBreakerConfig {
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
 *
 * Useful for managing circuit breakers for different services or endpoints.
 *
 * Example usage:
 * ```
 * val registry = CircuitBreakerRegistry()
 *
 * // Create circuit breakers for different services
 * val userServiceBreaker = registry.getOrCreate("user-service") {
 *     failureThreshold = 5
 *     resetTimeout = 30.seconds
 * }
 *
 * val paymentServiceBreaker = registry.getOrCreate("payment-service") {
 *     failureThreshold = 3
 *     resetTimeout = 60.seconds
 * }
 * ```
 */
class CircuitBreakerRegistry {
    private val breakers = mutableMapOf<String, CircuitBreaker>()

    /**
     * Gets an existing circuit breaker or creates a new one.
     *
     * @param name The name of the circuit breaker
     * @param configure Optional configuration block
     * @return The circuit breaker instance
     */
    suspend fun getOrCreate(
        name: String,
        configure: (CircuitBreakerConfigBuilder.() -> Unit)? = null,
    ): CircuitBreaker {
        return breakers.getOrPut(name) {
            if (configure != null) {
                circuitBreaker(configure)
            } else {
                CircuitBreaker.create()
            }
        }
    }

    /**
     * Gets an existing circuit breaker by name.
     *
     * @param name The name of the circuit breaker
     * @return The circuit breaker instance or null if not found
     */
    fun get(name: String): CircuitBreaker? {
        return breakers[name]
    }

    /**
     * Removes a circuit breaker from the registry.
     *
     * @param name The name of the circuit breaker to remove
     * @return The removed circuit breaker or null if not found
     */
    fun remove(name: String): CircuitBreaker? {
        return breakers.remove(name)
    }

    /**
     * Resets all circuit breakers in the registry.
     */
    suspend fun resetAll() {
        breakers.values.forEach { it.reset() }
    }

    /**
     * Gets all circuit breaker names in the registry.
     */
    fun getNames(): Set<String> {
        return breakers.keys.toSet()
    }

    /**
     * Gets statistics for all circuit breakers.
     */
    suspend fun getStatistics(): Map<String, CircuitBreakerStats> {
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
data class CircuitBreakerStats(
    val state: CircuitBreakerState,
    val failures: Int,
    val successes: Int,
)