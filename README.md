# Arrow Resilience Kit

[![CI](https://github.com/sorinirimies/arrow-resilience-kit/actions/workflows/ci.yml/badge.svg)](https://github.com/sorinirimies/arrow-resilience-kit/actions/workflows/ci.yml)
[![Release](https://github.com/sorinirimies/arrow-resilience-kit/actions/workflows/release.yml/badge.svg)](https://github.com/sorinirimies/arrow-resilience-kit/actions/workflows/release.yml)
[![JitPack](https://jitpack.io/v/sorinirimies/arrow-resilience-kit.svg)](https://jitpack.io/#sorinirimies/arrow-resilience-kit)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

## Overview

Arrow Resilience Kit is a Kotlin Multiplatform library that provides production-ready resilience patterns built on [Arrow-kt](https://arrow-kt.io/). It offers composable, coroutine-first implementations of **Bulkhead**, **Cache**, **Circuit Breaker**, **Rate Limiter**, **Retry & Repeat**, **Saga**, **Time Limiter**, **Adaptive Limiter**, **Hedge**, and **STM Helpers** — plus a **Policy** combinator and **Flow** operators to compose them — everything you need to build fault-tolerant applications.

**Supported platforms:** JVM (17+), JavaScript (Browser & Node.js), Native (Linux x64, macOS x64/ARM64, iOS x64/ARM64/Simulator ARM64).

📚 **[Full API documentation (Dokka)](https://sorinirimies.github.io/arrow-resilience-kit/)**

## Why Arrow Resilience Kit?

| | |
|---|---|
| **Coroutine-native** | Every pattern is a `suspend` function or operates on one — no thread blocking, no callback soup. |
| **Composable** | Patterns wrap plain lambdas, so you freely nest `retry { circuitBreaker.execute { bulkhead.execute { ... } } }`. |
| **DSL builders** | Every config type has a `{ }` builder with sane, documented defaults. |
| **Observable** | Listeners and `*Statistics` snapshots (hit rate, rejection rate, state transitions) on every pattern. |
| **Named registries** | Manage many instances (per-endpoint circuit breakers, per-tenant rate limiters, ...) by key. |
| **Multiplatform** | One dependency, same API on JVM, JS, and Native — no platform-specific forks. |

## Installation

**Coordinates:**

| | |
|---|---|
| **Group ID** | `ro.sorinirmies.arrow` |
| **Artifact ID** | `arrow-resilience-kit` |
| **Version** | `0.4.4` |

### JitPack

```arrow-resilience-kit/build.gradle.kts#L1-L8
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.sorinirimies:arrow-resilience-kit:0.4.4")
}
```

### GitHub Packages

```arrow-resilience-kit/build.gradle.kts#L1-L13
repositories {
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/sorinirimies/arrow-resilience-kit")
        credentials {
            username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
            password = project.findProperty("gpr.token") as String? ?: System.getenv("GITHUB_PACKAGES_TOKEN")
        }
    }
}

implementation("ro.sorinirmies.arrow:arrow-resilience-kit:0.4.4")
```

> Requires a GitHub Personal Access Token with `read:packages` scope.

See [INSTALLATION.md](INSTALLATION.md) for Maven and additional details.

## Quick Start

```/dev/null/QuickStart.kt#L1-L24
import ro.sorinirmies.arrow.resiliencekit.*
import kotlin.time.Duration.Companion.seconds

// Retry a flaky call
val data = retryWithExponentialBackoff(retries = 3) {
    api.fetchData()
}

// Protect with a circuit breaker
val breaker = circuitBreaker {
    failureThreshold = 5
    resetTimeout = 30.seconds
}
val result = breaker.execute { api.fetchData() }

// Enforce a deadline
val fast = withTimeLimit(5.seconds) {
    slowService.query()
}

// Limit concurrency
val guarded = withBulkhead(BulkheadConfig(maxConcurrentCalls = 10)) {
    db.query()
}
```

## Patterns

### Bulkhead

Limits concurrent access to a resource using a semaphore, with optional wait-queue limits and timeouts.

```/dev/null/BulkheadExample.kt#L1-L21
// DSL builder
val bh = bulkhead {
    maxConcurrentCalls = 10
    maxWaitingCalls = 20
    maxWaitDuration = 5.seconds
}

val result = bh.execute { dbPool.query() }

// With fallback on rejection
val safe = bh.executeOrFallback(
    fallback = { cachedValue }
) { dbPool.query() }

// Registry for named bulkheads
val registry = BulkheadRegistry()
val apiBulkhead = registry.getOrCreate("api") {
    maxConcurrentCalls = 50
}
```

### Cache

Thread-safe cache with TTL, size limits, and configurable eviction strategies (LRU, LFU, FIFO).

```/dev/null/CacheExample.kt#L1-L22
// DSL builder
val userCache = cache<String, User> {
    maxSize = 1_000
    ttl = 5.minutes
    evictionStrategy = EvictionStrategy.LRU
}

// Get or compute
val user = userCache.getOrPut("user-123") {
    userService.fetch("user-123")
}

// Loading cache (auto-fetch on miss)
val loader = LoadingCache<String, User>(
    config = CacheConfig(maxSize = 500, ttl = 10.minutes),
    loader = { id -> userService.fetch(id) }
)
val u = loader.get("user-456") // fetches automatically

// Statistics
val stats = userCache.statistics() // hits, misses, hitRate, evictions
```

### Circuit Breaker

Prevents cascading failures with three states: **Closed** → **Open** → **Half-Open**.

```/dev/null/CircuitBreakerExample.kt#L1-L24
val cb = circuitBreaker {
    failureThreshold = 5        // failures before opening
    resetTimeout = 60.seconds   // wait before half-open
    halfOpenSuccessThreshold = 2 // successes to close again
}

// Basic execution
val result = cb.execute { externalService.call() }

// With fallback
val safe = cb.executeOrFallback(
    fallback = { ex -> defaultResponse }
) { externalService.call() }

// Inspect state
when (cb.currentState()) {
    CircuitBreakerState.Closed -> println("Healthy")
    CircuitBreakerState.Open -> println("Failing fast")
    CircuitBreakerState.HalfOpen -> println("Testing recovery")
}

// Listen for transitions
cb.addListener { old, new -> log.info("Circuit: $old -> $new") }
```

### Rate Limiter

Controls request throughput using a token-bucket algorithm. Also includes a sliding-window variant.

```/dev/null/RateLimiterExample.kt#L1-L16
// Token bucket
val rl = rateLimiter {
    permitsPerSecond = 10.0
    burstCapacity = 20
}

val result = rl.execute { api.request() }

// Sliding window
val sw = SlidingWindowRateLimiter(
    SlidingWindowConfig(maxRequests = 100, windowDuration = 1.seconds)
)
sw.execute { api.request() }

// Check stats
val stats = rl.statistics() // accepted, rejected, acceptanceRate
```

### Retry & Repeat

Flexible retry strategies with exponential, constant, Fibonacci, and capped backoff. Repeat helpers for polling.

```/dev/null/RetryRepeatExample.kt#L1-L32
// Exponential backoff
val data = retryWithExponentialBackoff(
    retries = 5,
    initialDelay = 100.milliseconds,
    maxDelay = 10.seconds,
    factor = 2.0
) { api.fetch() }

// Constant delay
val d2 = retryWithConstantDelay(retries = 3, delay = 500.milliseconds) { api.fetch() }

// Fibonacci backoff
val d3 = retryWithFibonacci(retries = 5, baseDelay = 100.milliseconds) { api.fetch() }

// Conditional retry
val d4 = retryIf(retries = 3, delay = 1.seconds, predicate = { it is IOException }) {
    api.fetch()
}

// Retry with fallback default
val d5 = retryOrDefault(retries = 3, defaultValue = emptyList()) { api.fetchList() }

// Repeat until condition
val finalValue = repeatUntil(
    maxAttempts = 10,
    delay = 1.seconds,
    predicate = { it.status == "COMPLETE" }
) { api.pollStatus() }

// Repeat and collect all results
val all = repeatAndCollect(times = 5, delay = 500.milliseconds) { api.sample() }
```

### Saga

Distributed transaction coordination with automatic compensation on failure.

```/dev/null/SagaExample.kt#L1-L25
val orderSaga = saga<OrderResult> {
    step(
        name = "Reserve inventory",
        action = { inventoryService.reserve(items) },
        compensation = { reservation -> inventoryService.release(reservation.id) }
    )
    step(
        name = "Charge payment",
        action = { paymentService.charge(amount) },
        compensation = { payment -> paymentService.refund(payment.txId) }
    )
    stepWithRetry(
        name = "Send confirmation",
        retries = 3,
        action = { emailService.send(confirmation) }
    )
}

when (val result = orderSaga.execute()) {
    is SagaResult.Success -> println("Order placed: ${result.result}")
    is SagaResult.Failure -> {
        println("Order failed: ${result.error.message}")
        println("Compensated ${result.compensatedSteps} steps")
    }
}
```

### Time Limiter

Timeout enforcement with fallback, retry-on-timeout, race, and batch execution.

```/dev/null/TimeLimiterExample.kt#L1-L19
val tl = timeLimiter {
    timeout = 5.seconds
}

// Execute or throw on timeout
val result = tl.execute { slowOp() }

// Execute or return null
val maybe = tl.executeOrNull { slowOp() }

// Execute with fallback
val safe = tl.executeOrFallback(fallback = { cachedValue }) { slowOp() }

// Retry on timeout
val retried = tl.executeWithRetry(retries = 3) { slowOp() }

// Convenience free function
val quick = withTimeLimit(2.seconds) { fastOp() }
```

### STM Helpers

Lock-free, composable transactional primitives built on Arrow's Software Transactional Memory.

```/dev/null/StmExample.kt#L1-L30
import ro.sorinirmies.arrow.resiliencekit.stm.*
import arrow.fx.stm.atomically

// Atomic counter
val counter = StmCounter.create(0L)
atomically { with(counter) { increment() } }
println(counter.value()) // 1

// Gauge with min/max tracking
val gauge = StmGauge.create(0.0)
atomically { with(gauge) { set(42.0) } }

// Typed state machine
val sm = StmStateMachine.create("IDLE")
atomically { with(sm) { transitionIf("IDLE", "RUNNING") } }

// Composable semaphore
val sem = StmSemaphore.create(5)
atomically { with(sem) { acquire() } }

// Sliding-window rate tracking
val window = StmRateWindow.create(windowMs = 1000L)
atomically { with(window) { tryAcquire(maxRequests = 10, nowMs = currentTimeMs) } }

// TVar extensions
val tvar = TVar.new(0)
tvar.modify { it + 1 }
tvar.compareAndSet(expected = 1, new = 2)
stmTransaction { tvar.write(tvar.read() + 10) }
```

### Policy (composition)

Wraps [Bulkhead], [CircuitBreaker], [RateLimiter], [TimeLimiter], and retry into one reusable, order-explicit chain. `a + b` means *a wraps b* — the leftmost policy is outermost.

```/dev/null/PolicyExample.kt#L1-L10
val policy = retryPolicy(retries = 3) + circuitBreaker.asPolicy() + bulkhead.asPolicy()
val result = policy.apply { api.fetchData() }

// Or fold a list of policies:
val combined = Policy.combine(retryPolicy(), circuitBreaker.asPolicy(), bulkhead.asPolicy())
```

### Flow operators

`Flow`-native counterparts of the same patterns, protecting a whole collection instead of a single call.

```/dev/null/FlowExample.kt#L1-L12
api.streamUpdates()
    .throughCircuitBreaker(circuitBreaker)
    .throughBulkhead(bulkhead)
    .retryWithBackoff(retries = 3)
    .collect { println(it) }
```

### Hedge (speculative requests)

Fires a duplicate attempt after a delay if the first hasn't completed yet; the first to *succeed* wins. Cuts tail latency before a slow call has even failed — complements retry, which only reacts after failure.

```/dev/null/HedgeExample.kt#L1-L6
val result = hedge(hedgeDelay = 50.milliseconds, maxHedges = 2) {
    api.fetchData() // must be safe to call more than once concurrently
}
```

### Adaptive Limiter

AIMD (additive-increase / multiplicative-decrease) concurrency limiter, in the spirit of TCP congestion control and Netflix's `concurrency-limits`. Grows its limit on success, shrinks it multiplicatively on failure or excess latency — no hand-tuned fixed capacity required.

```/dev/null/AdaptiveLimiterExample.kt#L1-L10
val limiter = AdaptiveLimiter.create(AdaptiveLimiterConfig(
    initialLimit = 20,
    latencyThreshold = 200.milliseconds, // slow-but-successful calls still count as overload
))
val result = limiter.execute { downstream.call() }
println(limiter.statistics().currentLimit)
```

### Chaos (fault injection for tests)

Wraps a real or fake call so it randomly fails and/or adds latency — exercise your retry/circuit-breaker/bulkhead usage without hand-rolling flaky fakes.

```/dev/null/ChaosExample.kt#L1-L8
val flaky = chaos({ failureRate = 0.3; latency = 50.milliseconds }) {
    realApi.call()
}
val result = retryWithExponentialBackoff(retries = 5) { flaky() }
```

### Shared State Store (extension point)

`SharedStateStore` is a minimal key-value seam for backing resilience state with a distributed store (Redis, a database, ...) instead of in-memory STM — useful when circuit-breaker/rate-limiter decisions need to be coordinated across a horizontally-scaled fleet. Ships with `InMemorySharedStateStore` (single-process reference implementation); wiring a real backend is left to your application.

## Configuration

Every pattern supports a DSL builder for configuration:

```/dev/null/ConfigExamples.kt#L1-L22
val cb = circuitBreaker {
    failureThreshold = 10
    resetTimeout = 60.seconds
    halfOpenSuccessThreshold = 3
}

val bh = bulkhead {
    maxConcurrentCalls = 25
    maxWaitingCalls = 50
    maxWaitDuration = 10.seconds
}

val rl = rateLimiter {
    permitsPerSecond = 100.0
    burstCapacity = 200
}

val tl = timeLimiter {
    timeout = 15.seconds
}
```

Named registries (`CircuitBreakerRegistry`, `BulkheadRegistry`, `RateLimiterRegistry`, `TimeLimiterRegistry`, `CacheRegistry`) let you manage instances by name and collect statistics across all instances.

## Dependencies

| Dependency | Version |
|---|---|
| Kotlin | 2.2.20 |
| Arrow-kt (Core, FX Coroutines, FX STM, Resilience) | 1.2.4 |
| Kotlinx Coroutines | 1.11.0 |
| Kotlinx DateTime | 0.8.0 |
| Kotlinx Serialization (JSON) | 1.9.0 |
| Kotlin Logging | 3.0.5 |
| Kotest (test) | 6.2.5 |
| Detekt | 1.23.8 |
| Dokka | 1.9.20 |

See [`gradle/libs.versions.toml`](gradle/libs.versions.toml) for the full version catalog — kept current by an automated nightly dependency-upgrade workflow.

### Optional: Micrometer metrics bridge (JVM only)

`MicrometerBridge` exports pattern statistics (call counts, rejection rates, circuit state, adaptive limits, ...) as Micrometer gauges. It's `compileOnly` on the JVM target — add `io.micrometer:micrometer-core` yourself to use it, everyone else pays nothing:

```/dev/null/MicrometerExample.kt#L1-L4
val registry = SimpleMeterRegistry() // or PrometheusMeterRegistry, etc.
MicrometerBridge.bindCircuitBreaker(registry, "orders-api", circuitBreaker)
MicrometerBridge.bindBulkhead(registry, "orders-api", bulkhead)
```

## Project Structure

```/dev/null/tree.txt#L1-L32
arrow-resilience-kit/
├── src/
│   ├── commonMain/kotlin/ro/sorinirmies/arrow/resiliencekit/
│   │   ├── stm/
│   │   │   ├── StmExtensions.kt
│   │   │   └── StmHelpers.kt
│   │   ├── Bulkhead.kt
│   │   ├── Cache.kt
│   │   ├── CircuitBreaker.kt
│   │   ├── RateLimiter.kt
│   │   ├── RetryRepeat.kt
│   │   ├── Saga.kt
│   │   └── TimeLimiter.kt
│   ├── commonTest/kotlin/
│   ├── jvmMain/kotlin/
│   ├── jsMain/kotlin/
│   └── nativeMain/kotlin/
├── docs/                    # Published Dokka HTML (GitHub Pages)
├── gradle/
│   └── libs.versions.toml
├── .github/workflows/       # Public CI/release/deps-update (GitHub Actions)
├── .gitea/workflows/        # Mirrored CI/release/deps-update (self-hosted Gitea)
├── scripts/                 # Nushell automation: release, upgrade, validation
├── config/
│   ├── detekt/
│   └── dokka/               # Custom Dokka stylesheet
├── build.gradle.kts
├── settings.gradle.kts
├── justfile
└── jitpack.yml
```

## Contributing

1. Fork the repo and create a feature branch.
2. Make changes and run `./gradlew test` (or `./gradlew allTests` for all platforms).
3. Commit using [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `docs:`, etc.).
4. Open a Pull Request against `main`.

See [CONTRIBUTING.md](CONTRIBUTING.md) for detailed guidelines.

## License

This project is licensed under the [MIT License](LICENSE).
