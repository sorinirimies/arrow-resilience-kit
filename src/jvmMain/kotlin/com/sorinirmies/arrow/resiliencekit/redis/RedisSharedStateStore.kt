// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package com.sorinirmies.arrow.resiliencekit.redis

import com.sorinirmies.arrow.resiliencekit.SharedStateStore
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.async.RedisAsyncCommands
import kotlinx.coroutines.future.await
import kotlin.time.Duration

/**
 * Redis-backed [SharedStateStore] (via Lettuce) for coordinating resilience
 * state across instances, e.g. with `DistributedRateLimiter` and
 * `DistributedCircuitBreaker`. `compareAndSet` is atomic through a Lua script.
 *
 * Lettuce is a `compileOnly` dependency: add `io.lettuce:lettuce-core` yourself.
 *
 * ```kotlin
 * val store = RedisSharedStateStore(client.connect().async(), keyPrefix = "myapp:", ttl = 1.hours)
 * ```
 *
 * @param commands Lettuce async commands
 * @param keyPrefix prefix applied to every key
 * @param ttl optional expiry refreshed on each write
 */
public class RedisSharedStateStore(
    private val commands: RedisAsyncCommands<String, String>,
    private val keyPrefix: String = "arrow-resilience:",
    private val ttl: Duration? = null,
) : SharedStateStore {

    private fun k(key: String) = keyPrefix + key

    override suspend fun get(key: String): String? = commands.get(k(key)).await()

    override suspend fun set(key: String, value: String) {
        val ttlMs = ttl?.inWholeMilliseconds
        if (ttlMs != null) {
            commands.psetex(k(key), ttlMs, value).await()
        } else {
            commands.set(k(key), value).await()
        }
    }

    override suspend fun compareAndSet(key: String, expected: String?, new: String): Boolean {
        val result = commands.eval<Long>(
            CAS_SCRIPT,
            ScriptOutputType.INTEGER,
            arrayOf(k(key)),
            if (expected == null) "0" else "1",
            expected ?: "",
            new,
            (ttl?.inWholeMilliseconds ?: 0L).toString(),
        ).await()
        return result == 1L
    }

    override suspend fun remove(key: String) {
        commands.del(k(key)).await()
    }

    private companion object {
        const val CAS_SCRIPT = """
local cur = redis.call('GET', KEYS[1])
local match
if ARGV[1] == '1' then match = (cur == ARGV[2]) else match = (cur == false) end
if not match then return 0 end
if tonumber(ARGV[4]) > 0 then
  redis.call('SET', KEYS[1], ARGV[3], 'PX', ARGV[4])
else
  redis.call('SET', KEYS[1], ARGV[3])
end
return 1
"""
    }
}
