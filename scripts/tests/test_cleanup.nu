#!/usr/bin/env nu
# Tests for scripts/cleanup.nu

use std/assert
use runner.nu *
use ../cleanup.nu [targets existing-targets protected-paths assert-safe]

# ── protected-paths ─────────────────────────────────────────────

def "test cleanup: .gradle is protected" [] {
    assert (".gradle" in (protected-paths))
}

def "test cleanup: .gradle/gradle.properties is protected" [] {
    assert (".gradle/gradle.properties" in (protected-paths))
}

def "test cleanup: gradle.properties is protected" [] {
    assert ("gradle.properties" in (protected-paths))
}

def "test cleanup: gradle dir is protected" [] {
    assert ("gradle" in (protected-paths))
}

def "test cleanup: src is protected" [] {
    assert ("src" in (protected-paths))
}

def "test cleanup: scripts is protected" [] {
    assert ("scripts" in (protected-paths))
}

def "test cleanup: config is protected" [] {
    assert ("config" in (protected-paths))
}

def "test cleanup: build.gradle.kts is protected" [] {
    assert ("build.gradle.kts" in (protected-paths))
}

# ── assert-safe blocks dangerous paths ──────────────────────────

def "test cleanup: assert-safe blocks .gradle root" [] {
    let result = (do { nu -c "use scripts/cleanup.nu [assert-safe]; assert-safe '.gradle'" } | complete)
    assert ($result.exit_code != 0)
}

def "test cleanup: assert-safe blocks .gradle/gradle.properties" [] {
    let result = (do { nu -c "use scripts/cleanup.nu [assert-safe]; assert-safe '.gradle/gradle.properties'" } | complete)
    assert ($result.exit_code != 0)
}

def "test cleanup: assert-safe blocks gradle.properties" [] {
    let result = (do { nu -c "use scripts/cleanup.nu [assert-safe]; assert-safe 'gradle.properties'" } | complete)
    assert ($result.exit_code != 0)
}

def "test cleanup: assert-safe blocks nested gradle.properties" [] {
    let result = (do { nu -c "use scripts/cleanup.nu [assert-safe]; assert-safe 'some/nested/gradle.properties'" } | complete)
    assert ($result.exit_code != 0)
}

def "test cleanup: assert-safe blocks src" [] {
    let result = (do { nu -c "use scripts/cleanup.nu [assert-safe]; assert-safe 'src'" } | complete)
    assert ($result.exit_code != 0)
}

def "test cleanup: assert-safe blocks gradle" [] {
    let result = (do { nu -c "use scripts/cleanup.nu [assert-safe]; assert-safe 'gradle'" } | complete)
    assert ($result.exit_code != 0)
}

def "test cleanup: assert-safe blocks home paths with tilde" [] {
    let result = (do { nu -c "use scripts/cleanup.nu [assert-safe]; assert-safe '~/.gradle'" } | complete)
    assert ($result.exit_code != 0)
}

def "test cleanup: assert-safe blocks home paths absolute" [] {
    let result = (do { nu -c "use scripts/cleanup.nu [assert-safe]; assert-safe '/home/nadia/.gradle'" } | complete)
    assert ($result.exit_code != 0)
}

def "test cleanup: assert-safe blocks root home" [] {
    let result = (do { nu -c "use scripts/cleanup.nu [assert-safe]; assert-safe '/root/.gradle'" } | complete)
    assert ($result.exit_code != 0)
}

def "test cleanup: assert-safe blocks absolute paths" [] {
    let result = (do { nu -c "use scripts/cleanup.nu [assert-safe]; assert-safe '/tmp/something'" } | complete)
    assert ($result.exit_code != 0)
}

def "test cleanup: assert-safe allows build" [] {
    assert-safe "build"  # should not error
}

def "test cleanup: assert-safe allows kotlin-js-store" [] {
    assert-safe "kotlin-js-store"  # should not error
}

