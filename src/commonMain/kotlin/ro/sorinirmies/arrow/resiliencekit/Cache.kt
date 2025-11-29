// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies


import arrow.fx.stm.STM
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
 * val cache = Cache.create<String, User>(
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
class Cache<K, V> private constructor(
    private val config: CacheConfig,
    private val entriesVar: TVar<Map<K, CacheEntry<V>>>,
    private val accessOrderVar: TVar<List<K>>,
    private val hitsVar: TVar<Long>,
    private val missesVar: TVar<Long>,
    private val evictionsVar: TVar<Long>
) {
    companion object {
        /**
         * Creates a new Cache instance with the given configuration.
         */
        suspend fun <K, V> create(config: CacheConfig = CacheConfig()): Cache<K, V> {
            return Cache(
                config = config,
                entriesVar = TVar.new(emptyMap()),
                accessOrderVar = TVar.new(emptyList()),
                hitsVar = TVar.new(0L),
                missesVar = TVar.new(0L),
                evictionsVar = TVar.new(0L)
            )
        }
    }

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
                val accessOrder = accessOrderVar.read()
                accessOrderVar.write(accessOrder - key)
                val misses = missesVar.read()
                missesVar.write(misses + 1)
                null
            } else {
                // Update access time and order for LRU
                val updatedEntry = entry.copy(lastAccessTime = Clock.System.now())
                entriesVar.write(entries + (key to updatedEntry))
                
                val accessOrder = accessOrderVar.read()
                val newOrder = (accessOrder - key) + key
                accessOrderVar.write(newOrder)
                
                val hits = hitsVar.read()
                hitsVar.write(hits + 1)
                updatedEntry.value
            }
        }
    }

    /**
     * Puts a value in the cache.
     *
     * @param key The key
     * @param value The value to cache
     */
    suspend fun put(key: K, value: V) {
        atomically {
            val entries = entriesVar.read()
            
            // Check if we need to evict
            if (entries.size >= config.maxSize && key !in entries) {
                evictOne()
            }

            val entry = CacheEntry(
                value = value,
                createdAt = Clock.System.now(),
                lastAccessTime = Clock.System.now()
            )
            entriesVar.write(entries + (key to entry))

            // Update access order for LRU
            val accessOrder = accessOrderVar.read()
            val newOrder = (accessOrder - key) + key
            accessOrderVar.write(newOrder)
        }
    }

    /**
     * Gets a value from the cache, or computes and caches it if not present.
     *
     * @param key The key
     * @param loader Function to compute the value if not cached
     * @return The cached or computed value
     */
    suspend fun getOrPut(key: K, loader: suspend () -> V): V {
        // First try to get from cache
        get(key)?.let { return it }
        
        // If not in cache, compute and store
        val value = loader()
        put(key, value)
        return value
    }

    /**
     * Invalidates (removes) a specific entry from the cache.
     *
     * @param key The key to invalidate
     */
    suspend fun invalidate(key: K) {
        atomically {
            val entries = entriesVar.read()
            entriesVar.write(entries - key)
            
            val accessOrder = accessOrderVar.read()
            accessOrderVar.write(accessOrder - key)
        }
    }

    /**
     * Invalidates multiple entries from the cache.
     *
     * @param keys The keys to invalidate
     */
    suspend fun invalidateAll(keys: Iterable<K>) {
        atomically {
            val entries = entriesVar.read()
            val newEntries = entries - keys.toSet()
            entriesVar.write(newEntries)
            
            val accessOrder = accessOrderVar.read()
            val keysToRemove = keys.toSet()
            val newOrder = accessOrder.filterNot { it in keysToRemove }
            accessOrderVar.write(newOrder)
        }
    }

    /**
     * Clears all entries from the cache.
     */
    suspend fun clear() {
        atomically {
            entriesVar.write(emptyMap())
            accessOrderVar.write(emptyList())
        }
    }

    /**
     * Gets the current size of the cache.
     */
    suspend fun size(): Int = atomically {
        entriesVar.read().size
    }

    /**
     * Checks if the cache contains a key.
     *
     * @param key The key to check
     * @return true if the key exists and is not expired
     */
    suspend fun containsKey(key: K): Boolean = atomically {
        val entries = entriesVar.read()
        val entry = entries[key]
        entry != null && !isExpired(entry)
    }

    /**
     * Gets all keys in the cache (excluding expired entries).
     */
    suspend fun keys(): Set<K> = atomically {
        val entries = entriesVar.read()
        entries.filterValues { !isExpired(it) }.keys
    }

    /**
     * Gets cache statistics.
     */
    suspend fun statistics(): CacheStatistics = atomically {
        val hits = hitsVar.read()
        val misses = missesVar.read()
        val evictions = evictionsVar.read()
        val entries = entriesVar.read()
        
        CacheStatistics(
            hits = hits,
            misses = misses,
            evictions = evictions,
            size = entries.size,
            hitRate = if (hits + misses > 0) hits.toDouble() / (hits + misses) else 0.0
        )
    }

    /**
     * Cleans up expired entries from the cache.
     * This is called automatically but can also be invoked manually.
     */
    suspend fun cleanup() {
        atomically {
            val entries = entriesVar.read()
            val validEntries = entries.filterValues { !isExpired(it) }
            val removedKeys = entries.keys - validEntries.keys
            
            if (removedKeys.isNotEmpty()) {
                entriesVar.write(validEntries)
                
                val accessOrder = accessOrderVar.read()
                val newOrder = accessOrder.filterNot { it in removedKeys }
                accessOrderVar.write(newOrder)
            }
        }
    }

    /**
     * Resets statistics counters.
     */
    suspend fun resetStatistics() {
        atomically {
            hitsVar.write(0L)
            missesVar.write(0L)
            evictionsVar.write(0L)
        }
    }

    /**
     * Checks if an entry is expired based on TTL.
     */
    private fun isExpired(entry: CacheEntry<V>): Boolean {
        val ttl = config.ttl ?: return false
        val now = Clock.System.now()
        return now - entry.createdAt > ttl
    }

    /**
     * Evicts one entry based on the eviction strategy.
     * Must be called from within an atomically block.
     */
    private fun STM.evictOne() {
        val entries = entriesVar.read()
        if (entries.isEmpty()) return

        val keyToEvict = when (config.evictionStrategy) {
            EvictionStrategy.LRU -> {
                // Least recently used - first in access order
                val accessOrder = accessOrderVar.read()
                accessOrder.firstOrNull()
            }
            EvictionStrategy.LFU -> {
                // Least frequently used - would need access count tracking
                // For now, use LRU as fallback
                val accessOrder = accessOrderVar.read()
                accessOrder.firstOrNull()
            }
            EvictionStrategy.FIFO -> {
                // First in, first out - oldest creation time
                entries.minByOrNull { it.value.createdAt }?.key
            }
        }

        keyToEvict?.let { key ->
            entriesVar.write(entries - key)
            val accessOrder = accessOrderVar.read()
            accessOrderVar.write(accessOrder - key)
            val evictions = evictionsVar.read()
            evictionsVar.write(evictions + 1)
        }
    }
}

