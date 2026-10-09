// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

import com.sorinirmies.arrow.resiliencekit.failover
import kotlin.js.Promise

/**
 * One named candidate for [Failover.create]'s `providers` array.
 *
 * ```ts
 * new FailoverProviderSpec("websocket", () => connectWebSocket(), 5)
 * ```
 */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
public data class FailoverProviderSpec<T>(
    public val name: String,
    public val block: () -> Promise<T>,
    public val failureThreshold: Int = 5,
)

/**
 * JS/TS-friendly facade over [com.sorinirmies.arrow.resiliencekit.Failover] -- tries a prioritized
 * list of named providers, falling through to the next one when the current one's circuit is
 * open or it fails outright.
 *
 * The Kotlin `failover { provider(...) }` DSL builder isn't exposed directly (it's a suspend,
 * receiver-style builder lambda, which doesn't bridge to JS/TS the same way a plain callback
 * does); pass an array of [FailoverProviderSpec] instead.
 *
 * ```ts
 * import { Failover, FailoverProviderSpec } from "arrow-resilience-kit";
 *
 * const transport = await Failover.create([
 *     new FailoverProviderSpec("websocket", () => connectWebSocket()),
 *     new FailoverProviderSpec("mqtt", () => connectMqtt()),
 * ]);
 * const connection = await transport.execute();
 * ```
 */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
public class Failover<T> internal constructor(
    private val delegate: com.sorinirmies.arrow.resiliencekit.Failover<T>,
) {
    public companion object {
        /** Creates a new failover chain from [providers], in priority order. */
        public fun <T> create(providers: Array<FailoverProviderSpec<T>>): Promise<Failover<T>> = runAsPromise {
            val built = failover<T> {
                for (spec in providers) {
                    provider(spec.name, circuitBreakerConfig = { failureThreshold = spec.failureThreshold }) {
                        awaitBlock(spec.block)
                    }
                }
            }
            Failover(built)
        }
    }

    /** Tries each provider in priority order, resolving with the first one that succeeds. */
    public fun execute(): Promise<T> = runAsPromise { delegate.execute() }

    /** Names of all configured providers, in priority order. */
    public fun providerNames(): Array<String> = delegate.providerNames().toTypedArray()

    /** Current circuit-breaker state (`"Closed"`, `"Open"`, `"HalfOpen"`) of the named provider, or `undefined`. */
    public fun stateOf(name: String): Promise<String?> = runAsPromise { delegate.stateOf(name)?.name }

    /** Per-provider invocation statistics, in priority order. */
    public fun statistics(): Promise<Array<FailoverProviderStatistics>> = runAsPromise {
        delegate.statistics().map { it.toJs() }.toTypedArray()
    }

    /**
     * Starts actively probing every currently-open provider every [intervalMillis], instead of
     * waiting for real traffic to pass through [execute] and incidentally test them. Call the
     * returned function to stop probing.
     */
    public fun startHealthProbing(intervalMillis: Double = 30_000.0): () -> Unit {
        val job = delegate.startHealthProbing(jsInteropScope, intervalMillis.millisAsDuration)
        return { job.cancel() }
    }
}

/** JS/TS-friendly snapshot of [com.sorinirmies.arrow.resiliencekit.FailoverProviderStatistics]. */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
public data class FailoverProviderStatistics internal constructor(
    public val name: String,
    public val state: String,
    public val successes: Double,
    public val failures: Double,
    public val skipped: Double,
)

private fun com.sorinirmies.arrow.resiliencekit.FailoverProviderStatistics.toJs() = FailoverProviderStatistics(
    name = name,
    state = state.name,
    successes = successes.toDouble(),
    failures = failures.toDouble(),
    skipped = skipped.toDouble(),
)
