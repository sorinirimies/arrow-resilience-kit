// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies


import arrow.fx.stm.TVar
import arrow.fx.stm.atomically
import kotlinx.coroutines.sync.Semaphore
import mu.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Configuration for bulkhead behavior.
 *
 * @property maxConcurrentCalls Maximum number of concurrent calls allowed (default: 10)
 * @property maxWaitingCalls Maximum number of calls that can wait for a permit (default: 10)
 * @property maxWaitDuration Maximum time a call can wait for a permit (default: null = infinite)
 */
data class BulkheadConfig(
    val maxConcurrentCalls: Int = 10,
    val maxWaitingCalls: Int = 10,
    val maxWaitDuration: Duration? = null,
) {
    init {
        require(maxConcurrentCalls > 0) {
            "maxConcurrentCalls must be > 0, but was $maxConcurrentCalls"
        }
        require(maxWaitingCalls >= 0) {
            "maxWaitingCalls must be >= 0, but was $maxWaitingCalls"
        }
        require(maxWaitDuration == null || maxWaitDuration > Duration.ZERO) {
            "maxWaitDuration must be > 0 or null, but was $maxWaitDuration"
        }
    }
}

/**
 * Exception thrown when the bulkhead is full.
 */
class BulkheadFullException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Exception thrown when waiting for a bulkhead permit times out.
 */
class BulkheadTimeoutException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Executes an operation with bulkhead protection (concurrency limiting).
 *
 * The Bulkhead pattern isolates resources to prevent cascading failures. It limits the number
 * of concurrent calls to a resource, ensuring that one slow or failing service doesn't consume
 * all available resources.
 *
 * **Use Cases:**
 * - Limit concurrent database connections
 * - Prevent thread pool exhaustion
 * - Isolate calls to different microservices
 * - Protect shared resources from overload
 *
 * Example usage:
 * ```
 * val result = withBulkhead(
 *     config = BulkheadConfig(
 *         maxConcurrentCalls = 10,
 *         maxWaitingCalls = 5
 *     )
 * ) {
 *     externalService.call()
 * }
 * ```
 *
 * @param config Configuration for bulkhead behavior
 * @param block The operation to execute with bulkhead protection
 * @return The result of the operation
 * @throws BulkheadFullException if the bulkhead is full and maxWaitingCalls is exceeded
 * @throws BulkheadTimeoutException if waiting for a permit times out
 * @throws Exception if the operation fails
 */
suspend fun <T> withBulkhead(
    config: BulkheadConfig = BulkheadConfig(),
    block: suspend () -> T,
): T {
    val semaphore = Semaphore(config.maxConcurrentCalls)
    val activeCallsVar = TVar.new(0)
    val waitingCallsVar = TVar.new(0)

    // Check if we can accept this call
    val canAccept = atomically {
        val waiting = waitingCallsVar.read()
        waiting < config.maxWaitingCalls
    }

    if (!canAccept) {
        logger.warn { "Bulkhead is full, rejecting call" }
        val stats = atomically {
            "Active: ${activeCallsVar.read()}, Waiting: ${waitingCallsVar.read()}"
        }
        throw BulkheadFullException("Bulkhead is full. $stats, Max waiting: ${config.maxWaitingCalls}")
    }

    // Increment waiting calls
    atomically {
        val waiting = waitingCallsVar.read()
        waitingCallsVar.write(waiting + 1)
    }

    try {
        // Acquire semaphore permit
        if (config.maxWaitDuration != null) {
            val acquired = tryAcquireSemaphore(semaphore, config.maxWaitDuration)
            if (!acquired) {
                atomically {
                    val waiting = waitingCallsVar.read()
                    waitingCallsVar.write(waiting - 1)
                }
                throw BulkheadTimeoutException(
                    "Timeout waiting for bulkhead permit after ${config.maxWaitDuration}"
                )
            }
        } else {
            semaphore.acquire()
        }

        // Decrement waiting, increment active
        atomically {
            val waiting = waitingCallsVar.read()
            waitingCallsVar.write(waiting - 1)
            val active = activeCallsVar.read()
            activeCallsVar.write(active + 1)
        }

        logger.debug { "Executing call through bulkhead" }

        // Execute the operation
        return try {
            block()
        } finally {
            // Always decrement active count and release permit
            atomically {
                val active = activeCallsVar.read()
                if (active > 0) {
                    activeCallsVar.write(active - 1)
                }
            }
            semaphore.release()
            logger.debug { "Released bulkhead permit" }
        }
    } catch (e: BulkheadTimeoutException) {
        throw e
    } catch (e: Exception) {
        // We were still waiting, decrement waiting count
        atomically {
            val waiting = waitingCallsVar.read()
            waitingCallsVar.write(maxOf(0, waiting - 1))
        }
        throw e
    }
}

/**
 * Tries to execute an operation with bulkhead protection, returning null if the bulkhead is full.
 *
 * @param config Configuration for bulkhead behavior
 * @param block The operation to execute
 * @return The result of the operation or null if bulkhead is full
 */
suspend fun <T> tryWithBulkhead(
    config: BulkheadConfig = BulkheadConfig(),
    block: suspend () -> T,
): T? {
    return try {
        withBulkhead(config, block)
    } catch (e: BulkheadFullException) {
        null
    } catch (e: BulkheadTimeoutException) {
        null
    }
}

/**
 * Executes an operation with bulkhead protection and a fallback if the bulkhead is full.
 *
 * @param config Configuration for bulkhead behavior
 * @param fallback The fallback function to call if bulkhead is full
 * @param block The primary operation to execute
 * @return The result of the operation or fallback
 */
suspend fun <T> withBulkheadOrFallback(
    config: BulkheadConfig = BulkheadConfig(),
    fallback: suspend (Exception) -> T,
    block: suspend () -> T,
): T {
    return try {
        withBulkhead(config, block)
    } catch (e: BulkheadFullException) {
        logger.debug { "Bulkhead is full, using fallback" }
        fallback(e)
    } catch (e: BulkheadTimeoutException) {
        logger.debug { "Bulkhead timeout, using fallback" }
        fallback(e)
    }
}

/**
 * Semaphore extension to support timeout-based acquisition.
 */
private suspend fun tryAcquireSemaphore(semaphore: Semaphore, timeout: Duration): Boolean {
    return kotlinx.coroutines.withTimeoutOrNull(timeout) {
        semaphore.acquire()
        true
    } ?: false
}

/**
 * DSL for bulkhead configuration.
 */
fun bulkhead(configure: BulkheadConfigBuilder.() -> Unit): BulkheadConfig {
    val builder = BulkheadConfigBuilder()
    builder.configure()
    return builder.build()
}

/**
 * Builder for bulkhead configuration.
 */
class BulkheadConfigBuilder {
    var maxConcurrentCalls: Int = 10
    var maxWaitingCalls: Int = 10
    var maxWaitDuration: Duration? = null

    fun build(): BulkheadConfig {
        return BulkheadConfig(
            maxConcurrentCalls = maxConcurrentCalls,
            maxWaitingCalls = maxWaitingCalls,
            maxWaitDuration = maxWaitDuration,
        )
    }
}