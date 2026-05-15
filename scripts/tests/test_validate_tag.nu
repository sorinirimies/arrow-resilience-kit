#!/usr/bin/env nu
# Tests for scripts/validate_tag.nu

use std/assert
use runner.nu *
use ../validate_tag.nu [validate]

def "test validate_tag: simple release tag" [] {
    let result = (validate "v1.0.0")
    assert equal $result.tag "v1.0.0"
    assert equal $result.version "1.0.0"
}

def "test validate_tag: patch release" [] {
    let result = (validate "v0.2.1")
    assert equal $result.tag "v0.2.1"
    assert equal $result.version "0.2.1"
}

def "test validate_tag: zero version" [] {
    let result = (validate "v0.0.0")
    assert equal $result.tag "v0.0.0"
    assert equal $result.version "0.0.0"
}

def "test validate_tag: large version numbers" [] {
    let result = (validate "v12.345.6789")
    assert equal $result.tag "v12.345.6789"
    assert equal $result.version "12.345.6789"
}

def "test validate_tag: version strips v prefix" [] {
    let result = (validate "v3.2.1")
    assert (not ($result.version | str starts-with "v"))
}

def "test validate_tag: tag preserves v prefix" [] {
    let result = (validate "v3.2.1")
    assert ($result.tag | str starts-with "v")
}

def "test validate_tag: result has both keys" [] {
    let result = (validate "v1.0.0")
    let columns = ($result | columns)
    assert ("tag" in $columns)
    assert ("version" in $columns)
}

def "test validate_tag: rejects missing v prefix" [] {
    let result = (do { nu scripts/validate_tag.nu "1.0.0" } | complete)
    assert ($result.exit_code != 0)
}

def "test validate_tag: rejects two-part version" [] {
    let result = (do { nu scripts/validate_tag.nu "v1.0" } | complete)
    assert ($result.exit_code != 0)
}

def "test validate_tag: rejects four-part version" [] {
    let result = (do { nu scripts/validate_tag.nu "v1.0.0.0" } | complete)
    assert ($result.exit_code != 0)
}

def "test validate_tag: rejects empty tag" [] {
    let result = (do { nu scripts/validate_tag.nu "" } | complete)
    assert ($result.exit_code != 0)
}

def "test validate_tag: rejects non-numeric parts" [] {
    let result = (do { nu scripts/validate_tag.nu "v1.abc.0" } | complete)
    assert ($result.exit_code != 0)
}

def main [] { run-tests }
