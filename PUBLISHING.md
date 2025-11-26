# Publishing Guide

This guide explains how to publish the Arrow Resilience Kit library to GitHub Packages.

## Table of Contents

- [Prerequisites](#prerequisites)
- [Configuration](#configuration)
- [Version Management](#version-management)
- [Manual Publishing](#manual-publishing)
- [Automated Publishing](#automated-publishing)
- [Consuming the Package](#consuming-the-package)
- [Troubleshooting](#troubleshooting)

## Prerequisites

### 1. GitHub Personal Access Token

You need a Personal Access Token (PAT) with the appropriate permissions:

**For Publishing:**
- `write:packages` - Upload packages to GitHub Package Registry
- `read:packages` - Download packages from GitHub Package Registry
- `repo` - Full control of private repositories (if the repo is private)

**For Consuming:**
- `read:packages` - Download packages from GitHub Package Registry

**Create a token:**
1. Go to GitHub Settings → Developer settings → Personal access tokens → Tokens (classic)
2. Click "Generate new token (classic)"
3. Give it a descriptive name (e.g., "arrow-resilience-kit-packages")
4. Select the required scopes
5. Click "Generate token"
6. **Important:** Copy the token immediately - you won't be able to see it again!

### 2. Gradle Configuration

The project uses Gradle Kotlin DSL with version catalogs (`gradle/libs.versions.toml`).

## Configuration

### Option 1: Environment Variables (Recommended for CI/CD)

Set these environment variables:

```bash
export GITHUB_ACTOR=your-github-username
export GITHUB_TOKEN=your-github-personal-access-token
```

### Option 2: Gradle Properties (Local Development)

Create or edit `gradle.properties` in your project root or `~/.gradle/gradle.properties`:

```properties
gpr.user=your-github-username
gpr.token=your-github-personal-access-token
```

**⚠️ Security Warning:** Never commit `gradle.properties` with tokens to version control!

### Option 3: Local Properties File (Most Secure)

Create a `local.properties` file (already in `.gitignore`):

```properties
gpr.user=your-github-username
gpr.token=your-github-personal-access-token
```

Then update `build.gradle.kts` to read from it:

```kotlin
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}
```

## Version Management

Versions follow [Semantic Versioning](https://semver.org/):

- **MAJOR.MINOR.PATCH** (e.g., `1.2.3`)
- Append `-SNAPSHOT` for development versions (e.g., `1.2.3-SNAPSHOT`)
- Use `-alpha`, `-beta`, `-rc` for pre-releases (e.g., `1.2.3-alpha.1`)

### Updating the Version

Edit `build.gradle.kts`:

```kotlin
version = "0.2.0-SNAPSHOT"  // Development version
// or
version = "0.2.0"           // Release version
```

### Version Strategy

- **Development:** Always end with `-SNAPSHOT`
- **Pre-release:** Use `-alpha.N`, `-beta.N`, or `-rc.N`
- **Release:** Remove all suffixes
- **Hotfix:** Increment PATCH version

## Manual Publishing

### 1. Prepare the Release

```bash
# Ensure you're on the main branch
git checkout main
git pull origin main

# Update version in build.gradle.kts (remove -SNAPSHOT)
# Example: version = "0.2.0"

# Commit the version change
git commit -am "chore: prepare release v0.2.0"
```

### 2. Build and Test

```bash
# Clean build
./gradlew clean

# Run all tests
./gradlew allTests

# Build all artifacts
./gradlew build
```

### 3. Publish to GitHub Packages

```bash
# Publish all publications
./gradlew publish

# Or publish specific targets
./gradlew publishJvmPublicationToGitHubPackagesRepository
./gradlew publishJsPublicationToGitHubPackagesRepository
./gradlew publishKotlinMultiplatformPublicationToGitHubPackagesRepository
```

### 4. Create Git Tag and Release

```bash
# Create and push tag
git tag -a v0.2.0 -m "Release version 0.2.0"
git push origin v0.2.0

# Create GitHub Release through the UI or GitHub CLI
gh release create v0.2.0 \
  --title "v0.2.0" \
  --notes "Release notes here" \
  --verify-tag
```

### 5. Bump to Next Development Version

```bash
# Update version in build.gradle.kts
# Example: version = "0.3.0-SNAPSHOT"

git commit -am "chore: bump version to 0.3.0-SNAPSHOT"
git push origin main
```

## Automated Publishing

### GitHub Actions Workflow

The project includes automated publishing workflows:

#### 1. On Release Creation (`.github/workflows/publish.yml`)

Automatically publishes when you create a GitHub Release:

1. Go to GitHub → Releases → "Draft a new release"
2. Choose or create a tag (e.g., `v0.2.0`)
3. Fill in the release title and notes
4. Click "Publish release"
5. The workflow automatically builds and publishes to GitHub Packages

#### 2. Manual Workflow Dispatch

Trigger publishing manually from GitHub Actions:

1. Go to GitHub → Actions → "Publish to GitHub Packages"
2. Click "Run workflow"
3. Select branch (usually `main`)
4. Click "Run workflow"

### Workflow Features

- ✅ Validates Gradle wrapper
- ✅ Runs full test suite
- ✅ Builds all platform artifacts (JVM, JS, Native)
- ✅ Publishes to GitHub Packages
- ✅ Uploads build artifacts
- ✅ Automatic credentials from GitHub Secrets

## Consuming the Package

### For Users

Users need to configure their project to download from GitHub Packages:

#### build.gradle.kts

```kotlin
repositories {
    mavenCentral()
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/sorinirimies/arrow-resilience-kit")
        credentials {
            username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
            password = project.findProperty("gpr.token") as String? ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("ro.sorinirmies.arrow:arrow-resilience-kit:0.2.0")
}
```

#### gradle.properties

```properties
gpr.user=their-github-username
gpr.token=their-github-token-with-read-packages
```

### Package URL

Published packages are available at:
```
https://github.com/sorinirimies/arrow-resilience-kit/packages
```

## Troubleshooting

### 401 Unauthorized

**Problem:** Publishing fails with 401 Unauthorized error.

**Solutions:**
1. Verify your Personal Access Token has `write:packages` scope
2. Check token hasn't expired
3. Ensure `GITHUB_ACTOR` matches the token owner
4. For organization repos, ensure you have write access

### 404 Not Found

**Problem:** Cannot find package when consuming.

**Solutions:**
1. Verify the package exists at `https://github.com/USERNAME/REPO/packages`
2. Check repository owner and name in the URL
3. Ensure your token has `read:packages` scope
4. For private repos, ensure you have access

### Version Already Exists

**Problem:** Cannot publish - version already exists.

**Solutions:**
1. For releases: Increment the version number
2. For snapshots: Delete the existing snapshot package and republish
3. Never republish the same release version

### Build Fails on Unsupported Platform

**Problem:** Build fails for macOS targets on Linux (or vice versa).

**Solution:** This is expected. Multiplatform Kotlin can only build native targets for the host OS. The CI/CD matrix handles this:
- Linux runners build: JVM, JS, Linux native
- macOS runners build: JVM, JS, macOS native

### Network/Timeout Issues

**Problem:** Publishing times out or fails with network errors.

**Solutions:**
1. Check your internet connection
2. Retry the publish command
3. Increase Gradle timeout: `org.gradle.internal.http.socketTimeout=120000`
4. Check GitHub Status: https://www.githubstatus.com/

### Gradle Daemon Issues

**Problem:** Weird caching or state issues.

**Solution:**
```bash
./gradlew --stop
./gradlew clean
./gradlew publish --no-daemon
```

## Best Practices

### 1. Version Control
- ✅ Tag every release
- ✅ Use semantic versioning
- ✅ Maintain a CHANGELOG.md
- ✅ Never delete or modify published releases

### 2. Security
- ✅ Never commit tokens to version control
- ✅ Use environment variables in CI/CD
- ✅ Rotate tokens periodically
- ✅ Use minimal required scopes
- ✅ Keep `.gitignore` up to date

### 3. Testing
- ✅ Always run full test suite before publishing
- ✅ Test on multiple platforms if possible
- ✅ Verify documentation is up to date
- ✅ Test the published package by consuming it

### 4. Documentation
- ✅ Update README with version numbers
- ✅ Document breaking changes
- ✅ Provide migration guides for major versions
- ✅ Keep examples current

### 5. Release Process
- ✅ Follow a consistent release schedule
- ✅ Announce releases (GitHub Releases, social media, etc.)
- ✅ Support at least one previous major version
- ✅ Respond to issues promptly

## Advanced Topics

### Signing Releases

For additional security, you can sign your releases with GPG:

1. Generate GPG key pair
2. Export private key: `gpg --export-secret-keys --armor YOUR_KEY_ID`
3. Add to GitHub Secrets or gradle.properties:
   ```properties
   signingKey=your-ascii-armored-private-key
   signingPassword=your-key-password
   ```

The build.gradle.kts already includes signing configuration (commented out).

### Publishing to Multiple Repositories

You can publish to multiple Maven repositories:

```kotlin
publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/sorinirimies/arrow-resilience-kit")
            credentials { /* ... */ }
        }
        maven {
            name = "MavenCentral"
            url = uri("https://oss.sonatype.org/service/local/staging/deploy/maven2/")
            credentials { /* ... */ }
        }
    }
}
```

### Snapshot Releases

For snapshot releases:
- Version must end with `-SNAPSHOT`
- Can be republished with the same version
- Useful for testing unreleased features
- Not recommended for production use

## Support

For issues or questions:
- Open an issue: https://github.com/sorinirimies/arrow-resilience-kit/issues
- GitHub Packages Documentation: https://docs.github.com/en/packages
- Gradle Publishing Guide: https://docs.gradle.org/current/userguide/publishing_maven.html