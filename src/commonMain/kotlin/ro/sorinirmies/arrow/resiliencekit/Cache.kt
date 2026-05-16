// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package ro.sorinirmies.arrow.resiliencekit

import arrow.fx.stm.STM
import arrow.fx.stm.TVar
import arrow.fx.stm.atomically
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Thread-safe cache with TTL, eviction strategies, and statistics.
 *
 * Provides a configurable in-memory cache with support for multiple eviction
 * strategies (LRU, LFU, FIFO), time-to-live expiration, and hit/miss statistics.
 *
 * **Basic usage:**
 * ```kotlin
 * val cache = Cache.create<String, User>()
 * cache.put("user-1", User("Alice"))
 * val user = cache.get("user-1") // User("Alice")
 * ```
 *
 * **With TTL and LRU eviction:**
 * ```kotlin
 * val cache = Cache.create<String, User>(config = CacheConfig(
 *     maxSize = 1000,
 *     ttl = 5.minutes,
 *     evictionStrategy = EvictionStrategy.LRU
 * ))
 * ```
 *
 * **Compute-if-absent:**
 * ```kotlin
 * val user = cache.getOrPut("user-1") {
 *     userRepository.findById("user-1")
 * }
 * ```
 *
 * **Auto-loading cache:**
 * ```kotlin
 * val cache = LoadingCache.create<String, User>(
 *     config = CacheConfig(maxSize = 100)
 * ) { key ->
 *     userRepository.findById(key)
 * }
 * val user = cache.get("user-1") // auto-loads on miss
 * ```
 *
 * **DSL builder:**
 * ```kotlin
 * val cache = cache<String, User> {
 *     maxSize = 500
 *     ttl = 10.minutes
 *     evictionStrategy = EvictionStrategy.LFU
 * }
 * ```
 *
 * @param K Key type
 * @param V Value type
 * @param config Configuration for cache behavior
 */
