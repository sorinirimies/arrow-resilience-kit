# Documentation and Publishing Guide

## 📚 Documentation URL

The API documentation is automatically deployed to GitHub Pages:

**Live Documentation**: https://sorinirimies.github.io/arrow-resilience-kit/

This URL should be used in:
- README.md (already configured)
- GitHub repository description
- Release notes
- External references

---

## 🔄 How Documentation is Generated and Deployed

### Automatic Deployment (Development)

Documentation is automatically generated and deployed on every push to `main` that affects:
- Source files (`src/**`)
- `README.md`
- `Module.md`
- `build.gradle.kts`
- `.github/workflows/docs.yml`

**Workflow**: `.github/workflows/docs.yml`

**Process**:
1. Checkout code
2. Set up JDK 17
3. Generate Dokka HTML: `./gradlew dokkaHtml`
4. Copy to `docs/` directory
5. Create `.nojekyll` file (disables Jekyll processing)
6. Commit and push to `main`
7. GitHub Pages automatically serves from `docs/` directory

### Manual Documentation Update

To manually trigger documentation deployment:

```bash
# Via GitHub Actions UI
# Go to: Actions → Deploy Documentation (Development) → Run workflow

# Or locally generate and commit
just doc          # Generate docs
just doc-open     # Generate and open in browser
```

---

## 📦 Publishing to GitHub Packages

### Overview

The library is published to GitHub Packages, NOT to Maven Central. Users need to configure their build to pull from GitHub Packages.

### Triggering a Publish

Publishing is triggered by **creating a GitHub Release**, not by pushing a tag alone.

**Workflow**: `.github/workflows/publish.yml`

**Trigger**: 
```yaml
on:
  release:
    types: [created]
  workflow_dispatch:
```

### Release Process

#### Step 1: Version Bump (Local)

```bash
# Using justfile
just release 0.1.2

# This will:
# - Update version in build.gradle.kts
# - Update version in gradle.properties
# - Generate CHANGELOG.md
# - Create git commit
# - Create git tag v0.1.2
```

#### Step 2: Push to GitHub

```bash
# Push commits and tags
just push-release

# Or manually:
git push github main
git push github --tags
```

#### Step 3: Create GitHub Release

This is the crucial step that triggers publishing!

**Option A: Via GitHub Web UI**
1. Go to: https://github.com/sorinirimies/arrow-resilience-kit/releases/new
2. Select tag: `v0.1.2`
3. Set release title: `v0.1.2`
4. Add release notes from CHANGELOG.md
5. Click "Publish release"

**Option B: Via GitHub CLI**
```bash
gh release create v0.1.2 \
  --title "v0.1.2" \
  --notes-file CHANGELOG.md
```

**Option C: Manual Trigger**
```bash
# Go to GitHub Actions UI
# Navigate to: Publish to GitHub Packages
# Click: Run workflow
# Enter version (optional)
```

#### Step 4: Automated Publishing

Once the release is created, GitHub Actions will automatically:

1. ✅ Checkout code
2. ✅ Build project: `./gradlew assemble`
3. ✅ Generate documentation: `./gradlew dokkaHtml`
4. ✅ **Publish to GitHub Packages**: `./gradlew publish`
5. ✅ Update documentation in `docs/` directory
6. ✅ Commit and push documentation
7. ✅ Upload build artifacts

---

## 🔐 Required Secrets

For publishing to work, the following secret must be configured in GitHub:

### PACKAGES_PUBLISH

**Location**: Repository Settings → Secrets and variables → Actions → Repository secrets

**Value**: A GitHub Personal Access Token (classic) with scopes:
- `write:packages` - Required for publishing
- `read:packages` - Required for dependencies
- `repo` - Required for committing documentation

**Creating the Token**:
1. Go to: https://github.com/settings/tokens/new
2. Select scopes: `write:packages`, `read:packages`, `repo`
3. Generate token
4. Copy and save to repository secrets as `PACKAGES_PUBLISH`

---

## 📊 Verifying Publication

### Check GitHub Actions

1. Go to: https://github.com/sorinirimies/arrow-resilience-kit/actions
2. Look for "Publish to GitHub Packages" workflow
3. Check that all steps completed successfully

### Check GitHub Packages

1. Go to: https://github.com/sorinirimies?tab=packages
2. Look for `arrow-resilience-kit` package
3. Verify version is listed

### Check Documentation

1. Go to: https://sorinirimies.github.io/arrow-resilience-kit/
2. Verify documentation has been updated
3. Check version information at bottom

---

## 🚨 Troubleshooting

### Publishing Not Triggered

**Problem**: Tag was pushed but `publish.yml` didn't run

**Cause**: Workflow is triggered by **GitHub Release creation**, not tag pushes

**Solution**: Create a GitHub Release for the tag

### Documentation Not Updating

**Problem**: Docs workflow ran but GitHub Pages shows old content

**Causes**:
1. GitHub Pages not enabled
2. Wrong source branch/directory configured
3. Build failed but was not visible

