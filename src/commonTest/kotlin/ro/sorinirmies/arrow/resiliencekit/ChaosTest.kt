// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package ro.sorinirmies.arrow.resiliencekit

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.js.JsName
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

class ChaosTest {

    @JsName("chaosPassesThroughWithZeroFailureRate")
    @Test
    fun `chaos passes through with zero failure rate`() = runTest {
        val op = chaos { "value" }

        op() shouldBe "value"
    }

    @JsName("chaosAlwaysFailsWithFailureRateOne")
    @Test
    fun `chaos always fails with failure rate one`() = runTest {
        val op = chaos(ChaosConfig(failureRate = 1.0)) { "value" }

        shouldThrow<ChaosException> { op() }
    }

    @JsName("chaosIsDeterministicWithASeededRandom")
    @Test
    fun `chaos is deterministic with a seeded random`() = runTest {
        val op = chaos(ChaosConfig(failureRate = 0.5, random = Random(42))) { "value" }
        val expected = Random(42).nextDouble() < 0.5

        if (expected) {
            shouldThrow<ChaosException> { op() }
        } else {
            op() shouldBe "value"
        }
    }

    @JsName("chaosDslBuilderConfiguresFailureRateAndLatency")
    @Test
    fun `chaos DSL builder configures failure rate and latency`() = runTest {
        val op = chaos({
            failureRate = 0.0
            latency = 10.milliseconds
        }) { "value" }

        op() shouldBe "value"
    }
}
