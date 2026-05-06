# arrow-resilience-kit — Resilience patterns for Kotlin Multiplatform using Arrow
#
# Install just:      cargo install just
# Install git-cliff: cargo install git-cliff
# Usage: just <task>
# ── Default ───────────────────────────────────────────────────────────────────

default:
    @just --list

# ── Tool checks ───────────────────────────────────────────────────────────────

_check-git-cliff:
    @command -v git-cliff >/dev/null 2>&1 || { \
        echo "❌ git-cliff not found. Install with: cargo install git-cliff"; exit 1; \
    }

# Install all recommended development tools
install-tools:
    @echo "Installing development tools…"
    @command -v git-cliff >/dev/null 2>&1 || cargo install git-cliff --locked
    @command -v gh >/dev/null 2>&1 || echo "⚠️  GitHub CLI (gh) not found. Install from: https://cli.github.com/"
    @echo "✅ All tools checked!"
    @echo ""
    @echo "Required tools:"
    @echo "  just:      $$(command -v just >/dev/null 2>&1 && echo '✅' || echo '❌')"
    @echo "  git-cliff: $$(command -v git-cliff >/dev/null 2>&1 && echo '✅' || echo '❌')"
    @echo "  gh:        $$(command -v gh >/dev/null 2>&1 && echo '✅' || echo '❌ (required for GitHub releases)')"

# ── Build ─────────────────────────────────────────────────────────────────────

# Build the project (all targets)
build:
    ./gradlew build --no-daemon

# Build without running tests
assemble:
    ./gradlew assemble --no-daemon

# Build release version (skip tests)
build-release:
    ./gradlew clean assemble --no-daemon -x test

# ── Run / Test ────────────────────────────────────────────────────────────────

# Run all tests
test:
    ./gradlew allTests --no-daemon

# Run JVM tests only
test-jvm:
    ./gradlew jvmTest --no-daemon

# Run JS tests only
test-js:
    ./gradlew jsTest --no-daemon

# Run Linux native tests
test-linux:
    ./gradlew linuxX64Test --no-daemon

# ── Code quality ──────────────────────────────────────────────────────────────

# Run all checks (build + test)
check:
    ./gradlew check --no-daemon

# Run detekt linting
lint:
    ./gradlew check --no-daemon

# Run all quality checks — must pass before a release.
check-all: build test
    @echo "✅ All checks passed!"

# Full pre-release quality gate — everything in check-all plus documentation.
check-release: check-all doc
    @echo "✅ Release quality gate passed (build + test + doc)!"

# ── Documentation ─────────────────────────────────────────────────────────────

# Generate API documentation (Dokka)
doc:
    ./gradlew dokkaHtml --no-daemon
    @echo "📚 Documentation generated at: build/docs/index.html"

# Generate and open docs in browser
doc-open: doc
    @command -v xdg-open >/dev/null 2>&1 && xdg-open build/docs/index.html || \
     command -v open >/dev/null 2>&1 && open build/docs/index.html || \
     echo "Please open build/docs/index.html manually"

# Generate docs for the full workspace (no browser)
doc-prepare:
    ./gradlew prepareDocs --no-daemon
    @echo "📚 Documentation prepared in docs/ directory"

# ── Changelog ─────────────────────────────────────────────────────────────────

# Regenerate the full CHANGELOG.md from all tags
changelog: _check-git-cliff
    @echo "Generating full changelog…"
    git-cliff --output CHANGELOG.md
    @echo "✅ CHANGELOG.md updated."

# Prepend only unreleased commits to CHANGELOG.md
changelog-unreleased: _check-git-cliff
    git-cliff --unreleased --prepend CHANGELOG.md
    @echo "✅ Unreleased changes prepended."

# Preview changelog for the next release without writing the file
changelog-preview: _check-git-cliff
    @git-cliff --unreleased

# ── Version bump ─────────────────────────────────────────────────────────────

# Show the current version from build.gradle.kts
version:
    @grep '^version = ' build.gradle.kts | sed 's/version = "\(.*\)"/\1/' | tr -d '"'

# Validate that a version string will produce a valid vX.Y.Z tag.
validate-tag version:
    #!/usr/bin/env sh
    TAG="v{{ version }}"
    if echo "$TAG" | grep -qE '^v[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?$'; then
        echo "✅ Tag $TAG is valid."
    else
        echo "❌ Tag '$TAG' does not match vX.Y.Z — aborting."
        exit 1
    fi

