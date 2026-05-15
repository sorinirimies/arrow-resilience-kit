#!/usr/bin/env nu
# Tests for scripts/quality_gate.nu — validate flag parsing

use std/assert
use runner.nu *

def "test quality_gate: dry run with all skips completes" [] {
    # Running with all skips should just print the header and success
    let result = (do { nu scripts/quality_gate.nu --skip-lint --skip-test --skip-build } | complete)
    assert equal $result.exit_code 0
    assert ($result.stdout | str contains "Quality Gate")
    assert ($result.stdout | str contains "Quality gate passed")
}

def "test quality_gate: output contains header" [] {
    let result = (do { nu scripts/quality_gate.nu --skip-lint --skip-test --skip-build } | complete)
    assert ($result.stdout | str contains "╔")
    assert ($result.stdout | str contains "╚")
}

def main [] { run-tests }
