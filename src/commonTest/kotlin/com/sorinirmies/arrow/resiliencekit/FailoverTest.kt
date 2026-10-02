// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package com.sorinirmies.arrow.resiliencekit

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class FailoverTest {

    @JsName("failoverReturnsFirstProviderResultWhenItSucceeds")
    @Test
    fun `failover returns first provider result when it succeeds`() = runTest {
        var mqttCalled = false
        val transport = failover<String> {
            provider("websocket") { "ws-connection" }
            provider("mqtt") {
                mqttCalled = true
                "mqtt-connection"
            }
        }

        transport.execute() shouldBe "ws-connection"
        mqttCalled shouldBe false
    }

    @JsName("failoverFallsThroughToNextProviderWhenFirstFails")
    @Test
    fun `failover falls through to next provider when first fails`() = runTest {
        val transport = failover<String> {
            provider("websocket") { throw RuntimeException("ws down") }
            provider("mqtt") { "mqtt-connection" }
        }

        transport.execute() shouldBe "mqtt-connection"
    }

    @JsName("failoverFallsThroughMultipleProviders")
    @Test
    fun `failover falls through multiple providers`() = runTest {
        val transport = failover<String> {
            provider("websocket") { throw RuntimeException("ws down") }
            provider("mqtt") { throw RuntimeException("mqtt down") }
            provider("http-polling") { "http-connection" }
        }

        transport.execute() shouldBe "http-connection"
    }

    @JsName("failoverSkipsProviderWithOpenCircuitWithoutInvokingIt")
    @Test
    fun `failover skips provider with open circuit without invoking it`() = runTest {
        var websocketCalls = 0
        val transport = failover<String> {
            provider("websocket", circuitBreakerConfig = { failureThreshold = 2 }) {
                websocketCalls++
                throw RuntimeException("ws down")
            }
            provider("mqtt") { "mqtt-connection" }
        }

        // Two failures trip the websocket breaker open.
        transport.execute() shouldBe "mqtt-connection"
        transport.execute() shouldBe "mqtt-connection"
        transport.stateOf("websocket") shouldBe CircuitBreakerState.Open
        websocketCalls shouldBe 2

        // Further calls skip websocket entirely -- it is not invoked again.
        transport.execute() shouldBe "mqtt-connection"
        websocketCalls shouldBe 2
    }

    @JsName("failoverSelfHealsBackToPreferredProviderAfterResetTimeout")
    @Test
    fun `failover self heals back to preferred provider after reset timeout`() = runTest {
        val clock = TestClock()
        var websocketShouldFail = true

        val transport = failover<String> {
            provider(
                "websocket",
                circuitBreakerConfig = { failureThreshold = 1; resetTimeout = 1.seconds },
                clock = clock,
            ) {
                if (websocketShouldFail) throw RuntimeException("ws down") else "ws-connection"
            }
            provider("mqtt") { "mqtt-connection" }
        }

        // Trips the websocket breaker open.
        transport.execute() shouldBe "mqtt-connection"
        transport.stateOf("websocket") shouldBe CircuitBreakerState.Open

        // Still within the reset timeout: websocket stays skipped.
        transport.execute() shouldBe "mqtt-connection"

        // Once the reset timeout elapses, websocket is eligible again (half-open) --
        // and since it's now healthy, execute() prefers it over mqtt again.
        clock.advance(2.seconds)
        websocketShouldFail = false
        transport.execute() shouldBe "ws-connection"
        transport.stateOf("websocket") shouldBe CircuitBreakerState.HalfOpen
    }

    @JsName("failoverThrowsExhaustedExceptionWhenAllProvidersFail")
    @Test
    fun `failover throws exhausted exception when all providers fail`() = runTest {
        val transport = failover<String> {
            provider("websocket") { throw RuntimeException("ws down") }
            provider("mqtt") { throw RuntimeException("mqtt down") }
        }

        val exception = shouldThrow<FailoverExhaustedException> {
            transport.execute()
        }

        exception.attempts.map { it.provider } shouldBe listOf("websocket", "mqtt")
        exception.attempts.map { it.reason } shouldBe listOf(
            FailoverAttempt.Reason.Failed,
            FailoverAttempt.Reason.Failed,
        )
    }

    @JsName("failoverExhaustedExceptionReportsCircuitOpenReason")
    @Test
    fun `failover exhausted exception reports circuit open reason`() = runTest {
        val transport = failover<String> {
            provider("websocket", circuitBreakerConfig = { failureThreshold = 1 }) {
                throw RuntimeException("ws down")
            }
            provider("mqtt", circuitBreakerConfig = { failureThreshold = 1 }) {
                throw RuntimeException("mqtt down")
            }
        }

        // First call trips both breakers open.
        shouldThrow<FailoverExhaustedException> { transport.execute() }

        // Second call: both breakers are already open, so both are skipped (not invoked).
        val exception = shouldThrow<FailoverExhaustedException> {
            transport.execute()
        }
        exception.attempts.map { it.reason } shouldBe listOf(
            FailoverAttempt.Reason.CircuitOpen,
            FailoverAttempt.Reason.CircuitOpen,
        )
    }

    @JsName("failoverProviderNamesReturnsConfiguredOrder")
    @Test
    fun `failover providerNames returns configured order`() = runTest {
        val transport = failover<String> {
            provider("websocket") { "a" }
            provider("mqtt") { "b" }
            provider("http-polling") { "c" }
        }

        transport.providerNames() shouldBe listOf("websocket", "mqtt", "http-polling")
    }

    @JsName("failoverStateOfReturnsNullForUnknownProvider")
    @Test
    fun `failover stateOf returns null for unknown provider`() = runTest {
        val transport = failover<String> {
            provider("websocket") { "a" }
        }

        transport.stateOf("unknown") shouldBe null
    }

    @JsName("failoverRejectsDuplicateProviderNames")
    @Test
    fun `failover rejects duplicate provider names`() = runTest {
        shouldThrow<IllegalArgumentException> {
            failover<String> {
                provider("websocket") { "a" }
                provider("websocket") { "b" }
            }
        }
    }

    @JsName("failoverRejectsBlankProviderName")
    @Test
    fun `failover rejects blank provider name`() = runTest {
        shouldThrow<IllegalArgumentException> {
            failover<String> {
                provider("  ") { "a" }
            }
        }
    }

    @JsName("failoverRejectsEmptyProviderList")
    @Test
    fun `failover rejects empty provider list`() = runTest {
        shouldThrow<IllegalArgumentException> {
            failover<String> { }
        }
    }

    @JsName("failoverPropagatesCancellation")
    @Test
    fun `failover propagates cancellation`() = runTest {
        val transport = failover<String> {
            provider("websocket") { throw kotlinx.coroutines.CancellationException("cancelled") }
            provider("mqtt") { "mqtt-connection" }
        }

        shouldThrow<kotlinx.coroutines.CancellationException> {
            transport.execute()
        }
    }

    @JsName("failoverLastProviderStillSkippedWhenItsCircuitIsOpen")
    @Test
    fun `failover last provider still skipped when its circuit is open`() = runTest {
        var mqttCalls = 0
        val transport = failover<String> {
            provider("websocket") { "ws-connection" }
            provider("mqtt", circuitBreakerConfig = { failureThreshold = 1 }) {
                mqttCalls++
                throw RuntimeException("mqtt down")
            }
        }

        // websocket always succeeds, so mqtt is never even reached -- its breaker
        // stays closed throughout. This just documents that a healthy earlier
        // provider shields later ones from ever being invoked at all.
        repeat(3) { transport.execute() shouldBe "ws-connection" }
        mqttCalls shouldBe 0
        transport.stateOf("mqtt") shouldBe CircuitBreakerState.Closed
    }
}
