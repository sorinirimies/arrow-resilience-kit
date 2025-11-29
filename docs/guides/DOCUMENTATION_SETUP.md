# Documentation Setup Guide

This guide explains how to generate, view, and deploy the API documentation for Arrow Resilience Kit using GitHub Pages with the `docs/` directory.

## Overview

Arrow Resilience Kit uses [Dokka](https://kotlin.github.io/dokka/) to generate API documentation from KDoc comments in the source code. The documentation is automatically deployed to GitHub Pages by committing to the `docs/` directory.

## GitHub Pages Configuration

The project is configured to serve documentation from the `docs/` directory on the `main` branch.

### Current Setup

- **Source**: `docs/` directory on `main` branch
- **URL**: `https://sorinirimies.github.io/arrow-resilience-kit/`
- **Auto-deploy**: Yes, via GitHub Actions workflows

### Verify GitHub Pages Settings

1. Go to your repository on GitHub
2. Click **Settings** → **Pages**
3. Verify configuration:
   - **Source**: Deploy from a branch
   - **Branch**: `main`
   - **Folder**: `/docs`
4. Save if any changes were made

## Automatic Documentation Deployment

Documentation is automatically generated and deployed in two scenarios:

### 1. On Release (Publish Workflow)

**Trigger**: When a new GitHub Release is created

**Workflow**: `.github/workflows/publish.yml`

**What it does**:
1. Builds the project
2. Publishes to GitHub Packages
3. Generates Dokka documentation
4. Commits docs to `docs/` directory
5. Pushes to main branch
6. GitHub Pages automatically updates

**Example**:
```bash
# Create a release
git tag v0.1.0
git push origin v0.1.0

# Then create GitHub Release - workflow runs automatically
```

### 2. On Code Changes (Docs Workflow)

**Trigger**: Push to main branch with changes to:
- `src/**` - Source code
- `README.md` - README file
- `build.gradle.kts` - Build configuration
- `.github/workflows/docs.yml` - Workflow file

**Workflow**: `.github/workflows/docs.yml`

**What it does**:
1. Generates Dokka documentation
2. Commits docs to `docs/` directory
3. Pushes to main branch
4. GitHub Pages automatically updates

## Manual Documentation Generation

### Generate Locally

```bash
# Generate documentation
./gradlew dokkaHtml

# Output will be in: build/docs/
```

### View Locally

```bash
# Open in browser (Linux)
xdg-open build/docs/index.html

# Open in browser (macOS)
open build/docs/index.html

# Or with just
just doc-open
```

### Prepare for Manual Deployment

```bash
# Generate and prepare
./gradlew dokkaHtml
./gradlew prepareDocs

# This copies build/docs/ to docs/ with .nojekyll file
```

### Manual Commit and Push

```bash
# After prepareDocs
git add docs/
git commit -m "docs: update API documentation"
git push origin main

# GitHub Pages will update automatically
```

## Documentation Structure

```
docs/
├── .nojekyll                           # Prevents Jekyll processing
├── BUILD_INFO.html                     # Build metadata
├── VERSION.html                        # Version info (releases only)
├── index.html                          # Main entry point
├── navigation.html                     # Navigation menu
├── arrow-resilience-kit/               # Module documentation
│   └── ro.sorinirimies.arrow.resiliencekit/
│       ├── index.html                  # Package overview
│       ├── -circuit-breaker/          # Circuit Breaker docs
│       ├── -rate-limiter/             # Rate Limiter docs
│       ├── -bulkhead/                 # Bulkhead docs
│       └── ...
├── scripts/                            # JavaScript files
└── styles/                             # CSS stylesheets
```

## Writing Documentation

### KDoc Format

Use KDoc comments in your source code:

```kotlin
/**
 * Circuit breaker pattern implementation for fault tolerance.
 *
 * A circuit breaker monitors failures and prevents cascading failures by
 * temporarily blocking requests when a failure threshold is reached.
 *
 * ## States
 * - **CLOSED**: Normal operation, requests pass through
 * - **OPEN**: Failure threshold reached, requests fail immediately
 * - **HALF_OPEN**: Testing if service has recovered
 *
 * ## Example
 * ```kotlin
 * val breaker = CircuitBreaker(
 *     failureThreshold = 5,
 *     successThreshold = 2,
 *     timeout = 60.seconds
 * )
 *
 * val result = breaker.execute {
 *     externalService.call()
 * }
 * ```
 *
 * @property failureThreshold Number of failures before opening circuit
 * @property successThreshold Number of successes to close circuit
 * @property timeout Time to wait before attempting recovery
 * @see CircuitState
 * @since 0.1.0
 */
class CircuitBreaker(
    val failureThreshold: Int = 5,
    val successThreshold: Int = 2,
    val timeout: Duration = 60.seconds
) {
    /**
     * Executes the given [block] with circuit breaker protection.
     *
     * @param block The operation to execute
     * @return The result of the operation
     * @throws CircuitBreakerOpenException if circuit is open
     */
    suspend fun <T> execute(block: suspend () -> T): T {
        // Implementation
    }
}
```

### KDoc Tags

Common tags:

- `@param` - Parameter description
- `@return` - Return value description
- `@throws` - Exception description
- `@see` - Reference to related elements
- `@since` - Version when added
- `@sample` - Code sample reference
- `@suppress` - Suppress from documentation

### Markdown in KDoc

KDoc supports Markdown:

```kotlin
/**
 * # Main Title
 * 
 * ## Subtitle
 * 
 * Regular text with **bold** and *italic*.
 * 
 * - Bullet point 1
 * - Bullet point 2
 * 
 * ```kotlin
 * // Code block
 * val example = "value"
 * ```
 * 
 * [Link text](https://example.com)
 */
```

## Dokka Configuration

Configuration in `build.gradle.kts`:

```kotlin
tasks.dokkaHtml.configure {
    outputDirectory.set(layout.buildDirectory.dir("docs"))
    
    dokkaSourceSets {
        named("commonMain") {
            moduleName.set("Arrow Resilience Kit")
            
            // Include README in documentation
            includes.from("README.md")
            
            // Source code links to GitHub
            sourceLink {
                localDirectory.set(file("src/commonMain/kotlin"))
                remoteUrl.set(uri("https://github.com/sorinirimies/arrow-resilience-kit/tree/main/src/commonMain/kotlin").toURL())
                remoteLineSuffix.set("#L")
            }
            
            // External documentation links
            externalDocumentationLink {
                url.set(uri("https://arrow-kt.io/docs/").toURL())
            }
            
            externalDocumentationLink {
                url.set(uri("https://kotlinlang.org/api/kotlinx.coroutines/").toURL())
            }
        }
    }
}

// Task to prepare docs for GitHub Pages
tasks.register<Copy>("prepareDocs") {
    dependsOn(tasks.dokkaHtml)
    from(layout.buildDirectory.dir("docs"))
    into(file("docs"))
    
    doLast {
        file("docs/.nojekyll").writeText("")
        println("Documentation prepared in docs/ directory")
    }
}
```

## Workflow Details

### Publish Workflow (publish.yml)

```yaml
- name: Generate Dokka documentation
  run: ./gradlew dokkaHtml --no-daemon

- name: Prepare documentation for GitHub Pages
  run: |
    rm -rf docs/
    mkdir -p docs
    cp -r build/docs/* docs/
    touch docs/.nojekyll
    echo "Version: ${{ github.ref_name }}" > docs/VERSION.html

- name: Commit and push documentation
  run: |
    git config --local user.email "github-actions[bot]@users.noreply.github.com"
    git config --local user.name "github-actions[bot]"
    git add docs/
    git diff --staged --quiet || git commit -m "docs: update API documentation for ${{ github.ref_name }}"
    git push
```

### Docs Workflow (docs.yml)

Runs on every push to main that touches source code or documentation files.

## Troubleshooting

### Documentation Not Building

**Problem**: `./gradlew dokkaHtml` fails

**Solutions**:
```bash
# Clean build
./gradlew clean dokkaHtml --stacktrace

# Check for KDoc syntax errors
./gradlew compileKotlin

# View detailed logs
./gradlew dokkaHtml --info
```

### GitHub Pages Not Updating

**Problem**: Changes not visible on GitHub Pages

**Solutions**:
1. Check workflow status in **Actions** tab
2. Verify workflow completed successfully
3. Check if `docs/` directory was committed
4. Wait 2-5 minutes for GitHub Pages to update
5. Clear browser cache or use incognito mode
6. Verify Settings → Pages shows correct configuration

### Workflow Permission Issues

**Problem**: Workflow fails with permission errors

**Solutions**:
1. Go to **Settings** → **Actions** → **General**
2. Under "Workflow permissions", select:
   - **Read and write permissions**
3. Check "Allow GitHub Actions to create and approve pull requests"
4. Save and re-run workflow

### docs/ Directory Conflicts

**Problem**: Merge conflicts in `docs/` directory

**Solutions**:
```bash
# Always accept incoming changes for docs/
git checkout --theirs docs/
git add docs/
git commit

# Or delete and regenerate
rm -rf docs/
./gradlew prepareDocs
git add docs/
git commit -m "docs: regenerate API documentation"
```

### 404 Error on GitHub Pages

**Problem**: GitHub Pages shows 404

**Solutions**:
1. Verify `docs/index.html` exists
2. Check Settings → Pages configuration
3. Ensure branch is `main` and folder is `/docs`
4. Wait a few minutes after pushing
5. Check Actions tab for deployment status

## Best Practices

### 1. Keep docs/ Out of Normal Development

The `docs/` directory should be generated and managed by CI/CD:

```gitignore
# Generally ignore docs/ in development
docs/

# But don't add this to .gitignore since we need it for GitHub Pages
```

**Note**: Do NOT add `docs/` to `.gitignore` - it needs to be committed for GitHub Pages to work.

### 2. Document All Public APIs

Every public class, function, and property should have KDoc:

```kotlin
/**
 * Brief description.
 *
 * Detailed description with examples.
 *
 * @param name Parameter description
 * @return Return value description
 * @since 0.1.0
 */
public suspend fun operation(name: String): Result
```

### 3. Include Examples

Provide code examples in documentation:

```kotlin
/**
 * Rate limiter implementation.
 *
 * Example:
 * ```kotlin
 * val limiter = RateLimiter(permitsPerSecond = 10)
 * limiter.execute { apiCall() }
 * ```
 */
```

### 4. Link Related Items

Use `@see` to link related classes and functions:

```kotlin
/**
 * Circuit breaker implementation.
 *
 * @see RetryPolicy For retry mechanisms
 * @see Bulkhead For concurrency limiting
 */
```

### 5. Update Version Numbers

Always include `@since` for new APIs:

```kotlin
/**
 * New feature added in version 0.2.0.
 *
 * @since 0.2.0
 */
```

## Commands Reference

```bash
# Generate documentation locally
./gradlew dokkaHtml

# View documentation
just doc-open

# Prepare for GitHub Pages
./gradlew prepareDocs

# Manual deployment
./gradlew prepareDocs
git add docs/
git commit -m "docs: update API documentation"
git push origin main

# Clean and regenerate
./gradlew clean dokkaHtml
```

## Viewing Documentation

### Local
- **URL**: `file:///path/to/project/build/docs/index.html`
- **Command**: `just doc-open`

### GitHub Pages
- **URL**: `https://sorinirimies.github.io/arrow-resilience-kit/`
- **Update time**: 2-5 minutes after push
- **Automatically updated**: On every release and code change

## Resources

- **Dokka Documentation**: https://kotlin.github.io/dokka/
- **KDoc Format**: https://kotlinlang.org/docs/kotlin-doc.html
- **GitHub Pages**: https://docs.github.com/en/pages
- **Markdown Guide**: https://www.markdownguide.org/

## Support

For documentation issues:

1. Check workflow logs in Actions tab
2. Verify `docs/` directory was updated
3. Review this guide
4. Open an issue on GitHub with:
   - Workflow run URL
   - Error messages
   - Steps to reproduce

---

**Next Steps**:
1. Documentation is already configured ✅
2. Will auto-update on releases ✅
3. Will auto-update on code changes ✅
4. Just push to main and it works! 🚀