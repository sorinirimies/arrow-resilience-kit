# Changelog and Badges Setup

This document explains the git-cliff changelog generation and README badges configuration.

## Badges in README

The project now includes comprehensive badges at the top of README.md:

```markdown
[![GitHub Release](https://img.shields.io/github/v/release/sorinirimies/arrow-resilience-kit)](https://github.com/sorinirimies/arrow-resilience-kit/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![CI](https://github.com/sorinirimies/arrow-resilience-kit/actions/workflows/ci.yml/badge.svg)](https://github.com/sorinirimies/arrow-resilience-kit/actions/workflows/ci.yml)
[![Publish](https://github.com/sorinirimies/arrow-resilience-kit/actions/workflows/publish.yml/badge.svg)](https://github.com/sorinirimies/arrow-resilience-kit/actions/workflows/publish.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-blue.svg?logo=kotlin)](https://kotlinlang.org)
[![Arrow](https://img.shields.io/badge/Arrow-1.2.1-blue.svg)](https://arrow-kt.io)
```

### Badge Types

1. **GitHub Release** - Shows latest release version
2. **License** - MIT License badge
3. **CI** - Continuous Integration workflow status
4. **Publish** - Publishing workflow status
5. **Kotlin** - Kotlin version with logo
6. **Arrow** - Arrow-kt version

All badges link to relevant resources (releases, workflows, licenses, etc.)

## Git-Cliff Changelog

### Configuration

The `cliff.toml` configuration follows the same format as tui-slider:

```toml
[changelog]
header = """
# Changelog
All notable changes to this project will be documented in this file.

"""
body = """
{% if version -%}
## [{{ version | trim_start_matches(pat="v") }}] - {{ timestamp | date(format="%Y-%m-%d") }}
{% else -%}
## [unreleased]
{% endif -%}
{% for group, commits in commits | group_by(attribute="group") %}
### {{ group | upper_first }}
{% for commit in commits -%}
- {{ commit.message | upper_first }}
{% endfor -%}
{% endfor %}

"""
trim = true

[git]
conventional_commits = true
filter_unconventional = true
commit_parsers = [
  { message = "^feat", group = "Features"},
  { message = "^fix", group = "Bug Fixes"},
  { message = "^doc", group = "Documentation"},
  { message = "^perf", group = "Performance"},
  { message = "^refactor", group = "Refactor"},
  { message = "^chore", group = "Miscellaneous"},
]
```

### Key Features

- **Conventional Commits** - Follows conventional commit standard
- **Automatic Grouping** - Groups commits by type (Features, Bug Fixes, etc.)
- **Date Stamping** - Adds release date to each version
- **Clean Format** - Simple, readable changelog format

### Commit Types

Use these prefixes in your commit messages:

| Prefix | Group | Example |
|--------|-------|---------|
| `feat:` | Features | `feat: add retry mechanism` |
| `fix:` | Bug Fixes | `fix: circuit breaker race condition` |
| `doc:` | Documentation | `doc: update installation guide` |
| `perf:` | Performance | `perf: optimize cache lookup` |
| `refactor:` | Refactor | `refactor: simplify retry logic` |
| `chore:` | Miscellaneous | `chore: bump version to 0.2.0` |

### Using Justfile Commands

```bash
# Generate full changelog from all tags
just changelog

# Generate changelog for unreleased commits
just changelog-unreleased

# Preview without writing to file
just changelog-preview

# Preview unreleased changes
just changelog-preview-unreleased

# Generate for specific version
just changelog-version 0.2.0
```

### Manual Commands

```bash
# Generate full changelog
git-cliff -o CHANGELOG.md

# Generate unreleased changelog
git-cliff --unreleased --prepend CHANGELOG.md

# Preview
git-cliff

# Tag-specific
git-cliff --tag v0.2.0 -o CHANGELOG.md
```

## Changelog Example

After running `just changelog`, you get:

```markdown
# Changelog
All notable changes to this project will be documented in this file.

## [unreleased]

### Documentation
- Add Git dual-remote setup documentation
- Add badges to README and configure git-cliff changelog

### Features
- Initial setup with modular Gradle configuration and GitHub Packages publishing
- Add justfile workflow automation and git-cliff changelog generation
```

## Release Workflow

When you create a release:

```bash
# Full release workflow
just release 0.1.0

# This automatically:
# 1. Runs checks (build + test)
# 2. Updates version in build.gradle.kts
# 3. Generates CHANGELOG.md with new version section
# 4. Commits changes
# 5. Creates git tag v0.1.0
# 6. Pushes to both remotes
```

After release, CHANGELOG.md becomes:

```markdown
# Changelog
All notable changes to this project will be documented in this file.

## [0.1.0] - 2025-01-26

### Documentation
- Add Git dual-remote setup documentation
- Add badges to README and configure git-cliff changelog

### Features
- Initial setup with modular Gradle configuration and GitHub Packages publishing
- Add justfile workflow automation and git-cliff changelog generation

## [unreleased]
```

## Automatic Updates

The bump_version.sh script automatically:
1. Updates version in build.gradle.kts
2. Updates version references in README.md
3. Generates updated CHANGELOG.md
4. Creates commit and tag

## Best Practices

1. **Use Conventional Commits**
   ```bash
   just commit "feat: add new feature"
   just commit "fix: resolve bug"
   just commit "docs: update README"
   ```

2. **Preview Before Release**
   ```bash
   just changelog-preview-unreleased
   ```

3. **Keep Changelog Updated**
   ```bash
   # After several commits
   just changelog-unreleased
   ```

4. **Full Release Process**
   ```bash
   just release 0.1.0
   # Creates version, updates changelog, pushes to both remotes
   ```

## Comparison with tui-slider

The configuration is identical to tui-slider:
- ✅ Same cliff.toml format
- ✅ Same commit parsers
- ✅ Same changelog structure
- ✅ Same conventional commit style

## Files

- `cliff.toml` - git-cliff configuration
- `CHANGELOG.md` - Generated changelog
- `README.md` - Project readme with badges
- `scripts/bump_version.sh` - Automated version bumping (includes changelog)

## Integration with Justfile

All changelog commands are available in justfile:

```bash
just changelog          # Generate full
just changelog-preview  # Preview
just bump 0.2.0        # Bump version (includes changelog)
just release 0.2.0     # Full release (includes changelog)
```

## Troubleshooting

### Changelog Empty

```bash
# Make sure you have commits following conventional format
git log --oneline

# Should have commits like:
# feat: add feature
# fix: fix bug
# docs: update docs
```

### Badge Not Showing

Check that:
1. Workflow files exist in `.github/workflows/`
2. Repository is public or badges are configured for private repos
3. At least one workflow run has completed

### Git-cliff Not Found

```bash
# Install git-cliff
just install-tools

# Or manually
cargo install git-cliff
```

## Links

- [git-cliff Documentation](https://git-cliff.org/)
- [Conventional Commits](https://www.conventionalcommits.org/)
- [Shields.io](https://shields.io/) - Badge generator

---

**Status:** Configured and working ✅  
**Format:** Matches tui-slider  
**Automation:** Integrated with justfile
