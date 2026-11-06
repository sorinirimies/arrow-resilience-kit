#!/usr/bin/env nu
# Tests for scripts/create_gitea_release.nu — the git-cliff arguments.

use std/assert
use ../create_gitea_release.nu [cliff-runs]
use runner.nu run-tests

# A Gitea runner points GITHUB_API_URL at Gitea; online git-cliff then fails with a 404.
def "test create gitea release: git-cliff never goes online" [] {
    for args in (cliff-runs) { assert ("--offline" in $args) }
}

def "test create gitea release: writes the notes and the changelog" [] {
    let outs = cliff-runs | each {|a| $a | last }
    assert equal $outs ["RELEASE_NOTES.md" "CHANGELOG.md"]
    assert ("--current" in (cliff-runs | first))
}

def main [] { run-tests }
