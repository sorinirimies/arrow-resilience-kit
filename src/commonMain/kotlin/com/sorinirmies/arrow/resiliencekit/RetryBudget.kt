// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package com.sorinirmies.arrow.resiliencekit

import arrow.fx.stm.TVar
import arrow.fx.stm.atomically
import kotlinx.coroutines.delay
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger {}

/**
 * Retry budget: a system-wide guard against retry storms.
 *
 * Per-call retry (see [retryWithExponentialBackoff] and friends) is unaware of how much
 * *total* retry volume the rest of the system is currently generating. The well-known failure
 * mode: a downstream outage causes every caller to retry simultaneously, multiplying load on an
 * already-struggling service and making the outage worse instead of better.
 *
 * A [RetryBudget] caps total retry volume as a fraction of primary request volume (the same
 * token-bucket throttling strategy gRPC and Finagle use): every *successful, non-retry* call
 * deposits a small fraction of a token; every retry attempt withdraws one whole token. Once the
 * budget is exhausted, further retries are refused outright (failing fast) until enough
 * successful traffic replenishes it — this is what makes it self-healing: the budget recovers on
 * its own as soon as the system is healthy enough to produce the successes needed to refill it,
 * with no manual intervention or reset required.
 *
 * **Basic usage, sharing one budget across every caller of a given downstream:**
 * ```kotlin
 * val budget = RetryBudget.create() // one instance per downstream, shared across calls
 *
 * val result = retryWithBudget(budget, retries = 5) {
 *     callFlakyService()
 * }
 * ```
 *
 * **Using the primitive directly, to gate a custom retry loop:**
 * ```kotlin
 * if (budget.tryWithdraw()) {
 *     // perform the retry
 * } else {
 *     // budget exhausted -- fail fast instead of piling onto a struggling downstream
 * }
 * ```
 */
public class RetryBudget private constructor(
    private val tokensVar: TVar<Double>,
    private val config: RetryBudgetConfig,
) {
    /** Factory for [RetryBudget]. */
    public companion object {
        /** Creates a new [RetryBudget], starting fully topped up at [RetryBudgetConfig.maxTokens]. */
        public suspend fun create(config: RetryBudgetConfig = RetryBudgetConfig()): RetryBudget =
            RetryBudget(TVar.new(config.maxTokens), config)
    }

    /**
     * Records a successful, non-retry call, depositing [RetryBudgetConfig.tokenRatio] tokens
     * (capped at [RetryBudgetConfig.maxTokens]).
     */
    public suspend fun recordSuccess() {
        atomically {
            val current = tokensVar.read()
            tokensVar.write(minOf(config.maxTokens, current + config.tokenRatio))
        }
    }

    /**
     * Attempts to withdraw one token for a retry attempt.
     *
     * @return `true` if a token was available (the retry may proceed), `false` if the budget is
     *   exhausted (the caller should fail fast instead of retrying).
     */
    public suspend fun tryWithdraw(): Boolean = atomically {
        val current = tokensVar.read()
        if (current >= 1.0) {
            tokensVar.write(current - 1.0)
            true
        } else {
            false
        }
    }

    /** Current token balance, from `0.0` to [RetryBudgetConfig.maxTokens]. */
    public suspend fun availableTokens(): Double = atomically { tokensVar.read() }
}

/**
 * Configuration for a [RetryBudget].
 *
 * @property maxTokens The budget's ceiling -- also its starting balance.
 * @property tokenRatio Tokens deposited per successful, non-retry call. `0.1` means roughly one
 *   retry may be in flight per ten successful calls once the budget reaches steady state.
 */
public data class RetryBudgetConfig(
    public val maxTokens: Double = 10.0,
    public val tokenRatio: Double = 0.1,
) {
    init {
        require(maxTokens > 0.0) { "maxTokens must be > 0, but was $maxTokens" }
        require(tokenRatio > 0.0) { "tokenRatio must be > 0, but was $tokenRatio" }
    }
}

/**
 * Thrown by [retryWithBudget] when a retry would be warranted (the operation failed and attempts
 * remain) but [budget] has no tokens left -- failing fast instead of piling onto a downstream
 * that retries elsewhere in the system may already be overwhelming.
 *
 * @property attempt The attempt number (1-indexed) at which the budget ran out.
 */
public class RetryBudgetExhaustedException(
    public val attempt: Long,
    cause: Throwable,
) : Exception("Retry budget exhausted after attempt $attempt", cause)

/**
 * Like [retryWithExponentialBackoff], but every retry (not the initial attempt) must first
 * withdraw a token from [budget] -- see [RetryBudget] for why this matters. A successful call
 * (whether on the first attempt or a later one) deposits a token back into the budget.
 *
 * @param budget Shared [RetryBudget] -- use the *same* instance across every call site retrying
 *   against a given downstream, so the budget reflects total system-wide retry volume for it.
 * @param retries The maximum number of retry attempts after the initial attempt (defaults to 3)
 * @param initialDelay The initial delay before the first retry (defaults to 100 milliseconds)
 * @param maxDelay The maximum delay between retries (defaults to 1 minute)
 * @param factor The exponential backoff factor (defaults to 2.0)
 * @param block The suspend operation to execute
 *
 * @return The result of the successful execution
 * @throws RetryBudgetExhaustedException if the operation fails and the budget has no tokens left
 * @throws Exception the original exception, if retries are exhausted with budget still available
 */
public suspend fun <T> retryWithBudget(
    budget: RetryBudget,
    retries: Long = 3,
    initialDelay: Duration = 100.milliseconds,
    maxDelay: Duration = 1.minutes,
    factor: Double = 2.0,
    block: suspend () -> T,
): T {
    require(retries >= 0) { "retries must be >= 0, but was $retries" }
    require(initialDelay >= Duration.ZERO) { "initialDelay must be >= 0, but was $initialDelay" }
    require(factor > 0) { "factor must be > 0, but was $factor" }

    var attempt = 0L
    var delayDuration = initialDelay

    while (true) {
        try {
            val result = block()
            budget.recordSuccess()
            return result
        } catch (exception: kotlinx.coroutines.CancellationException) {
            throw exception
        } catch (exception: Exception) {
            if (attempt >= retries) {
                logger.warn(exception) { "Retries exhausted (attempt ${attempt + 1}/${retries + 1})" }
                throw exception
            }

            if (!budget.tryWithdraw()) {
                logger.warn(exception) { "Retry budget exhausted at attempt ${attempt + 1}/${retries + 1}" }
                throw RetryBudgetExhaustedException(attempt + 1, exception)
            }

            logger.debug { "Retrying after ${exception::class.simpleName} (attempt ${attempt + 1}/${retries + 1})" }
            delay(delayDuration)
            delayDuration = minOf(delayDuration * factor, maxDelay)
            attempt++
        }
    }
}