def "test cleanup: assert-safe allows .gradle subdirs" [] {
    assert-safe ".gradle/buildOutputCleanup"  # should not error
    assert-safe ".gradle/expanded"
    assert-safe ".gradle/kotlin"
}

def "test cleanup: assert-safe allows .gradle version dirs" [] {
    assert-safe ".gradle/8.5"  # should not error
    assert-safe ".gradle/9.5.0"
}

# ── targets ─────────────────────────────────────────────────────

def "test cleanup: default targets include build" [] {
    let t = (targets)
    assert ("build" in $t)
}

def "test cleanup: default targets include kotlin-js-store" [] {
    let t = (targets)
    assert ("kotlin-js-store" in $t)
}

def "test cleanup: default targets have no .gradle paths" [] {
    let t = (targets)
    let gradle_items = ($t | where { |d| $d | str starts-with ".gradle" })
    assert ($gradle_items | is-empty)
}

def "test cleanup: deep targets include .gradle cache subdirs" [] {
    let t = (targets --deep)
    assert (".gradle/buildOutputCleanup" in $t)
    assert (".gradle/expanded" in $t)
    assert (".gradle/kotlin" in $t)
}

def "test cleanup: deep targets never include .gradle root" [] {
    let t = (targets --deep)
    assert (not (".gradle" in $t)) ".gradle root must not be a target"
}

def "test cleanup: deep targets never include gradle.properties" [] {
    let t = (targets --deep)
    let gp = ($t | where { |d| ($d | path basename) == "gradle.properties" })
    assert ($gp | is-empty) "gradle.properties must never be a target"
}

def "test cleanup: all targets pass assert-safe" [] {
    let t = (targets --deep)
    for dir in $t {
        assert-safe $dir  # should not error for any target
    }
}

def "test cleanup: targets never include src" [] {
    let t = (targets --deep)
    assert (not ("src" in $t))
}

def "test cleanup: targets never include gradle" [] {
    let t = (targets --deep)
    assert (not ("gradle" in $t))
}

def "test cleanup: targets never include scripts" [] {
    let t = (targets --deep)
    assert (not ("scripts" in $t))
}

def "test cleanup: targets never include .editorconfig" [] {
    let t = (targets --deep)
    assert (not (".editorconfig" in $t))
}

def "test cleanup: targets never include .gitea" [] {
    let t = (targets --deep)
    assert (not (".gitea" in $t))
}

def "test cleanup: targets never include .github" [] {
    let t = (targets --deep)
    assert (not (".github" in $t))
}

def "test cleanup: targets never include home paths" [] {
    let t = (targets --deep)
    for dir in $t {
        assert (not ($dir | str starts-with "~"))
        assert (not ($dir | str starts-with "/home"))
    }
}

# ── existing-targets ────────────────────────────────────────────

def "test cleanup: existing-targets returns only paths that exist" [] {
    let existing = (existing-targets)
    for dir in $existing {
        assert ($dir | path exists) $"Expected ($dir) to exist"
    }
}

def "test cleanup: existing-targets is subset of targets" [] {
    let all = (targets --deep)
    let existing = (existing-targets --deep)
    for dir in $existing {
        assert ($dir in $all) $"($dir) not in targets list"
    }
}

# ── dry-run ─────────────────────────────────────────────────────

def "test cleanup: dry-run does not delete anything" [] {
    let build_existed = ("build" | path exists)
    let result = (do { nu scripts/cleanup.nu --dry-run } | complete)
    assert equal $result.exit_code 0
    assert ($result.stdout | str contains "dry run")
    if $build_existed {
        assert ("build" | path exists) "dry-run should not delete build/"
    }
}

def "test cleanup: output contains header" [] {
    let result = (do { nu scripts/cleanup.nu --dry-run } | complete)
    assert ($result.stdout | str contains "Cleanup")
    assert ($result.stdout | str contains "╔")
}

def main [] { run-tests }
