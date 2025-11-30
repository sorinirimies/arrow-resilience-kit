# Release Automation Guide

## Overview

The Arrow Resilience Kit has a fully automated release workflow. A single command handles everything from version bumping to package publishing.

## Quick Start

### Full Release

```bash
just release 0.2.0
```

This automatically:
1. ✅ Updates version in build files
2. ✅ Generates CHANGELOG.md
3. ✅ Creates git commit and tag
4. ✅ Pushes to GitHub
5. ✅ Syncs to Gitea
6. ✅ Creates GitHub Release
7. ✅ Triggers CI/CD publishing
8. ✅ Deploys documentation

---

## Commands Reference

### `just release <version>`
Complete automated release workflow.

```bash
just release 0.2.0
```

### `just create-release <tag>`
Create GitHub Release for existing tag.

```bash
just create-release v0.1.2
```

### `just push-release`
Push existing commits/tags and create GitHub Release.

```bash
just push-release
```

### `just sync-gitea`
Force sync Gitea with GitHub.

```bash
just sync-gitea
```

### `just sync-all`
Complete synchronization of all remotes.

```bash
just sync-all
```

---

## Git Strategy

**GitHub is the source of truth.** All releases push to GitHub first, then sync to Gitea.

### Why This Approach?

1. GitHub has the CI/CD workflows
2. GitHub Packages hosting
3. GitHub Pages for documentation
4. GitHub Releases for distribution
5. Gitea is backup/mirror only

### Handling Conflicts

If Gitea diverges from GitHub:

```bash
just sync-gitea
```

This will force-sync Gitea to match GitHub exactly.

---

## What Happens During Release

### 1. Version Bump
- Updates `build.gradle.kts`
- Updates `gradle.properties`
- Commits changes

### 2. Changelog Generation
- Runs `git-cliff`
- Generates from conventional commits
- Overwrites `CHANGELOG.md`

### 3. Git Operations
- Creates commit: `chore: bump version to X.Y.Z`
- Creates tag: `vX.Y.Z`
- Pushes to GitHub
- Attempts Gitea sync (non-critical)

### 4. GitHub Release
- Creates release via GitHub CLI
- Uses CHANGELOG.md as release notes
- Triggers `publish.yml` workflow

### 5. CI/CD Automation
GitHub Actions automatically:
- Builds project
- Generates Dokka documentation
- Publishes to GitHub Packages
- Updates documentation in `/docs`
- Deploys to GitHub Pages

---

## Prerequisites

### Required Tools

```bash
# Check what's installed
just install-tools
```

**Tools needed:**
- `just` - Task runner
- `git-cliff` - Changelog generator  
- `gh` - GitHub CLI (for releases)

### GitHub CLI Authentication

```bash
# Login to GitHub
gh auth login

# Verify
gh auth status
```

### Repository Secrets

Ensure `PACKAGES_PUBLISH` secret is set in:  
https://github.com/sorinirimies/arrow-resilience-kit/settings/secrets/actions

Token needs scopes:
- `write:packages`
- `read:packages`
- `repo`

---

## Troubleshooting

### Release Creation Failed

```bash
# Check if gh is authenticated
gh auth status

# Re-authenticate if needed
gh auth login
```

### Gitea Push Failed

This is non-critical. The release continues. To sync later:

```bash
just sync-gitea
```

### CI/CD Workflow Failed

Check workflow logs:

```bash
gh run list
gh run view <run-id> --log
```

### Version Already Exists

```bash
# Delete tag locally and remotely
git tag -d v0.1.2
git push github :v0.1.2
git push origin :v0.1.2

# Then retry release
just release 0.1.2
```

---

## Manual Release (Fallback)

If automated release fails, you can do it manually:

```bash
# 1. Bump version
./scripts/bump_version.sh 0.2.0

# 2. Push to GitHub
git push github main
git push github v0.2.0

# 3. Create release manually
gh release create v0.2.0 \
  --title "v0.2.0" \
  --notes-file CHANGELOG.md
```

---

## Best Practices

### Commit Messages

Use conventional commits for better changelogs:

```
feat: add new retry strategy
fix: correct circuit breaker timeout
docs: update API documentation
chore: bump dependencies
test: add rate limiter tests
refactor: simplify cache implementation
```

### Version Numbering

Follow semantic versioning:
- **MAJOR** (1.0.0): Breaking changes
- **MINOR** (0.2.0): New features, backward compatible
- **PATCH** (0.1.1): Bug fixes, backward compatible

### Pre-Release Checklist

Before running `just release`:

- [ ] All tests passing locally
- [ ] Documentation updated
- [ ] CHANGELOG reviewed
- [ ] No uncommitted changes
- [ ] On `main` branch
- [ ] Pulled latest from GitHub

---

## Monitoring

### GitHub Actions

View all workflows:  
https://github.com/sorinirimies/arrow-resilience-kit/actions

### Package Registry

View published packages:  
https://github.com/sorinirimies?tab=packages

### Documentation

View live docs:  
https://sorinirimies.github.io/arrow-resilience-kit/

---

## Related Documentation

- [Documentation and Publishing Guide](DOCUMENTATION_AND_PUBLISHING.md)
- [Release Guide](../RELEASE.md)
- [Next Steps](../NEXT_STEPS.md)

---

**Summary**: Run `just release <version>` and everything happens automatically!
