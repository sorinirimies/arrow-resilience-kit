#!/usr/bin/env nu
# Publish to Maven Central and/or GitHub Packages
def main [--dry-run, --target: string = "all"] {
    print "╔══════════════════════════════════════╗"
    print "║          Publishing                  ║"
    print "╚══════════════════════════════════════╝"

    let version = (nu scripts/version.nu | str trim)
    print $"  Version: ($version)"

    if $dry_run {
        print "▸ Dry run — validating publishability..."
        run-external "./gradlew" "publishToMavenLocal" "--no-daemon"
        print "✅ Dry run successful"
        return
    }

    match $target {
        "maven-central" | "central" => {
            print "▸ Publishing to Maven Central..."
            run-external "./gradlew" "publishAllPublicationsToMavenCentralRepository" "--no-daemon"
        }
        "github" => {
            print "▸ Publishing to GitHub Packages..."
            run-external "./gradlew" "publishAllPublicationsToGitHubPackagesRepository" "--no-daemon"
        }
        "all" => {
            print "▸ Publishing to all repositories..."
            run-external "./gradlew" "publish" "--no-daemon"
        }
        _ => {
            print $"❌ Unknown target: ($target)"
            exit 1
        }
    }

    print $"✅ Published ($version) to ($target)"
}
