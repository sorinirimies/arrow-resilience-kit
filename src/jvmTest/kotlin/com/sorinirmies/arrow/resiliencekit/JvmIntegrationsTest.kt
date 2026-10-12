// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package com.sorinirmies.arrow.resiliencekit

import com.sorinirmies.arrow.resiliencekit.ktor.ArrowResilience
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class JvmIntegrationsTest {

    @Test
    fun threadPoolBulkheadRunsOnDedicatedThread() = runBlocking {
        ThreadPoolBulkhead.create("tp-test", BulkheadConfig(maxConcurrentCalls = 2)).use { b ->
            val name = b.execute { Thread.currentThread().name }
            name.startsWith("tp-test-") shouldBe true
        }
    }

    @Test
    fun ktorPluginMapsRejectionToStatus() = testApplication {
        val limiter = DistributedRateLimiter(
            InMemorySharedStateStore.create(),
            "k",
            maxRequests = 1,
            window = kotlin.time.Duration.parse("1m"),
        )
        application {
            routing {
                install(ArrowResilience) { policy = limiter.asPolicy() }
                get("/x") { call.respondText("ok") }
            }
        }
        client.get("/x").status shouldBe HttpStatusCode.OK
        client.get("/x").status shouldBe HttpStatusCode.TooManyRequests
    }
}
