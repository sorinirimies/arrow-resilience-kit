# Arrow Resilience Kit

[![CI](https://github.com/sorinirimies/arrow-resilience-kit/actions/workflows/ci.yml/badge.svg)](https://github.com/sorinirimies/arrow-resilience-kit/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.sorinirimies.arrow/arrow-resilience-kit?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.sorinirimies.arrow/arrow-resilience-kit)
[![npm](https://img.shields.io/npm/v/arrow-resilience-kit?label=npm)](https://www.npmjs.com/package/arrow-resilience-kit)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

Coroutine-first resilience patterns for Kotlin Multiplatform, built on [Arrow](https://arrow-kt.io/): retry, circuit breakers, bulkheads, rate limiting, timeouts, hedging, failover, sagas and caching. They compose, report statistics, and run unchanged on every target.

**Targets:** JVM 17+ (and Android), JS (browser, Node.js), Wasm, Linux, Windows, macOS, iOS.
**Docs:** [API reference](https://sorinirimies.github.io/arrow-resilience-kit/) · [Java, Swift and JS/TS usage](INTEROP.md) · [Changelog](CHANGELOG.md)

## Install

Replace `<version>` with the version from the badges above.

### Kotlin / Java (JVM, Android, Multiplatform)

```kotlin
dependencies {
    implementation("io.github.sorinirimies.arrow:arrow-resilience-kit:<version>")
}
```

From Maven Central. JitPack, GitHub Packages and Maven setup: [INSTALLATION.md](INSTALLATION.md). Calling it from Java: [INTEROP.md](INTEROP.md#java).

### iOS (Swift Package Manager)

```swift
.package(url: "https://github.com/sorinirimies/arrow-resilience-kit", from: "<version>")
```

Ships a prebuilt XCFramework. Calling it from Swift: [INTEROP.md](INTEROP.md#swift-ios).

### JavaScript / TypeScript (npm)

```bash
npm install arrow-resilience-kit
```

A Promise API with TypeScript types, ES modules and CommonJS, no dependencies. Usage: [the package README](src/jsMain/npm/README.md).

## Quick start

```kotlin
import com.sorinirmies.arrow.resiliencekit.*
import kotlin.time.Duration.Companion.seconds

val breaker = circuitBreaker {
    failureThreshold = 5
    resetTimeout = 30.seconds
}

val data = retryWithBackoff(
    retries = 3,
    shouldRetry = { e, _ -> e is HttpException && (e.status == 429 || e.status >= 500) },
    delayFor = { e, _ -> (e as? HttpException)?.retryAfter }, // honor Retry-After
) {
    breaker.execute { withTimeLimit(5.seconds) { api.fetchData() } }
}
```

## Patterns

| Pattern | Use it to | Entry point |
|---|---|---|
| [Retry & repeat](#retry) | retry transient failures, poll until done | `retryWithBackoff`, `retryIf`, `repeatUntil` |
| [Retry budget](#retry-budget) | stop retries from amplifying an outage | `RetryBudget`, `retryWithBudget` |
| [Circuit breaker](#circuit-breaker) | fail fast while a dependency is down | `circuitBreaker { }`, `slidingWindowCircuitBreaker { }` |
| [Bulkhead](#bulkhead) | cap concurrent calls | `bulkhead { }`, `withBulkhead` |
| [Rate limiter](#rate-limiter) | cap throughput | `rateLimiter { }`, `SlidingWindowRateLimiter` |
| [Adaptive limiter](#adaptive-limiter) | find the concurrency limit from latency | `AdaptiveLimiter` |
| [Time limiter](#time-limiter) | enforce deadlines | `timeLimiter { }`, `withTimeLimit` |
| [Hedge](#hedge) | cut tail latency with a backup request | `hedge` |
| [Failover](#failover) | fall back across providers | `failover { }` |
| [Saga](#saga) | undo completed steps when a later one fails | `saga { }` |
| [Cache](#cache) | TTL cache with LRU/LFU/FIFO eviction | `cache { }`, `LoadingCache` |
| [Composition](#composition) | combine patterns, or apply them to a `Flow` | `Policy`, `throughCircuitBreaker` |
| [Testing](#chaos) | inject failures and latency | `chaos` |

Every pattern has a DSL builder with documented defaults, a `statistics()` snapshot, and a named registry (`CircuitBreakerRegistry`, `BulkheadRegistry`, ...) for per-endpoint or per-tenant instances.

### Retry

```kotlin
retryWithExponentialBackoff(retries = 5, base = 100.milliseconds) { api.fetch() }
retryWithCappedBackoff(retries = 8, base = 100.milliseconds, maxDelay = 10.seconds) { api.fetch() }
retryIf(retries = 3, delay = 1.seconds, shouldRetry = { it is IOException }) { api.fetch() }
retryOrDefault(retries = 3, fallback = { emptyList() }) { api.fetchList() }

repeatUntil(maxAttempts = 10, delay = 1.seconds, condition = { it.status == "COMPLETE" }) { api.pollStatus() }
```

Also `retryWithConstantDelay`, `retryWithFibonacci`, `retryIfWithExponentialBackoff` and `repeatAndCollect`. `retryWithBackoff` (see [Quick start](#quick-start)) takes all options at once: retry filter, per-error delay, cap, jitter and an `onRetry` hook.

### Retry budget

One budget per downstream, shared by every caller. Retries are allowed only up to a fraction of successful traffic, so a failing service isn't hammered by everyone retrying at once.

```kotlin
val budget = RetryBudget.create()
retryWithBudget(budget, retries = 5) { callFlakyService() }
```

### Circuit breaker

```kotlin
val cb = circuitBreaker {
    failureThreshold = 5          // consecutive failures before opening
    resetTimeout = 60.seconds     // then half-open: let trial calls through
    halfOpenSuccessThreshold = 2  // successes needed to close again
}
cb.executeOrFallback(fallback = { defaultResponse }) { service.call() }
cb.addListener { from, to -> log.info("circuit $from -> $to") }

// Trips on a failure rate instead: at least 50 % of the last 20 calls
val rate = slidingWindowCircuitBreaker {
    slidingWindowSize = 20
    minimumNumberOfCalls = 10
    failureRateThreshold = 0.5
}
```

### Bulkhead

```kotlin
val bh = bulkhead {
    maxConcurrentCalls = 10
    maxWaitingCalls = 20
    maxWaitDuration = 5.seconds
}
bh.execute { dbPool.query() }
```

`DispatcherBulkhead` runs admitted calls on a dedicated dispatcher; `ThreadPoolBulkhead` (JVM) owns a thread pool.

### Rate limiter

```kotlin
val rl = rateLimiter { permitsPerSecond = 10.0; burstCapacity = 20 }  // token bucket
rl.execute { api.request() }

val window = SlidingWindowRateLimiter(SlidingWindowConfig(maxRequests = 100, windowDuration = 1.seconds))
```

### Adaptive limiter

Additive-increase, multiplicative-decrease, like TCP congestion control: the limit grows while calls succeed quickly and shrinks on failures or slow calls.

```kotlin
val limiter = AdaptiveLimiter.create(AdaptiveLimiterConfig(initialLimit = 20, latencyThreshold = 200.milliseconds))
limiter.execute { downstream.call() }
```

### Time limiter

```kotlin
val tl = timeLimiter { timeout = 5.seconds }
tl.executeOrNull { slowOp() }
tl.executeOrFallback(fallback = { cachedValue }) { slowOp() }
tl.executeWithRetry(retries = 3) { slowOp() }
```

### Hedge

Starts another attempt if the first is slow; the first success wins. Only for calls that are safe to run twice.

```kotlin
hedge(hedgeDelay = 50.milliseconds, maxHedges = 2) { api.fetchData() }
```

### Failover

Each provider has its own circuit breaker: failing providers are skipped and re-tried after their reset timeout, so traffic returns to the preferred one once it recovers.

```kotlin
val transport = failover<Connection> {
    provider("websocket") { connectWebSocket() }
    provider("mqtt") { connectMqtt() }
    provider("http-polling") { connectHttpPolling() }
}
val connection = transport.execute() // FailoverExhaustedException if all are down

transport.startHealthProbing(scope, interval = 30.seconds) // re-check skipped providers without real traffic
```

### Saga

```kotlin
val order = saga<OrderResult> {
    step("Reserve inventory", action = { inventory.reserve(items) }, compensation = { inventory.release(it.id) })
    step("Charge payment", action = { payments.charge(amount) }, compensation = { payments.refund(it.txId) })
    stepWithRetry("Send confirmation", retries = 3, action = { email.send(confirmation) })
}
when (val result = order.execute()) {
    is SagaResult.Success -> println(result.result)
    is SagaResult.Failure -> println("failed, ${result.compensatedSteps} steps undone: ${result.error}")
}
```

### Cache

```kotlin
val users = cache<String, User> { maxSize = 1_000; ttl = 5.minutes; evictionStrategy = EvictionStrategy.LRU }
users.getOrPut("user-123") { userService.fetch("user-123") }

val loader = LoadingCache.create<String, User>(CacheConfig(maxSize = 500, ttl = 10.minutes)) { id -> userService.fetch(id) }
loader.get("user-456")
```

### Composition

`a + b` means *a wraps b*.

```kotlin
val policy = retryPolicy(retries = 3) + circuitBreaker.asPolicy() + bulkhead.asPolicy()
policy.apply { api.fetchData() }

api.streamUpdates()
    .throughCircuitBreaker(circuitBreaker)
    .retryWithBackoff(retries = 3)
    .collect { println(it) }
```

### Chaos

```kotlin
val flaky = chaos({ failureRate = 0.3; latency = 50.milliseconds }) { realApi.call() }
retryWithExponentialBackoff(retries = 5) { flaky() } // does your retry logic hold up?
```

Also included: lock-free [STM primitives](https://sorinirimies.github.io/arrow-resilience-kit/) (`StmCounter`, `StmSemaphore`, `StmStateMachine`, ...) built on Arrow STM.

## Integrations

All optional and `compileOnly`: add the library you use to your own build.

- **Micrometer** (JVM): `MicrometerBridge.bindCircuitBreaker(registry, "orders", cb)` exports statistics as gauges (also bulkhead, rate limiter, adaptive limiter, cache, time limiter, failover).
- **Ktor** (JVM): `install(ArrowResilience) { policy = ... }`; rejections map to 429, 503 and 504.
- **Spring Boot** (JVM): `arrow.resilience.circuit-breaker.<name>.*` (and `bulkhead`, `rate-limiter`, `time-limiter`) properties create a `ResilienceInstances` bean.
- **Redis** (JVM): `DistributedCircuitBreaker` and `DistributedRateLimiter` share state across instances via `RedisSharedStateStore` (Lettuce), or any `SharedStateStore`.
- **Config files:** `ResilienceConfig.fromJson(...)` or `fromProperties(...)`, then `ResilienceInstances.create(...)`.

## Development

```bash
just test         # JVM tests (just test-all: every platform)
just check-all    # quality gate: detekt, build, JVM + JS tests, npm package smoke test
just release 1.2.3
```

Commits follow [Conventional Commits](https://www.conventionalcommits.org/); the changelog is generated with git-cliff. Dependencies are upgraded nightly within their major version. See [CONTRIBUTING.md](CONTRIBUTING.md) and [RELEASING.md](RELEASING.md).

## License

[MIT](LICENSE)
