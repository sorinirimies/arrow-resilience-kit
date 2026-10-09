// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

import com.sorinirmies.arrow.resiliencekit.hedge
import kotlin.js.Promise

/**
 * JS/TS-friendly facade over [com.sorinirmies.arrow.resiliencekit.hedge] (speculative requests):
 * fires a duplicate attempt after a delay if the first hasn't completed yet; the first to
 * *succeed* wins. [block] may be invoked more than once, concurrently -- it must be safe to run
 * multiple times in parallel (e.g. an idempotent GET).
 *
 * ```ts
 * import { hedge } from "arrow-resilience-kit";
 *
 * const result = await hedge(() => api.fetchData(), 50, 2);
 * ```
 */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
public fun <T> hedge(block: () -> Promise<T>, hedgeDelayMillis: Double = 100.0, maxHedges: Int = 1): Promise<T> =
    runAsPromise {
        hedge(hedgeDelay = hedgeDelayMillis.millisAsDuration, maxHedges = maxHedges) { awaitBlock(block) }
    }
