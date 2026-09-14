// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

/**
 * Extension point for sharing resilience state across process instances.
 *
 * Every pattern in this library keeps its state in-memory (via Arrow STM),
 * which is the right default for a single instance but doesn't coordinate
 * circuit-breaker/rate-limiter decisions across a horizontally-scaled fleet.
 * [SharedStateStore] is a minimal key-value abstraction that a future
 * pattern variant (or your own wrapper around [CircuitBreaker]/[RateLimiter])
 * can use to back its counters with Redis, a database, or any other shared
 * store, instead of a local [arrow.fx.stm.TVar].
 *
 * This module ships only [InMemorySharedStateStore] (the trivial, single-process
 * implementation, useful for tests). Wiring a real distributed backend (e.g.
 * Redis via Lettuce) is intentionally left to your application — this
 * interface is the seam to plug it in without forking the resilience logic.
 *
 * ```kotlin
 * class RedisSharedStateStore(private val client: RedisClient) : SharedStateStore {
 *     override suspend fun get(key: String): String? = ...
 *     override suspend fun set(key: String, value: String) { ... }
 *     override suspend fun compareAndSet(key: String, expected: String?, new: String): Boolean = ...
 * }
 * ```
 */
package ro.sorinirmies.arrow.resiliencekit

import arrow.fx.stm.TVar
import arrow.fx.stm.atomically

/**
 * A minimal shared, string-keyed key-value store for cross-instance
 * coordination of resilience state. See file-level docs for intended usage.
 */
public interface SharedStateStore {
    /** Reads the current value for [key], or `null` if absent. */
    public suspend fun get(key: String): String?

    /** Unconditionally sets [key] to [value]. */
    public suspend fun set(key: String, value: String)

    /**
     * Atomically sets [key] to [new] only if its current value equals [expected]
     * (`null` meaning "absent"). Returns whether the update happened — the
     * building block for distributed counters/state machines.
     */
    public suspend fun compareAndSet(key: String, expected: String?, new: String): Boolean

    /** Removes [key], if present. */
    public suspend fun remove(key: String)
}

/**
 * Single-process, STM-backed [SharedStateStore] implementation. Useful for
 * tests and as a reference implementation — it does **not** coordinate
 * across processes/machines.
 */
public class InMemorySharedStateStore private constructor(
    private val entriesVar: TVar<Map<String, String>>,
) : SharedStateStore {

    public companion object {
        /** Creates a new, empty [InMemorySharedStateStore]. */
        public suspend fun create(): InMemorySharedStateStore = InMemorySharedStateStore(TVar.new(emptyMap()))
    }

    override suspend fun get(key: String): String? = atomically { entriesVar.read()[key] }

    override suspend fun set(key: String, value: String) {
        atomically { entriesVar.write(entriesVar.read() + (key to value)) }
    }

    override suspend fun compareAndSet(key: String, expected: String?, new: String): Boolean = atomically {
        val current = entriesVar.read()
        if (current[key] == expected) {
            entriesVar.write(current + (key to new))
            true
        } else {
            false
        }
    }

    override suspend fun remove(key: String) {
        atomically { entriesVar.write(entriesVar.read() - key) }
    }
}