# Fail fast if the requested version is the same as the current one.
_check-version-changed version:
    #!/usr/bin/env sh
    current=$(grep '^version = ' build.gradle.kts | sed 's/version = "\(.*\)"/\1/' | tr -d '"')
    if [ "$current" = "{{ version }}" ]; then
        echo "❌ Version {{ version }} is already the current version. Nothing to bump."
        exit 1
    fi
    echo "✅ Version will change: $current → {{ version }}"

# Bump the version, regenerate CHANGELOG.md, commit and tag.
bump version: (validate-tag version) (_check-version-changed version) check-all _check-git-cliff
    @./scripts/bump_version.sh {{ version }}

# ── Publish ───────────────────────────────────────────────────────────────────

# Publish to Maven Local for testing
publish-local:
    ./gradlew publishToMavenLocal --no-daemon
    @echo "✅ Published to ~/.m2/repository"

# Publish to GitHub Packages (requires credentials)
publish:
    ./gradlew publish --no-daemon

# Dry run of publishing
publish-dry:
    ./gradlew publish --dry-run --no-daemon

# Show publishing tasks
publish-tasks:
    ./gradlew tasks --group=publishing

# ── Housekeeping ──────────────────────────────────────────────────────────────

# Remove build artifacts
clean:
    ./gradlew clean --no-daemon

# Deep clean (includes .gradle cache)
clean-all:
    ./gradlew clean --no-daemon
    rm -rf .gradle build
    @echo "✅ Deep clean complete!"

# Check dependencies
dependencies:
    ./gradlew dependencies

# Update Gradle wrapper
update-gradle version:
    ./gradlew wrapper --gradle-version {{ version }}
    @echo "✅ Gradle wrapper updated to {{ version }}"

# Refresh dependencies
refresh-deps:
    ./gradlew build --refresh-dependencies --no-daemon

# Stop Gradle daemon
daemon-stop:
    ./gradlew --stop
    @echo "✅ Gradle daemon stopped"

# Show all configured remotes
remotes:
    @git remote -v

# Show project info
info:
    @echo "Project: arrow-resilience-kit"
    @echo "Group:   ro.sorinirmies.arrow"
    @echo "Version: $(just version)"
    @echo "Platforms: JVM, JS, Linux x64, macOS x64, macOS ARM64"
    @echo ""
    @echo "GitHub:           git@github.com:sorinirimies/arrow-resilience-kit.git"
    @echo "Gitea:            ssh://git@192.168.1.204:30009/sorin/arrow-resilience-kit.git"
    @echo "Gitea Starscream: gitea@192.168.1.44:sorin/arrow-resilience-kit.git"

# ── Git remotes & pushing ────────────────────────────────────────────────────

# Push the current branch to GitHub (origin)
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
    git push github main             || failed="$failed github"
    git push gitea main              || failed="$failed gitea"
    git push gitea_starscream main   || failed="$failed gitea_starscream"
    if [ -n "$failed" ]; then
        echo "⚠️  Failed to push to:$failed"
    else
        echo "✅ Pushed to GitHub, Gitea, and Gitea Starscream!"
    fi

# Force-push the current branch to all remotes
push-all-force:
    #!/usr/bin/env sh
    failed=""
    git push --force github main             || failed="$failed github"
    git push --force gitea main              || failed="$failed gitea"
    git push --force gitea_starscream main   || failed="$failed gitea_starscream"
    if [ -n "$failed" ]; then
        echo "⚠️  Failed to force-push to:$failed"
    else
        echo "✅ Force-pushed to GitHub, Gitea, and Gitea Starscream!"
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
    git pull github main             || failed="$failed github"
    git pull gitea main              || failed="$failed gitea"
    git pull gitea_starscream main   || failed="$failed gitea_starscream"
    if [ -n "$failed" ]; then
        echo "⚠️  Failed to pull from:$failed"
    else
        echo "✅ Pulled from GitHub, Gitea, and Gitea Starscream!"
    fi

# Push all tags to GitHub
push-tags:
    git push github --tags

# Push all tags to all remotes (continues on failure)
push-tags-all:
    #!/usr/bin/env sh
    failed=""
    git push github --tags             || failed="$failed github"
    git push gitea --tags              || failed="$failed gitea"
    git push gitea_starscream --tags   || failed="$failed gitea_starscream"
    if [ -n "$failed" ]; then
        echo "⚠️  Failed to push tags to:$failed"
    else
        echo "✅ Tags pushed to all remotes!"
    fi

