// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package com.sorinirmies.arrow.resiliencekit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration

/**
 * Declarative, externalizable configuration for named resilience instances
 * (the `application.yml`-style equivalent of the programmatic DSLs).
 *
 * Durations: ISO-8601 (`"PT30S"`) in JSON; ISO or Kotlin forms (`30s`, `1m`) in properties. Load from
 * JSON via [fromJson], from flat properties via [fromProperties] (YAML/Spring
 * `application.properties` flattened to `Map<String, String>`), then build live
 * instances with [ResilienceInstances.create].
 *
 * Property key format: `<kind>.<name>.<setting>`, with kinds `circuitBreaker`,
 * `bulkhead`, `rateLimiter`, `timeLimiter`, e.g. `circuitBreaker.payments.failureThreshold=3`.
 *
 * ```kotlin
 * val config = ResilienceConfig.fromJson("""
 *   {"circuitBreakers":{"payments":{"failureThreshold":3,"resetTimeout":"10s"}}}
 * """)
 * val instances = ResilienceInstances.create(config)
 * instances.circuitBreaker("payments").execute { call() }
 * ```
 */
@Serializable
public data class ResilienceConfig(
    /** Circuit-breaker settings by instance name. */
    val circuitBreakers: Map<String, CircuitBreakerSettings> = emptyMap(),
    /** Bulkhead settings by instance name. */
    val bulkheads: Map<String, BulkheadSettings> = emptyMap(),
    /** Token-bucket rate-limiter settings by instance name. */
    val rateLimiters: Map<String, RateLimiterSettings> = emptyMap(),
    /** Time-limiter settings by instance name. */
    val timeLimiters: Map<String, TimeLimiterSettings> = emptyMap(),
) {
    /** Factories for [ResilienceConfig]. */
    public companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Parses a JSON document into a [ResilienceConfig]. */
        public fun fromJson(text: String): ResilienceConfig = json.decodeFromString(serializer(), text)

        /** Builds a [ResilienceConfig] from flat `<kind>.<name>.<setting>` properties. */
        public fun fromProperties(properties: Map<String, String>): ResilienceConfig {
            fun group(kind: String): Map<String, Map<String, String>> = properties.entries
                .mapNotNull { (k, v) ->
                    val parts = k.split('.')
                    if (parts.size >= 3 && parts[0] == kind) {
                        Triple(parts[1], parts.drop(2).joinToString("."), v)
                    } else {
                        null
                    }
                }
                .groupBy({ it.first }, { it.second to it.third })
                .mapValues { it.value.toMap() }

            return ResilienceConfig(
                circuitBreakers = group("circuitBreaker").mapValues { (_, m) ->
                    val d = CircuitBreakerSettings()
                    CircuitBreakerSettings(
                        failureThreshold = m["failureThreshold"]?.toInt() ?: d.failureThreshold,
                        resetTimeout = m["resetTimeout"]?.let(Duration::parse) ?: d.resetTimeout,
                        halfOpenSuccessThreshold = m["halfOpenSuccessThreshold"]?.toInt() ?: d.halfOpenSuccessThreshold,
                        halfOpenMaxCalls = m["halfOpenMaxCalls"]?.toInt() ?: d.halfOpenMaxCalls,
                    )
                },
                bulkheads = group("bulkhead").mapValues { (_, m) ->
                    val d = BulkheadSettings()
                    BulkheadSettings(
                        maxConcurrentCalls = m["maxConcurrentCalls"]?.toInt() ?: d.maxConcurrentCalls,
                        maxWaitingCalls = m["maxWaitingCalls"]?.toInt() ?: d.maxWaitingCalls,
                        maxWaitDuration = m["maxWaitDuration"]?.let(Duration::parse),
                    )
                },
                rateLimiters = group("rateLimiter").mapValues { (_, m) ->
                    val d = RateLimiterSettings()
                    RateLimiterSettings(
                        permitsPerSecond = m["permitsPerSecond"]?.toDouble() ?: d.permitsPerSecond,
                        burstCapacity = m["burstCapacity"]?.toInt() ?: d.burstCapacity,
                    )
                },
                timeLimiters = group("timeLimiter").mapValues { (_, m) ->
                    TimeLimiterSettings(timeout = m["timeout"]?.let(Duration::parse) ?: TimeLimiterSettings().timeout)
                },
            )
        }
    }
}

