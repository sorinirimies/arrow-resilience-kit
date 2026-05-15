#!/usr/bin/env nu
# Run the full quality gate: detekt, build, test
def main [--skip-lint, --skip-test, --skip-build] {
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
        run-external "./gradlew" "jvmTest" "--no-daemon"
    }

    print "✅ Quality gate passed!"
}
