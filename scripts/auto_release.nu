#!/usr/bin/env nu
# Bump the patch version and trigger the Release workflow.
#
# Meant to run right after an automated dependency-upgrade commit lands on
# main (the nightly deps-update workflow). Bumps X.Y.(Z+1), runs the full
# quality gate via bump_version.nu, pushes the commit + tag, then explicitly
# dispatches the Release workflow via the platform's API.
#
# Explicit dispatch (rather than relying on the tag push itself) is used
# because on GitHub, events created by the default GITHUB_TOKEN do not
# re-trigger other workflows — an explicit workflow_dispatch API call does.
#
# Usage:
#   nu scripts/auto_release.nu --platform github --repo owner/name --token $TOKEN
#   nu scripts/auto_release.nu --platform gitea  --repo owner/name --token $TOKEN --gitea-url https://gitea.example.com
#   nu scripts/auto_release.nu --platform github --check   # dry run — no side effects

export def bump-patch [version: string]: nothing -> string {
    let parts = ($version | split row '.')
    let major = ($parts | get 0)
    let minor = ($parts | get 1)
    let patch = ($parts | get 2 | into int) + 1
    $"($major).($minor).($patch)"
}

def dispatch-github [repo: string, token: string, tag: string] {
    print $"▸ Dispatching GitHub Release workflow for tag ($tag)..."
    http post $"https://api.github.com/repos/($repo)/actions/workflows/release.yml/dispatches" {
        ref: "main",
        inputs: { tag: $tag }
    } --content-type application/json --headers [
        Authorization $"Bearer ($token)"
        Accept "application/vnd.github+json"
        X-GitHub-Api-Version "2022-11-28"
    ]
    print "✅ Dispatched."
}

def dispatch-gitea [gitea_url: string, repo: string, token: string, tag: string] {
    print $"▸ Dispatching Gitea Release workflow for tag ($tag)..."
    http post $"($gitea_url)/api/v1/repos/($repo)/actions/workflows/release.yml/dispatches" {
        ref: "main",
        inputs: { tag: $tag }
    } --content-type application/json --headers [Authorization $"token ($token)"]
    print "✅ Dispatched."
}

def main [
    --platform: string       # "github" or "gitea"
    --repo: string = ""      # "owner/name"
    --token: string = ""     # token with actions:write (contents:write is also required to push)
    --gitea-url: string = "" # required when --platform gitea
    --remote: string = "origin"
    --branch: string = "main"
    --check                  # dry run — validate inputs and show the planned bump, no side effects
] {
    # Validate arguments up front, before touching git/gradle/network,
    # so bad invocations fail fast and cheaply.
    if not ($platform in ["github" "gitea"]) {
        print $"❌ Unknown platform: ($platform). Use 'github' or 'gitea'."
        exit 1
    }

    if $platform == "gitea" and ($gitea_url | is-empty) {
        print "❌ --gitea-url is required when --platform gitea"
        exit 1
    }

    if not $check and (($repo | is-empty) or ($token | is-empty)) {
        print "❌ --repo and --token are required (unless --check is used)"
        exit 1
    }

    let current = (nu scripts/version.nu | str trim)
    let next = (bump-patch $current)

    if $check {
        print $"▸ Would bump ($current) → ($next) and dispatch the ($platform) Release workflow for tag ($next)."
        return
    }

    print "╔══════════════════════════════════════╗"
    print "║   Auto-release after dependency bump ║"
    print "╚══════════════════════════════════════╝"
    print $"▸ Auto-bumping ($current) → ($next) after dependency upgrade"

    nu scripts/bump_version.nu $next

    print $"▸ Pushing commit and tag to ($remote)/($branch)..."
    run-external "git" "push" $remote $branch
    run-external "git" "push" $remote $next

    match $platform {
        "github" => (dispatch-github $repo $token $next)
        "gitea" => (dispatch-gitea $gitea_url $repo $token $next)
    }

    print $"✅ Released ($next) — build will run automatically."
}
