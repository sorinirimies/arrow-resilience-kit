#!/usr/bin/env nu
# Tests for scripts/validate_tag.nu

use std/assert
use runner.nu *
use ../validate_tag.nu [validate]

def "test validate_tag: bare version" [] {
    let result = (validate "0.3.0")
    assert equal $result.tag "0.3.0"
    assert equal $result.version "0.3.0"
}

def "test validate_tag: simple version" [] {
    let result = (validate "1.0.0")
    assert equal $result.tag "1.0.0"
    assert equal $result.version "1.0.0"
}

def "test validate_tag: patch version" [] {
    let result = (validate "0.2.1")
    assert equal $result.tag "0.2.1"
    assert equal $result.version "0.2.1"
}

def "test validate_tag: zero version" [] {
    let result = (validate "0.0.0")
    assert equal $result.tag "0.0.0"
    assert equal $result.version "0.0.0"
}

def "test validate_tag: large version numbers" [] {
    let result = (validate "12.345.6789")
    assert equal $result.tag "12.345.6789"
    assert equal $result.version "12.345.6789"
}

def "test validate_tag: version has no v prefix" [] {
    let result = (validate "3.2.1")
    assert (not ($result.version | str starts-with "v"))
    assert (not ($result.tag | str starts-with "v"))
}

def "test validate_tag: result has both keys" [] {
    let result = (validate "1.0.0")
    let columns = ($result | columns)
    assert ("tag" in $columns)
    assert ("version" in $columns)
}

def "test validate_tag: rejects v prefix" [] {
    let result = (do { nu scripts/validate_tag.nu "v1.0.0" } | complete)
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
