#!/usr/bin/env nu
# Run the randomised robustness tests (FuzzTest) for a while.
#
# Usage: nu scripts/fuzz.nu [--iters 200000] [--seed 42]
#   A failure prints the seed that reproduces it; rerun with --seed <n>.

export const DEFAULT_ITERS = 2000

# Gradle arguments that run only the fuzz tests, uncached so they always execute.
export def gradle-args []: nothing -> list<string> {
    ["jvmTest" "--tests" "*FuzzTest" "--no-daemon" "--rerun-tasks"]
}

# Environment for the test JVM; seed is omitted when empty so the tests pick a random one.
export def fuzz-env [iters: int, seed?: int]: nothing -> record {
    let base = {FUZZ_ITERS: ($iters | into string)}
    if $seed == null { $base } else { $base | insert FUZZ_SEED ($seed | into string) }
}

def main [--iters: int = 200000, --seed: int] {
    if $iters < 1 {
        error make {msg: $"--iters must be >= 1, got ($iters)"}
    }
    let env_vars = (fuzz-env $iters $seed)
    print $"▸ Fuzzing: iterations=($iters) seed=($seed | default 'random')"
    with-env $env_vars { run-external "./gradlew" ...(gradle-args) }
    print "✅ Fuzz run passed!"
}