# ── Release workflows ─────────────────────────────────────────────────────────

# Bump, commit, tag, then push to GitHub — triggers Release workflow.
release version: (bump version)
    @echo "Pushing release v{{ version }} to GitHub…"
    git push --follow-tags github main
    @echo "✅ Release v{{ version }} pushed — Release workflow will trigger automatically."
    @echo "   https://github.com/sorinirimies/arrow-resilience-kit/actions"

# Bump, commit, tag, then push to Gitea only.
release-gitea version: (bump version)
    @echo "Pushing release v{{ version }} to Gitea…"
    git push --follow-tags gitea main
    @echo "✅ Release v{{ version }} live on Gitea."

# Bump, commit, tag, then push to Gitea Starscream only.
release-gitea-starscream version: (bump version)
    @echo "Pushing release v{{ version }} to Gitea Starscream…"
    git push --follow-tags gitea_starscream main
    @echo "✅ Release v{{ version }} live on Gitea Starscream."

# Bump, commit, tag, then push to all remotes (continues on failure).
release-all version: (bump version)
    #!/usr/bin/env sh
    echo "Pushing release v{{ version }} to all remotes…"
    failed=""
    git push --follow-tags github main             || failed="$failed github"
    git push --follow-tags gitea main              || failed="$failed gitea"
    git push --follow-tags gitea_starscream main   || failed="$failed gitea_starscream"
    if [ -n "$failed" ]; then
        echo "⚠️  Release v{{ version }} failed to push to:$failed"
    else
        echo "✅ Release v{{ version }} pushed to GitHub, Gitea, and Gitea Starscream!"
    fi

# Push the latest commit and all tags to every remote (no bump, continues on failure).
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

# Manually re-trigger the Release workflow for an existing tag via the gh CLI.
release-retrigger version:
    @command -v gh >/dev/null 2>&1 || { \
        echo "❌ GitHub CLI (gh) not found. Install from https://cli.github.com"; exit 1; \
    }
    @echo "Manually dispatching Release workflow for tag v{{ version }}…"
    gh workflow run release.yml --field tag=v{{ version }}
    @echo "✅ Dispatched — check progress at: https://github.com/sorinirimies/arrow-resilience-kit/actions"

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

# ── CI simulation ────────────────────────────────────────────────────────────

# CI simulation - run what CI runs
ci: clean build test doc
    @echo "✅ CI simulation complete!"

# Quick pre-commit check
pre-commit: check-all
    @echo "✅ Ready to commit!"

# ── Setup ─────────────────────────────────────────────────────────────────────

# Setup project after clone
setup:
    @echo "Setting up project…"
    @echo "1. Installing Gradle wrapper dependencies…"
    ./gradlew --version
    @echo ""
    @echo "2. Downloading dependencies…"
    ./gradlew dependencies --quiet --no-daemon
    @echo ""
    @echo "3. Building project…"
    ./gradlew build --no-daemon
    @echo ""
    @echo "✅ Setup complete!"
    @echo ""
    @echo "Configuration needed for publishing:"
    @echo "  1. Create ~/.gradle/gradle.properties with:"
    @echo "     gpr.user=your-github-username"
    @echo "     gpr.token=your-github-token"
    @echo ""
    @echo "  2. Or set environment variables:"
    @echo "     export GITHUB_ACTOR=your-github-username"
    @echo "     export GITHUB_PACKAGES_TOKEN=your-github-token"

# Verify project structure
verify:
    @echo "Verifying project structure…"
    @test -f build.gradle.kts && echo "✅ build.gradle.kts" || echo "❌ build.gradle.kts missing"
    @test -f settings.gradle.kts && echo "✅ settings.gradle.kts" || echo "❌ settings.gradle.kts missing"
    @test -f gradle.properties && echo "✅ gradle.properties" || echo "❌ gradle.properties missing"
    @test -d src/commonMain && echo "✅ src/commonMain" || echo "❌ src/commonMain missing"
    @test -d src/commonTest && echo "✅ src/commonTest" || echo "❌ src/commonTest missing"
    @test -d .github/workflows && echo "✅ .github/workflows" || echo "❌ .github/workflows missing"
    @test -d .gitea/workflows && echo "✅ .gitea/workflows" || echo "❌ .gitea/workflows missing"
    @echo ""
    @echo "✅ Project structure verified!"
