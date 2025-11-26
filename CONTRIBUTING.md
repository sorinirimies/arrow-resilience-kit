# Contributing to Arrow Resilience Kit

Thank you for your interest in contributing to Arrow Resilience Kit! This document provides guidelines and instructions for contributing.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Development Setup](#development-setup)
- [Building the Project](#building-the-project)
- [Testing](#testing)
- [Code Style](#code-style)
- [Submitting Changes](#submitting-changes)
- [Publishing](#publishing)

## Code of Conduct

Be respectful, professional, and constructive in all interactions. We aim to foster an inclusive and welcoming community.

## Getting Started

1. Fork the repository on GitHub
2. Clone your fork locally:
   ```bash
   git clone https://github.com/YOUR_USERNAME/arrow-resilience-kit.git
   cd arrow-resilience-kit
   ```
3. Add the upstream repository:
   ```bash
   git remote add upstream https://github.com/sorinirimies/arrow-resilience-kit.git
   ```

## Development Setup

### Prerequisites

- JDK 17 or higher
- Git

### IDE Setup

We recommend using IntelliJ IDEA for development:

1. Open the project in IntelliJ IDEA
2. The IDE should automatically detect the Gradle configuration
3. Wait for Gradle sync to complete

## Building the Project

### Build all targets

```bash
./gradlew build
```

### Build specific targets

```bash
# JVM only
./gradlew jvmJar

# JS only
./gradlew jsJar

# Native targets
./gradlew linuxX64Binaries
./gradlew macosX64Binaries
./gradlew macosArm64Binaries
```

### Clean build

```bash
./gradlew clean build
```

## Testing

### Run all tests

```bash
./gradlew allTests
```

### Run tests for specific platforms

```bash
# JVM tests
./gradlew jvmTest

# JS tests
./gradlew jsTest

# Native tests
./gradlew linuxX64Test
./gradlew macosX64Test
./gradlew macosArm64Test
```

### Run specific test

```bash
./gradlew test --tests "ClassName.testMethodName"
```

### Test Coverage

```bash
./gradlew koverReport
```

## Code Style

### Kotlin Coding Conventions

This project follows the [official Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html).

### Explicit API Mode

The project uses Explicit API mode. All public APIs must have explicit visibility modifiers and return types:

```kotlin
// Good
public fun processData(input: String): Result<Data>

// Bad (implicit public, implicit return type)
fun processData(input: String) = ...
```

### Documentation

- All public APIs must have KDoc comments
- Include `@param`, `@return`, and `@throws` tags where applicable
- Provide usage examples for complex APIs

Example:
```kotlin
/**
 * Executes the given action with retry logic using exponential backoff.
 *
 * @param retries Maximum number of retry attempts
 * @param initialDelay Initial delay between retries
 * @param maxDelay Maximum delay between retries
 * @param factor Exponential backoff factor
 * @param action The suspending action to execute
 * @return The result of the action
 * @throws MaxRetriesExceededException if all retry attempts fail
 */
public suspend fun <T> retryWithExponentialBackoff(
    retries: Int = 3,
    initialDelay: Duration = 1.seconds,
    maxDelay: Duration = 30.seconds,
    factor: Double = 2.0,
    action: suspend () -> T
): T
```

## Submitting Changes

### Branch Naming

Use descriptive branch names:
- `feature/add-timeout-handler`
- `fix/circuit-breaker-state-race`
- `docs/update-readme`
- `refactor/simplify-retry-logic`

### Commit Messages

Follow conventional commit format:

```
type(scope): subject

body

footer
```

Types:
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation changes
- `style`: Code style changes (formatting, etc.)
- `refactor`: Code refactoring
- `test`: Adding or updating tests
- `chore`: Maintenance tasks

Example:
```
feat(retry): add jittered exponential backoff

Implement jittered exponential backoff strategy to prevent
thundering herd problem when multiple clients retry simultaneously.

Closes #123
```

### Pull Request Process

1. Update your fork:
   ```bash
   git fetch upstream
   git rebase upstream/main
   ```

2. Create a feature branch:
   ```bash
   git checkout -b feature/your-feature-name
   ```

3. Make your changes and commit:
   ```bash
   git add .
   git commit -m "feat: your feature description"
   ```

4. Run tests and checks:
   ```bash
   ./gradlew build test
   ```

5. Push to your fork:
   ```bash
   git push origin feature/your-feature-name
   ```

6. Open a Pull Request on GitHub

### Pull Request Guidelines

- Provide a clear description of the changes
- Reference any related issues
- Ensure all tests pass
- Update documentation if needed
- Add tests for new features
- Keep changes focused and atomic

## Publishing

### Version Management

Versions follow [Semantic Versioning](https://semver.org/):
- `MAJOR.MINOR.PATCH` (e.g., `1.2.3`)
- Append `-SNAPSHOT` for development versions (e.g., `1.2.3-SNAPSHOT`)

Update version in `build.gradle.kts`:
```kotlin
version = "0.2.0-SNAPSHOT"
```

### Publishing to GitHub Packages

#### Prerequisites

1. Generate a GitHub Personal Access Token:
   - Go to GitHub Settings → Developer settings → Personal access tokens
   - Create a token with `write:packages` scope

2. Configure credentials in `gradle.properties`:
   ```properties
   gpr.user=your-github-username
   gpr.token=your-github-token
   ```

   Or set environment variables:
   ```bash
   export GITHUB_ACTOR=your-github-username
   export GITHUB_TOKEN=your-github-token
   ```

#### Manual Publishing

```bash
./gradlew publish
```

#### Automated Publishing

Publishing is automated via GitHub Actions:
- On release creation: Publishes the release version
- Manual workflow dispatch: Allows custom version publishing

### Release Process

1. Update version in `build.gradle.kts` (remove `-SNAPSHOT`)
2. Update CHANGELOG.md
3. Commit changes:
   ```bash
   git commit -am "chore: prepare release v0.2.0"
   ```
4. Create and push tag:
   ```bash
   git tag -a v0.2.0 -m "Release version 0.2.0"
   git push origin v0.2.0
   ```
5. Create GitHub Release
6. The GitHub Action will automatically publish to GitHub Packages

## Documentation

### Generate API Documentation

```bash
./gradlew dokkaHtml
```

Documentation will be available in `build/documentation/html/`

### Update Documentation

- Keep README.md up to date with new features
- Update code examples
- Document breaking changes in CHANGELOG.md

## Questions?

If you have questions or need help:
- Open an issue on GitHub
- Check existing issues and discussions
- Review the documentation

## License

By contributing, you agree that your contributions will be licensed under the MIT License.