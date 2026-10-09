// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

import com.sorinirmies.arrow.resiliencekit.CacheConfig
import com.sorinirmies.arrow.resiliencekit.EvictionStrategy
import kotlin.js.Promise

/**
 * JS/TS-friendly facade over [com.sorinirmies.arrow.resiliencekit.Cache], specialized to
 * `string` keys (the common case for JS/TS caches) to keep this facade's generics simple.
 *
 * ```ts
 * import { Cache } from "arrow-resilience-kit";
 *
 * const cache = await Cache.create<User>({ maxSize: 1000, ttlMillis: 300_000 });
 * const user = await cache.getOrPut("user-123", () => userService.fetch("user-123"));
 * ```
 */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
public class Cache<T> internal constructor(
    private val delegate: com.sorinirmies.arrow.resiliencekit.Cache<String, T>,
) {
    public companion object {
        /**
         * Creates a new cache. [ttlMillis] of `undefined`/omitted means no expiration.
         * [evictionStrategy] is one of `"LRU"`, `"LFU"`, `"FIFO"` (default `"LRU"`).
         */
        public fun <T> create(
            maxSize: Int = 100,
            ttlMillis: Double? = 300_000.0,
            evictionStrategy: String = "LRU",
        ): Promise<Cache<T>> = runAsPromise {
            Cache(
                com.sorinirmies.arrow.resiliencekit.Cache.create<String, T>(
                    CacheConfig(
                        maxSize = maxSize,
                        ttl = ttlMillis?.millisAsDuration,
                        evictionStrategy = EvictionStrategy.valueOf(evictionStrategy),
                    )
                )
            )
        }
    }

    /** Gets the value for [key], or `undefined` if absent/expired. */
    public fun get(key: String): Promise<T?> = runAsPromise { delegate.get(key) }

    /** Puts [value] under [key]. */
    public fun put(key: String, value: T): Promise<Unit> = runAsPromise { delegate.put(key, value) }

    /** Gets the value for [key], computing and storing it via [loader] if absent/expired. */
    public fun getOrPut(key: String, loader: () -> Promise<T>): Promise<T> = runAsPromise {
        delegate.getOrPut(key) { awaitBlock(loader) }
    }

    /** Removes and returns the value for [key], or `undefined` if it wasn't present. */
    public fun remove(key: String): Promise<T?> = runAsPromise { delegate.remove(key) }

    /** Removes every entry. */
    public fun clear(): Promise<Unit> = runAsPromise { delegate.clear() }

    /** Current number of entries. */
    public fun size(): Promise<Int> = runAsPromise { delegate.size() }

    /** Whether [key] is currently present (and not expired). */
    public fun containsKey(key: String): Promise<Boolean> = runAsPromise { delegate.containsKey(key) }

    /** Current statistics snapshot. */
    public fun statistics(): Promise<CacheStatistics> = runAsPromise { delegate.statistics().toJs() }

    /** Resets statistics counters (not the cache's contents). */
    public fun resetStatistics(): Promise<Unit> = runAsPromise { delegate.resetStatistics() }
}

/** JS/TS-friendly snapshot of [com.sorinirmies.arrow.resiliencekit.CacheStatistics]. */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
public data class CacheStatistics internal constructor(
    public val hits: Double,
    public val misses: Double,
    public val evictions: Double,
    public val size: Int,
    public val hitRate: Double,
    public val missRate: Double,
)

private fun com.sorinirmies.arrow.resiliencekit.CacheStatistics.toJs() = CacheStatistics(
    hits = hits.toDouble(),
    misses = misses.toDouble(),
    evictions = evictions.toDouble(),
    size = size,
    hitRate = hitRate,
    missRate = missRate,
)
