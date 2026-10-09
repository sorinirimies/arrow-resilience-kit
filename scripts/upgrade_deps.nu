#!/usr/bin/env nu
# Upgrade Gradle wrapper and dependencies, then run quality gate
#
# Usage:
#   nu scripts/upgrade_deps.nu             # upgrade + quality gate
#   nu scripts/upgrade_deps.nu --check     # dry run (just check latest)
#   nu scripts/upgrade_deps.nu --commit    # upgrade + quality gate + commit + push (CI mode)

export def latest-gradle-version []: nothing -> string {
    http get https://services.gradle.org/versions/current | get version
}

export def has-catalog-update-plugin []: nothing -> bool {
    let tasks_out = (do { ./gradlew tasks --all --no-daemon } | complete)
    $tasks_out.stdout | str contains "versionCatalogUpdate"
}

export def has-changes []: nothing -> bool {
    let diff = (do { git diff --name-only } | complete)
    not ($diff.stdout | str trim | is-empty)
}

# gradle/actions/wrapper-validation@v3's `allow-checksums` is pinned to the
# sha256 of the gradle-wrapper.jar actually committed in this repo (see the
# comment above each `allow-checksums:` line) -- every time this script
# regenerates that jar, the pin goes stale until updated here too, otherwise
# CI falls back to validating over the network, which flakes with ECONNRESET
# on the self-hosted Gitea runner.
export def sync-wrapper-checksum []: nothing -> nothing {
    let checksum = (open --raw gradle/wrapper/gradle-wrapper.jar | hash sha256)
    let files = [
        ".github/workflows/ci.yml"
        ".github/workflows/release.yml"
        ".gitea/workflows/ci.yml"
        ".gitea/workflows/release.yml"
    ]
    for file in $files {
        let content = (open --raw $file)
        let updated = ($content | str replace --regex 'allow-checksums: [0-9a-f]{64}' $"allow-checksums: ($checksum)")
        $updated | save -f $file
    }
    print $"  New checksum: ($checksum)"
}

def main [
    --check       # Dry run — just show latest versions
    --commit      # Auto-commit and push (CI mode)
    --bot-name: string = "gitea-actions[bot]"
    --bot-email: string = "gitea-actions[bot]@noreply.gitea"
] {
    print "╔══════════════════════════════════════╗"
    print "║     Dependency Upgrade               ║"
    print "╚══════════════════════════════════════╝"

    print "▸ Fetching latest Gradle version..."
    let latest = (latest-gradle-version)
    print $"  Latest Gradle: ($latest)"

    if $check {
        print "  (dry run — not applying changes)"
        return
    }

    print "▸ Updating Gradle wrapper..."
    run-external "./gradlew" "wrapper" $"--gradle-version=($latest)" "--no-daemon"
    run-external "./gradlew" "wrapper" $"--gradle-version=($latest)" "--no-daemon"

    print "▸ Syncing pinned gradle-wrapper.jar checksum in CI workflows..."
    sync-wrapper-checksum

    if (has-catalog-update-plugin) {
        print "▸ Running versionCatalogUpdate..."
        run-external "./gradlew" "versionCatalogUpdate" "--no-daemon"
    } else {
        print "  No catalog update plugin — skipping."
    }

    if not (has-changes) {
        print "✅ All dependencies already up to date."
        return
    }

    let diff = (do { git diff --name-only } | complete)
    print "▸ Changed files:"
    print $diff.stdout

    print "▸ Running quality gate..."
    run-external "./gradlew" "assemble" "jvmTest" "--no-daemon" "--stacktrace"

    if $commit {
        let date = (date now | format date "%Y-%m-%d")
        run-external "git" "config" "user.name" $bot_name
        run-external "git" "config" "user.email" $bot_email
        run-external "git" "add" "-A"
        run-external "git" "commit" "-m" $"chore\(deps\): nightly dependency upgrade ($date)"
        run-external "git" "push" "origin" "main"
        print "✅ Dependency updates committed and pushed."
    } else {
        print "✅ Dependencies upgraded. Review changes and commit manually."
    }
}
