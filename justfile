# ╔══════════════════════════════════════════════════════════════╗
# ║  arrow-resilience-kit                                       ║
# ║  Resilience patterns for Kotlin Multiplatform using Arrow   ║
# ╚══════════════════════════════════════════════════════════════╝

set shell := ["sh", "-cu"]

# ── Default ────────────────────────────────────────────────────

default:
    @just --list

# ── Tool checks ────────────────────────────────────────────────

[private]
_check-nu:
    @which nu > /dev/null 2>&1 || (echo "❌ nushell not found. Install: https://www.nushell.sh" && exit 1)

[private]
_check-git-cliff:
    @which git-cliff > /dev/null 2>&1 || (echo "❌ git-cliff not found. Install: cargo install git-cliff" && exit 1)

[private]
_check-gh:
    @which gh > /dev/null 2>&1 || (echo "❌ GitHub CLI not found. Install: https://cli.github.com" && exit 1)

[private]
_check-version-changed version:
    #!/usr/bin/env sh
    current=$(grep '^version = ' build.gradle.kts | sed 's/version = "\(.*\)"/\1/' | tr -d '"')
    if [ "$current" = "{{ version }}" ]; then
        echo "❌ Version {{ version }} is already the current version. Nothing to bump."
        exit 1
    fi
    echo "✅ Version will change: $current → {{ version }}"

# ── Info ───────────────────────────────────────────────────────

# Print project version
version: _check-nu
    @nu scripts/version.nu

# Show project info
info:
    @echo "╔════════════════════════════════════════════╗"
    @echo "║   arrow-resilience-kit                     ║"
    @echo "╚════════════════════════════════════════════╝"
    @echo "  Group:     ro.sorinirmies.arrow"
    @echo "  Version:   $(just version)"
    @echo "  Platforms: JVM, JS, Linux x64, macOS x64, macOS ARM64"
    @echo ""
    @echo "  GitHub:           git@github.com:sorinirimies/arrow-resilience-kit.git"
    @echo "  Gitea:            ssh://git@192.168.1.204:30009/sorin/arrow-resilience-kit.git"
    @echo "  Gitea Starscream: gitea@192.168.1.44:sorin/arrow-resilience-kit.git"

# Show installed tools
tools:
    @echo "JDK:       $(java -version 2>&1 | head -1)"
    @echo "Gradle:    $(./gradlew --version 2>/dev/null | grep '^Gradle' || echo 'not found')"
    @echo "Nushell:   $(nu --version 2>/dev/null || echo 'not found')"
    @echo "git-cliff: $(git-cliff --version 2>/dev/null || echo 'not found')"
    @echo "gh:        $(gh --version 2>/dev/null | head -1 || echo 'not found')"

# Show all configured remotes
remotes:
    @git remote -v

# ── Build ──────────────────────────────────────────────────────

# Build the project (all targets)
build:
    ./gradlew assemble --no-daemon

# Build for release
build-release:
    ./gradlew clean assemble --no-daemon -x test -Prelease=true

# ── Test ───────────────────────────────────────────────────────

# Run JVM tests
test:
    ./gradlew jvmTest --no-daemon

# Run all platform tests
test-all:
    ./gradlew allTests --no-daemon

# Run JS tests only
test-js:
    ./gradlew jsTest --no-daemon

# Run Linux native tests
test-linux:
    ./gradlew linuxX64Test --no-daemon

# Run nushell script tests
test-nu: _check-nu
    @nu scripts/tests/run_all.nu

# Run nushell tests with filter
test-nu-filter FILTER: _check-nu
    @nu scripts/tests/run_all.nu --filter {{ FILTER }}

# ── Quality ────────────────────────────────────────────────────

# Run detekt linter
lint:
    ./gradlew detekt --no-daemon

# Full quality gate (lint + build + test)
check-all: _check-nu
    @nu scripts/quality_gate.nu

# Quality gate skipping tests
check-quick: _check-nu
    @nu scripts/quality_gate.nu --skip-test

# Quality gate skipping lint
check-no-lint: _check-nu
    @nu scripts/quality_gate.nu --skip-lint

# Quality gate — build only
check-build-only: _check-nu
    @nu scripts/quality_gate.nu --skip-lint --skip-test

# Full release readiness check
check-release: check-all doc
    @echo "✅ Release quality gate passed (lint + build + test + doc)!"

