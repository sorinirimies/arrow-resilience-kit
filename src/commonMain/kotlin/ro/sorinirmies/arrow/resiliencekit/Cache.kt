// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies


import arrow.fx.stm.TVar
import arrow.fx.stm.atomically
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import mu.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger {}

/**
 * Cache pattern implementation with TTL, size limits, and eviction strategies using Arrow STM.
 *
 * The Cache pattern stores frequently accessed data to reduce latency and load on backend systems.
 * This implementation provides thread-safe caching with automatic expiration and size management.
 *
 * **Use Cases:**
 * - Reduce database queries
 * - Cache API responses
 * - Memoize expensive computations
 * - Reduce network calls
 *
 * **Features:**
 * - Time-based expiration (TTL)
 * - Size-based eviction (LRU, LFU, FIFO)
 * - Thread-safe operations using STM
 * - Statistics tracking
 *
 * @param K Key type
 * @param V Value type
 * @param config Configuration for cache behavior
 *
 * Example usage:
 * ```
 * val cache = Cache<String, User>(
 *     config = CacheConfig(
 *         maxSize = 100,
 *         ttl = 5.minutes,
 *         evictionStrategy = EvictionStrategy.LRU
 *     )
 * )
 *
 * // Get or compute value
 * val user = cache.getOrPut("user123") {
 *     userService.fetchUser("user123")
 * }
 * ```
 */