public class Cache<K, V> private constructor(
    private val config: CacheConfig,
    private val clock: Clock,
    private val entries: TVar<Map<K, CacheEntry<V>>>,
    private val accessOrder: TVar<List<K>>,
    private val hits: TVar<Long>,
    private val misses: TVar<Long>,
    private val evictions: TVar<Long>
) {
    private val listeners = mutableListOf<CacheListener<K, V>>()

    public companion object {
        /**
         * Creates a new [Cache] instance.
         * @param config Configuration for cache behavior
         * @param clock Clock used for TTL calculations
         * @return a new [Cache] instance
         */
        public suspend fun <K, V> create(
            config: CacheConfig = CacheConfig(),
            clock: Clock = Clock.System
        ): Cache<K, V> {
            val entries = TVar.new(emptyMap<K, CacheEntry<V>>())
            val accessOrder = TVar.new(emptyList<K>())
            val hits = TVar.new(0L)
            val misses = TVar.new(0L)
            val evictions = TVar.new(0L)
            return Cache(config, clock, entries, accessOrder, hits, misses, evictions)
        }
    }

    /**
     * Gets a value from the cache by key.
     * @param key the cache key
     * @return the cached value or null if not found or expired
     */
    public suspend fun get(key: K): V? = atomically {
        val currentEntries = entries.read()
        val entry = currentEntries[key]
        if (entry == null) {
            misses.write(misses.read() + 1)
            null
        } else if (isExpired(entry)) {
            entries.write(currentEntries - key)
            accessOrder.write(accessOrder.read() - key)
            misses.write(misses.read() + 1)
            null
        } else {
            val updated = entry.copy(lastAccessTime = clock.now(), accessCount = entry.accessCount + 1)
            entries.write(currentEntries + (key to updated))
            val order = accessOrder.read()
            accessOrder.write(order - key + key)
            hits.write(hits.read() + 1)
            updated.value
        }
    }

    /**
     * Puts a value into the cache, evicting an entry if at capacity.
     * @param key the cache key
     * @param value the value to cache
     */
    public suspend fun put(key: K, value: V) {
        val listenersSnapshot = listeners.toList()
        var evictedKey: K? = null
        var evictedValue: V? = null
        atomically {
            val currentEntries = entries.read()
            val currentOrder = accessOrder.read()

            val afterEvict = if (currentEntries.size >= config.maxSize && key !in currentEntries) {
                val result = evictOne(currentEntries, currentOrder)
                // Track what was evicted for listener notification
                val evictedKeys = currentEntries.keys - result.first.keys
                if (evictedKeys.isNotEmpty()) {
                    val ek = evictedKeys.first()
                    evictedKey = ek
                    evictedValue = currentEntries[ek]?.value
                }
                result
            } else {
                currentEntries to currentOrder
            }

            val entry = CacheEntry(
                value = value,
                createdAt = clock.now(),
                lastAccessTime = clock.now(),
                accessCount = 0
            )
            entries.write(afterEvict.first + (key to entry))
            accessOrder.write(afterEvict.second - key + key)
        }
        if (evictedKey != null && evictedValue != null) {
            listenersSnapshot.forEach {
                try {
                    it.onEviction(evictedKey!!, evictedValue!!, EvictionReason.SIZE)
                } catch (_: Exception) {
                }
            }
        }
        listenersSnapshot.forEach {
            try {
                it.onPut(key, value)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Gets a cached value or computes and caches it if absent.
     * @param key the cache key
     * @param loader function to compute the value on cache miss
     * @return the cached or newly computed value
     */
    public suspend fun getOrPut(key: K, loader: suspend () -> V): V {
        get(key)?.let { return it }
        val value = loader()
        put(key, value)
        return value
    }

    /**
     * Removes an entry from the cache.
     * @param key the cache key
     * @return the removed value or null if not found
     */
    public suspend fun remove(key: K): V? {
        val listenersSnapshot = listeners.toList()
        val removedEntry = atomically {
            val currentEntries = entries.read()
            val entry = currentEntries[key]
            if (entry != null) {
                entries.write(currentEntries - key)
                accessOrder.write(accessOrder.read() - key)
            }
            entry
        }
        if (removedEntry != null) {
            listenersSnapshot.forEach {
                try {
                    it.onRemove(key, removedEntry.value)
                } catch (_: Exception) {
                }
            }
        }
        return removedEntry?.value
    }

    /**
     * Invalidates a cache entry by key.
     * @param key the cache key to invalidate
     */
    public suspend fun invalidate(key: K) {
        remove(key)
    }

    /**
     * Invalidates multiple cache entries.
     * @param keys the keys to invalidate
     */
    public suspend fun invalidateAll(keys: Iterable<K>) {
        val listenersSnapshot = listeners.toList()
        val removed = atomically {
            val keysSet = keys.toSet()
            val currentEntries = entries.read()
            val currentOrder = accessOrder.read()
            val removedEntries = mutableListOf<Pair<K, CacheEntry<V>>>()
            var newEntries = currentEntries
            var newOrder = currentOrder
            for (k in keysSet) {
                val entry = newEntries[k]
                if (entry != null) {
                    newEntries = newEntries - k
                    newOrder = newOrder - k
                    removedEntries.add(k to entry)
                }
            }
            entries.write(newEntries)
            accessOrder.write(newOrder)
            removedEntries
        }
        for ((k, entry) in removed) {
            listenersSnapshot.forEach {
                try {
                    it.onRemove(k, entry.value)
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Removes all entries from the cache. */
    public suspend fun clear() {
        val listenersSnapshot = listeners.toList()
        atomically {
            entries.write(emptyMap())
            accessOrder.write(emptyList())
        }
        listenersSnapshot.forEach {
            try {
                it.onClear()
            } catch (_: Exception) {
            }
        }
    }

    /** Returns the total number of entries in the cache (including expired). */
    public suspend fun size(): Int = atomically {
        entries.read().size
    }

    /**
     * Checks if the cache contains a non-expired entry for the given key.
     * @param key the cache key
     * @return true if a valid entry exists
     */
    public suspend fun containsKey(key: K): Boolean = atomically {
        val entry = entries.read()[key]
        entry != null && !isExpired(entry)
    }

    /** Returns all non-expired keys in the cache. */
    public suspend fun keys(): Set<K> = atomically {
        entries.read().filterValues { !isExpired(it) }.keys.toSet()
    }

    /** Returns all non-expired keys in the cache. Alias for [keys]. */
    public suspend fun validKeys(): Set<K> = keys()

    /** Returns the number of non-expired entries in the cache. */
    public suspend fun validSize(): Int = atomically {
        entries.read().values.count { !isExpired(it) }
    }

    /**
     * Removes all expired entries from the cache.
     * @return the number of entries removed
     */
    public suspend fun cleanUp(): Int {
        val listenersSnapshot = listeners.toList()
        val expired = atomically {
            val currentEntries = entries.read()
            val expiredEntries = currentEntries.filter { isExpired(it.value) }
            if (expiredEntries.isNotEmpty()) {
                val expiredKeys = expiredEntries.keys
                entries.write(currentEntries - expiredKeys)
                accessOrder.write(accessOrder.read().filter { it !in expiredKeys })
            }
            expiredEntries
        }
        for ((k, v) in expired) {
            listenersSnapshot.forEach {
                try {
                    it.onEviction(k, v.value, EvictionReason.EXPIRED)
                } catch (_: Exception) {
                }
            }
        }
        return expired.size
    }

    /** Returns a snapshot of the current cache statistics. */
    public suspend fun statistics(): CacheStatistics = atomically {
        val h = hits.read()
        val m = misses.read()
        val e = evictions.read()
        val s = entries.read().size
        val total = h + m
        CacheStatistics(
            hits = h,
            misses = m,
            evictions = e,
            size = s,
            hitRate = if (total > 0) h.toDouble() / total else 0.0,
            missRate = if (total > 0) m.toDouble() / total else 0.0,
            utilizationRate = s.toDouble() / config.maxSize,
            currentSize = s
        )
    }

    /** Resets all statistics counters to zero. */
    public suspend fun resetStatistics(): Unit = atomically {
        hits.write(0L)
        misses.write(0L)
        evictions.write(0L)
    }

    /**
     * Adds a listener for cache events.
     * @param listener the listener to add
     */
    public fun addListener(listener: CacheListener<K, V>) {
        listeners.add(listener)
    }

    /**
     * Removes a previously added cache listener.
     * @param listener the listener to remove
     */
    public fun removeListener(listener: CacheListener<K, V>) {
        listeners.remove(listener)
    }

    private fun isExpired(entry: CacheEntry<V>): Boolean {
        val ttl = config.ttl ?: return false
        return clock.now() - entry.createdAt > ttl
    }

    /** Called inside an STM transaction. Returns updated (entries, accessOrder). */
    private fun STM.evictOne(
        currentEntries: Map<K, CacheEntry<V>>,
        currentOrder: List<K>
    ): Pair<Map<K, CacheEntry<V>>, List<K>> {
        if (currentEntries.isEmpty()) return currentEntries to currentOrder

        val keyToEvict = when (config.evictionStrategy) {
            EvictionStrategy.LRU -> currentOrder.firstOrNull()
            EvictionStrategy.LFU -> currentEntries.minByOrNull { it.value.accessCount }?.key
            EvictionStrategy.FIFO -> currentEntries.minByOrNull { it.value.createdAt }?.key
        }

        return if (keyToEvict != null) {
            evictions.write(evictions.read() + 1)
            (currentEntries - keyToEvict) to (currentOrder - keyToEvict)
        } else {
            currentEntries to currentOrder
        }
    }
}

/**
 * Configuration for cache behavior.
 */
public data class CacheConfig(
    /** Maximum number of entries the cache can hold. */
    public val maxSize: Int = 100,
    /** Time-to-live for cache entries, or null for no expiration. */
    public val ttl: Duration? = 5.minutes,
    /** Strategy used to evict entries when the cache is full. */
    public val evictionStrategy: EvictionStrategy = EvictionStrategy.LRU
) {
    init {
        require(maxSize > 0) { "maxSize must be greater than 0" }
        if (ttl != null) {
            require(ttl > Duration.ZERO) { "ttl must be greater than Duration.ZERO" }
        }
    }
}

/** Strategy used to determine which cache entries to evict. */
public enum class EvictionStrategy {
    /** Least Recently Used: evicts the entry that was accessed least recently. */
    LRU,

    /** Least Frequently Used: evicts the entry with the fewest accesses. */
    LFU,

    /** First In First Out: evicts the oldest entry by creation time. */
    FIFO
}

internal data class CacheEntry<V>(
    val value: V,
    val createdAt: Instant,
    val lastAccessTime: Instant,
    val accessCount: Int = 0
)

/** Statistics tracked by a cache. */
public data class CacheStatistics(
    /** Number of cache hits. */
    public val hits: Long,
    /** Number of cache misses. */
    public val misses: Long,
    /** Number of entries evicted. */
    public val evictions: Long,
    /** Total number of entries in the cache. */
    public val size: Int,
    /** Cache hit rate as a ratio from 0.0 to 1.0. */
    public val hitRate: Double,
    /** Cache miss rate as a ratio from 0.0 to 1.0. */
    public val missRate: Double = 0.0,
    /** Cache utilization as a ratio from 0.0 to 1.0. */
    public val utilizationRate: Double = 0.0,
    /** Current number of entries in the cache. */
    public val currentSize: Int = size
)

/** Listener for cache events. */
public interface CacheListener<K, V> {
    /** Called when an entry is added or updated. */
    public fun onPut(key: K, value: V) {}

    /** Called when an entry is explicitly removed. */
    public fun onRemove(key: K, value: V) {}

    /** Called when an entry is evicted. */
    public fun onEviction(key: K, value: V, reason: EvictionReason) {}

    /** Called when the cache is cleared. */
    public fun onClear() {}
}

/** Reason an entry was evicted from the cache. */
public enum class EvictionReason {
    /** Evicted because the cache exceeded its maximum size. */
    SIZE,

    /** Evicted because the entry's TTL expired. */
    EXPIRED
}

/** Builder for cache configuration. */
public class CacheConfigBuilder {
    /** Maximum number of entries the cache can hold. */
    public var maxSize: Int = 100

    /** Time-to-live for cache entries, or null for no expiration. */
    public var ttl: Duration? = 5.minutes

    /** Strategy used to evict entries when the cache is full. */
    public var evictionStrategy: EvictionStrategy = EvictionStrategy.LRU

    /** Builds the [CacheConfig] from the current builder state. */
    public fun build(): CacheConfig = CacheConfig(
        maxSize = maxSize,
        ttl = ttl,
        evictionStrategy = evictionStrategy
    )
}

/**
 * Creates a cache with DSL-style configuration.
 * @param configure configuration block
 * @return a new [Cache] instance
 */
public suspend fun <K, V> cache(configure: CacheConfigBuilder.() -> Unit): Cache<K, V> {
    val builder = CacheConfigBuilder()
    builder.configure()
    return Cache.create(builder.build())
}

/**
 * A cache that automatically loads values on cache miss.
 *
 * @param K Key type
 * @param V Value type
 * @param config Configuration for cache behavior
 * @param loader function to load a value when not found in the cache
 */
public class LoadingCache<K, V> private constructor(
    private val delegate: Cache<K, V>,
    private val loader: suspend (K) -> V
) {
    public companion object {
        /**
         * Creates a new [LoadingCache] instance.
         * @param config Configuration for cache behavior
         * @param loader function to load a value when not found in the cache
         * @return a new [LoadingCache] instance
         */
        public suspend fun <K, V> create(
            config: CacheConfig = CacheConfig(),
            loader: suspend (K) -> V
        ): LoadingCache<K, V> {
            val delegate = Cache.create<K, V>(config)
            return LoadingCache(delegate, loader)
        }
    }

    /**
     * Gets a value from the cache, auto-loading it via [loader] on miss.
     * @param key the cache key
     * @return the cached or newly loaded value
     */
    public suspend fun get(key: K): V {
        delegate.get(key)?.let { return it }
        val value = loader(key)
        delegate.put(key, value)
        return value
    }

    /**
     * Puts a value into the cache.
     * @param key the cache key
     * @param value the value to cache
     */
    public suspend fun put(key: K, value: V): Unit = delegate.put(key, value)

    /**
     * Removes an entry from the cache.
     * @param key the cache key
     * @return the removed value or null if not found
     */
    public suspend fun remove(key: K): V? = delegate.remove(key)

    /** Removes all entries from the cache. */
    public suspend fun clear(): Unit = delegate.clear()

    /** Returns a snapshot of the current cache statistics. */
    public suspend fun statistics(): CacheStatistics = delegate.statistics()
}

/** Registry for managing named cache instances. */
public class CacheRegistry private constructor(
    private val caches: TVar<Map<String, Cache<*, *>>>
) {
    public companion object {
        /** Creates a new [CacheRegistry] instance. */
        public suspend fun create(): CacheRegistry {
            val caches = TVar.new(emptyMap<String, Cache<*, *>>())
            return CacheRegistry(caches)
        }
    }

    /**
     * Gets or creates a cache with the given name.
     * @param name unique identifier for the cache
     * @param configure optional configuration block
     * @return the existing or newly created cache
     */
    @Suppress("UNCHECKED_CAST")
    public suspend fun <K, V> getOrCreate(name: String, configure: CacheConfigBuilder.() -> Unit = {}): Cache<K, V> {
        val existing = atomically { caches.read()[name] }
        if (existing != null) return existing as Cache<K, V>
        val newCache = cache<K, V>(configure)
        atomically {
            val current = caches.read()
            if (name !in current) {
                caches.write(current + (name to newCache))
            }
        }
        // Re-read in case another coroutine created it first
        return atomically { caches.read()[name] as Cache<K, V> }
    }

    /**
     * Gets an existing cache by name.
     * @param name the cache name
     * @return the cache or null if not found
     */
    @Suppress("UNCHECKED_CAST")
    public suspend fun <K, V> get(name: String): Cache<K, V>? = atomically {
        caches.read()[name] as? Cache<K, V>
    }

    /**
     * Removes a cache from the registry.
     * @param name the cache name
     * @return the removed cache or null if not found
     */
    public suspend fun remove(name: String): Cache<*, *>? = atomically {
        val current = caches.read()
        val removed = current[name]
        if (removed != null) {
            caches.write(current - name)
        }
        removed
    }

    /** Returns the names of all registered caches. */
    public suspend fun getNames(): Set<String> = atomically { caches.read().keys.toSet() }

    /** Clears all entries from all registered caches. */
    public suspend fun clearAll() {
        val snapshot = atomically { caches.read().values.toList() }
        snapshot.forEach { it.clear() }
    }
}
