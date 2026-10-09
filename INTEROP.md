# Language Interop Guide

Arrow Resilience Kit is a Kotlin Multiplatform library, so "how do I use it" has
a different, honest answer per consuming language. This doc covers Java, Swift
(iOS), and JavaScript/TypeScript — Kotlin itself is already fully covered by the
[README](README.md)'s Quick Start and Patterns sections, so it isn't repeated
here.

Everything below (Java and Swift examples) was actually compiled and run
against this library, not written from memory — where there's a real rough
edge in the interop, it's called out explicitly rather than glossed over.

| Language | Support | Notes |
|---|---|---|
| **Kotlin** | Full | The library's native language. See [README.md](README.md). |
| **Swift (iOS)** | Supported, with adapters | Direct suspend functions bridge to `async throws` automatically. Lambda-parameter APIs (`execute { }`) and the `Clock` parameter need small, reusable Swift adapter types — shown below. DSL builder sugar (`circuitBreaker { }`, `saga { }`, `failover { }`, ...) is **not** practically usable from Swift; use the direct factories instead. |
| **Java** | Supported, with caveats | Every pattern's API is `suspend`-based, so Java callers need `kotlinx.coroutines.BuildersKt.runBlocking` plus a hand-rolled `Function1` for each lambda parameter. Configuration types with a `Duration` property (e.g. `CircuitBreakerConfig.resetTimeout`) can only be constructed with their Kotlin *default* values from Java — see below. |
| **JavaScript / TypeScript** | Supported (facade), published to npm | `@JsExport` can't export `suspend` functions, so a hand-written Promise-based facade (not the Kotlin API as-is) covers most patterns -- see below for exactly what's covered. |

## Java

Every pattern's entry points are Kotlin `suspend fun`s, which the JVM sees as a
method taking an extra trailing `kotlin.coroutines.Continuation` parameter and
returning `Object`. The standard, dependency-free way to call into one from
Java is `kotlinx.coroutines.BuildersKt.runBlocking` (already on the classpath
transitively via `kotlinx-coroutines-core`), bridging a lambda parameter with a
hand-rolled `Function1`.

A lambda that completes synchronously (the common case for Java callers — you
just want to run some blocking Java code) can ignore the `Continuation`
entirely and return its result directly:

```java
import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function1;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;

import com.sorinirmies.arrow.resiliencekit.CircuitBreaker;
import com.sorinirmies.arrow.resiliencekit.CircuitBreakerConfig;

public class Example {
    // A suspend lambda that never actually suspends can just return its value.
    static <T> Function1<Continuation<? super T>, Object> syncBlock(T value) {
        return (Continuation<? super T> continuation) -> value;
    }

    public static void main(String[] args) throws InterruptedException {
        CircuitBreaker breaker = BuildersKt.runBlocking(
            EmptyCoroutineContext.INSTANCE,
            (CoroutineScope scope, Continuation<? super CircuitBreaker> continuation) ->
                CircuitBreaker.Companion.create(
                    new CircuitBreakerConfig(), // see the Duration caveat below
                    kotlin.time.Clock.System.INSTANCE,
                    continuation
                )
        );

        String result = BuildersKt.runBlocking(
            EmptyCoroutineContext.INSTANCE,
            (CoroutineScope scope, Continuation<? super String> continuation) ->
                breaker.execute(syncBlock("ok"), continuation)
        );

        System.out.println(result); // "ok"
    }
}
```

### The `Duration` caveat

`CircuitBreakerConfig`, `BulkheadConfig`, `TimeLimiterConfig`, and every other
config type with a `resetTimeout`/`timeout`/`maxWaitDuration`-style property use
`kotlin.time.Duration` — a Kotlin inline value class. Inline classes get JVM
method-name mangling (`setResetTimeout-LRDsOJo`, a hyphenated name that isn't
even syntactically callable from Java source) and their containing class's
default-parameter constructor bridge isn't visible to `javac` as a selectable
overload. In practice, **Java source code can only construct these config types
with `new XxxConfig()` (all Kotlin defaults) — not with custom duration
values.** The builder DSL (`CircuitBreakerConfigBuilder`, etc.) has the same
problem for its setters.

If you need non-default timeouts/thresholds from Java, the only clean option
today is a small Kotlin-side helper in your own codebase that takes a plain
`Long` (milliseconds) and builds the `Duration` internally, e.g.:

