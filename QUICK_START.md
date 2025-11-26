# Quick Start Guide

Get up and running with Arrow Resilience Kit development and publishing in minutes.

## 🚀 Initial Setup

### 1. Clone and Build

```bash
git clone https://github.com/sorinirimies/arrow-resilience-kit.git
cd arrow-resilience-kit
./gradlew build
```

### 2. Run Tests

```bash
# All tests
./gradlew test

# Specific platform
./gradlew jvmTest
./gradlew jsTest
./gradlew linuxX64Test
```

## 📦 Publishing to GitHub Packages

### Setup (One-Time)

1. **Create GitHub Personal Access Token**
   - Go to: https://github.com/settings/tokens
   - Click "Generate new token (classic)"
   - Select scopes: `write:packages`, `read:packages`
   - Copy the token

2. **Configure Credentials**

   Add to `~/.gradle/gradle.properties`:
   ```properties
   gpr.user=your-github-username
   gpr.token=ghp_your_token_here
   ```

   Or set environment variables:
   ```bash
   export GITHUB_ACTOR=your-github-username
   export GITHUB_TOKEN=ghp_your_token_here
   ```

### Publish

```bash
# Clean, build, test, and publish
./gradlew clean build test publish
```

## 📥 Using the Library

### Add to Your Project

**build.gradle.kts:**
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
    implementation("ro.sorinirmies.arrow:arrow-resilience-kit:0.1.0-SNAPSHOT")
}
```

**gradle.properties:**
```properties
gpr.user=your-github-username
gpr.token=ghp_your_token_here
```

## 🔧 Common Commands

### Build & Test

```bash
# Clean build
./gradlew clean build

# Run all tests
./gradlew allTests

# Build without tests
./gradlew assemble

# Check for issues
./gradlew check
```

### Documentation

```bash
# Generate API docs
./gradlew dokkaHtml

# View docs
open build/documentation/html/index.html
```

### Platform-Specific

```bash
# JVM only
./gradlew jvmJar

# JavaScript only
./gradlew jsJar

# Native targets
./gradlew linuxX64Binaries
./gradlew macosX64Binaries
```

### Publishing

```bash
# Publish all platforms
./gradlew publish

# Publish to local Maven
./gradlew publishToMavenLocal

# Specific platform
./gradlew publishJvmPublicationToGitHubPackagesRepository
```

## 🏷️ Release Process

### 1. Prepare Release

```bash
# Update version in build.gradle.kts
# Change: version = "0.2.0-SNAPSHOT"
# To:     version = "0.2.0"

git commit -am "chore: prepare release v0.2.0"
git tag -a v0.2.0 -m "Release v0.2.0"
```

### 2. Publish

```bash
./gradlew clean build test publish
```

### 3. Create GitHub Release

```bash
git push origin v0.2.0
# Then create release on GitHub UI
```

### 4. Bump Version

```bash
# Update version in build.gradle.kts
# Change: version = "0.2.0"
# To:     version = "0.3.0-SNAPSHOT"

git commit -am "chore: bump version to 0.3.0-SNAPSHOT"
git push origin main
```

## 🤖 GitHub Actions

### Automated Publishing

**On Release:**
1. Go to GitHub → Releases → "Draft a new release"
2. Create tag: `v0.2.0`
3. Publish release
4. GitHub Actions automatically publishes to packages

**Manual Trigger:**
1. Go to Actions → "Publish to GitHub Packages"
2. Click "Run workflow"
3. Select branch and run

## 🐛 Troubleshooting

### 401 Unauthorized

```bash
# Check token
echo $GITHUB_TOKEN

# Verify token has correct scopes
# Regenerate token if needed
```

### Build Fails

```bash
# Stop daemon and retry
./gradlew --stop
./gradlew clean build --no-daemon
```

### Cannot Find Package

```bash
# Verify package exists
open https://github.com/sorinirimies/arrow-resilience-kit/packages

# Check credentials in gradle.properties
cat ~/.gradle/gradle.properties
```

## 📚 More Information

- **Full Publishing Guide:** See `PUBLISHING.md`
- **Contributing:** See `CONTRIBUTING.md`
- **Project Setup:** See `SETUP_SUMMARY.md`
- **Usage Examples:** See `README.md`

## 🎯 Quick Reference

| Task | Command |
|------|---------|
| Build | `./gradlew build` |
| Test | `./gradlew test` |
| Publish | `./gradlew publish` |
| Docs | `./gradlew dokkaHtml` |
| Clean | `./gradlew clean` |
| Check | `./gradlew check` |

## 📞 Support

- Issues: https://github.com/sorinirimies/arrow-resilience-kit/issues
- Discussions: https://github.com/sorinirimies/arrow-resilience-kit/discussions

---

**Ready to build?** Start with: `./gradlew build test`
