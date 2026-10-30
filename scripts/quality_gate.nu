#!/usr/bin/env nu
# Run the full quality gate: detekt, build, JVM + JS tests, npm package smoke test
def main [--skip-lint, --skip-test, --skip-build, --skip-npm] {
    print "╔══════════════════════════════════════╗"
    print "║       Quality Gate                   ║"
    print "╚══════════════════════════════════════╝"

    if not $skip_lint {
        print "▸ Running detekt..."
        run-external "./gradlew" "detekt" "--no-daemon"
    }

    if not $skip_build {
        print "▸ Building project..."
        run-external "./gradlew" "assemble" "--no-daemon"
    }

    if not $skip_test {
        print "▸ Running tests..."
        run-external "./gradlew" "jvmTest" "jsNodeTest" "--no-daemon"
    }

    if not $skip_npm and not $skip_build {
        print "▸ Smoke-testing the npm package..."
        run-external "./gradlew" "jsNodeProductionLibraryDistribution" "--no-daemon"
        run-external "node" "scripts/npm_smoke/run.mjs"
    }

    print "✅ Quality gate passed!"
}