```kotlin
// in a Kotlin file in your own project
@JvmOverloads
@JvmName("circuitBreakerConfig")
fun javaCircuitBreakerConfig(
    failureThreshold: Int = 5,
    resetTimeoutMillis: Long = 30_000,
    halfOpenSuccessThreshold: Int = 2,
    halfOpenMaxCalls: Int = 3,
): CircuitBreakerConfig = CircuitBreakerConfig(
    failureThreshold = failureThreshold,
    resetTimeout = resetTimeoutMillis.milliseconds,
    halfOpenSuccessThreshold = halfOpenSuccessThreshold,
    halfOpenMaxCalls = halfOpenMaxCalls,
)
```

Java callers can then call `JavaConfigHelpersKt.circuitBreakerConfig(3, 10_000, 2, 3)`
normally.

### A note on `Policy`, `Saga`, `Failover`, and friends

The composable/DSL-heavy patterns are considerably more awkward from Java than
`CircuitBreaker`/`Bulkhead`/`TimeLimiter`'s direct `create`/`execute` shape,
since they chain multiple suspend lambda parameters. If your Java code needs
them, it's much more practical to write a small Kotlin façade in your own
project that builds the `Saga`/`Failover`/`Policy` in Kotlin and exposes only
its final `execute()` call (itself bridged via `BuildersKt.runBlocking` as
above) to Java — rather than trying to construct the whole chain from Java
directly.

## Swift (iOS)

