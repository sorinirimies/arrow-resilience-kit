#!/usr/bin/env node
// Smoke-tests the built npm package (build/dist/js/productionLibrary) the way a consumer uses it:
// ESM + CommonJS entry points, error identity, AbortSignal, logging, Retry.withBackoff (node:test),
// then type-checks types.ts against the generated .d.ts (tsc; skip with --skip-types).
//
//   ./gradlew jsNodeProductionLibraryDistribution && node scripts/npm_smoke/run.mjs
import { execFileSync } from "node:child_process";
import { cpSync, existsSync, mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
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
