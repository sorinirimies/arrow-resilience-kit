package ro.sorinirmies.arrow.resiliencekit.stm

import arrow.fx.stm.STM
import arrow.fx.stm.TVar
import arrow.fx.stm.atomically

/**
 * STM-based counter that can be atomically incremented/decremented.
 *
 * Uses Arrow's Software Transactional Memory to provide lock-free,
 * composable atomic operations on a counter value.
 *
 * **Basic usage:**
 * ```kotlin
 * val counter = StmCounter.create(0L)
 * atomically { with(counter) { increment() } }
 * println(counter.value()) // 1
 * ```
 *
 * **Composing with other STM operations:**
 * ```kotlin
 * val counter = StmCounter.create(0L)
 * val gauge = StmGauge.create(0.0)
 *
 * atomically {
 *     val count = with(counter) { increment() }
 *     with(gauge) { set(count.toDouble()) }
 * }
 * ```
 *
 * **Batch updates inside a single transaction:**
 * ```kotlin
 * atomically {
 *     with(counter) {
 *         add(10)
 *         decrement()
 *     }
 * }
 * println(counter.value()) // 9
 * ```
 */
public class StmCounter private constructor(private val tvar: TVar<Long>) {

    public companion object {
        /**
         * Creates a new [StmCounter] with the given [initial] value.
         */
        public suspend fun create(initial: Long = 0L): StmCounter =
            StmCounter(TVar.new(initial))
    }

    /** Reads the current counter value within an STM transaction. */
    public fun STM.get(): Long = tvar.read()

    /** Sets the counter to [value] within an STM transaction. */
    public fun STM.set(value: Long) {
        tvar.write(value)
    }

    /** Increments the counter by 1 and returns the new value within an STM transaction. */
    public fun STM.increment(): Long {
        val v = tvar.read() + 1
        tvar.write(v)
        return v
    }

    /** Decrements the counter by 1 and returns the new value within an STM transaction. */
    public fun STM.decrement(): Long {
        val v = tvar.read() - 1
        tvar.write(v)
        return v
    }

    /** Adds [amount] to the counter and returns the new value within an STM transaction. */
    public fun STM.add(amount: Long): Long {
        val v = tvar.read() + amount
        tvar.write(v)
        return v
    }

    /** Reads the current counter value by running an atomic transaction. */
    public suspend fun value(): Long = atomically { tvar.read() }
}

/**
 * STM-based gauge that tracks a current value with min/max bounds.
 *
 * Automatically tracks the minimum and maximum values ever set,
 * useful for monitoring metrics like concurrent request counts.
 *
 * **Basic usage:**
 * ```kotlin
 * val gauge = StmGauge.create(0.0)
 * atomically { with(gauge) { set(42.0) } }
 * println(gauge.value()) // 42.0
 * ```
 *
 * **Tracking min/max:**
 * ```kotlin
 * val gauge = StmGauge.create(0.0)
 * atomically { with(gauge) { set(10.0) } }
 * atomically { with(gauge) { set(5.0) } }
 * atomically { with(gauge) { set(8.0) } }
 *
 * atomically {
 *     with(gauge) {
 *         println("Min: ${min()}") // 0.0
 *         println("Max: ${max()}") // 10.0
 *         println("Current: ${get()}") // 8.0
 *     }
 * }
 * ```
 */
public class StmGauge private constructor(
    private val valueTVar: TVar<Double>,
    private val minTVar: TVar<Double>,
    private val maxTVar: TVar<Double>,
) {

    public companion object {
        /**
         * Creates a new [StmGauge] with the given [initial] value.
         * The initial value is also used as the starting min and max.
         */
        public suspend fun create(initial: Double = 0.0): StmGauge = StmGauge(
            TVar.new(initial),
            TVar.new(initial),
            TVar.new(initial),
        )
    }

    /** Reads the current gauge value within an STM transaction. */
    public fun STM.get(): Double = valueTVar.read()

    /**
     * Sets the gauge to [value] within an STM transaction.
     * Automatically updates min/max tracking.
     */
    public fun STM.set(value: Double) {
        valueTVar.write(value)
        if (value < minTVar.read()) minTVar.write(value)
        if (value > maxTVar.read()) maxTVar.write(value)
    }

    /** Reads the minimum value ever recorded within an STM transaction. */
    public fun STM.min(): Double = minTVar.read()

    /** Reads the maximum value ever recorded within an STM transaction. */
    public fun STM.max(): Double = maxTVar.read()

    /** Reads the current gauge value by running an atomic transaction. */
    public suspend fun value(): Double = atomically { valueTVar.read() }
}

/**
 * STM-based state machine with typed states and atomic transitions.
 *
 * Provides composable, transactional state transitions that can be
 * combined with other STM operations in a single atomic block.
 *
 * **Basic usage:**
 * ```kotlin
 * enum class State { IDLE, RUNNING, STOPPED }
 *
 * val sm = StmStateMachine.create(State.IDLE)
 * atomically { with(sm) { transitionIf(State.IDLE, State.RUNNING) } } // true
 * println(sm.currentState()) // RUNNING
 * ```
 *
 * **Composing state transitions with counters:**
 * ```kotlin
 * val sm = StmStateMachine.create(State.IDLE)
 * val counter = StmCounter.create(0L)
 *
 * atomically {
 *     val transitioned = with(sm) { transitionIf(State.IDLE, State.RUNNING) }
 *     if (transitioned) {
 *         with(counter) { increment() }
 *     }
 * }
 * ```
 */
