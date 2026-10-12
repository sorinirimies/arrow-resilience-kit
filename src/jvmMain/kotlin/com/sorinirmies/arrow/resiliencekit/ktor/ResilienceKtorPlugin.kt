// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Sorin Albu-Irimies

package com.sorinirmies.arrow.resiliencekit.ktor

import com.sorinirmies.arrow.resiliencekit.BulkheadFullException
import com.sorinirmies.arrow.resiliencekit.BulkheadTimeoutException
import com.sorinirmies.arrow.resiliencekit.CircuitBreakerOpenException
import com.sorinirmies.arrow.resiliencekit.Policy
import com.sorinirmies.arrow.resiliencekit.RateLimitExceededException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Hook
import io.ktor.server.application.RouteScopedPlugin
import io.ktor.server.application.call
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.response.respondText
import kotlinx.coroutines.TimeoutCancellationException

/** Configuration for the [ArrowResilience] Ktor plugin. */
public class ArrowResilienceConfig {
    /** Policy wrapped around the downstream route handling. Defaults to a no-op. */
    public var policy: Policy = Policy.identity

    /**
     * Maps a resilience rejection to an HTTP status, or `null` to rethrow.
     * Defaults: rate limit → 429, open breaker / full bulkhead → 503, timeout → 504.
     */
    public var statusFor: (Throwable) -> HttpStatusCode? = ::defaultStatusFor
}

private fun defaultStatusFor(e: Throwable): HttpStatusCode? = when (e) {
    is RateLimitExceededException -> HttpStatusCode.TooManyRequests
    is CircuitBreakerOpenException, is BulkheadFullException, is BulkheadTimeoutException ->
        HttpStatusCode.ServiceUnavailable
    is TimeoutCancellationException -> HttpStatusCode.GatewayTimeout
    else -> null
}

private object ResilienceHook : Hook<suspend (ApplicationCall, suspend () -> Unit) -> Unit> {
    override fun install(
        pipeline: ApplicationCallPipeline,
        handler: suspend (ApplicationCall, suspend () -> Unit) -> Unit,
    ) {
        pipeline.intercept(ApplicationCallPipeline.Plugins) { handler(call) { proceed() } }
    }
}

/**
 * Route-scoped Ktor plugin that runs the rest of the call pipeline (the route
 * handler) through a resilience [Policy] and translates rejections to HTTP statuses.
 *
 * ```kotlin
 * routing {
 *     route("/payments") {
 *         install(ArrowResilience) {
 *             policy = rateLimiter.asPolicy() + circuitBreaker.asPolicy()
 *         }
 *         get { call.respondText("ok") }
 *     }
 * }
 * ```
 */
@Suppress("TopLevelPropertyNaming")
public val ArrowResilience: RouteScopedPlugin<ArrowResilienceConfig> = createRouteScopedPlugin("ArrowResilience", ::ArrowResilienceConfig) {
    val policy = pluginConfig.policy
    val statusFor = pluginConfig.statusFor
    on(ResilienceHook) { call, proceed ->
        try {
            policy.apply { proceed() }
        } catch (e: Throwable) {
            val status = statusFor(e) ?: throw e
            call.respondText(e.message ?: status.description, status = status)
        }
    }
}
