// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package ro.sorinirmies.arrow.resiliencekit

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * val cache = Cache<String, User>()
 * cache.put("user-1", User("Alice"))
 * val user = cache.get("user-1") // User("Alice")
 * ```
 *
 * **With TTL and LRU eviction:**
 * ```kotlin
 * val cache = Cache<String, User>(config = CacheConfig(
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
 * val cache = LoadingCache<String, User>(
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
public class Cache<K, V>(
    private val config: CacheConfig = CacheConfig(),
    private val clock: Clock = Clock.System
) {
    private val mutex = Mutex()
    private val entries = mutableMapOf<K, CacheEntry<V>>()
    private val accessOrder = mutableListOf<K>()
    private var hits = 0L
    private var misses = 0L
    private var evictions = 0L
    private val listeners = mutableListOf<CacheListener<K, V>>()

    /**
     * Gets a value from the cache by key.
     * @param key the cache key
     * @return the cached value or null if not found or expired
     */
    public suspend fun get(key: K): V? = mutex.withLock {
        val entry = entries[key]
        if (entry == null) {
            misses++
            null
        } else if (isExpired(entry)) {
            misses++
            null
        } else {
            val updated = entry.copy(lastAccessTime = clock.now(), accessCount = entry.accessCount + 1)
            entries[key] = updated
            accessOrder.remove(key)
            accessOrder.add(key)
            hits++
            updated.value
        }
    }

    /**
     * Puts a value into the cache, evicting an entry if at capacity.
     * @param key the cache key
     * @param value the value to cache
     */
    public suspend fun put(key: K, value: V): Unit = mutex.withLock {
        if (entries.size >= config.maxSize && key !in entries) {
            evictOne()
        }
        val entry = CacheEntry(
            value = value,
            createdAt = clock.now(),
            lastAccessTime = clock.now(),
            accessCount = 0
        )
        entries[key] = entry
        accessOrder.remove(key)
        accessOrder.add(key)
        listeners.forEach { it.onPut(key, value) }
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
    public suspend fun remove(key: K): V? = mutex.withLock {
        val entry = entries.remove(key)
        if (entry != null) {
            accessOrder.remove(key)
            listeners.forEach { it.onRemove(key, entry.value) }
        }
        entry?.value
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
        mutex.withLock {
            val keysSet = keys.toSet()
            for (k in keysSet) {
                val entry = entries.remove(k)
                if (entry != null) {
                    accessOrder.remove(k)
                    listeners.forEach { it.onRemove(k, entry.value) }
                }
            }
        }
    }

    /** Removes all entries from the cache. */
    public suspend fun clear(): Unit = mutex.withLock {
        entries.clear()
        accessOrder.clear()
        listeners.forEach { it.onClear() }
    }

    /** Returns the total number of entries in the cache (including expired). */
    public suspend fun size(): Int = mutex.withLock {
        entries.size
    }

    /**
     * Checks if the cache contains a non-expired entry for the given key.
     * @param key the cache key
     * @return true if a valid entry exists
     */
    public suspend fun containsKey(key: K): Boolean = mutex.withLock {
        val entry = entries[key]
        entry != null && !isExpired(entry)
    }

    /** Returns all non-expired keys in the cache. */
    public suspend fun keys(): Set<K> = mutex.withLock {
        entries.filterValues { !isExpired(it) }.keys.toSet()
    }

    /** Returns all non-expired keys in the cache. Alias for [keys]. */
    public suspend fun validKeys(): Set<K> = keys()

    /** Returns the number of non-expired entries in the cache. */
    public suspend fun validSize(): Int = mutex.withLock {
        entries.values.count { !isExpired(it) }
    }

    /**
     * Removes all expired entries from the cache.
     * @return the number of entries removed
     */
    public suspend fun cleanUp(): Int = mutex.withLock {
        val expired = entries.filter { isExpired(it.value) }
        for ((k, v) in expired) {
            entries.remove(k)
            accessOrder.remove(k)
            listeners.forEach { it.onEviction(k, v.value, EvictionReason.EXPIRED) }
        }
        expired.size
    }

    /** Returns a snapshot of the current cache statistics. */
    public suspend fun statistics(): CacheStatistics = mutex.withLock {
        val total = hits + misses
        CacheStatistics(
            hits = hits,
            misses = misses,
            evictions = evictions,
            size = entries.size,
            hitRate = if (total > 0) hits.toDouble() / total else 0.0,
            missRate = if (total > 0) misses.toDouble() / total else 0.0,
            utilizationRate = entries.size.toDouble() / config.maxSize,
            currentSize = entries.size
        )
    }

    /** Resets all statistics counters to zero. */
    public suspend fun resetStatistics(): Unit = mutex.withLock {
        hits = 0L
        misses = 0L
        evictions = 0L
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

    /** Must be called while holding mutex. */
    private fun evictOne() {
        if (entries.isEmpty()) return

        val keyToEvict = when (config.evictionStrategy) {
            EvictionStrategy.LRU -> accessOrder.firstOrNull()
            EvictionStrategy.LFU -> entries.minByOrNull { it.value.accessCount }?.key
            EvictionStrategy.FIFO -> entries.minByOrNull { it.value.createdAt }?.key
        }

        keyToEvict?.let { key ->
            val entry = entries.remove(key)
            accessOrder.remove(key)
            evictions++
            if (entry != null) {
                listeners.forEach { it.onEviction(key, entry.value, EvictionReason.SIZE) }
            }
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
public fun <K, V> cache(configure: CacheConfigBuilder.() -> Unit): Cache<K, V> {
    val builder = CacheConfigBuilder()
    builder.configure()
    return Cache(builder.build())
}

/**
 * A cache that automatically loads values on cache miss.
 *
 * @param K Key type
 * @param V Value type
 * @param config Configuration for cache behavior
 * @param loader function to load a value when not found in the cache
 */
public class LoadingCache<K, V>(
    config: CacheConfig = CacheConfig(),
    private val loader: suspend (K) -> V
) {
    private val delegate = Cache<K, V>(config)

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
public class CacheRegistry {
    private val caches = mutableMapOf<String, Cache<*, *>>()

    /**
     * Gets or creates a cache with the given name.
     * @param name unique identifier for the cache
     * @param configure optional configuration block
     * @return the existing or newly created cache
     */
    @Suppress("UNCHECKED_CAST")
    public fun <K, V> getOrCreate(name: String, configure: CacheConfigBuilder.() -> Unit = {}): Cache<K, V> {
        return caches.getOrPut(name) {
            cache<K, V>(configure)
        } as Cache<K, V>
    }

    /**
     * Gets an existing cache by name.
     * @param name the cache name
     * @return the cache or null if not found
     */
    @Suppress("UNCHECKED_CAST")
    public fun <K, V> get(name: String): Cache<K, V>? {
        return caches[name] as? Cache<K, V>
    }

    /**
     * Removes a cache from the registry.
     * @param name the cache name
     * @return the removed cache or null if not found
     */
    public fun remove(name: String): Cache<*, *>? {
        return caches.remove(name)
    }

    /** Returns the names of all registered caches. */
    public fun getNames(): Set<String> = caches.keys.toSet()

    /** Clears all entries from all registered caches. */
    public suspend fun clearAll() {
        caches.values.forEach { it.clear() }
    }
}
