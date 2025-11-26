# Justfile Workflow Guide

This project uses [just](https://github.com/casey/just) as a command runner for common development tasks. It provides a simple, unified interface for building, testing, publishing, and managing releases.

## Quick Start

```bash
# Show all available commands
just

# Build the project
just build

# Run tests
just test

# Push to both remotes
just push

# Create a release
just release 0.2.0
```

## Installation

### Install Just

```bash
# Using cargo (Rust)
cargo install just

# On macOS
brew install just

# On Arch Linux
pacman -S just

# On Ubuntu/Debian (snap)
snap install --edge --classic just
```

### Install Optional Tools

```bash
# Install all required tools (just + git-cliff)
just install-tools
```

## Command Categories

### 🔨 Build & Test

| Command | Description |
|---------|-------------|
| `just build` | Build the project |
| `just assemble` | Build without running tests |
| `just build-release` | Build release version |
| `just test` | Run all tests |
| `just test-all` | Run tests for all platforms |
| `just test-jvm` | Run JVM tests only |
| `just test-js` | Run JS tests only |
| `just test-linux` | Run Linux native tests |
| `just check` | Check code without building |
| `just check-all` | Run all checks (build + test) |
| `just clean` | Clean build artifacts |
| `just clean-all` | Deep clean (including .gradle) |

### 📚 Documentation

| Command | Description |
|---------|-------------|
| `just doc` | Generate API documentation |
| `just doc-open` | Generate and open documentation |
| `just view-changelog` | View current changelog |

### 📦 Publishing

| Command | Description |
|---------|-------------|
| `just publish-local` | Publish to Maven Local (~/.m2) |
| `just publish` | Publish to GitHub Packages |
| `just publish-dry` | Dry run of publishing |
| `just publish-tasks` | Show available publishing tasks |

### 🔖 Git & Release Management

| Command | Description |
|---------|-------------|
| `just commit "message"` | Commit current changes |
| `just pull` | Pull from origin (GitHub via fetch URL) |
| `just push` | Push to both GitHub and Gitea |
| `just push-github` | Push to GitHub only |
| `just push-tags` | Push tags to both remotes |
| `just push-force` | Force push to both remotes ⚠️ |
| `just remotes` | Show configured git remotes |

### 📝 Changelog

| Command | Description |
|---------|-------------|
| `just changelog` | Generate full changelog |
| `just changelog-unreleased` | Generate unreleased changelog |
| `just changelog-version 0.2.0` | Generate changelog for specific version |
| `just changelog-preview` | Preview changelog without writing |
| `just changelog-preview-unreleased` | Preview unreleased changes |

### 🚀 Release Workflow

| Command | Description |
|---------|-------------|
| `just bump 0.2.0` | Bump version (runs checks first) |
| `just release 0.2.0` | Full release: bump + push to both remotes |
| `just push-release` | Push existing release to both remotes |

### 🛠️ Utilities

| Command | Description |
|---------|-------------|
| `just info` | Show project information |
| `just version` | Show current version |
| `just verify` | Verify project structure |
| `just setup` | Setup project after clone |
| `just pre-commit` | Quick pre-commit check |
| `just ci` | Simulate CI build |
| `just daemon-stop` | Stop Gradle daemon |
| `just daemon-status` | Show Gradle daemon status |

## Common Workflows

### Development Workflow

```bash
# 1. Make changes to code
vim src/commonMain/kotlin/...

# 2. Run checks
just check-all

# 3. Commit
just commit "feat: add new feature"

# 4. Push to both remotes
just push
```

### Release Workflow

```bash
# Full release (recommended)
just release 0.2.0

# This will:
# 1. Run check-all (build + test)
# 2. Update version in build.gradle.kts
# 3. Update version in README.md
# 4. Generate CHANGELOG.md
# 5. Commit changes
# 6. Create git tag v0.2.0
# 7. Push to both GitHub and Gitea
# 8. Show next steps (create GitHub Release)
```

### Manual Release Steps

If you prefer manual control:

```bash
# 1. Bump version
just bump 0.2.0

# 2. Review changes
git show

# 3. Push to remotes
just push
just push-tags

# 4. Create GitHub Release (manual)
# Visit: https://github.com/sorinirimies/arrow-resilience-kit/releases
```

### Testing Workflow

```bash
# Run all tests
just test-all

# Or test specific platforms
just test-jvm
just test-js
just test-linux

# Check without building
just check
```

### Documentation Workflow

```bash
# Generate and open docs
just doc-open

# Or just generate
just doc

# View in browser manually
# build/documentation/html/index.html
```

### Publishing Workflow

```bash
# Test locally first
just publish-local

# Check what will be published
just publish-dry

# Publish to GitHub Packages
just publish

# Note: Publishing to GitHub Packages requires:
# - GITHUB_ACTOR and GITHUB_TOKEN environment variables
# - Or gpr.user and gpr.token in ~/.gradle/gradle.properties
```

## Git Remote Setup

This project pushes to both GitHub and Gitea simultaneously:

```bash
# Check remote configuration
just remotes

# Output shows:
# origin (fetch) - Gitea
# origin (push)  - Gitea
# origin (push)  - GitHub (second push URL)
# github (fetch) - GitHub
# github (push)  - GitHub
```

### How It Works

- `just push` → pushes to both Gitea and GitHub via origin
- `just push-github` → pushes to GitHub only
- Fetch always comes from Gitea (origin fetch URL)

## Version Management

### Version Format

Follow [Semantic Versioning](https://semver.org/):
- **MAJOR.MINOR.PATCH** (e.g., 1.2.3)
- **Development:** X.Y.Z-SNAPSHOT (e.g., 0.2.0-SNAPSHOT)
- **Pre-release:** X.Y.Z-alpha.N, X.Y.Z-beta.N (e.g., 1.0.0-beta.1)

### Version Locations

Versions are automatically updated in:
1. `build.gradle.kts` - Main version declaration
2. `README.md` - Installation examples
3. `CHANGELOG.md` - Generated by git-cliff

## Changelog Generation

This project uses [git-cliff](https://git-cliff.org/) for automatic changelog generation from git commits.

### Commit Message Convention

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <subject>

<body>

<footer>
```

**Types:**
- `feat:` - New features
- `fix:` - Bug fixes
- `docs:` - Documentation changes
- `refactor:` - Code refactoring
- `perf:` - Performance improvements
- `test:` - Test changes
- `chore:` - Maintenance tasks
- `style:` - Code style changes

**Examples:**
```bash
just commit "feat: add retry mechanism with exponential backoff"
just commit "fix: circuit breaker state transition race condition"
just commit "docs: update installation instructions"
```

### Generate Changelog

```bash
# Full changelog from all tags
just changelog

# Only unreleased changes
just changelog-unreleased

# Preview without writing
just changelog-preview
```

## CI Simulation

Test locally what CI will run:

```bash
just ci

# This runs:
# - clean
# - build
# - test
# - doc
```

## Gradle Tasks

Access Gradle directly when needed:

```bash
# Show all tasks
just tasks

# Show tasks for specific group
just tasks-group publishing
just tasks-group build
just tasks-group verification

# Run specific task
just gradle compileKotlinJvm
just gradle publishToMavenLocal
```

## Project Setup (New Clone)

```bash
# After cloning the repository
git clone https://github.com/sorinirimies/arrow-resilience-kit.git
cd arrow-resilience-kit

# Run setup
just setup

# This will:
# 1. Install Gradle wrapper dependencies
# 2. Download project dependencies
# 3. Build the project
# 4. Show configuration instructions
```

## Troubleshooting

### Build Fails

```bash
# Clean and rebuild
just clean-all
just build

# Stop daemon and retry
just daemon-stop
just build
```

### Tests Fail

```bash
# Run specific platform tests
just test-jvm
just test-js

# Check for compilation errors first
just check
```

### Push Fails

```bash
# Check remotes
just remotes

# Try pushing to one remote at a time
just push-github
git push origin main

# Force push if needed (use with caution!)
just push-force
```

### Publishing Fails (401 Unauthorized)

```bash
# Check credentials
echo $GITHUB_ACTOR
echo $GITHUB_TOKEN

# Or check ~/.gradle/gradle.properties
cat ~/.gradle/gradle.properties

# See PUBLISHING.md for detailed setup
```

## Environment Variables

### For Publishing

```bash
export GITHUB_ACTOR=your-github-username
export GITHUB_TOKEN=ghp_your_token_here
```

### For Signing (Optional)

```bash
export SIGNING_KEY=your-ascii-armored-key
export SIGNING_PASSWORD=your-key-password
```

## Tips & Best Practices

1. **Always run checks before committing**
   ```bash
   just pre-commit
   ```

2. **Use conventional commit messages**
   ```bash
   just commit "feat: your feature"
   ```

3. **Test releases locally first**
   ```bash
   just publish-local
   ```

4. **Preview changelog before release**
   ```bash
   just changelog-preview-unreleased
   ```

5. **Verify structure after major changes**
   ```bash
   just verify
   ```

## Related Documentation

- **QUICK_START.md** - Quick reference guide
- **PUBLISHING.md** - Detailed publishing guide
- **CONTRIBUTING.md** - Contributor guidelines
- **SETUP_SUMMARY.md** - Complete project setup

## Getting Help

```bash
# List all commands
just

# Show commands for specific topic
just help build
just help publish
just help git
```

## Advanced Usage

### Custom Gradle Arguments

```bash
# Run Gradle with custom args
just gradle build --info
just gradle test --debug
just gradle publish --stacktrace
```

### Refresh Dependencies

```bash
just refresh-deps
```

### Update Gradle Wrapper

```bash
just update-gradle 8.6
```

## Links

- [Just Manual](https://just.systems/man/en/)
- [Git-cliff Documentation](https://git-cliff.org/)
- [Conventional Commits](https://www.conventionalcommits.org/)
- [Semantic Versioning](https://semver.org/)