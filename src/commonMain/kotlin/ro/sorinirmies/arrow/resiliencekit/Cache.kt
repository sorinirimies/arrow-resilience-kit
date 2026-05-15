// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package ro.sorinirmies.arrow.resiliencekit

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import mu.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger {}

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

    public suspend fun getOrPut(key: K, loader: suspend () -> V): V {
        get(key)?.let { return it }
        val value = loader()
        put(key, value)
        return value
    }

    public suspend fun remove(key: K): V? = mutex.withLock {
        val entry = entries.remove(key)
        if (entry != null) {
            accessOrder.remove(key)
            listeners.forEach { it.onRemove(key, entry.value) }
        }
        entry?.value
    }

    public suspend fun invalidate(key: K) {
        remove(key)
    }

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

    public suspend fun clear(): Unit = mutex.withLock {
        entries.clear()
        accessOrder.clear()
        listeners.forEach { it.onClear() }
    }

    public suspend fun size(): Int = mutex.withLock {
        entries.size
    }

    public suspend fun containsKey(key: K): Boolean = mutex.withLock {
        val entry = entries[key]
        entry != null && !isExpired(entry)
    }

    public suspend fun keys(): Set<K> = mutex.withLock {
        entries.filterValues { !isExpired(it) }.keys.toSet()
    }

    public suspend fun validKeys(): Set<K> = keys()

    public suspend fun validSize(): Int = mutex.withLock {
        entries.values.count { !isExpired(it) }
    }

    public suspend fun cleanUp(): Int = mutex.withLock {
        val expired = entries.filter { isExpired(it.value) }
        for ((k, v) in expired) {
            entries.remove(k)
            accessOrder.remove(k)
            listeners.forEach { it.onEviction(k, v.value, EvictionReason.EXPIRED) }
        }
        expired.size
    }

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

    public suspend fun resetStatistics(): Unit = mutex.withLock {
        hits = 0L
        misses = 0L
        evictions = 0L
    }

    public fun addListener(listener: CacheListener<K, V>) {
        listeners.add(listener)
    }

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

public data class CacheConfig(
    public val maxSize: Int = 100,
    public val ttl: Duration? = 5.minutes,
    public val evictionStrategy: EvictionStrategy = EvictionStrategy.LRU
) {
    init {
        require(maxSize > 0) { "maxSize must be greater than 0" }
        if (ttl != null) {
            require(ttl > Duration.ZERO) { "ttl must be greater than Duration.ZERO" }
        }
    }
}

public enum class EvictionStrategy {
    LRU,
    LFU,
    FIFO
}

internal data class CacheEntry<V>(
    val value: V,
    val createdAt: Instant,
    val lastAccessTime: Instant,
    val accessCount: Int = 0
)

public data class CacheStatistics(
    public val hits: Long,
    public val misses: Long,
    public val evictions: Long,
    public val size: Int,
    public val hitRate: Double,
    public val missRate: Double = 0.0,
    public val utilizationRate: Double = 0.0,
    public val currentSize: Int = size
)

public interface CacheListener<K, V> {
    public fun onPut(key: K, value: V) {}
    public fun onRemove(key: K, value: V) {}
    public fun onEviction(key: K, value: V, reason: EvictionReason) {}
    public fun onClear() {}
}

public enum class EvictionReason {
    SIZE,
    EXPIRED
}

public class CacheConfigBuilder {
    public var maxSize: Int = 100
    public var ttl: Duration? = 5.minutes
    public var evictionStrategy: EvictionStrategy = EvictionStrategy.LRU

    public fun build(): CacheConfig = CacheConfig(
        maxSize = maxSize,
        ttl = ttl,
        evictionStrategy = evictionStrategy
    )
}

public fun <K, V> cache(configure: CacheConfigBuilder.() -> Unit): Cache<K, V> {
    val builder = CacheConfigBuilder()
    builder.configure()
    return Cache(builder.build())
}

public class LoadingCache<K, V>(
    config: CacheConfig = CacheConfig(),
    private val loader: suspend (K) -> V
) {
    private val delegate = Cache<K, V>(config)

    public suspend fun get(key: K): V {
        delegate.get(key)?.let { return it }
        val value = loader(key)
        delegate.put(key, value)
        return value
    }

    public suspend fun put(key: K, value: V): Unit = delegate.put(key, value)

    public suspend fun remove(key: K): V? = delegate.remove(key)

    public suspend fun clear(): Unit = delegate.clear()

    public suspend fun statistics(): CacheStatistics = delegate.statistics()
}

public class CacheRegistry {
    private val caches = mutableMapOf<String, Cache<*, *>>()

    @Suppress("UNCHECKED_CAST")
    public fun <K, V> getOrCreate(name: String, configure: CacheConfigBuilder.() -> Unit = {}): Cache<K, V> {
        return caches.getOrPut(name) {
            cache<K, V>(configure)
        } as Cache<K, V>
    }

    @Suppress("UNCHECKED_CAST")
    public fun <K, V> get(name: String): Cache<K, V>? {
        return caches[name] as? Cache<K, V>
    }

    public fun remove(name: String): Cache<*, *>? {
        return caches.remove(name)
    }

    public fun getNames(): Set<String> = caches.keys.toSet()

    public suspend fun clearAll() {
        caches.values.forEach { it.clear() }
    }
}
