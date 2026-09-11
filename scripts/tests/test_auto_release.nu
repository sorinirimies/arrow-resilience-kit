#!/usr/bin/env nu
# Tests for scripts/auto_release.nu — version bump math + CLI validation
#
# Only the pure `bump-patch` helper and the fail-fast argument validation
# are exercised here. The real dispatch path (git push + HTTP call to
# GitHub/Gitea) is intentionally NOT run in tests — it has real side
# effects (mutates build.gradle.kts, commits, tags, pushes, hits the
# network) and belongs in a manual/integration check, not a unit test.

use std/assert
use runner.nu *
use ../auto_release.nu [bump-patch]

def "test auto_release: bumps patch version" [] {
    assert equal (bump-patch "1.2.3") "1.2.4"
}

def "test auto_release: bumps from zero" [] {
    assert equal (bump-patch "0.0.0") "0.0.1"
}

def "test auto_release: rolls patch past single digit" [] {
    assert equal (bump-patch "0.0.9") "0.0.10"
}

def "test auto_release: does not touch major or minor" [] {
    assert equal (bump-patch "2.10.5") "2.10.6"
}

def "test auto_release: handles multi-digit patch" [] {
    assert equal (bump-patch "1.4.99") "1.4.100"
}

def "test auto_release: rejects unknown platform" [] {
    let result = (do {
        nu scripts/auto_release.nu --platform bogus --check
    } | complete)
    assert ($result.exit_code != 0)
    assert ($result.stdout | str contains "Unknown platform")
}

def "test auto_release: unknown platform fails before bumping" [] {
    let result = (do {
        nu scripts/auto_release.nu --platform bogus --check
    } | complete)
    assert (not ($result.stdout | str contains "Would bump"))
}

def "test auto_release: rejects gitea platform without gitea-url" [] {
    let result = (do {
        nu scripts/auto_release.nu --platform gitea --check
    } | complete)
    assert ($result.exit_code != 0)
    assert ($result.stdout | str contains "--gitea-url is required")
}

def "test auto_release: requires repo and token outside check mode" [] {
    let result = (do {
        nu scripts/auto_release.nu --platform github
    } | complete)
    assert ($result.exit_code != 0)
    assert ($result.stdout | str contains "--repo and --token are required")
}

def "test auto_release: check mode does not require repo or token" [] {
    let result = (do {
        nu scripts/auto_release.nu --platform github --check
    } | complete)
    assert equal $result.exit_code 0
    assert ($result.stdout | str contains "Would bump")
}

def "test auto_release: check mode with gitea and gitea-url succeeds" [] {
    let result = (do {
        nu scripts/auto_release.nu --platform gitea --check --gitea-url "https://gitea.example.com"
    } | complete)
    assert equal $result.exit_code 0
    assert ($result.stdout | str contains "Would bump")
}

def "test auto_release: check mode reports current and next version" [] {
    let current = (nu scripts/version.nu | str trim)
    let expected_next = (bump-patch $current)
    let result = (do {
        nu scripts/auto_release.nu --platform github --check
    } | complete)
    assert ($result.stdout | str contains $current)
    assert ($result.stdout | str contains $expected_next)
}

def "test auto_release: check mode never mutates build.gradle.kts" [] {
    let before = (open build.gradle.kts)
    nu scripts/auto_release.nu --platform github --check | ignore
    let after = (open build.gradle.kts)
    assert equal $before $after
}

def main [] { run-tests }