# Run Gradle check (compile + test)
check:
    ./gradlew check --no-daemon

# Quick pre-commit check
pre-commit: check-all
    @echo "✅ Ready to commit!"

# ── Documentation ──────────────────────────────────────────────

# Generate API documentation (Dokka)
doc:
    ./gradlew dokkaHtml --no-daemon
    @echo "📚 Documentation generated at: build/docs/index.html"

# Generate and open docs in browser
doc-open: doc
    @command -v xdg-open >/dev/null 2>&1 && xdg-open build/docs/index.html || \
     command -v open >/dev/null 2>&1 && open build/docs/index.html || \
     echo "Please open build/docs/index.html manually"

# ── Publishing ─────────────────────────────────────────────────

# Publish to local Maven
publish-local:
    ./gradlew publishToMavenLocal --no-daemon
    @echo "✅ Published to ~/.m2/repository"

# Publish to GitHub Packages
publish-github: _check-nu
    @nu scripts/publish.nu --target github

# Publish to all repositories
publish-all: _check-nu
    @nu scripts/publish.nu --target all

# Dry run publish
publish-dry: _check-nu
    @nu scripts/publish.nu --dry-run

# Show publishing tasks
publish-tasks:
    ./gradlew tasks --group=publishing

# ── Versioning ─────────────────────────────────────────────────

# Validate a release tag
validate-tag TAG: _check-nu
    @nu scripts/validate_tag.nu {{ TAG }}

# Bump version, quality gate, commit and tag
bump VERSION: _check-nu (validate-tag VERSION) (_check-version-changed VERSION)
    @nu scripts/bump_version.nu {{ VERSION }}

# ── Changelog ──────────────────────────────────────────────────

# Generate full CHANGELOG.md
changelog: _check-git-cliff
    git-cliff -o CHANGELOG.md
    @echo "✅ CHANGELOG.md updated."

# Preview unreleased changes
changelog-unreleased: _check-git-cliff
    git-cliff --unreleased

# Prepend unreleased changes to CHANGELOG.md
changelog-prepend: _check-git-cliff
    git-cliff --unreleased --prepend CHANGELOG.md
    @echo "✅ Unreleased changes prepended."

# Generate release notes for a tag
release-notes TAG: _check-nu _check-git-cliff
    @nu scripts/release_notes.nu {{ TAG }}

# ── Dependencies ───────────────────────────────────────────────

# Check for dependency updates (dry run)
outdated: _check-nu
    @nu scripts/upgrade_deps.nu --check

# Upgrade all dependencies
update-deps: _check-nu
    @nu scripts/upgrade_deps.nu

# CI dependency upgrade (with auto-commit)
ci-update-deps: _check-nu
    @nu scripts/upgrade_deps.nu --commit

# Update Gradle wrapper
update-gradle VERSION:
    ./gradlew wrapper --gradle-version {{ VERSION }}
    @echo "✅ Gradle wrapper updated to {{ VERSION }}"

# Refresh Gradle dependency cache
refresh-deps:
    ./gradlew build --refresh-dependencies --no-daemon

# Show dependency tree
dependencies:
    ./gradlew dependencies

# ── Release ────────────────────────────────────────────────────

# Bump, commit, tag, then push to GitHub — triggers Release workflow
release VERSION: (bump VERSION)
    @echo "Pushing release {{ VERSION }} to GitHub…"
    git push --follow-tags github main
    @echo "✅ Release {{ VERSION }} pushed to GitHub — workflow will trigger automatically."

# Bump, commit, tag, then push to Gitea only
release-gitea VERSION: (bump VERSION)
    @echo "Pushing release {{ VERSION }} to Gitea…"
    git push --follow-tags gitea main
    @echo "✅ Release {{ VERSION }} live on Gitea."

# Bump, commit, tag, then push to Gitea Starscream only
release-gitea-starscream VERSION: (bump VERSION)
    @echo "Pushing release {{ VERSION }} to Gitea Starscream…"
    git push --follow-tags gitea_starscream main
    @echo "✅ Release {{ VERSION }} live on Gitea Starscream."

