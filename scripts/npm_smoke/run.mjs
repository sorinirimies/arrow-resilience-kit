#!/usr/bin/env node
// Smoke-tests the built npm package (build/dist/js/productionLibrary) the way a consumer uses it:
// ESM + CommonJS entry points, error identity, AbortSignal, logging, Retry.withBackoff (node:test),
// then type-checks types.ts and every ```ts example in the package README against the generated
// .d.ts (tsc; skip with --skip-types).
//
//   ./gradlew jsNodeProductionLibraryDistribution && node scripts/npm_smoke/run.mjs
import { execFileSync } from "node:child_process";
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const pkg = resolve(here, "../../build/dist/js/productionLibrary");
if (!existsSync(join(pkg, "index.mjs"))) {
    console.error("No npm package found; run ./gradlew jsNodeProductionLibraryDistribution first.");
    process.exit(1);
}
const work = mkdtempSync(join(tmpdir(), "ark-npm-smoke-"));
try {
    mkdirSync(join(work, "node_modules"));
    symlinkSync(pkg, join(work, "node_modules", "arrow-resilience-kit"), "dir");
    for (const f of ["smoke.test.mjs", "types.ts", "tsconfig.json"]) cpSync(join(here, f), join(work, f));
    writeFileSync(join(work, "package.json"), '{ "type": "module", "private": true }\n');
    writeReadmeExamples(join(pkg, "README.md"), work);
    const run = (cmd, args) => execFileSync(cmd, args, { cwd: work, stdio: "inherit", shell: process.platform === "win32" });
    console.log("▸ node --test (ESM/CJS, errors, abort, logging, Retry.withBackoff)");
    run(process.execPath, ["--test", "smoke.test.mjs"]);
    if (!process.argv.includes("--skip-types")) {
        console.log("▸ tsc --noEmit against the generated .d.ts");
        run("npx", ["--yes", "-p", "typescript@5", "tsc", "-p", "tsconfig.json"]);
    }
    console.log("✅ npm package smoke test passed");
} finally {
    rmSync(work, { recursive: true, force: true });
}

// Each ```ts block of the README becomes its own module, so the docs can't drift from the API.
// Helpers the examples only use (fetchJson, url, ...) are declared; a `breaker`/`bulkhead` an
// example uses without creating it is declared with the library's type.
function writeReadmeExamples(readme, dir) {
    const blocks = [...readFileSync(readme, "utf8").matchAll(/```ts\n([\s\S]*?)```/g)].map((m) => m[1]);
    if (blocks.length === 0) throw new Error("README.md has no ```ts examples");
    const preamble = [
        "declare function fetchJson(url: string): Promise<any>;",
        "declare const url: string;",
        "declare const cachedValue: any;",
        "declare class HttpError extends Error { status: number }",
        "interface User { id: number }",
    ];
    const implicit = { breaker: "CircuitBreaker", bulkhead: "Bulkhead" };
    blocks.forEach((code, i) => {
        const extra = Object.entries(implicit)
            .filter(([name]) => new RegExp(`\\b${name}\\.`).test(code) && !new RegExp(`const ${name}\\b`).test(code))
            .map(([name, type]) => `declare const ${name}: import("arrow-resilience-kit").${type};`);
        writeFileSync(join(dir, `readme_${i + 1}.ts`), [...preamble, ...extra, code, "export {};"].join("\n"));
    });
}
