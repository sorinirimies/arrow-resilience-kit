// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

import com.sorinirmies.arrow.resiliencekit.retryIf
import com.sorinirmies.arrow.resiliencekit.retryOrDefault
import com.sorinirmies.arrow.resiliencekit.retryWithConstantDelay
import com.sorinirmies.arrow.resiliencekit.retryWithBackoff
import com.sorinirmies.arrow.resiliencekit.retryWithExponentialBackoff
import kotlin.js.Promise
import kotlin.time.Duration

/**
 * Options for [Retry.withBackoff] -- a plain object; every field is optional.
 * (Declared for TypeScript in `src/jsMain/typescript/extra.d.ts`, appended to the package's `.d.ts`.)
 */
public external interface RetryOptions {
    /** Maximum retries after the first attempt. Default 3. */
    public val retries: Int?
    /** First backoff delay in ms. Default 200. */
    public val baseMillis: Double?
    /** Backoff growth per retry. Default 2. */
    public val factor: Double?
    /** Cap for the computed backoff in ms. Default: none. */
    public val maxDelayMillis: Double?
    /** Random spread of the computed backoff, 0..1. Default 0.1 (±10%). */
    public val jitter: Double?
    /** Retry this error? Gets the error exactly as your block rejected with it. Default: always. */
    public val shouldRetry: ((error: Any?, retry: Int) -> Boolean)?
    /** Exact delay in ms for this error (e.g. from a `Retry-After` header), or null/undefined for the backoff. */
    public val delayMillis: ((error: Any?, retry: Int) -> Double?)?
    /** Called before each wait. */
    public val onRetry: ((error: Any?, retry: Int, delayMillis: Double) -> Unit)?
    /** Aborting cancels the current attempt's wait and rejects with `signal.reason`. */
    public val signal: AbortSignal?
}

/**
 * JS/TS-friendly facades over this library's top-level retry functions.
 *
 * ```ts
 * import { Retry } from "arrow-resilience-kit";
 *
 * const data = await Retry.withBackoff(() => fetchJson(url), {
 *     retries: 5,
 *     maxDelayMillis: 10_000,
 *     shouldRetry: (e) => e.status === 429 || e.status >= 500,
 *     delayMillis: (e) => e.retryAfterMillis, // honor Retry-After; undefined = backoff
 *     signal,
 * });
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
        signal: AbortSignal? = null,
    ): Promise<T> = runAsPromise(signal) {
        retryWithExponentialBackoff(retries.toLong(), baseMillis.millisAsDuration, factor) { awaitBlock(block) }
    }

    /** Retries [block] with a fixed delay between attempts. */
    public fun <T> withConstantDelay(
        block: () -> Promise<T>,
        retries: Int = 3,
        delayMillis: Double = 1_000.0,
        signal: AbortSignal? = null,
    ): Promise<T> = runAsPromise(signal) {
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

    /**
     * Retries [block] with a fixed delay, but only while [shouldRetry] (given the error's message) returns `true`.
     * Prefer [withBackoff], whose `shouldRetry` gets the error itself (status codes, types, ...).
     */
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

    /**
     * Retries [block] with capped exponential backoff and jitter, only for errors
     * `options.shouldRetry` accepts, honoring a per-error delay (`options.delayMillis`, e.g.
     * `Retry-After`) and `options.signal`. See [RetryOptions].
     */
    public fun <T> withBackoff(block: () -> Promise<T>, options: RetryOptions? = null): Promise<T> =
        runAsPromise(options?.signal) {
            val shouldRetry = options?.shouldRetry
            val delayMillis = options?.delayMillis
            val onRetry = options?.onRetry
            retryWithBackoff(
                retries = (options?.retries ?: 3).toLong(),
                base = (options?.baseMillis ?: 200.0).millisAsDuration,
                factor = options?.factor ?: 2.0,
                maxDelay = options?.maxDelayMillis?.millisAsDuration ?: Duration.INFINITE,
                jitterFactor = options?.jitter ?: 0.1,
                shouldRetry = { e, retry -> shouldRetry?.invoke(jsError(e), retry.toInt()) ?: true },
                delayFor = { e, retry -> delayMillis?.invoke(jsError(e), retry.toInt())?.millisAsDuration },
                onRetry = { e, retry, wait -> onRetry?.invoke(jsError(e), retry.toInt(), wait.inWholeMilliseconds.toDouble()) },
            ) { awaitBlock(block) }
        }
}
