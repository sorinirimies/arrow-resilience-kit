#!/usr/bin/env nu
# Tests for scripts/fuzz.nu — argument and environment construction

use std/assert
use runner.nu *
use ../fuzz.nu [gradle-args fuzz-env]

def "test fuzz: runs only FuzzTest, uncached" [] {
    let args = (gradle-args)
    assert ("*FuzzTest" in $args)
    assert ("--rerun-tasks" in $args)
}

def "test fuzz: env carries iterations" [] {
    assert equal (fuzz-env 500) {FUZZ_ITERS: "500"}
}

def "test fuzz: env carries seed when given" [] {
    assert equal (fuzz-env 500 42) {FUZZ_ITERS: "500", FUZZ_SEED: "42"}
}

def "test fuzz: rejects non-positive iterations" [] {
    let r = (do { nu scripts/fuzz.nu --iters 0 } | complete)
    assert ($r.exit_code != 0)
    assert (($r.stderr | str contains "--iters must be >= 1"))
}

def main [] { run-tests }
