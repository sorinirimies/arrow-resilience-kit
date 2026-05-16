#!/usr/bin/env nu
# Clean up build artifacts after a CI run or local build.
#
# Removes ONLY generated/ephemeral files — never touches source code,
# configuration, or intentionally installed tooling (e.g. ~/.gradle,
# ~/.konan, ~/.sdkman, IDE configs).
#
# SAFETY: Hard-blocks removal of .gradle/ root and any gradle.properties file.
#
# Usage:
#   nu scripts/cleanup.nu            # standard cleanup
#   nu scripts/cleanup.nu --deep     # also remove .gradle cache subdirs
#   nu scripts/cleanup.nu --dry-run  # show what would be removed
#   nu scripts/cleanup.nu --ci       # CI mode: deep + stop daemon

# Paths that must NEVER be deleted, no matter what.
export def protected-paths []: nothing -> list<string> {
    [
        ".gradle"                    # the directory itself — only subdirs are fair game
        ".gradle/gradle.properties"  # user credentials, signing keys, custom config
        "gradle.properties"          # project-level properties
        "gradle"                     # wrapper jar + version catalog
        "src"                        # source code
        "scripts"                    # automation scripts
        "config"                     # detekt, etc.
        ".editorconfig"              # editor settings
        ".gitea"                     # Gitea workflows
        ".github"                    # GitHub workflows
        ".idea"                      # IDE project config
        "README.md"                  # documentation
        "build.gradle.kts"           # build config
        "settings.gradle.kts"        # settings
    ]
}

# Validate that a path is safe to remove. Errors if it hits a protected path.
export def assert-safe [path: string]: nothing -> nothing {
    let protected = (protected-paths)

    # Exact match against protected list
    if $path in $protected {
        error make {msg: $"🛑 BLOCKED: '($path)' is a protected path and must never be deleted."}
    }

    # Block anything named gradle.properties anywhere in the tree
    if ($path | path basename) == "gradle.properties" {
        error make {msg: $"🛑 BLOCKED: '($path)' is a gradle.properties file and must never be deleted."}
    }

    # Block home directory paths
    if ($path | str starts-with "~") or ($path | str starts-with "/home") or ($path | str starts-with "/root") {
        error make {msg: $"🛑 BLOCKED: '($path)' is a home directory path and must never be deleted."}
    }

    # Block absolute paths entirely — cleanup should only use relative project paths
    if ($path | str starts-with "/") {
        error make {msg: $"🛑 BLOCKED: '($path)' is an absolute path. Cleanup only operates on relative project paths."}
    }
}

# Directories that are safe to remove (all generated, none checked in).
export def targets [--deep]: nothing -> list<string> {
    mut dirs = [
        "build"              # compiled classes, test reports, docs, jars
        "kotlin-js-store"    # auto-generated yarn.lock — regenerated on next build
    ]
    if $deep {
        # Only remove GENERATED subdirectories inside .gradle/
        # Never remove .gradle/ itself — it may contain gradle.properties
        # with signing keys, credentials, or other user config.
        let gradle_caches = [
            ".gradle/buildOutputCleanup"
            ".gradle/expanded"
            ".gradle/kotlin"
            ".gradle/vcs-1"
            ".gradle/file-system.probe"
        ]
        # Also remove version-specific caches (e.g. .gradle/8.5/, .gradle/9.5.0/)
        if (".gradle" | path exists) {
            let version_dirs = (ls .gradle
                | where { |it| $it.type == "dir" }
                | where { |it| ($it.name | path basename) =~ '^[0-9]' }
                | get name
            )
            $dirs = ($dirs | append $gradle_caches | append $version_dirs)
        } else {
            $dirs = ($dirs | append $gradle_caches)
        }
    }
    $dirs
}

# Return list of target paths that actually exist on disk.
export def existing-targets [--deep]: nothing -> list<string> {
    targets --deep=$deep | where { |d| $d | path exists }
}

# Safely remove a path after validating it against the protected list.
export def safe-remove [path: string]: nothing -> nothing {
    assert-safe $path
    rm -rf $path
}

# Stop the Gradle daemon so it releases file locks and memory.
export def stop-daemon []: nothing -> nothing {
    let result = (do { ./gradlew --stop } | complete)
    if $result.exit_code == 0 {
        print "  ✅ Gradle daemon stopped."
    } else {
        print "  ⚠ No Gradle daemon was running."
    }
}

def main [
    --deep     # Also remove .gradle cache subdirs
    --dry-run  # Only show what would be removed, don't delete
    --ci       # CI mode: deep clean + stop daemon
] {
    let is_deep = $deep or $ci

    print "╔══════════════════════════════════════╗"
    print "║          Cleanup                     ║"
    print "╚══════════════════════════════════════╝"

    # Stop daemon first (before deleting caches it may hold locks on)
    if $ci {
        print "▸ Stopping Gradle daemon..."
        stop-daemon
    }

    let to_clean = (existing-targets --deep=$is_deep)

    if ($to_clean | is-empty) {
        print "✅ Nothing to clean — workspace is already clean."
        return
    }

    # Validate ALL targets before deleting anything
    for dir in $to_clean {
        assert-safe $dir
    }

    print "▸ Targets:"
    for dir in $to_clean {
        let size = (du $dir | get apparent | first)
        print $"    ($dir)  \(($size)\)"
    }

    if $dry_run {
        print ""
        print "  (dry run — nothing was deleted)"
        return
    }

    for dir in $to_clean {
        safe-remove $dir
        print $"  🗑 Removed ($dir)"
    }

    print ""
    print "✅ Cleanup complete."
    print ""
    print "  Preserved:"
    print "    ~/.gradle             (shared dependency cache)"
    print "    .gradle/gradle.properties  (credentials & signing)"
    print "    gradle.properties     (project properties)"
    print "    ~/.konan              (Kotlin/Native compiler)"
    print "    .editorconfig         (editor settings)"
    print "    .idea/                (IDE project config)"
    print "    config/               (detekt, etc.)"
    print "    gradle/               (wrapper jar + version catalog)"
}
