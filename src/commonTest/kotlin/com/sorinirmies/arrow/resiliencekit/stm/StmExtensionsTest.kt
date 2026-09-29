// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package ro.sorinirmies.arrow.resiliencekit.stm

import arrow.fx.stm.TVar
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.js.JsName
import kotlin.test.Test

class StmExtensionsTest {

    @JsName("modifyUpdatesValue")
    @Test
    fun `modify updates TVar value`() = runTest {
        val tvar = TVar.new(10)
        val result = tvar.modify { it + 5 }
        result shouldBe 15
    }

    @JsName("getAndSetReturnsOldValue")
    @Test
    fun `getAndSet returns old value`() = runTest {
        val tvar = TVar.new("old")
        val old = tvar.getAndSet("new")
        old shouldBe "old"
    }

    @JsName("getAndSetWritesNewValue")
    @Test
    fun `getAndSet writes new value`() = runTest {
        val tvar = TVar.new("old")
        tvar.getAndSet("new")
        val current = stmTransaction { tvar.read() }
        current shouldBe "new"
    }

    @JsName("compareAndSetSucceedsOnMatch")
    @Test
    fun `compareAndSet succeeds when value matches`() = runTest {
        val tvar = TVar.new(42)
        val result = tvar.compareAndSet(42, 99)
        result shouldBe true
        val current = stmTransaction { tvar.read() }
        current shouldBe 99
    }

    @JsName("compareAndSetFailsOnMismatch")
    @Test
    fun `compareAndSet fails when value does not match`() = runTest {
        val tvar = TVar.new(42)
        val result = tvar.compareAndSet(0, 99)
        result shouldBe false
        val current = stmTransaction { tvar.read() }
        current shouldBe 42
    }

    @JsName("withTVarCreatesAndUsesTransactionally")
    @Test
    fun `withTVar creates TVar and uses it in transaction`() = runTest {
        val result = withTVar(100) { tvar ->
            val v = tvar.read()
            tvar.write(v + 1)
            tvar.read()
        }
        result shouldBe 101
    }

    @JsName("stmTransactionExecutesAtomically")
    @Test
    fun `stmTransaction executes block atomically`() = runTest {
        val tvar = TVar.new(0)
        stmTransaction {
            tvar.write(tvar.read() + 10)
        }
        val result = stmTransaction { tvar.read() }
        result shouldBe 10
    }
}