class Cache<K, V>(
    private val config: CacheConfig = CacheConfig(),
) {
    private val entriesVar = TVar(emptyMap<K, CacheEntry<V>>())
    private val accessOrderVar = TVar(emptyList<K>()) // For LRU
    private val hitsVar = TVar(0L)
    private val missesVar = TVar(0L)
    private val evictionsVar = TVar(0L)
    private val listeners = mutableListOf<CacheListener<K, V>>()

    /**
     * Gets a value from the cache.
     *
     * @param key The key to look up
     * @return The cached value or null if not found or expired
     */
    suspend fun get(key: K): V? {
        return atomically {
            val entries = entriesVar.read()
            val entry = entries[key]

            if (entry == null) {
                val misses = missesVar.read()
                missesVar.write(misses + 1)
                null
            } else if (isExpired(entry)) {
                // Entry expired, remove it
                entriesVar.write(entries - key)
                accessOrderVar.write(accessOrderVar.read() - key)
                val misses = missesVar.read()
                missesVar.write(misses + 1)
                notifyListenersSync { it.onEviction(key, entry.value, EvictionReason.EXPIRED) }
                null
            } else {
                // Hit! Update access tracking
                val hits = hitsVar.read()
                hitsVar.write(hits + 1)
                entry.accessCount++
                entry.lastAccessTime = Clock.System.now()

                // Update LRU order
                val currentOrder = accessOrderVar.read()
                val newOrder = (currentOrder - key) + key
                accessOrderVar.write(newOrder)

                entry.value
            }
        }
    }

    /**
     * Puts a value in the cache.
     *
     * @param key The key to store
     * @param value The value to store
     */
    suspend fun put(key: K, value: V) {
        atomically {
            val entries = entriesVar.read()
            val now = Clock.System.now()

            // Check if we need to evict
            if (key !in entries && entries.size >= config.maxSize) {
                evictOne()
            }

            val entry = CacheEntry(
                value = value,
                createdAt = now,
                lastAccessTime = now,
                accessCount = 0,
            )

            entriesVar.write(entries + (key to entry))

            // Update access order for LRU
            val currentOrder = accessOrderVar.read()
            val newOrder = (currentOrder - key) + key
            accessOrderVar.write(newOrder)

            notifyListenersSync { it.onPut(key, value) }
        }
    }

    /**
     * Gets a value from the cache, or computes and caches it if not present.
     *
     * @param key The key to look up
     * @param compute Function to compute the value if not cached
     * @return The cached or computed value
     */
    suspend fun getOrPut(key: K, compute: suspend () -> V): V {
        val cached = get(key)
        if (cached != null) {
            return cached
        }

        val computed = compute()
        put(key, computed)
        return computed
    }

    /**
     * Removes a value from the cache.
     *
     * @param key The key to remove
     * @return The removed value or null if not found
     */
    suspend fun remove(key: K): V? {
        return atomically {
            val entries = entriesVar.read()
            val entry = entries[key]

            if (entry != null) {
                entriesVar.write(entries - key)
                accessOrderVar.write(accessOrderVar.read() - key)
                notifyListenersSync { it.onRemove(key, entry.value) }
                entry.value
            } else {
                null
            }
        }
    }

    /**
     * Checks if a key exists in the cache and is not expired.
     *
     * @param key The key to check
     * @return true if the key exists and is valid, false otherwise
     */
    suspend fun containsKey(key: K): Boolean {
        return get(key) != null
    }

    /**
     * Gets all keys in the cache (including expired entries).
     *
     * @return Set of all keys
     */
    suspend fun keys(): Set<K> = atomically {
        entriesVar.read().keys
    }

    /**
     * Gets all valid (non-expired) keys in the cache.
     *
     * @return Set of valid keys
     */
    suspend fun validKeys(): Set<K> = atomically {
        val entries = entriesVar.read()
        entries.filterNot { (_, entry) -> isExpired(entry) }.keys
    }

    /**
     * Gets the current size of the cache (including expired entries).
     *
     * @return Number of entries in the cache
     */
    suspend fun size(): Int = atomically {
        entriesVar.read().size
    }

    /**
     * Gets the number of valid (non-expired) entries in the cache.
     *
     * @return Number of valid entries
     */
    suspend fun validSize(): Int = atomically {
        val entries = entriesVar.read()
        entries.count { (_, entry) -> !isExpired(entry) }
    }

    /**
     * Clears all entries from the cache.
     */
    suspend fun clear() {
        atomically {
            val entries = entriesVar.read()
            entries.forEach { (key, entry) ->
                notifyListenersSync { it.onRemove(key, entry.value) }
            }
            entriesVar.write(emptyMap())
            accessOrderVar.write(emptyList())
        }
    }

    /**
     * Removes all expired entries from the cache.
     *
     * @return Number of entries removed
     */
    suspend fun cleanUp(): Int {
        return atomically {
            val entries = entriesVar.read()
            val expiredKeys = entries.filter { (_, entry) -> isExpired(entry) }.keys

            expiredKeys.forEach { key ->
                val entry = entries[key]!!
                notifyListenersSync { it.onEviction(key, entry.value, EvictionReason.EXPIRED) }
            }

            entriesVar.write(entries - expiredKeys)
            accessOrderVar.write(accessOrderVar.read() - expiredKeys)
            val evictions = evictionsVar.read()
            evictionsVar.write(evictions + expiredKeys.size)

            expiredKeys.size
        }
    }

    /**
     * Gets the current statistics.
     */
    suspend fun statistics(): CacheStatistics = atomically {
        val hits = hitsVar.read()
        val misses = missesVar.read()
        val size = entriesVar.read().size

        CacheStatistics(
            hits = hits,
            misses = misses,
            evictions = evictionsVar.read(),
            currentSize = size,
            maxSize = config.maxSize,
        )
    }

    /**
     * Adds a listener to be notified of cache events.
     */
    fun addListener(listener: CacheListener<K, V>) {
        listeners.add(listener)
    }

    /**
     * Removes a listener.
     */
    fun removeListener(listener: CacheListener<K, V>) {
        listeners.remove(listener)
    }

    /**
     * Resets all statistics counters.
     */
    suspend fun resetStatistics() {
        atomically {
            hitsVar.write(0L)
            missesVar.write(0L)
            evictionsVar.write(0L)
        }
    }

    /**
     * Checks if a cache entry is expired.
     * Must be called within an STM transaction.
     */
    private fun isExpired(entry: CacheEntry<V>): Boolean {
        if (config.ttl == null) return false
        val now = Clock.System.now()
        return (now - entry.createdAt) >= config.ttl
    }

    /**
     * Evicts one entry based on the eviction strategy.
     * Must be called within an STM transaction.
     */
    private suspend fun evictOne() {
        atomically {
            val entries = entriesVar.read()
            if (entries.isEmpty()) return@atomically

            val keyToEvict = when (config.evictionStrategy) {
                EvictionStrategy.LRU -> {
                    // Least Recently Used - first in access order
                    accessOrderVar.read().firstOrNull()
                }
                EvictionStrategy.LFU -> {
                    // Least Frequently Used - lowest access count
                    entries.minByOrNull { (_, entry) -> entry.accessCount }?.key
                }
                EvictionStrategy.FIFO -> {
                    // First In First Out - oldest creation time
                    entries.minByOrNull { (_, entry) -> entry.createdAt }?.key
                }
            }

            if (keyToEvict != null) {
                val entry = entries[keyToEvict]!!
                entriesVar.write(entries - keyToEvict)
                accessOrderVar.write(accessOrderVar.read() - keyToEvict)
                val evictions = evictionsVar.read()
                evictionsVar.write(evictions + 1)
                notifyListenersSync { it.onEviction(keyToEvict, entry.value, EvictionReason.SIZE) }
                logger.debug { "Evicted cache entry with key: $keyToEvict (strategy: ${config.evictionStrategy})" }
            }
        }
    }

    private fun notifyListenersSync(notify: (CacheListener<K, V>) -> Unit) {
        listeners.forEach { listener ->
            try {
                notify(listener)
            } catch (e: Exception) {
                logger.error(e) { "Error notifying cache listener" }
            }
        }
    }
}

