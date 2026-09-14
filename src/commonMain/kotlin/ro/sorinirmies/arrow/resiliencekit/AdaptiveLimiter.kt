// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

/**
 * Adaptive concurrency limiter using an AIMD (additive-increase /
 * multiplicative-decrease) control loop, in the spirit of TCP congestion
 * control and Netflix's `concurrency-limits`.
 *
 * Unlike [Bulkhead], whose capacity is fixed, [AdaptiveLimiter] grows its
 * concurrency limit by [AdaptiveLimiterConfig.increaseStep] after each
 * successful call and shrinks it multiplicatively by
 * [AdaptiveLimiterConfig.decreaseFactor] after a failure or a call slower
 * than [AdaptiveLimiterConfig.latencyThreshold] — so it settles near the
 * concurrency level the downstream resource can actually sustain instead of
 * requiring a hand-tuned fixed number.
 *
 * Admission uses [arrow.fx.stm.STM.retry], blocking (without polling) until
 * a slot frees up or the limit grows.
 *
 * ```kotlin
 * val limiter = AdaptiveLimiter.create(AdaptiveLimiterConfig(initialLimit = 20))
 * val result = limiter.execute { downstream.call() }
 * ```
 */
package ro.sorinirmies.arrow.resiliencekit

import arrow.fx.stm.STM
import arrow.fx.stm.TVar
import arrow.fx.stm.atomically
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * Configuration for [AdaptiveLimiter].
 */
public data class AdaptiveLimiterConfig(
    /** Concurrency limit the limiter starts at. */
    public val initialLimit: Int = 20,
    /** Lower bound the limit will never shrink below. */
    public val minLimit: Int = 1,
    /** Upper bound the limit will never grow above. */
    public val maxLimit: Int = 200,
    /** How much the limit grows after each successful, on-time call. */
    public val increaseStep: Int = 1,
    /** Multiplicative factor applied to the limit on failure/overload (e.g. `0.5` halves it). */
    public val decreaseFactor: Double = 0.5,
    /** If a call takes longer than this, it's treated as an overload signal
     * even though it succeeded — mirrors latency-based congestion detection.
     * `null` disables latency-based back-off. */
    public val latencyThreshold: Duration? = null,
) {
    init {
        require(initialLimit > 0) { "initialLimit must be > 0, but was $initialLimit" }
        require(minLimit > 0) { "minLimit must be > 0, but was $minLimit" }
        require(maxLimit >= minLimit) { "maxLimit ($maxLimit) must be >= minLimit ($minLimit)" }
        require(initialLimit in minLimit..maxLimit) {
            "initialLimit ($initialLimit) must be within [$minLimit, $maxLimit]"
        }
        require(increaseStep > 0) { "increaseStep must be > 0, but was $increaseStep" }
        require(decreaseFactor > 0.0 && decreaseFactor < 1.0) {
            "decreaseFactor must be in (0.0, 1.0), but was $decreaseFactor"
        }
    }
}

/**
 * Snapshot of [AdaptiveLimiter] state.
 */
@Serializable
public data class AdaptiveLimiterStatistics(
    /** Current concurrency limit (adapts over time). */
    public val currentLimit: Int,
    /** Number of calls currently in flight. */
    public val inFlight: Int,
    /** Total number of calls admitted so far. */
    public val totalCalls: Long,
    /** Total number of calls that triggered a multiplicative decrease. */
    public val overloadEvents: Long,
) {
    /** Current utilization as a ratio from 0.0 to 1.0. */
    public val utilizationRate: Double
        get() = if (currentLimit > 0) inFlight.toDouble() / currentLimit else 0.0
}

/**
 * AIMD-based adaptive concurrency limiter. See file-level docs for details.
 */
