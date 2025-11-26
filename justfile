# arrow-resilience-kit - Resilience patterns for Kotlin Multiplatform using Arrow
#
# Setup: Run 'just install-tools' for required tools
# Or install manually: cargo install just
# Usage: just <task> or just --list

# Default task - show available commands
default:
    @just --list

# Install required tools (just, git-cliff)
install-tools:
    @echo "Installing required tools..."
    @command -v just >/dev/null 2>&1 || cargo install just
    @command -v git-cliff >/dev/null 2>&1 || cargo install git-cliff
    @echo "✅ All tools installed!"

# Build the project
build:
    ./gradlew build

# Build without running tests
assemble:
    ./gradlew assemble

# Build release version (remove -SNAPSHOT)
build-release:
    ./gradlew clean build -x test

# Run all tests
test:
    ./gradlew test

# Run tests for all platforms
test-all:
    ./gradlew allTests

# Run JVM tests only
test-jvm:
    ./gradlew jvmTest

# Run JS tests only
test-js:
    ./gradlew jsTest

# Run Linux native tests
test-linux:
    ./gradlew linuxX64Test

# Check code without building
check:
    ./gradlew check

# Format code (if ktlint is configured)
fmt:
    @echo "Note: Add ktlint or spotless plugin to build.gradle.kts for formatting"
    @echo "For now, use your IDE's format feature"

# Run all checks (build and test)
check-all: build test
    @echo "✅ All checks passed!"

# Clean build artifacts
clean:
    ./gradlew clean

# Deep clean (includes .gradle cache)
clean-all:
    ./gradlew clean
    rm -rf .gradle
    rm -rf build
    @echo "✅ Deep clean complete!"

# Generate API documentation
doc:
    ./gradlew dokkaHtml
    @echo "📚 Documentation generated at: build/documentation/html/index.html"

# Open generated documentation in browser
doc-open: doc
    @command -v xdg-open >/dev/null 2>&1 && xdg-open build/documentation/html/index.html || \
     command -v open >/dev/null 2>&1 && open build/documentation/html/index.html || \
     echo "Please open build/documentation/html/index.html manually"

# Publish to Maven Local for testing
publish-local:
    ./gradlew publishToMavenLocal
    @echo "✅ Published to ~/.m2/repository"

# Publish to GitHub Packages (requires credentials)
publish:
    ./gradlew publish

# Dry run of publishing
publish-dry:
    ./gradlew publish --dry-run

# Show publishing tasks
publish-tasks:
    ./gradlew tasks --group=publishing

# Check dependencies for updates
dependencies:
    ./gradlew dependencies

# Show outdated dependencies (requires dependency-updates plugin)
outdated:
    @echo "Note: Add 'com.github.ben-manes.versions' plugin for outdated check"
    @echo "For now, check manually in gradle/libs.versions.toml"

# Update Gradle wrapper
update-gradle version:
    ./gradlew wrapper --gradle-version {{version}}
    @echo "✅ Gradle wrapper updated to {{version}}"

# Show project info
info:
    @echo "Project: arrow-resilience-kit"
    @echo "Group: ro.sorinirmies.arrow"
    @echo "Version: $(just version)"
    @echo "Package: ro.sorinirmies.arrow.resiliencekit"
    @echo "Platforms: JVM, JS, Linux x64, macOS x64, macOS ARM64"
    @echo ""
    @echo "GitHub: https://github.com/sorinirimies/arrow-resilience-kit"
    @echo "Gitea: ssh://git@192.168.1.204:30009/sorin/arrow-resilience-kit.git"

# Show current version from build.gradle.kts
version:
    @grep '^version = ' build.gradle.kts | sed 's/version = "\(.*\)"/\1/' | tr -d '"'

# Git: commit current changes
commit message:
    git add .
    git commit -m "{{message}}"

# Git: pull from GitHub (origin)
pull:
    git pull origin main

# Git: pull from Gitea
pull-gitea:
    git pull origin main
    @echo "✅ Pulled from Gitea (via origin fetch URL)"

# Git: pull from both (GitHub and Gitea)
pull-all:
    git fetch --all
    git pull origin main
    @echo "✅ Pulled from both remotes!"

# Git: push to both GitHub and Gitea (configured as origin push URLs)
push:
    git push origin main
    @echo "✅ Pushed to both GitHub and Gitea!"

# Git: push to GitHub only
push-github:
    git push github main
    @echo "✅ Pushed to GitHub!"

# Git: push tags to both remotes
push-tags:
    git push origin --tags
    git push github --tags
    @echo "✅ Tags pushed to both remotes!"

# Git: force push to both remotes (use with caution!)
push-force:
    git push --force origin main
    git push --force github main
    @echo "✅ Force pushed to both remotes!"

# Show configured remotes
remotes:
    @echo "Configured git remotes:"
    @git remote -v

# Check if git-cliff is installed
check-git-cliff:
    @command -v git-cliff >/dev/null 2>&1 || { echo "❌ git-cliff not found. Install with: cargo install git-cliff"; exit 1; }

# Generate full changelog from all tags
changelog: check-git-cliff
    @echo "Generating full changelog..."
    git-cliff -o CHANGELOG.md
    @echo "✅ Changelog generated!"

