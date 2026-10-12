// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package com.sorinirmies.arrow.resiliencekit.spring

import com.sorinirmies.arrow.resiliencekit.ResilienceConfig
import com.sorinirmies.arrow.resiliencekit.ResilienceInstances
import kotlinx.coroutines.runBlocking
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * Spring Boot `arrow.resilience.*` properties. Each map is `name -> setting -> value`:
 *
 * ```yaml
 * arrow:
 *   resilience:
 *     circuit-breaker:
 *       payments: { failureThreshold: 3, resetTimeout: 10s }
 *     bulkhead:
 *       db: { maxConcurrentCalls: 8 }
 *     rate-limiter:
 *       api: { permitsPerSecond: 50, burstCapacity: 100 }
 *     time-limiter:
 *       default: { timeout: 2s }
 * ```
 */
@ConfigurationProperties("arrow.resilience")
public class ArrowResilienceProperties {
    /** Circuit-breaker settings by name. */
    public var circuitBreaker: MutableMap<String, MutableMap<String, String>> = mutableMapOf()

    /** Bulkhead settings by name. */
    public var bulkhead: MutableMap<String, MutableMap<String, String>> = mutableMapOf()

    /** Rate-limiter settings by name. */
    public var rateLimiter: MutableMap<String, MutableMap<String, String>> = mutableMapOf()

    /** Time-limiter settings by name. */
    public var timeLimiter: MutableMap<String, MutableMap<String, String>> = mutableMapOf()

    /** Converts these properties to a [ResilienceConfig]. */
    public fun toConfig(): ResilienceConfig {
        val flat = buildMap {
            fun add(kind: String, m: Map<String, Map<String, String>>) =
                m.forEach { (name, settings) -> settings.forEach { (s, v) -> put("$kind.$name.$s", v) } }
            add("circuitBreaker", circuitBreaker)
            add("bulkhead", bulkhead)
            add("rateLimiter", rateLimiter)
            add("timeLimiter", timeLimiter)
        }
        return ResilienceConfig.fromProperties(flat)
    }
}

/**
 * Auto-configuration exposing a [ResilienceInstances] bean built from
 * `arrow.resilience.*` properties. Inject it and look up instances by name.
 */
@AutoConfiguration
@EnableConfigurationProperties(ArrowResilienceProperties::class)
public class ArrowResilienceAutoConfiguration {
    /** Builds the named instances declared in configuration. */
    @Bean
    @ConditionalOnMissingBean
    public fun resilienceInstances(properties: ArrowResilienceProperties): ResilienceInstances =
        runBlocking { ResilienceInstances.create(properties.toConfig()) }
}
