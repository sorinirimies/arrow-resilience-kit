#!/usr/bin/env nu
# Dependency vulnerability scan: resolve every Gradle configuration into a lockfile,
# then check it against the OSV database with osv-scanner. Fails on any known vulnerability.
#
# CI only: run by the Security workflow (.github/ and .gitea/), which installs osv-scanner.
# There is deliberately no local just recipe.
# Usage: nu scripts/security_audit.nu

export const LOCKFILE = "gradle.lockfile"
export const INIT_SCRIPT = "scripts/security/lock.init.gradle.kts"

export def lock-args []: nothing -> list<string> {
    ["dependencies" "--write-locks" "--init-script" $INIT_SCRIPT "--no-daemon" "-q"]
}

export def scan-args []: nothing -> list<string> {
    ["scan" "source" "--lockfile" $LOCKFILE]
}

def main [] {
    if (which osv-scanner | is-empty) {
        error make {msg: "osv-scanner not found. Install: https://google.github.io/osv-scanner/installation/"}
    }
    print "▸ Resolving dependencies into a lockfile…"
    run-external "./gradlew" ...(lock-args) out> /dev/null
    if not ($LOCKFILE | path exists) {
        error make {msg: $"($LOCKFILE) was not generated"}
    }
    print "▸ Scanning with osv-scanner…"
    let result = (do { run-external "osv-scanner" ...(scan-args) } | complete)
    rm -f $LOCKFILE
    print $result.stdout
    if $result.exit_code != 0 {
        print -e $result.stderr
        error make {msg: "❌ known vulnerabilities found (or scan failed)"}
    }
    print "✅ No known vulnerabilities."
}