# Bump, commit, tag, then push to all remotes (continues on failure)
release-all VERSION: (bump VERSION)
    #!/usr/bin/env sh
    echo "Pushing release {{ VERSION }} to all remotes…"
    failed=""
    git push --follow-tags github main             || failed="$failed github"
    git push --follow-tags gitea main              || failed="$failed gitea"
    git push --follow-tags gitea_starscream main   || failed="$failed gitea_starscream"
    if [ -n "$failed" ]; then
        echo "⚠️  Release {{ VERSION }} failed to push to:$failed"
    else
        echo "✅ Release {{ VERSION }} pushed to GitHub, Gitea, and Gitea Starscream!"
    fi

# Create a Gitea release for a tag
create-gitea-release TAG: _check-nu
    @nu scripts/create_gitea_release.nu {{ TAG }}

# Manually re-trigger the Release workflow for an existing tag via the gh CLI
release-retrigger TAG: _check-gh
    @echo "Manually dispatching Release workflow for tag {{ TAG }}…"
    gh workflow run release.yml --field tag={{ TAG }}
    @echo "✅ Dispatched — check progress at: https://github.com/sorinirimies/arrow-resilience-kit/actions"

# Push latest commit + tags to every remote (no bump)
push-release-all: check-all
    #!/usr/bin/env sh
    failed=""
    git push --follow-tags github main             || failed="$failed github"
    git push --follow-tags gitea main              || failed="$failed gitea"
    git push --follow-tags gitea_starscream main   || failed="$failed gitea_starscream"
    if [ -n "$failed" ]; then
        echo "⚠️  Failed to push to:$failed"
    else
        echo "✅ Latest commit + tags pushed to all remotes."
    fi

# ── Git Remotes ────────────────────────────────────────────────

# Push the current branch to GitHub
push:
    git push github main

# Push the current branch to Gitea
push-gitea:
    git push gitea main

# Push the current branch to Gitea Starscream
push-gitea-starscream:
    git push gitea_starscream main

# Push the current branch to all remotes (continues on failure)
push-all:
    #!/usr/bin/env sh
    failed=""
    echo "▸ Pushing to github..."
    git push github main             || failed="$failed github"
    echo "▸ Pushing to gitea..."
    git push gitea main              || failed="$failed gitea"
    echo "▸ Pushing to gitea_starscream..."
    git push gitea_starscream main   || failed="$failed gitea_starscream"
    if [ -n "$failed" ]; then
        echo "⚠️  Failed to push to:$failed"
    else
        echo "✅ Pushed to GitHub, Gitea, and Gitea Starscream!"
    fi

# Force-push to all remotes
push-all-force:
    #!/usr/bin/env sh
    failed=""
    echo "▸ Force-pushing to github..."
    git push --force github main             || failed="$failed github"
    echo "▸ Force-pushing to gitea..."
    git push --force gitea main              || failed="$failed gitea"
    echo "▸ Force-pushing to gitea_starscream..."
    git push --force gitea_starscream main   || failed="$failed gitea_starscream"
    if [ -n "$failed" ]; then
        echo "⚠️  Failed to force-push to:$failed"
    else
        echo "✅ Force-pushed to GitHub, Gitea, and Gitea Starscream!"
    fi

# Push all tags to GitHub
push-tags:
    git push github --tags

# Push all tags to all remotes (continues on failure)
push-tags-all:
    #!/usr/bin/env sh
    failed=""
    echo "▸ Pushing tags to github..."
    git push github --tags             || failed="$failed github"
    echo "▸ Pushing tags to gitea..."
    git push gitea --tags              || failed="$failed gitea"
    echo "▸ Pushing tags to gitea_starscream..."
    git push gitea_starscream --tags   || failed="$failed gitea_starscream"
    if [ -n "$failed" ]; then
        echo "⚠️  Failed to push tags to:$failed"
    else
        echo "✅ Tags pushed to GitHub, Gitea, and Gitea Starscream!"
    fi

# Pull the current branch from GitHub
pull:
    git pull github main

# Pull the current branch from Gitea
pull-gitea:
    git pull gitea main

# Pull the current branch from Gitea Starscream
pull-gitea-starscream:
    git pull gitea_starscream main

# Pull the current branch from all remotes (continues on failure)
pull-all:
    #!/usr/bin/env sh
    failed=""
    echo "▸ Pulling from github..."
    git pull github main             || failed="$failed github"
    echo "▸ Pulling from gitea..."
    git pull gitea main              || failed="$failed gitea"
    echo "▸ Pulling from gitea_starscream..."
    git pull gitea_starscream main   || failed="$failed gitea_starscream"
    if [ -n "$failed" ]; then
        echo "⚠️  Failed to pull from:$failed"
    else
        echo "✅ Pulled from GitHub, Gitea, and Gitea Starscream!"
    fi

