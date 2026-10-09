// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

import com.sorinirmies.arrow.resiliencekit.RetryBudgetConfig
import kotlin.js.Promise

/**
 * JS/TS-friendly facade over [com.sorinirmies.arrow.resiliencekit.RetryBudget] /
 * `retryWithBudget` -- a system-wide guard against retry storms, shared across every call site
 * retrying against a given downstream.
 *
 * ```ts
 * import { RetryBudget } from "arrow-resilience-kit";
 *
 * const budget = await RetryBudget.create(); // one instance per downstream, shared across call sites
 * const result = await budget.retry(() => callFlakyService(), 5);
 * ```
 */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
public class RetryBudget internal constructor(
    private val delegate: com.sorinirmies.arrow.resiliencekit.RetryBudget,
) {
    public companion object {
        /** Creates a new retry budget. */
        public fun create(maxTokens: Double = 10.0, tokenRatio: Double = 0.1): Promise<RetryBudget> = runAsPromise {
            RetryBudget(
                com.sorinirmies.arrow.resiliencekit.RetryBudget.create(
                    RetryBudgetConfig(maxTokens = maxTokens, tokenRatio = tokenRatio)
                )
            )
        }
    }

    /** Current token balance. */
    public fun availableTokens(): Promise<Double> = runAsPromise { delegate.availableTokens() }

    /** Attempts to withdraw one retry token directly; resolves `true` if one was available. */
    public fun tryWithdraw(): Promise<Boolean> = runAsPromise { delegate.tryWithdraw() }

    /** Records a successful, non-retry call, depositing a token (capped at `maxTokens`). */
    public fun recordSuccess(): Promise<Unit> = runAsPromise { delegate.recordSuccess() }

    /**
     * Retries [block] with exponential backoff, gated by this budget -- see
     * [com.sorinirmies.arrow.resiliencekit.RetryBudget] for why. Rejects with an error whose
     * message starts with `"Retry budget exhausted"` if the budget runs out before [retries]
     * does (distinct from the original failure's error, which is what every other exhaustion
     * rejects with).
     */
    public fun <T> retry(
        block: () -> Promise<T>,
        retries: Int = 3,
        initialDelayMillis: Double = 100.0,
        maxDelayMillis: Double = 60_000.0,
        factor: Double = 2.0,
    ): Promise<T> = runAsPromise {
        com.sorinirmies.arrow.resiliencekit.retryWithBudget(
            budget = delegate,
            retries = retries.toLong(),
            initialDelay = initialDelayMillis.millisAsDuration,
            maxDelay = maxDelayMillis.millisAsDuration,
            factor = factor,
        ) { awaitBlock(block) }
    }
}
