// Gradle init script: lock every resolvable configuration so `--write-locks` emits a
// gradle.lockfile that osv-scanner can read. Used only by scripts/security_audit.nu;
// the project itself does not use dependency locking.
allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}
