#!/usr/bin/env nu
# Tests for scripts/upgrade_deps.nu — pure helper functions

use std/assert
use runner.nu *
use ../upgrade_deps.nu [latest-gradle-version has-catalog-update-plugin]

def "test upgrade_deps: latest gradle version is non-empty" [] {
    let version = (latest-gradle-version)
    assert (($version | str length) > 0) $"Expected non-empty version, got: ($version)"
}

def "test upgrade_deps: latest gradle version follows semver" [] {
    let version = (latest-gradle-version)
    let parts = ($version | split row ".")
    assert (($parts | length) >= 2) $"Expected semver, got: ($version)"
}

def "test upgrade_deps: latest gradle version starts with digit" [] {
    let version = (latest-gradle-version)
    let first_char = ($version | str substring 0..1)
    assert ($first_char =~ '\d') $"Expected digit, got: ($first_char)"
}

def "test upgrade_deps: has-catalog-update-plugin returns bool" [] {
    let result = (has-catalog-update-plugin)
    assert (($result | describe) == "bool")
}

def main [] { run-tests }