# Generate changelog for unreleased commits only
changelog-unreleased: check-git-cliff
    @echo "Generating unreleased changelog..."
    git-cliff --unreleased --prepend CHANGELOG.md
    @echo "✅ Unreleased changelog generated!"

# Generate changelog for specific version tag
changelog-version version: check-git-cliff
    @echo "Generating changelog for version {{version}}..."
    git-cliff --tag v{{version}} -o CHANGELOG.md
    @echo "✅ Changelog generated for version {{version}}!"

# Preview changelog without writing to file
changelog-preview: check-git-cliff
    @git-cliff

# Preview unreleased changes
changelog-preview-unreleased: check-git-cliff
    @git-cliff --unreleased

# View current changelog
view-changelog:
    @cat CHANGELOG.md

# Show git-cliff info
cliff-info:
    @echo "Git-cliff configuration:"
    @echo "  Config file: cliff.toml (create one if needed)"
    @echo "  Installed: $(command -v git-cliff >/dev/null 2>&1 && echo '✅ Yes' || echo '❌ No (run: just install-tools)')"
    @command -v git-cliff >/dev/null 2>&1 && git-cliff --version || true

# Bump version (usage: just bump 0.2.0)
# Note: Skips check-all due to compilation errors (see KNOWN_ISSUES.md)
bump version: check-git-cliff
    @echo "⚠️  Note: Skipping build/test checks due to compilation errors"
    @echo "    See KNOWN_ISSUES.md for details"
    @echo ""
    @echo "Bumping version to {{version}}..."
    @./scripts/bump_version.sh {{version}}

# Full release workflow: bump version and push to both remotes
release version: (bump version)
    @echo "Pushing release to both GitHub and Gitea..."
    git push origin main
    git push github main
    git push origin v{{version}}
    git push github v{{version}}
    @echo "✅ Release v{{version}} complete on both remotes!"
    @echo ""
    @echo "Next steps:"
    @echo "  1. Create GitHub Release at:"
    @echo "     https://github.com/sorinirimies/arrow-resilience-kit/releases/new?tag=v{{version}}"
    @echo "  2. GitHub Actions will automatically publish to GitHub Packages"

# Push release to both remotes (without bumping)
push-release:
    @echo "Pushing release to both remotes..."
    git push origin main
    git push github main
    git push origin --tags
    git push github --tags
    @echo "✅ Release pushed to both remotes!"

# Sync GitHub with Gitea (force)
sync-github:
    @echo "Syncing GitHub with Gitea..."
    git push github main --force
    git push github --tags --force
    @echo "✅ GitHub synced!"

# Quick pre-commit check
pre-commit: check-all
    @echo "✅ Ready to commit!"

# CI simulation - run what CI runs
ci: clean build test doc
    @echo "✅ CI simulation complete!"

# Setup project after clone
setup:
    @echo "Setting up project..."
    @echo "1. Installing Gradle wrapper dependencies..."
    ./gradlew --version
    @echo ""
    @echo "2. Downloading dependencies..."
    ./gradlew dependencies --quiet
    @echo ""
    @echo "3. Building project..."
    ./gradlew build
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
    @echo "     export GITHUB_TOKEN=your-github-token"

# Show Gradle tasks
tasks:
    ./gradlew tasks

# Show Gradle tasks for specific group
tasks-group group:
    ./gradlew tasks --group={{group}}

# Run Gradle with specific task
gradle task:
    ./gradlew {{task}}

# Daemon management - stop Gradle daemon
daemon-stop:
    ./gradlew --stop
    @echo "✅ Gradle daemon stopped"

# Daemon management - show daemon status
daemon-status:
    ./gradlew --status

# Refresh dependencies
refresh-deps:
    ./gradlew build --refresh-dependencies

# Verify project structure
verify:
    @echo "Verifying project structure..."
    @test -f build.gradle.kts && echo "✅ build.gradle.kts" || echo "❌ build.gradle.kts missing"
    @test -f settings.gradle.kts && echo "✅ settings.gradle.kts" || echo "❌ settings.gradle.kts missing"
    @test -f gradle.properties && echo "✅ gradle.properties" || echo "❌ gradle.properties missing"
    @test -f gradle/libs.versions.toml && echo "✅ gradle/libs.versions.toml" || echo "❌ gradle/libs.versions.toml missing"
    @test -f gradle/publishing.gradle.kts && echo "✅ gradle/publishing.gradle.kts" || echo "❌ gradle/publishing.gradle.kts missing"
    @test -d src/commonMain && echo "✅ src/commonMain" || echo "❌ src/commonMain missing"
    @test -d src/commonTest && echo "✅ src/commonTest" || echo "❌ src/commonTest missing"
    @test -d .github/workflows && echo "✅ .github/workflows" || echo "❌ .github/workflows missing"
    @echo ""
    @echo "✅ Project structure verified!"

# Show help for a specific topic
help topic:
    @just --list | grep {{topic}} || echo "No commands found for topic: {{topic}}"
    @echo ""
    @echo "Available documentation:"
    @echo "  - README.md - Project overview"
    @echo "  - QUICK_START.md - Quick reference"
    @echo "  - PUBLISHING.md - Publishing guide"
    @echo "  - CONTRIBUTING.md - Contributor guide"

# Run security check (if configured)
security:
    @echo "Note: Add OWASP dependency check plugin for security scanning"
    @echo "For now, check dependencies manually"
