// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package ro.sorinirmies.arrow.resiliencekit

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class CacheTest {

    @JsName("cacheStoresAndRetrievesValues")
    @Test
    fun `cache stores and retrieves values`() = runTest {
        val cache = Cache<String, String>()

        cache.put("key1", "value1")
        val result = cache.get("key1")

        result shouldBe "value1"
    }

    @JsName("cacheReturnsNullForMissingKeys")
    @Test
    fun `cache returns null for missing keys`() = runTest {
        val cache = Cache<String, String>()

        val result = cache.get("nonexistent")

        result shouldBe null
    }

    @JsName("cacheGetOrPutComputesValueOnMiss")
    @Test
    fun `cache getOrPut computes value on miss`() = runTest {
        val cache = Cache<String, String>()
        var computed = false

        val result = cache.getOrPut("key1") {
            computed = true
            "computed"
        }

        result shouldBe "computed"
        computed shouldBe true
    }

    @JsName("cacheGetOrPutReturnsCachedValueOnHit")
    @Test
    fun `cache getOrPut returns cached value on hit`() = runTest {
        val cache = Cache<String, String>()
        var computeCount = 0

        cache.put("key1", "cached")

        val result = cache.getOrPut("key1") {
            computeCount++
            "computed"
        }

        result shouldBe "cached"
        computeCount shouldBe 0
    }

    @JsName("cacheRemovesValues")
    @Test
    fun `cache removes values`() = runTest {
        val cache = Cache<String, String>()

        cache.put("key1", "value1")
        val removed = cache.remove("key1")
        val result = cache.get("key1")

        removed shouldBe "value1"
        result shouldBe null
    }

    @JsName("cacheRemoveReturnsNullForMissingKeys")
    @Test
    fun `cache remove returns null for missing keys`() = runTest {
        val cache = Cache<String, String>()

        val removed = cache.remove("nonexistent")

        removed shouldBe null
    }

    @JsName("cacheContainsKeyChecksExistence")
    @Test
    fun `cache containsKey checks existence`() = runTest {
        val cache = Cache<String, String>()

        cache.put("key1", "value1")

        cache.containsKey("key1") shouldBe true
        cache.containsKey("key2") shouldBe false
    }

    @JsName("cacheTracksSize")
    @Test
    fun `cache tracks size`() = runTest {
        val cache = Cache<String, String>()

        cache.size() shouldBe 0

        cache.put("key1", "value1")
        cache.size() shouldBe 1

        cache.put("key2", "value2")
        cache.size() shouldBe 2

        cache.remove("key1")
        cache.size() shouldBe 1
    }

    @JsName("cacheClearRemovesAllEntries")
    @Test
    fun `cache clear removes all entries`() = runTest {
        val cache = Cache<String, String>()

        cache.put("key1", "value1")
        cache.put("key2", "value2")
        cache.clear()

        cache.size() shouldBe 0
        cache.get("key1") shouldBe null
        cache.get("key2") shouldBe null
    }

    @JsName("cacheRespectsTTL")
    @Test
    fun `cache respects TTL`() = runTest {
        val testClock = TestClock()
        val cache = Cache<String, String>(
            config = CacheConfig(
                maxSize = 100,
                ttl = 100.milliseconds
            ),
            clock = testClock
        )

        cache.put("key1", "value1")
        cache.get("key1") shouldBe "value1"

        testClock.advance(150.milliseconds)

        cache.get("key1") shouldBe null
    }

    @JsName("cacheWithNullTTLNeverExpires")
    @Test
    fun `cache with null TTL never expires`() = runTest {
        val cache = Cache<String, String>(
            config = CacheConfig(
                maxSize = 100,
                ttl = null
            )
        )

        cache.put("key1", "value1")

        cache.get("key1") shouldBe "value1"
    }

    @JsName("cacheCleanUpRemovesExpiredEntries")
    @Test
    fun `cache cleanUp removes expired entries`() = runTest {
        val testClock = TestClock()
        val cache = Cache<String, String>(
            config = CacheConfig(
                maxSize = 100,
                ttl = 100.milliseconds
            ),
            clock = testClock
        )

        cache.put("key1", "value1")
        cache.put("key2", "value2")

        testClock.advance(150.milliseconds)

        val removed = cache.cleanUp()

        removed shouldBe 2
        cache.size() shouldBe 0
    }

    @JsName("cacheEvictsEntriesWhenFullUsingLRU")
    @Test
    fun `cache evicts entries when full using LRU`() = runTest {
        val cache = Cache<String, String>(
            config = CacheConfig(
                maxSize = 3,
                ttl = null,
                evictionStrategy = EvictionStrategy.LRU
            )
        )

        cache.put("key1", "value1")
        cache.put("key2", "value2")
        cache.put("key3", "value3")

        // Access key1 to make it recently used
        cache.get("key1")

        // This should evict key2 (least recently used)
        cache.put("key4", "value4")

        cache.containsKey("key1") shouldBe true
        cache.containsKey("key2") shouldBe false
        cache.containsKey("key3") shouldBe true
        cache.containsKey("key4") shouldBe true
    }

    @JsName("cacheEvictsEntriesWhenFullUsingLFU")
    @Test
    fun `cache evicts entries when full using LFU`() = runTest {
        val cache = Cache<String, String>(
            config = CacheConfig(
                maxSize = 3,
                ttl = null,
                evictionStrategy = EvictionStrategy.LFU
            )
        )

        cache.put("key1", "value1")
        cache.put("key2", "value2")
        cache.put("key3", "value3")

        // Access key1 and key3 multiple times
        cache.get("key1")
        cache.get("key1")
        cache.get("key3")

        // This should evict key2 (least frequently used)
        cache.put("key4", "value4")

        cache.containsKey("key1") shouldBe true
        cache.containsKey("key2") shouldBe false
        cache.containsKey("key3") shouldBe true
        cache.containsKey("key4") shouldBe true
    }

    @JsName("cacheEvictsEntriesWhenFullUsingFIFO")
    @Test
    fun `cache evicts entries when full using FIFO`() = runTest {
        val testClock = TestClock()
        val cache = Cache<String, String>(
            config = CacheConfig(
                maxSize = 3,
                ttl = null,
                evictionStrategy = EvictionStrategy.FIFO
            ),
            clock = testClock
        )

        cache.put("key1", "value1")
        testClock.advance(10.milliseconds)
        cache.put("key2", "value2")
        testClock.advance(10.milliseconds)
        cache.put("key3", "value3")

        // This should evict key1 (first in)
        cache.put("key4", "value4")

        cache.containsKey("key1") shouldBe false
        cache.containsKey("key2") shouldBe true
        cache.containsKey("key3") shouldBe true
        cache.containsKey("key4") shouldBe true
    }

    @JsName("cacheTracksStatistics")
    @Test
    fun `cache tracks statistics`() = runTest {
        val cache = Cache<String, String>()

        // Miss
        cache.get("key1")

        // Hit
        cache.put("key1", "value1")
        cache.get("key1")

        val stats = cache.statistics()
        stats.hits shouldBe 1
        stats.misses shouldBe 1
        stats.hitRate shouldBe 0.5
        stats.missRate shouldBe 0.5
    }

    @JsName("cacheTracksEvictions")
    @Test
    fun `cache tracks evictions`() = runTest {
        val cache = Cache<String, String>(
            config = CacheConfig(
                maxSize = 2,
                ttl = null
            )
        )

        cache.put("key1", "value1")
        cache.put("key2", "value2")
        cache.put("key3", "value3") // Causes eviction

        val stats = cache.statistics()
        stats.evictions shouldBe 1
    }

    @JsName("cacheListenerIsNotifiedOfPut")
    @Test
    fun `cache listener is notified of put`() = runTest {
        var putCalled = false
        var putKey: String? = null
        var putValue: String? = null

        val cache = Cache<String, String>()

        cache.addListener(object : CacheListener<String, String> {
            override fun onPut(key: String, value: String) {
                putCalled = true
                putKey = key
                putValue = value
            }
        })

        cache.put("key1", "value1")

        putCalled shouldBe true
        putKey shouldBe "key1"
        putValue shouldBe "value1"
    }

    @JsName("cacheListenerIsNotifiedOfRemove")
    @Test
    fun `cache listener is notified of remove`() = runTest {
        var removeCalled = false
        var removeKey: String? = null

        val cache = Cache<String, String>()

        cache.addListener(object : CacheListener<String, String> {
            override fun onRemove(key: String, value: String) {
                removeCalled = true
                removeKey = key
            }
        })

        cache.put("key1", "value1")
        cache.remove("key1")

        removeCalled shouldBe true
        removeKey shouldBe "key1"
    }

    @JsName("cacheListenerIsNotifiedOfEviction")
    @Test
    fun `cache listener is notified of eviction`() = runTest {
        var evictionCalled = false
        var evictionKey: String? = null
        var evictionReason: EvictionReason? = null

        val cache = Cache<String, String>(
            config = CacheConfig(
                maxSize = 1,
                ttl = null
            )
        )

        cache.addListener(object : CacheListener<String, String> {
            override fun onEviction(key: String, value: String, reason: EvictionReason) {
                evictionCalled = true
                evictionKey = key
                evictionReason = reason
            }
        })

        cache.put("key1", "value1")
        cache.put("key2", "value2") // Causes eviction

        evictionCalled shouldBe true
        evictionKey shouldBe "key1"
        evictionReason shouldBe EvictionReason.SIZE
    }

    @JsName("cacheConfigValidatesParameters")
    @Test
    fun `cache config validates parameters`() {
        shouldThrow<IllegalArgumentException> {
            CacheConfig(maxSize = 0)
        }

        shouldThrow<IllegalArgumentException> {
            CacheConfig(maxSize = -1)
        }

        shouldThrow<IllegalArgumentException> {
            CacheConfig(ttl = 0.milliseconds)
        }
    }

    @JsName("cacheDSLCreatesConfiguredCache")
    @Test
    fun `cache DSL creates configured cache`() = runTest {
        val cache = cache<String, String> {
            maxSize = 50
            ttl = 10.minutes
            evictionStrategy = EvictionStrategy.LRU
        }

        cache.put("key1", "value1")
        cache.get("key1") shouldBe "value1"
    }

    @JsName("cacheKeysReturnsAllKeys")
    @Test
    fun `cache keys returns all keys`() = runTest {
        val cache = Cache<String, String>()

        cache.put("key1", "value1")
        cache.put("key2", "value2")
        cache.put("key3", "value3")

        val keys = cache.keys()
        keys shouldBe setOf("key1", "key2", "key3")
    }

    @JsName("cacheValidKeysReturnsOnlyNonExpiredKeys")
    @Test
    fun `cache validKeys returns only non-expired keys`() = runTest {
        val testClock = TestClock()
        val cache = Cache<String, String>(
            config = CacheConfig(
                maxSize = 100,
                ttl = 100.milliseconds
            ),
            clock = testClock
        )

        cache.put("key1", "value1")
        testClock.advance(50.milliseconds)
        cache.put("key2", "value2")
        testClock.advance(60.milliseconds)

        // key1 should be expired, key2 should be valid
        val validKeys = cache.validKeys()
        validKeys shouldBe setOf("key2")
    }

    @JsName("cacheValidSizeReturnsCountOfNonExpiredEntries")
    @Test
    fun `cache validSize returns count of non-expired entries`() = runTest {
        val testClock = TestClock()
        val cache = Cache<String, String>(
            config = CacheConfig(
                maxSize = 100,
                ttl = 100.milliseconds
            ),
            clock = testClock
        )

        cache.put("key1", "value1")
        testClock.advance(50.milliseconds)
        cache.put("key2", "value2")
        testClock.advance(60.milliseconds)

        // key1 should be expired, key2 should be valid
        cache.validSize() shouldBe 1
        cache.size() shouldBe 2 // Total size includes expired
    }

    @JsName("cacheResetStatisticsClearsCounters")
    @Test
    fun `cache reset statistics clears counters`() = runTest {
        val cache = Cache<String, String>()

        cache.put("key1", "value1")
        cache.get("key1")

        var stats = cache.statistics()
        stats.hits shouldBe 1

        cache.resetStatistics()

        stats = cache.statistics()
        stats.hits shouldBe 0
        stats.misses shouldBe 0
        stats.evictions shouldBe 0
    }

    @JsName("cacheStatisticsCalculatesUtilizationRate")
    @Test
    fun `cache statistics calculates utilization rate`() = runTest {
        val cache = Cache<String, String>(
            config = CacheConfig(maxSize = 10)
        )

        cache.put("key1", "value1")
        cache.put("key2", "value2")
        cache.put("key3", "value3")

        val stats = cache.statistics()
        stats.utilizationRate shouldBe 0.3
    }

    // LoadingCache Tests

    @JsName("loadingCacheLoadsValueOnMiss")
    @Test
    fun `loading cache loads value on miss`() = runTest {
        var loadCount = 0

        val cache = LoadingCache<String, String>(
            config = CacheConfig(maxSize = 100)
        ) { key ->
            loadCount++
            "loaded-$key"
        }

        val result = cache.get("key1")

        result shouldBe "loaded-key1"
        loadCount shouldBe 1
    }

    @JsName("loadingCacheReturnsCachedValueOnHit")
    @Test
    fun `loading cache returns cached value on hit`() = runTest {
        var loadCount = 0

        val cache = LoadingCache<String, String>(
            config = CacheConfig(maxSize = 100)
        ) { key ->
            loadCount++
            "loaded-$key"
        }

        cache.get("key1")
        cache.get("key1") // Second call should hit cache

        loadCount shouldBe 1
    }

    @JsName("loadingCacheAllowsManualPut")
    @Test
    fun `loading cache allows manual put`() = runTest {
        val cache = LoadingCache<String, String>(
            config = CacheConfig(maxSize = 100)
        ) { key ->
            "loaded-$key"
        }

        cache.put("key1", "manual")
        val result = cache.get("key1")

        result shouldBe "manual"
    }

    @JsName("loadingCacheSupportsRemove")
    @Test
    fun `loading cache supports remove`() = runTest {
        val cache = LoadingCache<String, String>(
            config = CacheConfig(maxSize = 100)
        ) { key ->
            "loaded-$key"
        }

        cache.get("key1")
        val removed = cache.remove("key1")

        removed shouldBe "loaded-key1"
    }

    @JsName("loadingCacheSupportsClear")
    @Test
    fun `loading cache supports clear`() = runTest {
        val cache = LoadingCache<String, String>(
            config = CacheConfig(maxSize = 100)
        ) { key ->
            "loaded-$key"
        }

        cache.get("key1")
        cache.get("key2")
        cache.clear()

        val stats = cache.statistics()
        stats.currentSize shouldBe 0
    }

    @JsName("loadingCacheTracksStatistics")
    @Test
    fun `loading cache tracks statistics`() = runTest {
        val cache = LoadingCache<String, String>(
            config = CacheConfig(maxSize = 100)
        ) { key ->
            "loaded-$key"
        }

        cache.get("key1")
        cache.get("key1") // Hit

        val stats = cache.statistics()
        stats.hits shouldBe 1
        stats.misses shouldBe 1
    }

    // CacheRegistry Tests

    @JsName("cacheRegistryManagesMultipleCaches")
    @Test
    fun `CacheRegistry manages multiple caches`() = runTest {
        val registry = CacheRegistry()

        val cache1 = registry.getOrCreate<String, String>("users") {
            maxSize = 100
        }

        val cache2 = registry.getOrCreate<String, Int>("products") {
            maxSize = 200
        }

        cache1.put("user1", "Alice")
        cache2.put("product1", 42)

        cache1.get("user1") shouldBe "Alice"
        cache2.get("product1") shouldBe 42

        registry.getNames() shouldBe setOf("users", "products")
    }

    @JsName("cacheRegistryGetReturnsExistingCache")
    @Test
    fun `CacheRegistry get returns existing cache`() = runTest {
        val registry = CacheRegistry()

        registry.get<String, String>("nonexistent") shouldBe null

        val cache = registry.getOrCreate<String, String>("test")
        registry.get<String, String>("test") shouldBe cache
    }

    @JsName("cacheRegistryRemoveRemovesCache")
    @Test
    fun `CacheRegistry remove removes cache`() = runTest {
        val registry = CacheRegistry()

        val cache = registry.getOrCreate<String, String>("test")
        val removed = registry.remove("test")

        removed shouldBe cache
        registry.get<String, String>("test") shouldBe null
    }

    @JsName("cacheRegistryClearAllClearsAllCaches")
    @Test
    fun `CacheRegistry clearAll clears all caches`() = runTest {
        val registry = CacheRegistry()

        val cache1 = registry.getOrCreate<String, String>("cache1")
        val cache2 = registry.getOrCreate<String, String>("cache2")

        cache1.put("key1", "value1")
        cache2.put("key2", "value2")

        registry.clearAll()

        cache1.size() shouldBe 0
        cache2.size() shouldBe 0
    }

    @JsName("cacheHandlesConcurrentAccess")
    @Test
    fun `cache handles concurrent access`() = runTest {
        val cache = Cache<Int, String>(
            config = CacheConfig(maxSize = 100)
        )

        val jobs = (1..50).map { i ->
            launch {
                cache.put(i, "value$i")
                cache.get(i)
            }
        }

        jobs.forEach { it.join() }

        cache.size() shouldBe 50
    }

    @JsName("cacheListenerCanBeRemoved")
    @Test
    fun `cache listener can be removed`() = runTest {
        var putCount = 0

        val cache = Cache<String, String>()
        val listener = object : CacheListener<String, String> {
            override fun onPut(key: String, value: String) {
                putCount++
            }
        }

        cache.addListener(listener)
        cache.put("key1", "value1")
        putCount shouldBe 1

        cache.removeListener(listener)
        cache.put("key2", "value2")
        putCount shouldBe 1 // Should not increase
    }

    @JsName("cacheInvalidateRemovesEntry")
    @Test
    fun `cache invalidate removes entry`() = runTest {
        val cache = Cache<String, String>()
        cache.put("key1", "value1")
        cache.invalidate("key1")
        cache.get("key1") shouldBe null
    }

    @JsName("cacheInvalidateAllRemovesEntries")
    @Test
    fun `cache invalidateAll removes multiple entries`() = runTest {
        val cache = Cache<String, String>()
        cache.put("key1", "value1")
        cache.put("key2", "value2")
        cache.put("key3", "value3")
        cache.invalidateAll(listOf("key1", "key3"))
        cache.get("key1") shouldBe null
        cache.get("key2") shouldBe "value2"
        cache.get("key3") shouldBe null
    }
}
