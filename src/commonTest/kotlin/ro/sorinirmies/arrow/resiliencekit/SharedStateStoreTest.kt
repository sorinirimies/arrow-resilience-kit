// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package ro.sorinirmies.arrow.resiliencekit

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.js.JsName
import kotlin.test.Test

class SharedStateStoreTest {

    @JsName("getReturnsNullForAnAbsentKey")
    @Test
    fun `get returns null for an absent key`() = runTest {
        val store = InMemorySharedStateStore.create()

        store.get("missing") shouldBe null
    }

    @JsName("setThenGetRoundTrips")
    @Test
    fun `set then get round-trips`() = runTest {
        val store = InMemorySharedStateStore.create()

        store.set("k", "v1")

        store.get("k") shouldBe "v1"
    }

    @JsName("compareAndSetSucceedsOnlyWhenExpectedMatches")
    @Test
    fun `compareAndSet succeeds only when expected matches`() = runTest {
        val store = InMemorySharedStateStore.create()
        store.set("k", "v1")

        val wrongExpected = store.compareAndSet("k", expected = "wrong", new = "v2")
        val rightExpected = store.compareAndSet("k", expected = "v1", new = "v2")

        wrongExpected shouldBe false
        rightExpected shouldBe true
        store.get("k") shouldBe "v2"
    }

    @JsName("compareAndSetTreatsNullExpectedAsAbsent")
    @Test
    fun `compareAndSet treats null expected as absent`() = runTest {
        val store = InMemorySharedStateStore.create()

        val created = store.compareAndSet("k", expected = null, new = "v1")

        created shouldBe true
        store.get("k") shouldBe "v1"
    }

    @JsName("removeDeletesTheKey")
    @Test
    fun `remove deletes the key`() = runTest {
        val store = InMemorySharedStateStore.create()
        store.set("k", "v1")

        store.remove("k")

        store.get("k") shouldBe null
    }
}
