#!/usr/bin/env nu
# Bump the project version, run quality gate, commit and tag
def main [version: string] {
    # Validate semver
    if not ($version | str contains '.') {
        print $"❌ Invalid version: ($version). Expected X.Y.Z format."
        exit 1
    }

    print $"╔══════════════════════════════════════╗"
    print $"║  Bumping to version ($version)       ║"
    print $"╚══════════════════════════════════════╝"

    # Update build.gradle.kts
    let content = (open build.gradle.kts)
    let updated = ($content | str replace --regex 'version = "[^"]*"' $'version = "($version)"')
    $updated | save -f build.gradle.kts
    print $"✅ Updated build.gradle.kts to ($version)"

    # Run quality gate
    print "▸ Running quality gate..."
    nu scripts/quality_gate.nu

    # Generate changelog
    if (which git-cliff | is-not-empty) {
        print "▸ Generating changelog..."
        run-external "git-cliff" "-o" "CHANGELOG.md"
    }

    # Git commit and tag
    run-external "git" "add" "-A"
    run-external "git" "commit" "-m" $"chore: bump version to ($version)"
    run-external "git" "tag" "-a" $"v($version)" "-m" $"Release v($version)"

    print $"✅ Version bumped to ($version) and tagged v($version)"
    print "Run 'just push --follow-tags' to publish"
}
