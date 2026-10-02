// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package com.sorinirmies.arrow.resiliencekit

import kotlinx.coroutines.CancellationException
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock

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
                return provider.circuitBreaker.execute(provider.block)
            } catch (e: CircuitBreakerOpenException) {
                logger.debug { "Failover: provider '${provider.name}' circuit is open, skipping" }
                attempts += FailoverAttempt(provider.name, FailoverAttempt.Reason.CircuitOpen, e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.debug(e) { "Failover: provider '${provider.name}' failed" }
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
}

/**
 * A single named candidate implementation within a [Failover], paired with its own
 * dedicated [CircuitBreaker].
 */
internal data class FailoverProvider<T>(
    val name: String,
    val circuitBreaker: CircuitBreaker,
    val block: suspend () -> T,
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
        providers += FailoverProvider(name, CircuitBreaker.create(config, clock), block)
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
