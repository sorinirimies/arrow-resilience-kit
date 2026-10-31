// End-to-end checks of the *published* npm package, the way a JS/TS consumer uses it.
// Run via `node scripts/npm_smoke/run.mjs` (installs build/dist/js/productionLibrary as a dependency).
import assert from "node:assert/strict";
import { readFileSync, readdirSync, realpathSync, statSync } from "node:fs";
import { AsyncLocalStorage } from "node:async_hooks";
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { test } from "node:test";
import kit, { Bulkhead, CircuitBreaker, Logging, Retry, RetryBudget, TimeLimiter, hedge } from "arrow-resilience-kit";

class HttpError extends Error {
    constructor(status, retryAfterMillis) {
        super(`HTTP ${status}`);
        this.status = status;
        this.retryAfterMillis = retryAfterMillis;
    }
}

/** Captures everything written to the console while `fn` runs. */
async function consoleOutput(fn) {
    const lines = [];
    const saved = { log: console.log, info: console.info, warn: console.warn, error: console.error, debug: console.debug };
    for (const k of Object.keys(saved)) console[k] = (...a) => lines.push(a.join(" "));
    try {
        await fn();
    } finally {
        Object.assign(console, saved);
    }
    return lines;
}

test("ESM named exports, default export and CommonJS agree", () => {
    const cjs = createRequire(import.meta.url)("arrow-resilience-kit");
    assert.equal(CircuitBreaker, cjs.CircuitBreaker);
    assert.equal(kit.Retry, Retry);
    assert.equal(typeof hedge, "function");
    assert.equal(createRequire(import.meta.url)("arrow-resilience-kit/arrow-resilience-kit.js").Retry, Retry, "deep imports still resolve");
});

