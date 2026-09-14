// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

/**
 * Fault-injection helpers for testing resilience code.
 *
 * Wrap a real or fake suspend call so it randomly fails and/or adds latency,
 * making it easy to exercise retry/circuit-breaker/bulkhead/etc. behavior in
 * consumer tests without hand-rolling flaky fakes.
 *
 * ```kotlin
 * val flaky = chaos({ failureRate = 0.3; latency = 50.milliseconds }) {
 *     realApi.call()
 * }
 * val result = retryWithExponentialBackoff(retries = 5) { flaky() }
 * ```
 */
package ro.sorinirmies.arrow.resiliencekit

import kotlinx.coroutines.delay
import kotlin.random.Random
import kotlin.time.Duration

/**
 * Thrown by [chaos]-wrapped operations when a simulated failure is injected.
 */
public class ChaosException(message: String = "Simulated failure injected by chaos()") : Exception(message)

/**
 * Configuration for [chaos]-injected faults.
 */
public data class ChaosConfig(
    /** Probability (0.0..1.0) that a given call fails with [exception]. */
    public val failureRate: Double = 0.0,
    /** Fixed latency added before every call (successful or not). */
    public val latency: Duration = Duration.ZERO,
    /** Additional random latency in `[0, latencyJitter)` added on top of [latency]. */
    public val latencyJitter: Duration = Duration.ZERO,
    /** Source of randomness; inject a seeded [Random] for deterministic tests. */
    public val random: Random = Random.Default,
    /** Factory for the exception thrown on a simulated failure. */
    public val exception: () -> Throwable = { ChaosException() },
) {
    init {
        require(failureRate in 0.0..1.0) { "failureRate must be in [0.0, 1.0], but was $failureRate" }
        require(latency >= Duration.ZERO) { "latency must be >= 0, but was $latency" }
        require(latencyJitter >= Duration.ZERO) { "latencyJitter must be >= 0, but was $latencyJitter" }
    }
}

/**
 * Wraps [block] so that invoking the returned function injects latency and/or
 * failures according to [config].
 */
public fun <T> chaos(config: ChaosConfig = ChaosConfig(), block: suspend () -> T): suspend () -> T = {
    if (config.latency > Duration.ZERO || config.latencyJitter > Duration.ZERO) {
        val jitter = if (config.latencyJitter > Duration.ZERO) {
            config.latencyJitter * config.random.nextDouble()
        } else {
            Duration.ZERO
        }
        delay(config.latency + jitter)
    }
    if (config.random.nextDouble() < config.failureRate) {
        throw config.exception()
    }
    block()
}

/**
 * DSL variant of [chaos] using a config builder.
 */
public fun <T> chaos(configure: ChaosConfigBuilder.() -> Unit, block: suspend () -> T): suspend () -> T {
    val config = ChaosConfigBuilder().apply(configure).build()
    return chaos(config, block)
}

/**
 * Builder for [ChaosConfig].
 */
public class ChaosConfigBuilder {
    /** Probability (0.0..1.0) that a given call fails. */
    public var failureRate: Double = 0.0

    /** Fixed latency added before every call. */
    public var latency: Duration = Duration.ZERO

    /** Additional random latency added on top of [latency]. */
    public var latencyJitter: Duration = Duration.ZERO

    /** Source of randomness; set to a seeded [Random] for deterministic tests. */
    public var random: Random = Random.Default

    /** Factory for the exception thrown on a simulated failure. */
    public var exception: () -> Throwable = { ChaosException() }

    /** Builds the [ChaosConfig] from the current builder state. */
    public fun build(): ChaosConfig = ChaosConfig(
        failureRate = failureRate,
        latency = latency,
        latencyJitter = latencyJitter,
        random = random,
        exception = exception,
    )
}
