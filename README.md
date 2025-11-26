# Arrow Resilience Kit

[![GitHub Release](https://img.shields.io/github/v/release/sorinirimies/arrow-resilience-kit)](https://github.com/sorinirimies/arrow-resilience-kit/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![CI](https://github.com/sorinirimies/arrow-resilience-kit/actions/workflows/ci.yml/badge.svg)](https://github.com/sorinirimies/arrow-resilience-kit/actions/workflows/ci.yml)
[![Publish](https://github.com/sorinirimies/arrow-resilience-kit/actions/workflows/publish.yml/badge.svg)](https://github.com/sorinirimies/arrow-resilience-kit/actions/workflows/publish.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-blue.svg?logo=kotlin)](https://kotlinlang.org)
[![Arrow](https://img.shields.io/badge/Arrow-1.2.1-blue.svg)](https://arrow-kt.io)

Comprehensive resilience patterns library for Kotlin Multiplatform using Arrow-kt.

## Features

- **Retry & Repeat** - Automatic retry with various backoff strategies
- **Circuit Breaker** - Prevent cascading failures
- **Bulkhead** - Resource isolation and concurrency limiting
- **Rate Limiter** - Control request rates
- **Time Limiter** - Timeout handling with fallbacks
- **Cache** - Thread-safe caching with TTL
- **Saga** - Distributed transaction coordination

## Installation

### From GitHub Packages

Add the GitHub Packages repository to your build configuration:

**Gradle Kotlin DSL (build.gradle.kts)**
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

**Gradle Properties (gradle.properties)**
```properties
gpr.user=your-github-username
gpr.token=your-github-personal-access-token
```

> **Note**: You need a GitHub Personal Access Token with `read:packages` scope to download packages.
> Create one at: https://github.com/settings/tokens

## Quick Start

```kotlin
import ro.sorinirimies.arrow.resiliencekit.*

// Retry with exponential backoff
val result = retryWithExponentialBackoff(retries = 3) {
    apiClient.fetchData()
}

// Circuit breaker
val breaker = CircuitBreaker()
breaker.execute {
    externalService.call()
}

// Bulkhead
withBulkhead(BulkheadConfig(maxConcurrentCalls = 10)) {
    databaseQuery()
}
```

## Building and Publishing

### Build Locally

```bash
./gradlew build
```

### Run Tests

```bash
./gradlew test
# Or for all platforms
./gradlew allTests
```

### Generate Documentation

```bash
./gradlew dokkaHtml
```

Documentation will be available in `build/documentation/html/`

### Publishing to GitHub Packages

The library is automatically published to GitHub Packages when a release is created. To publish manually:

1. Set up your credentials in `gradle.properties` or environment variables:
   ```properties
   gpr.user=your-github-username
   gpr.token=your-github-token
   ```

2. Run the publish task:
   ```bash
   ./gradlew publish
   ```

### Project Structure

This is a Kotlin Multiplatform library supporting:
- **JVM** - Java 17+
- **JS** - Browser and Node.js
- **Native** - Linux x64, macOS x64, macOS ARM64

### Version Catalog

Dependencies are managed using Gradle version catalogs in `gradle/libs.versions.toml`:
- Kotlin 1.9.22
- Arrow 1.2.1
- Kotlinx Coroutines 1.7.3

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Run tests: `./gradlew test`
5. Submit a pull request

## License

MIT License - see LICENSE file for details
