#!/usr/bin/env nu
# Tests for scripts/badges.nu
use std/assert
use runner.nu *
use ../badges.nu [badge-svg text-width]

def "test badges: svg contains label and value" [] {
    let s = (badge-svg "npm" "v1.2.3" "#cb3837")
    assert ($s | str contains "npm: v1.2.3")
    assert ($s | str starts-with "<svg")
}

def "test badges: width grows with text" [] {
    assert ((text-width "longer text") > (text-width "ab"))
}