/**
 * Cache entry with metadata.
 */
private data class CacheEntry<V>(
    val value: V,
    val createdAt: Instant,
    var lastAccessTime: Instant,
    var accessCount: Int,
)

/**
 * Configuration for cache behavior.
 *
 * @property maxSize Maximum number of entries in the cache (default: 100)
 * @property ttl Time-to-live for cache entries (default: 5 minutes)
 * @property evictionStrategy Strategy for evicting entries when cache is full (default: LRU)
 */
data class CacheConfig(
    val maxSize: Int = 100,
    val ttl: Duration? = 5.minutes,
    val evictionStrategy: EvictionStrategy = EvictionStrategy.LRU,
) {
    init {
        require(maxSize > 0) {
            "maxSize must be > 0, but was $maxSize"
        }
        require(ttl == null || ttl > Duration.ZERO) {
            "ttl must be > 0 or null, but was $ttl"
        }
    }
}

/**
 * Strategy for evicting cache entries when the cache is full.
 */
enum class EvictionStrategy {
    /**
     * Least Recently Used - evicts the entry that was accessed longest ago.
     */
    LRU,

    /**
     * Least Frequently Used - evicts the entry with the lowest access count.
     */
    LFU,

    /**
     * First In First Out - evicts the oldest entry.
     */
    FIFO,
}

/**
 * Reason for cache entry eviction.
 */
enum class EvictionReason {
    /**
     * Entry was evicted due to TTL expiration.
     */
    EXPIRED,

    /**
     * Entry was evicted due to cache size limit.
     */
    SIZE,
}

/**
 * Statistics for a cache.
 *
 * @property hits Number of cache hits
 * @property misses Number of cache misses
 * @property evictions Number of evictions
 * @property currentSize Current number of entries
 * @property maxSize Maximum number of entries
 */
data class CacheStatistics(
    val hits: Long,
    val misses: Long,
    val evictions: Long,
    val currentSize: Int,
    val maxSize: Int,
) {
    val hitRate: Double
        get() {
            val total = hits + misses
            return if (total > 0) {
                hits.toDouble() / total
            } else {
                0.0
            }
        }

    val missRate: Double
        get() {
            val total = hits + misses
            return if (total > 0) {
                misses.toDouble() / total
            } else {
                0.0
            }
        }

    val utilizationRate: Double
        get() = if (maxSize > 0) {
            currentSize.toDouble() / maxSize
        } else {
            0.0
        }
}

/**
 * Listener for cache events.
 */
interface CacheListener<K, V> {
    /**
     * Called when an entry is added to the cache.
     */
    fun onPut(key: K, value: V) {}

    /**
     * Called when an entry is removed from the cache.
     */
    fun onRemove(key: K, value: V) {}

    /**
     * Called when an entry is evicted from the cache.
     */
    fun onEviction(key: K, value: V, reason: EvictionReason) {}
}

/**
 * Creates a cache with DSL-style configuration.
 *
 * Example usage:
 * ```
 * val cache = cache<String, User> {
 *     maxSize = 200
 *     ttl = 10.minutes
 *     evictionStrategy = EvictionStrategy.LRU
 * }
 * ```
 */
