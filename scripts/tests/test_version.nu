#!/usr/bin/env nu
# Tests for scripts/version.nu — version extraction from build.gradle.kts

use std/assert
use runner.nu *
use ../version.nu [get-version]

def "test version: returns non-empty string" [] {
    let version = (get-version)
    assert (($version | str trim | str length) > 0)
}

def "test version: follows semver format" [] {
    let version = (get-version)
    let parts = ($version | split row ".")
    assert (($parts | length) >= 2) $"Expected at least 2 parts in version, got: ($version)"
}

def "test version: first part is numeric" [] {
    let version = (get-version)
    let major = ($version | split row "." | first)
    let parsed = ($major | into int)
    assert ($parsed >= 0)
}

def "test version: matches build.gradle.kts" [] {
    let version = (get-version)
    let content = (open build.gradle.kts)
    assert ($content | str contains $"version = \"($version)\"")
}

def main [] { run-tests }
