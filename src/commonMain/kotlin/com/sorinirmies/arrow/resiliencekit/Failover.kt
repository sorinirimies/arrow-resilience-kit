// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package com.sorinirmies.arrow.resiliencekit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import io.github.oshai.kotlinlogging.KotlinLogging
import arrow.fx.stm.TVar
import arrow.fx.stm.atomically
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Failover pattern: try a prioritized list of named providers, falling through to the
 * next one when the current provider's circuit is open or it fails outright.
 *
 * Useful when the same logical operation can be satisfied by more than one concrete
 * implementation and you want to prefer one over the others — e.g. a realtime transport
 * that prefers WebSocket but degrades to MQTT, then to HTTP long-polling, if the
 * preferred option is currently unhealthy.
 *
 * Each provider gets its own [CircuitBreaker]. A provider that keeps failing has its
 * breaker trip open, so subsequent calls skip it immediately instead of paying its
 * timeout/latency again — and the breaker's own half-open/reset-timeout behavior
 * periodically re-probes it, so [Failover] self-heals back to the preferred provider
 * once it recovers, with no extra bookkeeping needed from the caller.
 *
 * **Basic usage:**
 * ```kotlin
 * val transport = failover<Connection> {
 *     provider("websocket") { connectWebSocket() }
 *     provider("mqtt") { connectMqtt() }
 *     provider("http-polling") { connectHttpPolling() }
 * }
 *
 * val connection = transport.execute()
 * ```
 *
 * **Per-provider circuit breaker tuning:**
 * ```kotlin
 * val transport = failover<Connection> {
 *     provider("websocket", circuitBreakerConfig = { failureThreshold = 3 }) {
 *         connectWebSocket()
 *     }
 *     provider("mqtt") { connectMqtt() }
 * }
 * ```
 *
 * **Inspecting provider health:**
 * ```kotlin
 * transport.providerNames() // ["websocket", "mqtt", "http-polling"]
 * transport.stateOf("websocket") // CircuitBreakerState.Open, once it's known-bad
 * ```
 *
 * If every provider is either skipped (circuit open) or fails, [execute] throws
 * [FailoverExhaustedException] describing why each provider was unavailable.
 */