fun <K, V> cache(configure: CacheConfigBuilder.() -> Unit): Cache<K, V> {
    val builder = CacheConfigBuilder()
    builder.configure()
    return Cache(builder.build())
}

/**
 * Builder for cache configuration.
 */
class CacheConfigBuilder {
    var maxSize: Int = 100
    var ttl: Duration? = 5.minutes
    var evictionStrategy: EvictionStrategy = EvictionStrategy.LRU

    fun build(): CacheConfig {
        return CacheConfig(
            maxSize = maxSize,
            ttl = ttl,
            evictionStrategy = evictionStrategy,
        )
    }
}

/**
 * Loading cache that automatically loads values on cache miss.
 *
 * @param K Key type
 * @param V Value type
 * @param config Configuration for cache behavior
 * @param loader Function to load values on cache miss
 *
 * Example usage:
 * ```
 * val userCache = LoadingCache<String, User>(
 *     config = CacheConfig(maxSize = 100, ttl = 5.minutes)
 * ) { userId ->
 *     userService.fetchUser(userId)
 * }
 *
 * val user = userCache.get("user123") // Automatically loads if not cached
 * ```
 */
class LoadingCache<K, V>(
    config: CacheConfig = CacheConfig(),
    private val loader: suspend (K) -> V,
) {
    private val cache = Cache<K, V>(config)

    /**
     * Gets a value from the cache, loading it if necessary.
     *
     * @param key The key to look up
     * @return The cached or loaded value
     */
    suspend fun get(key: K): V {
        return cache.getOrPut(key) {
            loader(key)
        }
    }

    /**
     * Puts a value in the cache.
     */
    suspend fun put(key: K, value: V) {
        cache.put(key, value)
    }

    /**
     * Removes a value from the cache.
     */
    suspend fun remove(key: K): V? {
        return cache.remove(key)
    }

    /**
     * Clears all entries from the cache.
     */
    suspend fun clear() {
        cache.clear()
    }

    /**
     * Gets the current statistics.
     */
    suspend fun statistics(): CacheStatistics {
        return cache.statistics()
    }

    /**
     * Adds a listener to be notified of cache events.
     */
    fun addListener(listener: CacheListener<K, V>) {
        cache.addListener(listener)
    }

    /**
     * Removes a listener.
     */
    fun removeListener(listener: CacheListener<K, V>) {
        cache.removeListener(listener)
    }
}

/**
 * Registry for managing multiple named caches.
 *
 * Example usage:
 * ```
 * val registry = CacheRegistry()
 *
 * val userCache = registry.getOrCreate<String, User>("users") {
 *     maxSize = 100
 *     ttl = 5.minutes
 * }
 *
 * val productCache = registry.getOrCreate<String, Product>("products") {
 *     maxSize = 500
 *     ttl = 10.minutes
 * }
 * ```
 */
class CacheRegistry {
    private val caches = mutableMapOf<String, Cache<*, *>>()

    /**
     * Gets an existing cache or creates a new one.
     */
    @Synchronized
    fun <K, V> getOrCreate(
        name: String,
        configure: (CacheConfigBuilder.() -> Unit)? = null,
    ): Cache<K, V> {
        @Suppress("UNCHECKED_CAST")
        return caches.getOrPut(name) {
            if (configure != null) {
                cache<K, V>(configure)
            } else {
                Cache()
            }
        } as Cache<K, V>
    }

    /**
     * Gets an existing cache by name.
     */
    @Suppress("UNCHECKED_CAST")
    fun <K, V> get(name: String): Cache<K, V>? {
        return caches[name] as? Cache<K, V>
    }

    /**
     * Removes a cache from the registry.
     */
    @Synchronized
    fun remove(name: String): Cache<*, *)? {
        return caches.remove(name)
    }

    /**
     * Clears all caches in the registry.
     */
    suspend fun clearAll() {
        caches.values.forEach { cache ->
            @Suppress("UNCHECKED_CAST")
            (cache as Cache<Any, Any>).clear()
        }
    }

    /**
     * Gets all cache names in the registry.
     */
    fun getNames(): Set<String> {
        return caches.keys.toSet()
    }
}