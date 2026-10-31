# arrow-resilience-kit

Resilience patterns for JavaScript and TypeScript: **retry with backoff** (honoring `Retry-After`), **circuit breakers**, **bulkheads**, **rate limiters**, **time limits**, **hedged requests**, **failover**, **retry budgets**, **adaptive concurrency** and a **TTL/LRU cache**.

It is the JS build of the Kotlin Multiplatform library [arrow-resilience-kit](https://github.com/sorinirimies/arrow-resilience-kit), built on [Arrow](https://arrow-kt.io): the same, well-tested implementation that runs on the JVM, Android, iOS and native, exposed through a Promise-based API with generated TypeScript types.

- **Promise-based**: every operation takes an `async` function and returns a `Promise`.
- **Typed**: ships its own `.d.ts`; no `@types` package needed.
- **ES modules and CommonJS**: `import` and `require()` both work, with named exports.
- **No runtime dependencies**.
- **Cancellable**: operations that wait take an `AbortSignal`, like `fetch`.
- **Your errors, unchanged**: a rejection carries the exact error your function threw.
- **Silent**: logs nothing unless you ask it to.

```bash
npm install arrow-resilience-kit
```

## Quick start

```ts
import { CircuitBreaker, Retry } from "arrow-resilience-kit";

// Retry transient failures, waiting what the server asks for on 429.
const user = await Retry.withBackoff(() => fetchJson("https://api.example.com/user"), {
    retries: 3,
    shouldRetry: (e) => e.status === 429 || e.status >= 500,
    delayMillis: (e) => e.retryAfterMillis, // undefined = exponential backoff
});

// Stop calling a service that keeps failing; try again after 30 s.
const breaker = await CircuitBreaker.create(5, 30_000);
const orders = await breaker.execute(() => fetchJson("https://orders.example.com/latest"));
```

Every `create(...)` is `async` and takes positional arguments, all optional (see the signatures below). Durations are plain milliseconds.

## Patterns

### Retry

`Retry.withBackoff(block, options?)` is the general-purpose retry for network calls:

```ts
import { Retry } from "arrow-resilience-kit";

const controller = new AbortController();
const data = await Retry.withBackoff(() => fetchJson(url), {
    retries: 5,                 // retries after the first attempt (default 3)
    baseMillis: 200,            // first backoff (default 100), multiplied by `factor` (default 2)
    maxDelayMillis: 10_000,     // cap for the computed backoff
    jitter: 0.1,                // ±10 % randomization of the computed backoff
    shouldRetry: (e, retry) => e.status === 429 || e.status >= 500,
    delayMillis: (e, retry) => e.retryAfterMillis, // exact wait for this error, no jitter or cap
    onRetry: (e, retry, delayMillis) => console.debug(`retry ${retry} in ${delayMillis} ms`),
    signal: controller.signal,  // abort to stop waiting and reject with signal.reason
});
```

Simpler variants: `Retry.withExponentialBackoff(block, retries?, baseMillis?, factor?, signal?)`, `Retry.withConstantDelay(block, retries?, delayMillis?, signal?)`, `Retry.orDefault(block, fallback, retries?, baseMillis?, factor?)` and `Retry.ifMatches(block, shouldRetry, retries?, delayMillis?)`.

### Circuit breaker

Fails fast while a dependency is down instead of piling up slow, failing calls.

```ts
import { CircuitBreaker, SlidingWindowCircuitBreaker } from "arrow-resilience-kit";

// Opens after 5 consecutive failures; after 30 s lets 1 trial call through.
const breaker = await CircuitBreaker.create(5, 30_000, 1, 1);
breaker.onStateChange((from, to) => console.info(`circuit ${from} -> ${to}`));

const result = await breaker.executeOrFallback(
    async () => cachedValue,      // used while the circuit is open
    () => fetchJson(url),
);

// Opens when 50 % of the last 20 calls failed (once at least 10 were made).
const windowed = await SlidingWindowCircuitBreaker.create(20, 10, 0.5, 30_000);
await windowed.execute(() => fetchJson(url));
```

### Bulkhead

Limits how many calls run at once, so one slow dependency can't take every connection.

```ts
import { Bulkhead } from "arrow-resilience-kit";

// 10 concurrent calls, 50 waiting, wait at most 2 s for a slot.
const bulkhead = await Bulkhead.create(10, 50, 2_000);
const report = await bulkhead.execute(() => fetchJson(url));
```

### Rate limiter

```ts
import { RateLimiter, SlidingWindowRateLimiter } from "arrow-resilience-kit";

// Token bucket: 5 calls per second, bursts of up to 10.
const limiter = await RateLimiter.create(5, 10);
await limiter.execute(() => fetchJson(url)); // waits for a token

// At most 100 calls in any 60 s window.
const window = await SlidingWindowRateLimiter.create(100, 60_000);
const maybe = await window.tryExecute(() => fetchJson(url)); // null when over the limit
```

### Time limiter

```ts
import { TimeLimiter } from "arrow-resilience-kit";

const limiter = await TimeLimiter.create(5_000);               // default timeout
const fast = await limiter.execute(1_000, () => fetchJson(url)); // per-call timeout
const orNull = await limiter.executeOrNull(undefined, () => fetchJson(url));
```

### Hedged requests

Starts a backup request when the first one is slow, and takes whichever answers first.

```ts
import { hedge } from "arrow-resilience-kit";

// A second attempt after 200 ms, at most 2 extra attempts.
const fastest = await hedge(() => fetchJson(url), 200, 2);
```

### Failover

Tries providers in order, skipping one that keeps failing.

```ts
import { Failover, FailoverProviderSpec } from "arrow-resilience-kit";

const source = await Failover.create([
    new FailoverProviderSpec("primary", () => fetchJson("https://primary.example.com/data")),
    new FailoverProviderSpec("replica", () => fetchJson("https://replica.example.com/data")),
]);
const value = await source.execute();
```

### Retry budget

Caps retries across all calls to a dependency (a ratio of successful calls), so retries can't amplify an outage.

```ts
import { RetryBudget } from "arrow-resilience-kit";

const budget = await RetryBudget.create(10, 0.2); // up to 10 tokens, +0.2 per success
const reply = await budget.retry(() => fetchJson(url), 3, 100, 2_000);
```

### Adaptive limiter

Adjusts its concurrency limit to observed latency: grows while calls are fast, shrinks when they slow down.

```ts
import { AdaptiveLimiter } from "arrow-resilience-kit";

const adaptive = await AdaptiveLimiter.create(10, 1, 100, 1, 0.5, 250);
await adaptive.execute(() => fetchJson(url));
```

### Cache

String-keyed cache with LRU (or LFU/FIFO) eviction and an optional TTL.

```ts
import { Cache } from "arrow-resilience-kit";

const cache = await Cache.create<User>(1_000, 60_000, "LRU");
const cached = await cache.getOrPut("user:42", () => fetchJson("https://api.example.com/users/42"));
```

## Errors

A rejected promise carries **exactly the error your function rejected with**, so `instanceof` and custom fields keep working:

```ts
try {
    await breaker.execute(() => fetchJson(url));
} catch (e) {
    if (e instanceof HttpError && e.status === 404) {
        // not found, as thrown by fetchJson
    }
}
```

Errors raised by the library itself (circuit open, bulkhead full, timeout, retry budget exhausted, ...) are `Error`s with a descriptive message.

## Cancellation

`execute(...)` on the circuit breakers, bulkhead, rate limiters, adaptive limiter, time limiter and failover, plus `RetryBudget.retry(...)`, `hedge(...)` and the `Retry` functions accept an `AbortSignal` (the last argument, or the `signal` option of `Retry.withBackoff`). Aborting cancels whatever the library is waiting for (a retry delay, a permit, a timeout) and rejects with `signal.reason`, the same contract as `fetch(url, { signal })`. To also stop the work inside your function, pass the signal into it:

```ts
const controller = new AbortController();
const pending = breaker.execute(() => fetch(url, { signal: controller.signal }), controller.signal);
controller.abort();
```

## Async context

On Node.js 20.16+ and Bun, your callbacks run in the async context of the call that passed them, so `AsyncLocalStorage` values (request ids, tracing spans) belong to the right caller even when many calls run concurrently.

## Logging

Nothing is logged by default. Turn logging on, or route it into your own logger:

```ts
import { Logging } from "arrow-resilience-kit";

Logging.setLevel("WARN"); // OFF (default), ERROR, WARN, INFO, DEBUG, TRACE
Logging.setHandler((level, message, error) => console.log(`[${level}] ${message}`, error ?? ""));
Logging.setHandler(null); // back to the console
```

## Statistics

Most patterns report what they did, for metrics and dashboards:

```ts
const stats = await bulkhead.statistics();
console.log(stats.totalCalls, stats.rejectedCalls);
```

## Size

About 1.3 MB of JavaScript before bundling (the Kotlin standard library and coroutines are compiled in), plus source maps; no dependencies. Declared free of side effects, so bundlers can drop it when unused.

## Links

- [Source, Kotlin/JVM/iOS documentation and design notes](https://github.com/sorinirimies/arrow-resilience-kit)
- [Changelog](https://github.com/sorinirimies/arrow-resilience-kit/blob/main/CHANGELOG.md)
- [Issues](https://github.com/sorinirimies/arrow-resilience-kit/issues)

## License

MIT
