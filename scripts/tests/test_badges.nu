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

def "test badges: catalog-major reads the major version" [] {
    use ../badges.nu [catalog-major]
    let toml = "[versions]\nspring-boot = \"4.1.2\"\nlettuce = \"7.9.0.RELEASE\"\n"
    assert equal (catalog-major $toml "spring-boot") "4.x"
    assert equal (catalog-major $toml "lettuce") "7.x"
}