/** Serializable mirror of [CircuitBreakerConfig]. */
@Serializable
public data class CircuitBreakerSettings(
    /** See [CircuitBreakerConfig.failureThreshold]. */
    val failureThreshold: Int = CircuitBreakerConfig().failureThreshold,
    /** See [CircuitBreakerConfig.resetTimeout]. */
    val resetTimeout: Duration = CircuitBreakerConfig().resetTimeout,
    /** See [CircuitBreakerConfig.halfOpenSuccessThreshold]. */
    val halfOpenSuccessThreshold: Int = CircuitBreakerConfig().halfOpenSuccessThreshold,
    /** See [CircuitBreakerConfig.halfOpenMaxCalls]. */
    val halfOpenMaxCalls: Int = CircuitBreakerConfig().halfOpenMaxCalls,
) {
    /** Converts to a validated [CircuitBreakerConfig]. */
    public fun toConfig(): CircuitBreakerConfig =
        CircuitBreakerConfig(failureThreshold, resetTimeout, halfOpenSuccessThreshold, halfOpenMaxCalls)
}

/** Serializable mirror of [BulkheadConfig]. */
@Serializable
public data class BulkheadSettings(
    /** See [BulkheadConfig.maxConcurrentCalls]. */
    val maxConcurrentCalls: Int = BulkheadConfig().maxConcurrentCalls,
    /** See [BulkheadConfig.maxWaitingCalls]. */
    val maxWaitingCalls: Int = BulkheadConfig().maxWaitingCalls,
    /** See [BulkheadConfig.maxWaitDuration]. */
    val maxWaitDuration: Duration? = null,
) {
    /** Converts to a validated [BulkheadConfig]. */
    public fun toConfig(): BulkheadConfig = BulkheadConfig(maxConcurrentCalls, maxWaitingCalls, maxWaitDuration)
}

/** Serializable mirror of [RateLimiterConfig]. */
@Serializable
public data class RateLimiterSettings(
    /** See [RateLimiterConfig.permitsPerSecond]. */
    val permitsPerSecond: Double = RateLimiterConfig().permitsPerSecond,
    /** See [RateLimiterConfig.burstCapacity]. */
    val burstCapacity: Int = RateLimiterConfig().burstCapacity,
) {
    /** Converts to a validated [RateLimiterConfig]. */
    public fun toConfig(): RateLimiterConfig = RateLimiterConfig(permitsPerSecond, burstCapacity)
}

/** Serializable mirror of [TimeLimiterConfig]. */
@Serializable
public data class TimeLimiterSettings(
    /** See [TimeLimiterConfig.timeout]. */
    val timeout: Duration = TimeLimiterConfig().timeout,
) {
    /** Converts to a validated [TimeLimiterConfig]. */
    public fun toConfig(): TimeLimiterConfig = TimeLimiterConfig(timeout)
}

/**
 * Live, named resilience instances built from a [ResilienceConfig].
 */
public class ResilienceInstances private constructor(
    private val circuitBreakers: Map<String, CircuitBreaker>,
    private val bulkheads: Map<String, Bulkhead>,
    private val rateLimiters: Map<String, RateLimiter>,
    private val timeLimiters: Map<String, TimeLimiter>,
) {
    /** Factory for [ResilienceInstances]. */
    public companion object {
        /** Instantiates every pattern declared in [config]. */
        public suspend fun create(config: ResilienceConfig): ResilienceInstances = ResilienceInstances(
            config.circuitBreakers.mapValues { CircuitBreaker.create(it.value.toConfig()) },
            config.bulkheads.mapValues { Bulkhead.create(it.value.toConfig()) },
            config.rateLimiters.mapValues { RateLimiter.create(it.value.toConfig()) },
            config.timeLimiters.mapValues { TimeLimiter.create(it.value.toConfig()) },
        )
    }

    /** Returns the named circuit breaker or throws if undeclared. */
    public fun circuitBreaker(name: String): CircuitBreaker =
        requireNotNull(circuitBreakers[name]) { "No circuitBreaker configured with name '$name'" }

    /** Returns the named bulkhead or throws if undeclared. */
    public fun bulkhead(name: String): Bulkhead =
        requireNotNull(bulkheads[name]) { "No bulkhead configured with name '$name'" }

    /** Returns the named rate limiter or throws if undeclared. */
    public fun rateLimiter(name: String): RateLimiter =
        requireNotNull(rateLimiters[name]) { "No rateLimiter configured with name '$name'" }

    /** Returns the named time limiter or throws if undeclared. */
    public fun timeLimiter(name: String): TimeLimiter =
        requireNotNull(timeLimiters[name]) { "No timeLimiter configured with name '$name'" }

    /** Names of all configured circuit breakers. */
    public val circuitBreakerNames: Set<String> get() = circuitBreakers.keys

    /** Names of all configured bulkheads. */
    public val bulkheadNames: Set<String> get() = bulkheads.keys

    /** Names of all configured rate limiters. */
    public val rateLimiterNames: Set<String> get() = rateLimiters.keys

    /** Names of all configured time limiters. */
    public val timeLimiterNames: Set<String> get() = timeLimiters.keys
}
