#!/usr/bin/env nu
# Upgrade the Gradle wrapper and the version catalog (gradle/libs.versions.toml), then run the
# quality gate. Catalog versions move to the newest *stable* release within the same major
# version (major upgrades stay a deliberate, manual step); see CATALOG_POLICY for exceptions.
#
# Usage:
#   nu scripts/upgrade_deps.nu             # upgrade + quality gate
#   nu scripts/upgrade_deps.nu --check     # dry run (report what would change)
#   nu scripts/upgrade_deps.nu --commit    # upgrade + quality gate + commit + push (CI mode)

export def latest-gradle-version []: nothing -> string {
    http get https://services.gradle.org/versions/current | get version
}

# How far each catalog version may move: "major" (default) = newest stable with the same major
# version; "minor" = same major.minor. kotlin-stdlib is deliberately pinned to the 2.2 line
# (apiVersion 2.2, see gradle.properties), so only its patch version may change.
export const CATALOG_POLICY = { "kotlin-stdlib": "minor" }

# Stable release versions only (no alpha/beta/RC/M/SNAPSHOT/dev), e.g. 1.2.3 or 6.8.1.RELEASE.
export def stable-versions []: list<string> -> list<string> {
    where { |v| $v =~ '^\d+(\.\d+)*(\.RELEASE)?$' }
}

# Sort key for a version string: zero-padded numeric parts, so plain string comparison orders
# versions numerically ("6.8.1.RELEASE" -> "000006.000008.000001.000000.000000.000000").
export def version-key []: string -> string {
    let nums = ($in | parse --regex '(\d+)' | get capture0)
    $nums | append (0..(5 - ($nums | length)) | each { "0" }) | first 6 | each { |n| $n | fill --alignment right --character "0" --width 6 } | str join "."
}

# Newest version in `versions` allowed for `current` under `policy` ("major" | "minor"), or
# `current` itself if nothing newer qualifies.
export def pick-latest [current: string, policy: string = "major"]: list<string> -> string {
    let parts = ($current | split row ".")
    let prefix = if $policy == "minor" { $parts | first 2 | str join "." } else { $parts | first }
    let candidates = ($in | stable-versions | where { |v| ($v | split row "." | first (if $policy == "minor" { 2 } else { 1 }) | str join ".") == $prefix })
    let newest = ($candidates | append $current | sort-by { $in | version-key } | last)
    $newest
}

# maven-metadata.xml URL for a library module ("group:artifact") or a Gradle plugin id.
export def metadata-url [coordinate: string, --plugin]: nothing -> string {
    if $plugin {
        let path = ($coordinate | str replace --all "." "/")
        $"https://plugins.gradle.org/m2/($path)/($coordinate).gradle.plugin/maven-metadata.xml"
    } else {
        let ga = ($coordinate | split row ":")
        let group = ($ga | first | str replace --all "." "/")
        $"https://repo1.maven.org/maven2/($group)/($ga | last)/maven-metadata.xml"
    }
}

# For every [versions] key: one module (or plugin) that uses it, to look its releases up by.
export def catalog-lookups [catalog: record]: nothing -> list<record<key: string, url: string>> {
    let libs = ($catalog.libraries? | default {} | transpose name lib
        | where { |r| ($r.lib | describe | str starts-with "record") and ($r.lib.version?.ref? != null) }
        | each { |r| { key: $r.lib.version.ref, url: (metadata-url $r.lib.module) } })
    let plugins = ($catalog.plugins? | default {} | transpose name plugin
        | where { |r| $r.plugin.version?.ref? != null }
        | each { |r| { key: $r.plugin.version.ref, url: (metadata-url $r.plugin.id --plugin) } })
    $libs | append $plugins | uniq-by key
}

# All published versions in a maven-metadata.xml document.
export def metadata-versions []: string -> list<string> {
    $in | parse --regex '<version>([^<]+)</version>' | get capture0
}

# Rewrites `key = "old"` lines in the catalog text, keeping comments and layout.
export def apply-updates [updates: list<record<key: string, from: string, to: string>>]: string -> string {
    mut text = $in
    for u in $updates {
        $text = ($text | str replace --regex $'\n($u.key) = "($u.from | str replace --all "." "\\.")"' $"\n($u.key) = \"($u.to)\"")
    }
    $text
}

# Looks up every catalog version online and returns the ones with a newer allowed release.
export def outdated-catalog [path: string = "gradle/libs.versions.toml"]: nothing -> list<record> {
    let catalog = (open $path)
    catalog-lookups $catalog | each { |l|
        let current = ($catalog.versions | get $l.key)
        let policy = ($CATALOG_POLICY | get --optional $l.key | default "major")
        let latest = try { http get --raw $l.url | metadata-versions | pick-latest $current $policy } catch { $current }
        { key: $l.key, from: $current, to: $latest }
    } | where { |u| $u.from != $u.to }
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
    # GitHub refuses a workflow-file push from the default GITHUB_TOKEN (no
    # `workflows` permission), so the nightly job sets SKIP_GITHUB_WORKFLOW_SYNC
    # when no PAT is available. The pin is only an offline fallback there.
    let skip_github = (($env.SKIP_GITHUB_WORKFLOW_SYNC? | default "false") == "true")
    let files = (
        [
            ".github/workflows/ci.yml"
            ".github/workflows/release.yml"
            ".gitea/workflows/ci.yml"
            ".gitea/workflows/release.yml"
        ]
        | where {|f| not ($skip_github and ($f | str starts-with ".github/")) }
    )
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

    print "▸ Checking the version catalog..."
    let updates = (outdated-catalog)
    if ($updates | is-empty) {
        print "  Catalog is up to date."
    } else {
        print ($updates | table)
    }

    if $check {
        print "  (dry run — not applying changes)"
        return
    }

    print "▸ Updating Gradle wrapper..."
    run-external "./gradlew" "wrapper" $"--gradle-version=($latest)" "--no-daemon"
    run-external "./gradlew" "wrapper" $"--gradle-version=($latest)" "--no-daemon"

    print "▸ Syncing pinned gradle-wrapper.jar checksum in CI workflows..."
    sync-wrapper-checksum

    if not ($updates | is-empty) {
        print "▸ Updating gradle/libs.versions.toml..."
        open --raw gradle/libs.versions.toml | apply-updates $updates | save -f gradle/libs.versions.toml
        # Kotlin/JS + Wasm yarn locks follow the new versions.
        run-external "./gradlew" "kotlinUpgradeYarnLock" "kotlinWasmUpgradeYarnLock" "--no-daemon"
    }

    if not (has-changes) {
        print "✅ All dependencies already up to date."
        return
    }

    let diff = (do { git diff --name-only } | complete)
    print "▸ Changed files:"
    print $diff.stdout

    print "▸ Running quality gate..."
    run-external "./gradlew" "assemble" "jvmTest" "jsNodeTest" "--no-daemon" "--stacktrace"

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