public class Failover<T> internal constructor(
    private val providers: List<FailoverProvider<T>>,
) {
    /**
     * Tries each provider in priority order, returning the result of the first one
     * that succeeds.
     *
     * A provider is skipped without being invoked if its circuit breaker is currently
     * open. A provider that is invoked and fails has its own circuit breaker record
     * the failure (so it may trip open for subsequent calls), and [execute] moves on
     * to the next provider.
     *
     * @throws FailoverExhaustedException if every provider was skipped or failed
     */
    public suspend fun execute(): T {
        val attempts = mutableListOf<FailoverAttempt>()

        for (provider in providers) {
            try {
                val result = provider.circuitBreaker.execute(provider.block)
                atomically { provider.successes.write(provider.successes.read() + 1) }
                return result
            } catch (e: CircuitBreakerOpenException) {
                logger.debug { "Failover: provider '${provider.name}' circuit is open, skipping" }
                atomically { provider.skipped.write(provider.skipped.read() + 1) }
                attempts += FailoverAttempt(provider.name, FailoverAttempt.Reason.CircuitOpen, e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.debug(e) { "Failover: provider '${provider.name}' failed" }
                atomically { provider.failures.write(provider.failures.read() + 1) }
                attempts += FailoverAttempt(provider.name, FailoverAttempt.Reason.Failed, e)
            }
        }

        throw FailoverExhaustedException(attempts)
    }

    /** Names of all configured providers, in priority order. */
    public fun providerNames(): List<String> = providers.map { it.name }

    /** Current circuit-breaker state of the named provider, or `null` if no such provider exists. */
    public suspend fun stateOf(name: String): CircuitBreakerState? =
        providers.find { it.name == name }?.circuitBreaker?.currentState()

    /** Per-provider invocation statistics, in priority order. */
    public suspend fun statistics(): List<FailoverProviderStatistics> = providers.map { provider ->
        FailoverProviderStatistics(
            name = provider.name,
            state = provider.circuitBreaker.currentState(),
            successes = atomically { provider.successes.read() },
            failures = atomically { provider.failures.read() },
            skipped = atomically { provider.skipped.read() },
        )
    }

    /**
     * Starts actively probing every currently-open provider on a fixed [interval], instead of
     * waiting for real traffic to pass through [execute] and incidentally test them.
     *
     * Without this, a lower-priority provider that recovers while a higher-priority one keeps
     * satisfying every call may never get re-tried at all (since [execute] always returns as
     * soon as *any* provider succeeds) -- and even the top provider only gets re-tested exactly
     * as often as real traffic happens to arrive, which can be a long wait on a quiet system.
     * Active probing decouples recovery detection from real traffic entirely: by the time a real
     * request arrives, an open provider that has actually recovered is already back in rotation
     * (or at least progressing through its circuit breaker's half-open state).
     *
     * A probe that succeeds or fails updates that provider's statistics exactly like a real
     * [execute] call would; a provider whose circuit isn't open yet (still within its own
     * `resetTimeout`) is left alone -- probing never bypasses the breaker's own timing.
     *
     * ```kotlin
     * val transport = failover<Connection> {
     *     provider("websocket") { connectWebSocket() }
     *     provider("mqtt") { connectMqtt() }
     * }
     * val probing = transport.startHealthProbing(applicationScope, interval = 30.seconds)
     * // ... later, on shutdown:
     * probing.cancel()
     * ```
     *
     * @param scope Coroutine scope the background probing loop is launched in -- cancel the
     *   returned [Job] (or cancel [scope] itself) to stop probing.
     * @param interval How often to probe every currently-open provider.
     * @return The [Job] running the probing loop.
     */
    public fun startHealthProbing(scope: CoroutineScope, interval: Duration = 30.seconds): Job = scope.launch {
        while (isActive) {
            delay(interval)
            for (provider in providers) {
                if (provider.circuitBreaker.currentState() != CircuitBreakerState.Open) continue

                try {
                    provider.circuitBreaker.execute(provider.block)
                    atomically { provider.successes.write(provider.successes.read() + 1) }
                    logger.info { "Failover: active health probe succeeded for provider '${provider.name}'" }
                } catch (e: CircuitBreakerOpenException) {
                    // Still within its own resetTimeout -- nothing to probe yet.
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    atomically { provider.failures.write(provider.failures.read() + 1) }
                    logger.debug(e) { "Failover: active health probe failed for provider '${provider.name}'" }
                }
            }
        }
    }
}

/**
 * A single named candidate implementation within a [Failover], paired with its own
 * dedicated [CircuitBreaker] and invocation counters (see [FailoverProviderStatistics]).
 */
internal data class FailoverProvider<T>(
    val name: String,
    val circuitBreaker: CircuitBreaker,
    val block: suspend () -> T,
    val successes: TVar<Long>,
    val failures: TVar<Long>,
    val skipped: TVar<Long>,
)

/**
 * A single attempt recorded during [Failover.execute]: either a provider was skipped
 * because its circuit was open, or it was invoked and failed.
 *
 * @property provider Name of the provider this attempt refers to
 * @property reason Why this provider didn't satisfy the call
 * @property cause The underlying exception, if the provider was actually invoked and failed
 */
public data class FailoverAttempt(
    public val provider: String,
    public val reason: Reason,
    public val cause: Throwable?,
) {
    /** Why a provider didn't satisfy a [Failover.execute] call. */
    public enum class Reason {
        /** The provider's circuit breaker was open, so it was skipped without being invoked. */
        CircuitOpen,

        /** The provider was invoked and its block threw. */
        Failed,
    }
}

/**
 * Thrown by [Failover.execute] when every configured provider was either skipped
 * (circuit open) or invoked and failed.
 *
 * @property attempts One entry per configured provider, in priority order, describing
 *   why it didn't satisfy the call.
 */
public class FailoverExhaustedException(
    public val attempts: List<FailoverAttempt>,
) : Exception(
    "All failover providers unavailable: " +
        attempts.joinToString(", ") { attempt ->
            val reason = when (attempt.reason) {
                FailoverAttempt.Reason.CircuitOpen -> "circuit open"
                FailoverAttempt.Reason.Failed -> "failed: ${attempt.cause?.message}"
            }
            "${attempt.provider} ($reason)"
        },
)

/**
 * Invocation statistics for a single provider within a [Failover], as of [Failover.statistics].
 *
 * @property name The provider's name
 * @property state Current state of this provider's dedicated circuit breaker
 * @property successes Number of times this provider was invoked and succeeded
 * @property failures Number of times this provider was invoked and threw
 * @property skipped Number of times this provider was skipped because its circuit was open
 */
public data class FailoverProviderStatistics(
    public val name: String,
    public val state: CircuitBreakerState,
    public val successes: Long,
    public val failures: Long,
    public val skipped: Long,
)

/**
 * Builder for [Failover], used via the [failover] DSL function.
 */
public class FailoverBuilder<T> {
    private val providers = mutableListOf<FailoverProvider<T>>()

    /**
     * Registers a provider, lowest priority last.
     *
     * @param name Human-readable, unique name for this provider (used in [FailoverAttempt]
     *   and [Failover.stateOf])
     * @param circuitBreakerConfig Configures this provider's dedicated [CircuitBreaker]
     *   (defaults match [CircuitBreakerConfig]'s defaults)
     * @param clock Clock used by this provider's circuit breaker (defaults to [Clock.System])
     * @param block The operation this provider performs
     */
    public suspend fun provider(
        name: String,
        circuitBreakerConfig: CircuitBreakerConfigBuilder.() -> Unit = {},
        clock: Clock = Clock.System,
        block: suspend () -> T,
    ) {
        require(name.isNotBlank()) { "Failover provider name must not be blank" }
        require(providers.none { it.name == name }) { "Duplicate failover provider name: '$name'" }
        val config = CircuitBreakerConfigBuilder().apply(circuitBreakerConfig).build()
        providers += FailoverProvider(
            name = name,
            circuitBreaker = CircuitBreaker.create(config, clock),
            block = block,
            successes = TVar.new(0L),
            failures = TVar.new(0L),
            skipped = TVar.new(0L),
        )
    }

    internal fun build(): Failover<T> {
        require(providers.isNotEmpty()) { "failover requires at least one provider" }
        return Failover(providers.toList())
    }
}

/**
 * Creates a [Failover] with DSL-style configuration.
 *
 * ```kotlin
 * val transport = failover<Connection> {
 *     provider("websocket") { connectWebSocket() }
 *     provider("mqtt") { connectMqtt() }
 * }
 * ```
 */
public suspend fun <T> failover(configure: suspend FailoverBuilder<T>.() -> Unit): Failover<T> {
    val builder = FailoverBuilder<T>()
    builder.configure()
    return builder.build()
}

/**
 * Registry for managing multiple named [Failover] chains.
 *
 * Example usage:
 * ```
 * val registry = FailoverRegistry.create()
 *
 * val transport = registry.getOrCreate<Connection>("realtime-transport") {
 *     provider("websocket") { connectWebSocket() }
 *     provider("mqtt") { connectMqtt() }
 * }
 * ```
 */
public class FailoverRegistry private constructor(
    private val failovers: TVar<Map<String, Failover<*>>>,
) {
    /** Factory for [FailoverRegistry]. */
    public companion object {
        /** Creates a new, empty [FailoverRegistry]. */
        public suspend fun create(): FailoverRegistry {
            val failovers = TVar.new(emptyMap<String, Failover<*>>())
            return FailoverRegistry(failovers)
        }
    }

    /**
     * Gets an existing failover chain or builds a new one from [configure].
     *
     * If a failover is already registered under [name], [configure] is ignored
     * and the existing instance is returned as-is -- same caveat as
     * [CacheRegistry.getOrCreate]: calling this with a different `T` for an
     * already-registered name is a programmer error, not something this API
     * can check at compile time.
     */
    @Suppress("UNCHECKED_CAST")
    public suspend fun <T> getOrCreate(name: String, configure: suspend FailoverBuilder<T>.() -> Unit): Failover<T> {
        val existing = atomically { failovers.read()[name] }
        if (existing != null) return existing as Failover<T>

        val newFailover = failover(configure)
        return atomically {
            val current = failovers.read()
            val existingInTx = current[name]
            if (existingInTx != null) {
                existingInTx as Failover<T>
            } else {
                failovers.write(current + (name to newFailover))
                newFailover
            }
        }
    }

    /**
     * Gets an existing failover chain by name.
     */
    @Suppress("UNCHECKED_CAST")
    public suspend fun <T> get(name: String): Failover<T>? = atomically { failovers.read()[name] as? Failover<T> }

    /**
     * Removes a failover chain from the registry.
     */
    public suspend fun remove(name: String): Failover<*>? = atomically {
        val current = failovers.read()
        val removed = current[name]
        if (removed != null) {
            failovers.write(current - name)
        }
        removed
    }

    /**
     * Gets all failover chain names in the registry.
     */
    public suspend fun getNames(): Set<String> = atomically { failovers.read().keys }
}