// Kotlin emits a ~0.5 KB empty module stub for every library whose code was eliminated entirely;
// those are harmless. An unreferenced file *with code* is a leftover that shouldn't ship.
test("package ships no unreferenced .js code (stale leftovers)", () => {
    const dir = dirname(realpathSync(createRequire(import.meta.url).resolve("arrow-resilience-kit")));
    const reached = new Set();
    const visit = (file) => {
        if (reached.has(file)) return;
        reached.add(file);
        for (const [, dep] of readFileSync(join(dir, file), "utf8").matchAll(/(?:require\(|from )['"]\.\/([^'"]+)['"]/g)) visit(dep);
    };
    visit("index.cjs");
    visit("index.mjs");
    const shipped = readdirSync(dir).filter((f) => /\.(c|m)?js$/.test(f));
    assert.deepEqual(shipped.filter((f) => !reached.has(f) && statSync(join(dir, f)).size > 1024), []);
});

test("static create() works, Companion.create() still works", async () => {
    assert.equal(await (await CircuitBreaker.create(2)).currentState(), "Closed");
    assert.equal(await (await CircuitBreaker.Companion.create(2)).currentState(), "Closed");
});

test("rejections carry the caller's own error, not a wrapper", async () => {
    const err = new HttpError(404);
    const breaker = await CircuitBreaker.create(5);
    await assert.rejects(breaker.execute(() => Promise.reject(err)), (e) => e === err);
    const bulkhead = await Bulkhead.create(1, 0);
    await assert.rejects(bulkhead.execute(() => Promise.reject(err)), (e) => e === err);
    await assert.rejects(Retry.withConstantDelay(() => Promise.reject(err), 1, 1), (e) => e === err);
    assert.equal(await breaker.failures(), 1, "still counted as a failure internally");
});

test("silent by default; Logging.setLevel / setHandler", async () => {
    const quiet = await consoleOutput(async () => {
        const b = await CircuitBreaker.create(1);
        await b.execute(() => Promise.reject(new Error("x"))).catch(() => {});
        await Retry.ifMatches(() => Promise.reject(new Error("y")), () => false, 1, 1).catch(() => {});
    });
    assert.deepEqual(quiet, []);
    assert.equal(Logging.level(), "OFF");

    const events = [];
    Logging.setLevel("warn");
    Logging.setHandler((level, message, error) => events.push({ level, message, error }));
    const err = new Error("boom");
    const printed = await consoleOutput(() => Retry.ifMatches(() => Promise.reject(err), () => false, 1, 1).catch(() => {}));
    assert.deepEqual(printed, []);
    assert.ok(events.some((e) => e.level === "WARN" && e.error === err), JSON.stringify(events.map((e) => e.level)));
    assert.throws(() => Logging.setLevel("LOUD"), /Unknown log level/);
    Logging.setHandler(null);
    Logging.setLevel("OFF");
});

test("Retry.withBackoff: predicate sees the error, Retry-After delay, onRetry, give-up", async () => {
    let calls = 0;
    const seen = [];
    const t0 = Date.now();
    const result = await Retry.withBackoff(
        async () => {
            calls++;
            if (calls === 1) throw new HttpError(429, 120);
            if (calls === 2) throw new HttpError(503);
            return "ok";
        },
        {
            retries: 3,
            baseMillis: 1,
            shouldRetry: (e) => e.status === 429 || e.status >= 500,
            delayMillis: (e) => e.retryAfterMillis,
            onRetry: (e, retry, delay) => seen.push([e.status, retry, delay]),
        },
    );
    assert.equal(result, "ok");
    assert.equal(calls, 3);
    assert.deepEqual(seen.map(([s, r]) => [s, r]), [[429, 1], [503, 2]]);
    assert.equal(seen[0][2], 120, "Retry-After used as-is");
    assert.ok(seen[1][2] <= 2, "computed backoff for the 503");
    assert.ok(Date.now() - t0 >= 110);

    calls = 0;
    const notFound = new HttpError(404);
    await assert.rejects(Retry.withBackoff(async () => { calls++; throw notFound; }, { shouldRetry: (e) => e.status === 429 }), (e) => e === notFound);
    assert.equal(calls, 1, "404 not retried");

    calls = 0;
    await assert.rejects(Retry.withBackoff(async () => { calls++; throw new HttpError(500); }, { retries: 2, baseMillis: 1 }), /HTTP 500/);
    assert.equal(calls, 3);
    assert.equal(await Retry.withBackoff(() => Promise.resolve(7)), 7, "options are optional");
});

test("AbortSignal cancels waits and rejects with signal.reason", async () => {
    const ac = new AbortController();
    let calls = 0;
    const t0 = Date.now();
    setTimeout(() => ac.abort(), 30);
    await assert.rejects(
        Retry.withBackoff(async () => { calls++; throw new Error("down"); }, { baseMillis: 10_000, signal: ac.signal }),
        (e) => e === ac.signal.reason && e.name === "AbortError",
    );
    assert.ok(Date.now() - t0 < 2_000, "did not wait out the backoff");
    assert.equal(calls, 1);

    const pre = AbortSignal.abort(new Error("already"));
    let ran = false;
    await assert.rejects((await CircuitBreaker.create()).execute(async () => { ran = true; }, pre), /already/);
    assert.equal(ran, false, "block not run when already aborted");

    const tl = await TimeLimiter.create(60_000);
    const ac2 = new AbortController();
    setTimeout(() => ac2.abort(), 20);
    await assert.rejects(tl.execute(null, () => new Promise((r) => setTimeout(r, 1_000)), ac2.signal), { name: "AbortError" });

    const budget = await RetryBudget.create(10, 1);
    const ac3 = new AbortController();
    setTimeout(() => ac3.abort(), 20);
    await assert.rejects(budget.retry(() => Promise.reject(new Error("x")), 3, 10_000, 60_000, 2, ac3.signal), { name: "AbortError" });

    assert.equal(await hedge(() => Promise.resolve("h"), 10, 1, undefined), "h");
});

test("callbacks run in the caller's AsyncLocalStorage context, also under concurrency", async () => {
    const als = new AsyncLocalStorage();
    const breaker = await CircuitBreaker.create();
    const ids = ["A", "B", "C"];
    assert.deepEqual(await Promise.all(ids.map((id) => als.run(id, () => breaker.execute(async () => als.getStore())))), ids);
    const retried = ids.map((id) =>
        als.run(id, () => {
            let n = 0;
            const seen = [];
            return Retry.withBackoff(
                async () => {
                    seen.push(als.getStore());
                    if (n++ === 0) throw new Error("again");
                    return seen;
                },
                { baseMillis: 5, jitter: 0, shouldRetry: () => (seen.push(als.getStore()), true), onRetry: () => seen.push(als.getStore()) },
            );
        }),
    );
    assert.deepEqual(await Promise.all(retried), ids.map((id) => [id, id, id, id]));
});

test("package ships a README and the LICENSE", () => {
    const dir = dirname(realpathSync(createRequire(import.meta.url).resolve("arrow-resilience-kit")));
    assert.ok(readFileSync(join(dir, "README.md"), "utf8").length > 2000, "README.md");
    assert.match(readFileSync(join(dir, "LICENSE"), "utf8"), /MIT License/);
});
