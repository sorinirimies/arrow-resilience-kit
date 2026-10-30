#!/usr/bin/env nu
# Tests for scripts/upgrade_deps.nu — pure helper functions

use std/assert
use runner.nu *
use ../upgrade_deps.nu [latest-gradle-version stable-versions version-key pick-latest metadata-url catalog-lookups metadata-versions apply-updates]

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

def "test upgrade_deps: stable-versions drops pre-releases" [] {
    let vs = ["2.3.0" "2.3.0-alpha.1" "2.3.0-RC" "1.0-SNAPSHOT" "2.4.0-Beta1" "6.8.1.RELEASE" "2.3.0.1" "3.0.0-M1"]
    assert equal ($vs | stable-versions) ["2.3.0" "6.8.1.RELEASE" "2.3.0.1"]
}

def "test upgrade_deps: version-key compares numerically" [] {
    assert equal ("2.10.0" | version-key) "000002.000010.000000.000000.000000.000000"
    assert (("2.10.0" | version-key) > ("2.9.9" | version-key))
    assert (("2.3.0.1" | version-key) > ("2.3.0" | version-key))
}

def "test upgrade_deps: pick-latest stays within the major by default" [] {
    let vs = ["3.5.0" "3.5.16" "4.1.2" "4.2.0-RC1"]
    assert equal ($vs | pick-latest "3.5.0") "3.5.16"
    assert equal (["6.8.1.RELEASE" "6.9.0.RELEASE" "7.9.0.RELEASE"] | pick-latest "6.8.1.RELEASE") "6.9.0.RELEASE"
}

def "test upgrade_deps: pick-latest minor policy keeps major.minor" [] {
    assert equal (["2.2.20" "2.2.21" "2.4.21"] | pick-latest "2.2.20" "minor") "2.2.21"
}

def "test upgrade_deps: pick-latest never downgrades" [] {
    assert equal (["1.0.0" "1.1.0"] | pick-latest "1.2.0") "1.2.0"
    assert equal ([] | pick-latest "1.2.0") "1.2.0"
}

def "test upgrade_deps: metadata-url for modules and plugins" [] {
    assert equal (metadata-url "io.arrow-kt:arrow-core") "https://repo1.maven.org/maven2/io/arrow-kt/arrow-core/maven-metadata.xml"
    assert equal (metadata-url "io.gitlab.arturbosch.detekt" --plugin) "https://plugins.gradle.org/m2/io/gitlab/arturbosch/detekt/io.gitlab.arturbosch.detekt.gradle.plugin/maven-metadata.xml"
}

def "test upgrade_deps: catalog-lookups covers every versions key of the real catalog" [] {
    let catalog = (open gradle/libs.versions.toml)
    let keys = (catalog-lookups $catalog | get key | sort)
    assert equal $keys ($catalog.versions | columns | sort)
}

def "test upgrade_deps: metadata-versions parses maven metadata" [] {
    let xml = "<metadata><versioning><versions><version>1.0</version><version>1.1-RC</version></versions></versioning></metadata>"
    assert equal ($xml | metadata-versions) ["1.0" "1.1-RC"]
}

def "test upgrade_deps: apply-updates rewrites only the version lines" [] {
    let toml = "[versions]\n# kotlin = \"2.4.20\" stays in comments\nkotlin = \"2.4.20\"\nkotlin-stdlib = \"2.2.20\"\n[libraries]\nx = { module = \"a:b\", version = \"2.4.20\" }\n"
    let out = ($toml | apply-updates [{ key: "kotlin", from: "2.4.20", to: "2.4.21" }])
    assert ($out | str contains "\nkotlin = \"2.4.21\"")
    assert ($out | str contains "kotlin-stdlib = \"2.2.20\"")
    assert ($out | str contains "# kotlin = \"2.4.20\" stays")
    assert ($out | str contains "version = \"2.4.20\" }")
}

def main [] { run-tests }