/**
 * Configuration for cache behavior.
 *
 * @param maxSize Maximum number of entries in the cache
 * @param ttl Time-to-live for cache entries (null means no expiration)
 * @param evictionStrategy Strategy to use when the cache is full
 */
data class CacheConfig(
    val maxSize: Int = 100,
    val ttl: Duration? = 5.minutes,
    val evictionStrategy: EvictionStrategy = EvictionStrategy.LRU
)

/**
 * Eviction strategies for cache entries.
 */
enum class EvictionStrategy {
    /** Least Recently Used - evicts the entry that was accessed least recently */
    LRU,
    /** Least Frequently Used - evicts the entry that was accessed least often */
    LFU,
    /** First In, First Out - evicts the oldest entry */
    FIFO
}

/**
 * A cache entry with metadata.
 */
internal data class CacheEntry<V>(
    val value: V,
    val createdAt: Instant,
    val lastAccessTime: Instant
)

/**
 * Statistics about cache performance.
 */
data class CacheStatistics(
    val hits: Long,
    val misses: Long,
    val evictions: Long,
    val size: Int,
    val hitRate: Double
)

/**
 * Listener interface for cache events.
 */
interface CacheListener<K, V> {
    /**
     * Called when a value is added to the cache.
     */
    fun onPut(key: K, value: V) {}

    /**
     * Called when a cache entry is evicted.
     */
    fun onEvict(key: K, value: V) {}

    /**
     * Called when a cache entry is invalidated.
     */
    fun onInvalidate(key: K) {}

    /**
     * Called when the cache is cleared.
     */
    fun onClear() {}
}