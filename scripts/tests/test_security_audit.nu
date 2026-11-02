#!/usr/bin/env nu
# Tests for scripts/security_audit.nu — command construction and init script

use std/assert
use runner.nu *
use ../security_audit.nu [lock-args scan-args INIT_SCRIPT LOCKFILE]

def "test security_audit: init script exists" [] {
    assert ($INIT_SCRIPT | path exists)
}

def "test security_audit: lock args write locks with the init script" [] {
    let args = (lock-args)
    assert ("--write-locks" in $args)
    assert ($INIT_SCRIPT in $args)
}

def "test security_audit: scan args point at the lockfile" [] {
    assert equal (scan-args) ["scan" "source" "--lockfile" $LOCKFILE]
}

def main [] { run-tests }