Every release attaches a prebuilt `ArrowResilienceKit.xcframework` (see
[README.md](README.md#swift-package-manager-ios)). Kotlin/Native's Objective-C
export bridges plain `suspend fun`s to Swift's `async`/`await` automatically —
but two things in this library's API shape need a small, reusable adapter each,
because Kotlin/Native does **not** currently turn a Swift closure into a
`suspend () -> T` *parameter* automatically (only direct suspend functions get
the automatic bridge, not higher-order functions that accept one):

1. Every pattern's `create(config:clock:)` takes a `Clock` parameter with no
   exported "use the system clock" default reachable from Swift — you must
   implement the `KotlinClock` protocol yourself.
2. Every `execute { ... }`-style call's block parameter is exported as
   `id<KotlinSuspendFunction0>` (or `KotlinSuspendFunction1` for one-argument
   lambdas like `executeOrFallback`'s fallback), not a plain Swift closure type
   — you must wrap your closure to conform to that protocol.

Both are small, one-time, fully reusable types:

```swift
import ArrowResilienceKit
import Foundation

/// Bridges Swift's wall clock to Kotlin's `kotlin.time.Clock`.
final class SystemClock: KotlinClock {
    func now() -> KotlinInstant {
        KotlinInstant.companion.fromEpochMilliseconds(
            epochMilliseconds: Int64(Date().timeIntervalSince1970 * 1000)
        )
    }
}

/// Bridges a Swift `async throws -> T` closure into `KotlinSuspendFunction0`,
/// which every `execute { ... }`-style API expects for its block parameter.
final class SuspendBlock0<T: AnyObject>: KotlinSuspendFunction0 {
    private let block: () async throws -> T

    init(_ block: @escaping () async throws -> T) {
        self.block = block
    }

    func invoke(completionHandler: @escaping (Any?, Error?) -> Void) {
        Task {
            do {
                completionHandler(try await block(), nil)
            } catch {
                completionHandler(nil, error)
            }
        }
    }
}
```

With those in place, usage reads naturally:

```swift
let breaker = try await CircuitBreaker.companion.create(
    config: CircuitBreakerConfig(
        failureThreshold: 5,
        resetTimeout: 30_000_000_000, // nanoseconds -- Duration's raw ObjC representation, see below
        halfOpenSuccessThreshold: 2,
        halfOpenMaxCalls: 3
    ),
    clock: SystemClock()
)

let result = try await breaker.execute(
    block: SuspendBlock0 { try await callExternalService() }
) as! String
```

### The `Duration` caveat (Swift side)

Unlike the JVM (name-mangled but at least typed), Kotlin/Native's Objective-C
export represents `kotlin.time.Duration` as a plain `Int64` of nanoseconds, with
no `Duration`-like Swift type or `.seconds(_:)` convenience at the API
boundary. `30.seconds` in Kotlin is `30_000_000_000` from Swift. If you find
yourself doing this a lot, a tiny local extension helps:

```swift
extension Int64 {
    static func seconds(_ value: Double) -> Int64 { Int64(value * 1_000_000_000) }
}
// resetTimeout: .seconds(30)
```

### DSL builders don't bridge cleanly — use the direct factories

The `circuitBreaker { }`, `bulkhead { }`, `saga { }`, `failover { }`-style DSL
functions take a `suspend ReceiverType.() -> Unit` configuration lambda. Kotlin/
Native exports that as `KotlinSuspendFunction1` where the *first argument* is
the receiver/builder instance — meaning a Swift caller would need to implement
that protocol, receive the builder object inside `invoke`, and call its
(itself suspend-lambda-taking) methods like `provider(name:circuitBreakerConfig:clock:block:)`
by hand. This is technically possible but not practical boilerplate to
maintain in application code. **From Swift, prefer the direct class
constructors/factories** (`CircuitBreaker.companion.create(config:clock:)`,
plain `CircuitBreakerConfig(...)` initializers, etc.) over the Kotlin-only DSL
sugar — they're the same functionality, verified to bridge cleanly as shown
above.

## JavaScript / TypeScript

Published to npm as **[`arrow-resilience-kit`](https://www.npmjs.com/package/arrow-resilience-kit)**, with real, generated TypeScript definitions:

```bash
npm install arrow-resilience-kit
```

`@JsExport` can't export `suspend` functions at all (verified: it's a compile error, not just an unsupported-at-runtime situation), and this library's entire Kotlin API is suspend-based. So every pattern below is a **hand-written, Promise-based facade** (`src/jsMain/kotlin/`) over the real Kotlin implementation, not the Kotlin API exported as-is -- verified end to end by actually running the compiled output in Node (not just compiling it), including a real, subtle bug this caught: a rejected JS `Promise`'s error surfaces back in Kotlin as a plain `Throwable`, *not* an `Exception`, silently bypassing every pattern's internal `catch (e: Exception)` failure accounting unless the facade layer explicitly rewraps it (see `JsInterop.kt`'s `awaitBlock`).

```ts
import { CircuitBreaker } from "arrow-resilience-kit";

const breaker = await CircuitBreaker.create({ failureThreshold: 5, resetTimeoutMillis: 30_000 });
const result = await breaker.execute(() => callExternalService());
```

A block parameter is just `() => Promise<T>` -- an ordinary async function (or a sync one wrapped in `Promise.resolve(...)`), nothing special. `kotlin.time.Duration`-typed configuration (resetTimeout, maxWaitDuration, ...) is exposed as plain milliseconds (`...Millis: number`), since `Duration` itself doesn't export to JS as anything a JS/TS caller could construct.

### What's covered

`CircuitBreaker`, `SlidingWindowCircuitBreaker`, `Bulkhead`, `RateLimiter`, `SlidingWindowRateLimiter`, `TimeLimiter`, `RetryBudget`, `Retry` (the top-level `withExponentialBackoff`/`withConstantDelay`/`orDefault`/`ifMatches` functions), `AdaptiveLimiter`, `Cache` (specialized to `string` keys -- the common case for JS/TS), `Failover`, and `hedge`.

`Failover`'s Kotlin DSL builder (`failover { provider(...) }`) isn't exposed directly -- it's a suspend, receiver-style builder lambda, which doesn't bridge to JS/TS the same way a plain callback does. Pass an array of `FailoverProviderSpec` instead:

```ts
import { Failover, FailoverProviderSpec } from "arrow-resilience-kit";

const transport = await Failover.create([
    new FailoverProviderSpec("websocket", () => connectWebSocket()),
    new FailoverProviderSpec("mqtt", () => connectMqtt()),
]);
const connection = await transport.execute();
```

### What isn't covered (yet)

`Saga` (same DSL-builder bridging problem as `Failover`, not yet given the array-of-specs treatment), `Policy`/`FlowResilience` (composition utilities tightly coupled to Kotlin's `Policy` interface), `SharedStateStore` (an extension-point interface, not something you'd instantiate from JS), the STM primitives (`StmCounter`/`StmGauge`/etc. -- low-level Kotlin-specific building blocks), and `Chaos`.

If your project is Kotlin Multiplatform and simply targets JS as one of its
platforms (rather than consuming the published npm package from plain
JS/TS), none of this applies to you -- you're still writing ordinary Kotlin
code (the same APIs as the README's Quick Start/Patterns sections), just
compiling it to JS instead of JVM or Native.
