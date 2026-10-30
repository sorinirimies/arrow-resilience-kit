// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlin.js.Promise
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * JavaScript/TypeScript-friendly facades over this library's suspend-based API.
 *
 * `@JsExport` can't export `suspend` functions at all -- not the functions themselves, nor
 * function-typed *parameters* with a `suspend` modifier -- so every exported class in this
 * (deliberately package-less, for flat top-level npm exports) source set wraps the real
 * (commonMain) pattern with plain, non-suspend methods that take/return [kotlin.js.Promise]
 * instead, which is just an ordinary type as far as JS export is concerned. From
 * JavaScript/TypeScript, a block parameter is just `() => Promise<T>` -- an ordinary async
 * function (or a sync one wrapped in `Promise.resolve(...)`), not a special callback type.
 *
 * `kotlin.time.Duration`-typed configuration (resetTimeout, maxWaitDuration, ...) is exposed here
 * as plain milliseconds (`number`), since `Duration` itself doesn't export to JS as anything a
 * JS/TS caller could construct directly.
 *
 * Not every pattern in this library has a JS facade -- see INTEROP.md for exactly what's covered
 * and what isn't (and why).
 */
// SupervisorJob is required here, not optional: without it, one call's failure cancels this
// scope's Job entirely, silently breaking every *other* in-flight or future call through it
// (structured concurrency propagates a failed child's cancellation to an un-supervised parent,
// and every runAsPromise call is a sibling child of this one shared scope). Verified this bug
// for real by smoke-testing the compiled JS output: a single rejected execute() call poisoned
// every subsequent call with JobCancellationException until this was added.
internal val jsInteropScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

/**
 * The standard `AbortSignal` (DOM / Node.js), declared here because Kotlin's DOM bindings don't
 * include it. Being `external`, it isn't emitted into the generated `.d.ts`, so TypeScript
 * resolves the name to the global `AbortSignal` type.
 */
public external interface AbortSignal {
    public val aborted: Boolean
    public val reason: Any?
    public fun addEventListener(type: String, listener: (Any?) -> Unit)
    public fun removeEventListener(type: String, listener: (Any?) -> Unit)
}

/**
 * Runs [block] on [jsInteropScope], exposing its suspend result as a [Promise].
 *
 * The promise rejects with exactly what the caller's own callback rejected with (see [jsError]),
 * not with this library's internal [JsCallbackException] wrapper. With a [signal], aborting it
 * cancels [block] (including any pending retry/backoff delay or permit wait) and rejects with
 * `signal.reason` -- the same contract as `fetch(url, { signal })`.
 */
internal fun <T> runAsPromise(signal: AbortSignal? = null, block: suspend () -> T): Promise<T> {
    JsLogging.ensureConfigured()
    if (signal?.aborted == true) return Promise.reject(signal.reason.unsafeCast<Throwable>())
    return Promise { resolve, reject ->
        val job = jsInteropScope.launch {
            try {
                resolve(block())
            } catch (e: Throwable) {
                reject(if (signal?.aborted == true) signal.reason.unsafeCast<Throwable>() else jsError(e).unsafeCast<Throwable>())
            }
        }
        if (signal != null) {
            val onAbort: (Any?) -> Unit = { job.cancel() }
            signal.addEventListener("abort", onAbort)
            job.invokeOnCompletion { signal.removeEventListener("abort", onAbort) }
        }
    }
}

/**
 * The error as the JS/TS caller knows it: the original rejection reason of their callback if
 * [e] is this library's internal [JsCallbackException] wrapper, otherwise [e] itself.
 */
internal fun jsError(e: Throwable): Any? = if (e is JsCallbackException) e.cause else e

/** Awaits a plain JS/TS callback (`() => Promise<T>`) from suspending Kotlin code. */
internal suspend fun <T> awaitBlock(block: () -> Promise<T>): T = try {
    block().await()
} catch (e: Exception) {
    throw e
} catch (e: Throwable) {
    // A rejected JS Promise's reason surfaces here as a plain Throwable, not a kotlin.Exception --
    // verified by smoke-testing the compiled output directly in Node (`e is Exception` was
    // false). Every pattern's internal failure accounting (CircuitBreaker.onFailure,
    // RetryBudget.tryWithdraw gating, ...) is written against `catch (e: Exception)`, so without
    // this rewrap, a JS caller's rejected promise would silently bypass that accounting entirely
    // while still, confusingly, correctly propagating as a rejected Promise to the JS caller --
    // the bug would have been invisible from JS, only showing up as wrong state/statistics.
    throw JsCallbackException(e.message, e)
}

/** Awaits a plain JS/TS callback that takes one argument (`(e: E) => Promise<T>`). */
internal suspend fun <E, T> awaitBlock(arg: E, block: (E) -> Promise<T>): T = try {
    block(arg).await()
} catch (e: Exception) {
    throw e
} catch (e: Throwable) {
    throw JsCallbackException(e.message, e)
}

/**
 * Wraps a non-[Exception] [Throwable] surfaced by a rejected JS Promise (see [awaitBlock]) so it
 * satisfies this library's internal `catch (e: Exception)` failure-handling throughout.
 */
internal class JsCallbackException(message: String?, cause: Throwable) : Exception(message, cause)

/** Converts a millisecond count from JS/TS into a [Duration]. */
internal val Double.millisAsDuration: Duration get() = this.milliseconds

/** Converts a millisecond count from JS/TS into a [Duration]. */
internal val Int.millisAsDuration: Duration get() = this.milliseconds
