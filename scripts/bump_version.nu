#!/usr/bin/env nu
# Bump the project version, run quality gate, commit and tag
def main [version: string] {
    # Validate semver
    if not ($version | str contains '.') {
        print $"❌ Invalid version: ($version). Expected X.Y.Z format."
        exit 1
    }

    # Releasing from a branch that is behind GitHub (e.g. missing the bot's Package.swift commit)
    # makes the push fail and the release job's commit-back conflict: refuse up front.
    if ((^git remote | lines) | any {|r| $r == "github" }) {
        let branch = (^git branch --show-current | str trim)
        ^git fetch --quiet github $branch
        let behind = (^git rev-list --count $"HEAD..github/($branch)" | str trim | into int)
        if $behind > 0 {
            print $"❌ ($branch) is ($behind) commit\(s\) behind github/($branch). Run: git pull --rebase github ($branch)"
            exit 1
        }
    }

    let label = $"Bumping to version ($version)"
    let pad = ($label | str length) + 4
    let bar = ("═" | fill -c '═' -w $pad)
    print $"╔($bar)╗"
    print $"║  ($label)  ║"
    print $"╚($bar)╝"

    # Update build.gradle.kts
    let content = (open build.gradle.kts)
    let updated = ($content | str replace --regex 'version = "[^"]*"' $'version = "($version)"')
    $updated | save -f build.gradle.kts
    print $"✅ Updated build.gradle.kts to ($version)"

    # Refresh self-hosted badges (.github/badges) so the README shows the new version
    nu scripts/badges.nu $version

    # Run quality gate
    print "▸ Running quality gate..."
    nu scripts/quality_gate.nu

    # Generate changelog
    if (which git-cliff | is-not-empty) {
        print "▸ Generating changelog..."
        run-external "git-cliff" "--tag" $version "-o" "CHANGELOG.md"
    }

    # Git commit and tag
    run-external "git" "add" "-A"
    run-external "git" "commit" "-m" $"chore: bump version to ($version)"
    run-external "git" "tag" "-a" $"($version)" "-m" $"Release ($version)"

    print $"✅ Version bumped to ($version) and tagged ($version)"
    print "Run 'just push --follow-tags' to publish"
}
