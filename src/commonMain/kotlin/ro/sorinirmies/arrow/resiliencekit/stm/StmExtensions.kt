package ro.sorinirmies.arrow.resiliencekit.stm

import arrow.fx.stm.STM
import arrow.fx.stm.TVar
import arrow.fx.stm.atomically

/**
 * Atomically reads and updates a [TVar] using the given function [f].
 *
 * **Example:**
 * ```kotlin
 * val counter = TVar.new(0)
 * val newValue = counter.modify { it + 1 } // 1
 * ```
 *
 * @param f a transformation function applied to the current value
 * @return the new value after applying [f]
 */
public suspend fun <A> TVar<A>.modify(f: (A) -> A): A = atomically {
    val current = this@modify.read()
    val new = f(current)
    this@modify.write(new)
    new
}

/**
 * Atomically reads the current value and replaces it with [value].
 *
 * **Example:**
 * ```kotlin
 * val tvar = TVar.new("old")
 * val previous = tvar.getAndSet("new") // "old"
 * ```
 *
 * @param value the new value to set
 * @return the old value before the swap
 */
public suspend fun <A> TVar<A>.getAndSet(value: A): A = atomically {
    val old = this@getAndSet.read()
    this@getAndSet.write(value)
    old
}

/**
 * Atomically updates the [TVar] only if the current value matches [expected].
 *
 * **Example:**
 * ```kotlin
 * val tvar = TVar.new("initial")
 * val swapped = tvar.compareAndSet("initial", "updated") // true
 * val failed = tvar.compareAndSet("initial", "other")    // false (already "updated")
 * ```
 *
 * @param expected the value to compare against
 * @param new the value to set if the comparison succeeds
 * @return `true` if the update occurred, `false` otherwise
 */
public suspend fun <A> TVar<A>.compareAndSet(expected: A, new: A): Boolean = atomically {
    val current = this@compareAndSet.read()
    if (current == expected) {
        this@compareAndSet.write(new)
        true
    } else {
        false
    }
}

/**
 * Creates a [TVar] with [initial] value and immediately uses it in a transaction.
 *
 * Convenience function for cases where you need a short-lived transactional variable.
 *
 * **Example:**
 * ```kotlin
 * val result = withTVar(0) { tvar ->
 *     tvar.write(tvar.read() + 42)
 *     tvar.read()
 * } // 42
 * ```
 *
 * @param initial the initial value for the [TVar]
 * @param block the STM transaction block that receives the created [TVar]
 * @return the result of the transaction
 */
public suspend fun <A, R> withTVar(initial: A, block: STM.(TVar<A>) -> R): R {
    val tvar = TVar.new(initial)
    return atomically { block(tvar) }
}

/**
 * Executes multiple STM operations atomically.
 *
 * A convenient alias for [atomically] that reads more naturally when
 * coordinating state changes across multiple [TVar]s.
 *
 * ```kotlin
 * stmTransaction {
 *     counter.write(counter.read() + 1)
 *     flag.write(true)
 * }
 * ```
 *
 * @param block the STM transaction block
 * @return the result of the transaction
 */
public suspend fun <R> stmTransaction(block: STM.() -> R): R = atomically(block)
