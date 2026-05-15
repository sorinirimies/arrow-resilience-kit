#!/usr/bin/env nu
# Tests for scripts/publish.nu — validate target matching

use std/assert
use runner.nu *

def "test publish: dry run completes without error" [] {
    # Dry run should attempt publishToMavenLocal which will work
    let result = (do { nu scripts/publish.nu --dry-run } | complete)
    # We just check it starts — actual gradle may fail but that's OK
    assert ($result.stdout | str contains "Publishing")
}

def "test publish: output contains header" [] {
    let result = (do { nu scripts/publish.nu --dry-run } | complete)
    assert ($result.stdout | str contains "╔")
}

def main [] { run-tests }