**Solutions**:

1. **Enable GitHub Pages**:
   - Repository Settings → Pages
   - Source: Deploy from a branch
   - Branch: `main`, Directory: `/docs`

2. **Check workflow logs**:
   ```bash
   # View recent workflow runs
   gh run list --workflow=docs.yml
   
   # View specific run
   gh run view <run-id> --log
   ```

3. **Manual fix**:
   ```bash
   # Generate docs locally
   ./gradlew dokkaHtml
   
   # Copy to docs
   rm -rf docs/
   mkdir -p docs
   cp -r build/docs/* docs/
   touch docs/.nojekyll
   
   # Commit and push
   git add docs/
   git commit -m "docs: manual documentation update"
   git push github main
   ```

### Publish Fails with Authentication Error

**Problem**: 
```
Could not HEAD 'https://maven.pkg.github.com/...'. Received status code 401
```

**Causes**:
1. `PACKAGES_PUBLISH` secret not set
2. Token expired or invalid
3. Token missing required scopes

**Solutions**:
1. Verify secret exists in repository settings
2. Generate new token with correct scopes
3. Update `PACKAGES_PUBLISH` secret

### Documentation Build Fails

**Problem**: Dokka task fails with parse errors

**Cause**: Invalid markdown in `Module.md`

**Solution**: Keep `Module.md` simple, avoid:
- Nested headers (use `##` max)
- Complex code blocks
- Special characters in headers
- Inline HTML

---

## 📋 Complete Release Checklist

Use this checklist for every release:

- [ ] **Code Ready**
  - [ ] All features complete
  - [ ] Tests passing (or skipped with note)
  - [ ] No compiler warnings
  - [ ] Documentation comments up to date

- [ ] **Local Build**
  ```bash
  ./gradlew clean assemble dokkaHtml
  ```

- [ ] **Version Bump**
  ```bash
  just release <version>
  ```

- [ ] **Push to GitHub**
  ```bash
  just push-release
  # OR
  git push github main
  git push github --tags
  ```

- [ ] **Create GitHub Release**
  - Go to: https://github.com/sorinirimies/arrow-resilience-kit/releases/new
  - Select tag: `v<version>`
  - Add release notes from CHANGELOG.md
  - Click "Publish release"

- [ ] **Verify Publication**
  - [ ] Check GitHub Actions succeeded
  - [ ] Verify package appears in GitHub Packages
  - [ ] Verify documentation updated at https://sorinirimies.github.io/arrow-resilience-kit/
  - [ ] Test installation from GitHub Packages

- [ ] **Announce**
  - [ ] Update repository description if needed
  - [ ] Share on social media (optional)
  - [ ] Update dependent projects

---

## 🔗 Quick Links

### GitHub
- **Repository**: https://github.com/sorinirimies/arrow-resilience-kit
- **Releases**: https://github.com/sorinirimies/arrow-resilience-kit/releases
- **Packages**: https://github.com/sorinirimies?tab=packages
- **Actions**: https://github.com/sorinirimies/arrow-resilience-kit/actions

### Documentation
- **Live Docs**: https://sorinirimies.github.io/arrow-resilience-kit/
- **README**: https://github.com/sorinirimies/arrow-resilience-kit/blob/main/README.md
- **Installation**: https://github.com/sorinirimies/arrow-resilience-kit/blob/main/INSTALLATION.md
- **Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/blob/main/CHANGELOG.md

### CI/CD Workflows
- **Docs Deploy**: `.github/workflows/docs.yml`
- **Publish**: `.github/workflows/publish.yml`
- **CI**: `.github/workflows/ci.yml`

---

## 📖 Related Documentation

- [Pre-Release Checklist](../PRE_RELEASE_CHECKLIST.md)
- [Release Guide](../RELEASE.md)
- [Token Setup Guide](../TOKEN_SETUP_GUIDE.md)
- [Dokka Fix](DOKKA_FIX.md)
- [Project Cleanup](PROJECT_CLEANUP.md)

---

## 📝 Notes

### Why GitHub Packages?

GitHub Packages is used instead of Maven Central because:
1. Tighter integration with GitHub
2. Simpler authentication (uses GitHub tokens)
3. Automatic linking to repository
4. No manual verification process

### Why Commit Documentation to Repo?

GitHub Pages can serve from:
1. Branch + directory (we use `main` + `/docs`)
2. Separate `gh-pages` branch
3. GitHub Actions artifact

We chose option 1 because:
- Simple and transparent
- Easy to verify locally
- Version controlled
- No additional configuration needed

### Documentation URL Format

The documentation URL follows the pattern:
```
https://<username>.github.io/<repository-name>/
```

For this project:
```
https://sorinirimies.github.io/arrow-resilience-kit/
```

This is automatically configured when GitHub Pages is enabled with:
- Source: `main` branch
- Directory: `/docs`

---

**Last Updated**: 2024-11-30  
**Status**: ✅ Documentation and publishing fully configured and working