# Force-sync Gitea with GitHub
sync-gitea:
    git push gitea main --force
    git push gitea --tags --force
    @echo "✅ Gitea force-synced with GitHub."

# Force-sync Gitea Starscream with GitHub
sync-gitea-starscream:
    git push gitea_starscream main --force
    git push gitea_starscream --tags --force
    @echo "✅ Gitea Starscream force-synced with GitHub."

# Force-sync all Gitea instances with GitHub (continues on failure)
sync-all-gitea:
    #!/usr/bin/env sh
    failed=""
    git push gitea main --force                  || failed="$failed gitea"
    git push gitea --tags --force                || failed="$failed gitea-tags"
    git push gitea_starscream main --force       || failed="$failed gitea_starscream"
    git push gitea_starscream --tags --force     || failed="$failed gitea_starscream-tags"
    if [ -n "$failed" ]; then
        echo "⚠️  Failed to sync:$failed"
    else
        echo "✅ All Gitea instances force-synced with GitHub."
    fi

# ── Housekeeping ───────────────────────────────────────────────

# Clean build artifacts
clean: _check-nu
    @nu scripts/cleanup.nu

# Deep clean (includes .gradle project cache)
clean-all: _check-nu
    @nu scripts/cleanup.nu --deep

# CI cleanup (deep + stop daemon)
clean-ci: _check-nu
    @nu scripts/cleanup.nu --ci

# Show what would be cleaned (dry run)
clean-dry: _check-nu
    @nu scripts/cleanup.nu --dry-run

# Stop Gradle daemon
daemon-stop:
    ./gradlew --stop
    @echo "✅ Gradle daemon stopped."

# CI simulation — run what CI runs
ci: clean build test-all doc
    @echo "✅ CI simulation complete!"

# ── Setup ──────────────────────────────────────────────────────

# Setup project after clone
setup:
    @echo "╔════════════════════════════════════════════╗"
    @echo "║   Setting up arrow-resilience-kit          ║"
    @echo "╚════════════════════════════════════════════╝"
    @echo ""
    @echo "1. Configuring git remotes…"
    @just setup-remotes
    @echo ""
    @echo "2. Installing Gradle wrapper dependencies…"
    ./gradlew --version
    @echo ""
    @echo "3. Downloading dependencies…"
    ./gradlew dependencies --quiet --no-daemon
    @echo ""
    @echo "4. Building project…"
    ./gradlew build --no-daemon
    @echo ""
    @echo "✅ Setup complete!"

# Configure all git remotes (idempotent)
setup-remotes:
    #!/usr/bin/env sh
    set -eu
    add_or_update() {
        name="$1"; url="$2"
        if git remote get-url "$name" > /dev/null 2>&1; then
            current=$(git remote get-url "$name")
            if [ "$current" = "$url" ]; then
                echo "  ✓ $name already set"
            else
                git remote set-url "$name" "$url"
                echo "  ↻ $name updated → $url"
            fi
        else
            git remote add "$name" "$url"
            echo "  + $name added → $url"
        fi
    }
    add_or_update gitea_starscream "gitea@192.168.1.44:sorin/arrow-resilience-kit.git"
    add_or_update gitea            "ssh://git@192.168.1.204:30009/sorin/arrow-resilience-kit.git"
    add_or_update github           "https://github.com/sorinirimies/arrow-resilience-kit"
    echo "  ── Current remotes ──"
    git remote -v

# Verify project structure
verify:
    @echo "Verifying project structure…"
    @test -f build.gradle.kts && echo "✅ build.gradle.kts" || echo "❌ build.gradle.kts missing"
    @test -f settings.gradle.kts && echo "✅ settings.gradle.kts" || echo "❌ settings.gradle.kts missing"
    @test -f gradle.properties && echo "✅ gradle.properties" || echo "❌ gradle.properties missing"

# Install recommended development tools
install-tools:
    @echo "Installing development tools…"
    @command -v git-cliff >/dev/null 2>&1 || cargo install git-cliff --locked
    @command -v gh >/dev/null 2>&1 || echo "⚠️  gh not found. Install: https://cli.github.com/"
    @command -v nu >/dev/null 2>&1 || echo "⚠️  nu not found. Install: https://www.nushell.sh"
    @just tools