public class AdaptiveLimiter private constructor(
    public val config: AdaptiveLimiterConfig,
    private val limitVar: TVar<Int>,
    private val inFlightVar: TVar<Int>,
    private val totalCallsVar: TVar<Long>,
    private val overloadEventsVar: TVar<Long>,
    private val clock: Clock,
) {

    public companion object {
        /** Creates a new [AdaptiveLimiter] with the given [config]. */
        public suspend fun create(
            config: AdaptiveLimiterConfig = AdaptiveLimiterConfig(),
            clock: Clock = Clock.System,
        ): AdaptiveLimiter = AdaptiveLimiter(
            config = config,
            limitVar = TVar.new(config.initialLimit),
            inFlightVar = TVar.new(0),
            totalCallsVar = TVar.new(0L),
            overloadEventsVar = TVar.new(0L),
            clock = clock,
        )
    }

    /** Returns a snapshot of the current limiter statistics. */
    public suspend fun statistics(): AdaptiveLimiterStatistics = atomically {
        AdaptiveLimiterStatistics(
            currentLimit = limitVar.read(),
            inFlight = inFlightVar.read(),
            totalCalls = totalCallsVar.read(),
            overloadEvents = overloadEventsVar.read(),
        )
    }

    /** Returns the current dynamic concurrency limit. */
    public suspend fun currentLimit(): Int = atomically { limitVar.read() }

    private fun STM.acquire() {
        val limit = limitVar.read()
        val inFlight = inFlightVar.read()
        if (inFlight < limit) {
            inFlightVar.write(inFlight + 1)
            totalCallsVar.write(totalCallsVar.read() + 1)
        } else {
            retry()
        }
    }

    private fun STM.release(overloaded: Boolean) {
        val inFlight = inFlightVar.read()
        inFlightVar.write((inFlight - 1).coerceAtLeast(0))

        val limit = limitVar.read()
        val newLimit = if (overloaded) {
            overloadEventsVar.write(overloadEventsVar.read() + 1)
            (limit * config.decreaseFactor).toInt().coerceAtLeast(config.minLimit)
        } else {
            (limit + config.increaseStep).coerceAtMost(config.maxLimit)
        }
        limitVar.write(newLimit)
    }

    /**
     * Executes [block] under the adaptive concurrency limit, blocking (without
     * polling) until a slot is available.
     *
     * @param block the operation to execute
     * @return the result of the operation
     * @throws Throwable whatever [block] throws; a thrown exception always counts as overload
     */
    public suspend fun <T> execute(block: suspend () -> T): T {
        atomically { acquire() }

        val start = clock.now()
        return try {
            val result = block()
            val elapsed = clock.now() - start
            val overloaded = config.latencyThreshold != null && elapsed > config.latencyThreshold
            atomically { release(overloaded = overloaded) }
            result
        } catch (e: Throwable) {
            atomically { release(overloaded = true) }
            throw e
        }
    }
}

/**
 * DSL for creating a configured [AdaptiveLimiter].
 */
public suspend fun adaptiveLimiter(configure: AdaptiveLimiterConfigBuilder.() -> Unit): AdaptiveLimiter {
    val config = AdaptiveLimiterConfigBuilder().apply(configure).build()
    return AdaptiveLimiter.create(config)
}

/**
 * Builder for [AdaptiveLimiterConfig].
 */
public class AdaptiveLimiterConfigBuilder {
    /** Concurrency limit the limiter starts at. */
    public var initialLimit: Int = 20

    /** Lower bound the limit will never shrink below. */
    public var minLimit: Int = 1

    /** Upper bound the limit will never grow above. */
    public var maxLimit: Int = 200

    /** How much the limit grows after each successful, on-time call. */
    public var increaseStep: Int = 1

    /** Multiplicative factor applied to the limit on failure/overload. */
    public var decreaseFactor: Double = 0.5

    /** Latency beyond which a successful call is still treated as overload. */
    public var latencyThreshold: Duration? = null

    /** Builds the [AdaptiveLimiterConfig] from the current builder state. */
    public fun build(): AdaptiveLimiterConfig = AdaptiveLimiterConfig(
        initialLimit = initialLimit,
        minLimit = minLimit,
        maxLimit = maxLimit,
        increaseStep = increaseStep,
        decreaseFactor = decreaseFactor,
        latencyThreshold = latencyThreshold,
    )
}
