#!/usr/bin/env nu
# Tests for scripts/bump_version.nu — version validation

use std/assert
use runner.nu *

def "test bump_version: rejects version without dots" [] {
    let result = (do { nu scripts/bump_version.nu "nodots" } | complete)
    assert ($result.exit_code != 0)
    assert ($result.stdout | str contains "Invalid version")
}

def "test bump_version: rejects empty version" [] {
    # This will fail because nu requires the argument
    let result = (do { nu scripts/bump_version.nu "" } | complete)
    assert ($result.exit_code != 0)
}

def main [] { run-tests }