public class StmStateMachine<S> private constructor(
    private val stateTVar: TVar<S>,
) {

    public companion object {
        /**
         * Creates a new [StmStateMachine] with the given [initial] state.
         */
        public suspend fun <S> create(initial: S): StmStateMachine<S> =
            StmStateMachine(TVar.new(initial))
    }

    /** Reads the current state within an STM transaction. */
    public fun STM.current(): S = stateTVar.read()

    /** Unconditionally transitions to the [to] state within an STM transaction. */
    public fun STM.transition(to: S) {
        stateTVar.write(to)
    }

    /**
     * Transitions from [from] to [to] only if the current state equals [from].
     * Returns `true` if the transition occurred, `false` otherwise.
     */
    public fun STM.transitionIf(from: S, to: S): Boolean {
        return if (stateTVar.read() == from) {
            stateTVar.write(to)
            true
        } else {
            false
        }
    }

    /** Reads the current state by running an atomic transaction. */
    public suspend fun currentState(): S = atomically { stateTVar.read() }
}

/**
 * STM-based semaphore for concurrency limiting using transactional memory.
 *
 * Unlike mutex-based semaphores, STM semaphores can be composed with
 * other transactional operations atomically.
 *
 * **Basic usage:**
 * ```kotlin
 * val sem = StmSemaphore.create(5)
 * val acquired = atomically { with(sem) { acquire() } } // true
 * println(sem.availablePermits()) // 4
 * atomically { with(sem) { release() } }
 * println(sem.availablePermits()) // 5
 * ```
 *
 * **Composing with state machine:**
 * ```kotlin
 * val sem = StmSemaphore.create(3)
 * val sm = StmStateMachine.create(State.IDLE)
 *
 * atomically {
 *     val acquired = with(sem) { acquire() }
 *     if (acquired) {
 *         with(sm) { transition(State.RUNNING) }
 *     }
 * }
 * ```
 */
public class StmSemaphore private constructor(
    private val permitsTVar: TVar<Int>,
    private val maxPermits: Int,
) {

    public companion object {
        /**
         * Creates a new [StmSemaphore] with the given number of [permits].
         */
        public suspend fun create(permits: Int): StmSemaphore =
            StmSemaphore(TVar.new(permits), permits)
    }

    /**
     * Attempts to acquire a permit within an STM transaction.
     * Returns `true` if a permit was acquired, `false` if none are available.
     */
    public fun STM.acquire(): Boolean {
        val current = permitsTVar.read()
        return if (current > 0) {
            permitsTVar.write(current - 1)
            true
        } else {
            false
        }
    }

    /**
     * Releases a permit within an STM transaction.
     * Will not exceed [maxPermits].
     */
    public fun STM.release() {
        val current = permitsTVar.read()
        if (current < maxPermits) permitsTVar.write(current + 1)
    }

    /** Reads the number of available permits within an STM transaction. */
    public fun STM.available(): Int = permitsTVar.read()

    /** Reads the number of available permits by running an atomic transaction. */
    public suspend fun availablePermits(): Int = atomically { permitsTVar.read() }
}

/**
 * STM-based rate tracking window for sliding window rate limiting.
 *
 * Tracks request timestamps in a sliding window and provides atomic
 * acquire operations for rate limiting.
 *
 * **Basic usage:**
 * ```kotlin
 * val window = StmRateWindow.create(windowMs = 1000L)
 * val now = Clock.System.now().toEpochMilliseconds()
 * val allowed = atomically {
 *     with(window) { tryAcquire(maxRequests = 10, nowMs = now) }
 * } // true if under rate limit
 * ```
 *
 * **Checking current request count:**
 * ```kotlin
 * val window = StmRateWindow.create(windowMs = 5000L)
 * val now = Clock.System.now().toEpochMilliseconds()
 * atomically {
 *     with(window) {
 *         tryAcquire(maxRequests = 100, nowMs = now)
 *         val count = currentCount(nowMs = now)
 *         println("Requests in window: $count")
 *     }
 * }
 * ```
 */
public class StmRateWindow private constructor(
    private val requestsTVar: TVar<List<Long>>,
    private val windowMs: Long,
) {

    public companion object {
        /**
         * Creates a new [StmRateWindow] with the given [windowMs] duration in milliseconds.
         */
        public suspend fun create(windowMs: Long): StmRateWindow =
            StmRateWindow(TVar.new(emptyList()), windowMs)
    }

    /**
     * Attempts to acquire a request slot within an STM transaction.
     * Cleans up expired entries and checks against [maxRequests].
     *
     * @param maxRequests the maximum number of requests allowed in the window
     * @param nowMs the current time in milliseconds
     * @return `true` if the request was allowed, `false` if rate limited
     */
    public fun STM.tryAcquire(maxRequests: Int, nowMs: Long): Boolean {
        val requests = requestsTVar.read().filter { nowMs - it < windowMs }
        return if (requests.size < maxRequests) {
            requestsTVar.write(requests + nowMs)
            true
        } else {
            requestsTVar.write(requests) // cleanup old entries
            false
        }
    }

    /**
     * Returns the current number of active requests in the window within an STM transaction.
     * Also cleans up expired entries.
     */
    public fun STM.currentCount(nowMs: Long): Int {
        val requests = requestsTVar.read().filter { nowMs - it < windowMs }
        requestsTVar.write(requests)
        return requests.size
    }

    /** Resets the rate window, clearing all tracked requests within an STM transaction. */
    public fun STM.reset() {
        requestsTVar.write(emptyList())
    }
}
