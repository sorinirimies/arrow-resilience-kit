// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

import com.sorinirmies.arrow.resiliencekit.retryIf
import com.sorinirmies.arrow.resiliencekit.retryOrDefault
import com.sorinirmies.arrow.resiliencekit.retryWithConstantDelay
import com.sorinirmies.arrow.resiliencekit.retryWithExponentialBackoff
import kotlin.js.Promise

/**
 * JS/TS-friendly facades over this library's top-level retry functions.
 *
 * ```ts
 * import { retryWithExponentialBackoff } from "arrow-resilience-kit";
 *
 * const data = await retryWithExponentialBackoff(() => callFlakyService(), 5, 100);
 * ```
 */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
public object Retry {
    /** Retries [block] with exponential backoff and jitter. */
    public fun <T> withExponentialBackoff(
        block: () -> Promise<T>,
        retries: Int = 3,
        baseMillis: Double = 200.0,
        factor: Double = 2.0,
    ): Promise<T> = runAsPromise {
        retryWithExponentialBackoff(retries.toLong(), baseMillis.millisAsDuration, factor) { awaitBlock(block) }
    }

    /** Retries [block] with a fixed delay between attempts. */
    public fun <T> withConstantDelay(
        block: () -> Promise<T>,
        retries: Int = 3,
        delayMillis: Double = 1_000.0,
    ): Promise<T> = runAsPromise {
        retryWithConstantDelay(retries.toLong(), delayMillis.millisAsDuration) { awaitBlock(block) }
    }

    /** Retries [block] with exponential backoff, falling back to [fallback] (given the error's message) if every attempt fails. */
    public fun <T> orDefault(
        block: () -> Promise<T>,
        fallback: (message: String?) -> Promise<T>,
        retries: Int = 3,
        baseMillis: Double = 200.0,
        factor: Double = 2.0,
    ): Promise<T> = runAsPromise {
        retryOrDefault(
            retries = retries.toLong(),
            base = baseMillis.millisAsDuration,
            factor = factor,
            fallback = { error -> awaitBlock(error.message, fallback) },
        ) { awaitBlock(block) }
    }

    /** Retries [block] with a fixed delay, but only while [shouldRetry] (given the error's message) returns `true`. */
    public fun <T> ifMatches(
        block: () -> Promise<T>,
        shouldRetry: (message: String?) -> Boolean,
        retries: Int = 3,
        delayMillis: Double = 1_000.0,
    ): Promise<T> = runAsPromise {
        retryIf(
            retries = retries.toLong(),
            delay = delayMillis.millisAsDuration,
            shouldRetry = { error -> shouldRetry(error.message) },
        ) { awaitBlock(block) }
    }
}
