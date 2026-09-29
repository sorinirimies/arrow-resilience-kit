// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

/**
 * Hedged (speculative) request pattern.
 *
 * Fires a duplicate attempt after a delay if the first attempt hasn't
 * completed yet, and returns whichever attempt finishes first successfully.
 * Complements retry (which re-runs *after* failure) by cutting tail latency
 * *before* a slow call has even failed.
 *
 * ```kotlin
 * val result = hedge(HedgeConfig(hedgeDelay = 50.milliseconds, maxHedges = 2)) {
 *     api.fetchData()
 * }
 * ```
 */
package ro.sorinirmies.arrow.resiliencekit

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Configuration for the hedged-request pattern.
 */
public data class HedgeConfig(
    /** Delay before firing the first hedge attempt after the primary starts. Subsequent
     * hedges (if [maxHedges] > 1) fire at `hedgeDelay * (n + 1)`. */
    public val hedgeDelay: Duration = 100.milliseconds,
    /** Maximum number of additional (hedge) attempts beyond the primary one. */
    public val maxHedges: Int = 1,
) {
    init {
        require(hedgeDelay > Duration.ZERO) { "hedgeDelay must be > 0, but was $hedgeDelay" }
        require(maxHedges >= 0) { "maxHedges must be >= 0, but was $maxHedges" }
    }
}

/**
 * Executes [block] using the hedged-request pattern: the primary attempt
 * starts immediately, and up to [HedgeConfig.maxHedges] additional attempts
 * start at increasing multiples of [HedgeConfig.hedgeDelay] if the previous
 * attempts haven't completed yet. The first attempt to *succeed* wins; the
 * rest are cancelled. If every attempt fails, the last failure is rethrown.
 *
 * [block] may be invoked more than once and concurrently — it must be safe
 * to run multiple times in parallel (e.g. an idempotent GET request).
 *
 * @param config hedge timing configuration
 * @param block the operation to execute, possibly multiple times concurrently
 * @return the result of the first attempt to succeed
 * @throws Throwable the last observed failure if every attempt fails
 */
public suspend fun <T> hedge(config: HedgeConfig, block: suspend () -> T): T = coroutineScope {
    val attempts = (0..config.maxHedges).map { index ->
        async {
            if (index > 0) delay(config.hedgeDelay * index)
            runCatching { block() }
        }
    }

    val pending = attempts.toMutableList()
    var lastError: Throwable? = null
    try {
        while (pending.isNotEmpty()) {
            val (completed, result) = select {
                pending.forEach { deferred -> deferred.onAwait { r -> deferred to r } }
            }
            pending.remove(completed)
            if (result.isSuccess) {
                return@coroutineScope result.getOrThrow()
            } else {
                lastError = result.exceptionOrNull()
            }
        }
        throw lastError ?: IllegalStateException("Hedge: no attempts were executed")
    } finally {
        pending.forEach { it.cancel() }
    }
}

/**
 * Executes [block] using the hedged-request pattern with named parameters
 * instead of a [HedgeConfig] instance — convenient for one-off calls.
 *
 * @see hedge
 */
public suspend fun <T> hedge(
    hedgeDelay: Duration = 100.milliseconds,
    maxHedges: Int = 1,
    block: suspend () -> T,
): T = hedge(HedgeConfig(hedgeDelay = hedgeDelay, maxHedges = maxHedges), block)
