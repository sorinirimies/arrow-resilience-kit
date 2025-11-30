# Arrow Resilience Kit

[![GitHub Release](https://img.shields.io/github/v/release/sorinirimies/arrow-resilience-kit)](https://github.com/sorinirimies/arrow-resilience-kit/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![CI](https://github.com/sorinirimies/arrow-resilience-kit/actions/workflows/ci.yml/badge.svg)](https://github.com/sorinirimies/arrow-resilience-kit/actions/workflows/ci.yml)
[![Publish](https://github.com/sorinirimies/arrow-resilience-kit/actions/workflows/publish.yml/badge.svg)](https://github.com/sorinirimies/arrow-resilience-kit/actions/workflows/publish.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-blue.svg?logo=kotlin)](https://kotlinlang.org)
[![Arrow](https://img.shields.io/badge/Arrow-1.2.1-blue.svg)](https://arrow-kt.io)

A comprehensive resilience patterns library for Kotlin Multiplatform using Arrow-kt. Provides production-ready implementations of common resilience patterns including retry, circuit breaker, bulkhead, rate limiter, time limiter, cache, and saga patterns.

## Features

- **Retry & Repeat** - Automatic retry with exponential backoff, linear backoff, and custom strategies
- **Circuit Breaker** - Prevent cascading failures with configurable thresholds and half-open states
- **Bulkhead** - Resource isolation and concurrency limiting using semaphores
- **Rate Limiter** - Control request rates with token bucket algorithm
- **Time Limiter** - Timeout handling with fallback support
- **Cache** - Thread-safe caching with TTL and size limits
- **Saga** - Distributed transaction coordination with automatic compensation

## Supported Platforms

- **JVM** (Java 17+)
- **JavaScript** (Browser & Node.js)
- **Native** (Linux x64, macOS x64, macOS ARM64)

## Installation

### Add Repository

Add the GitHub Packages repository to your `build.gradle.kts`:

```kotlin
repositories {
    mavenCentral()
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/sorinirimies/arrow-resilience-kit")
        credentials {
            username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
            password = project.findProperty("gpr.token") as String? ?: System.getenv("GITHUB_PACKAGES_TOKEN")
        }
    }
}
```

### Add Dependency

```kotlin
dependencies {
    implementation("ro.sorinirmies.arrow:arrow-resilience-kit:0.1.1")
}
```

### Configure Credentials

Create or edit `~/.gradle/gradle.properties`:

```properties
gpr.user=your-github-username
gpr.token=ghp_YOUR_PERSONAL_ACCESS_TOKEN
```

> **Note**: You need a GitHub Personal Access Token with `read:packages` scope.  
> Create one at: https://github.com/settings/tokens/new

For detailed setup instructions, troubleshooting, and CI/CD configuration, see [INSTALLATION.md](INSTALLATION.md).

## Quick Start

### Retry with Exponential Backoff

```kotlin
import ro.sorinirimies.arrow.resiliencekit.*

// Simple retry
val result = retryWithExponentialBackoff(retries = 3) {
    apiClient.fetchData()
}

// Custom retry configuration
val customResult = retryWithExponentialBackoff(
    retries = 5,
    initialDelay = 100.milliseconds,
    maxDelay = 5.seconds,
    factor = 2.0
) {
    unreliableService.call()
}
```

### Circuit Breaker

```kotlin
import ro.sorinirimies.arrow.resiliencekit.*

// Create circuit breaker
val breaker = CircuitBreaker(
    failureThreshold = 5,
    successThreshold = 2,
    timeout = 60.seconds
)

// Execute with circuit breaker protection
val result = breaker.execute {
    externalService.call()
}

// Check circuit state
when (breaker.state) {
    CircuitState.CLOSED -> println("Circuit is healthy")
    CircuitState.OPEN -> println("Circuit is open, failing fast")
    CircuitState.HALF_OPEN -> println("Circuit is testing recovery")
}
```

### Bulkhead (Concurrency Limiting)

```kotlin
import ro.sorinirimies.arrow.resiliencekit.*

// Limit concurrent calls
withBulkhead(BulkheadConfig(maxConcurrentCalls = 10)) {
    databaseQuery()
}

// With custom timeout
withBulkhead(
    config = BulkheadConfig(
        maxConcurrentCalls = 10,
        maxWaitTime = 5.seconds
    )
) {
    heavyComputation()
}
```

### Rate Limiter

```kotlin
import ro.sorinirimies.arrow.resiliencekit.*

// Create rate limiter (10 requests per second)
val limiter = RateLimiter(
    permitsPerSecond = 10,
    maxBurstSize = 20
)

// Execute with rate limiting
val result = limiter.execute {
    apiClient.request()
}
```

### Time Limiter (Timeout)

```kotlin
import ro.sorinirimies.arrow.resiliencekit.*

// Simple timeout
val result = withTimeout(5.seconds) {
    longRunningOperation()
}

// With fallback
val resultWithFallback = withTimeout(
    timeout = 5.seconds,
    fallback = { TimeoutException -> "Default value" }
) {
    longRunningOperation()
}
```

### Cache

```kotlin
import ro.sorinirimies.arrow.resiliencekit.*

// Create cache with TTL
val cache = Cache<String, User>(
    ttl = 5.minutes,
    maxSize = 1000
)

// Get or compute
val user = cache.getOrPut("user123") {
    userService.fetchUser("user123")
}

// Manual operations
cache.put("user456", userInstance)
val cachedUser = cache.get("user456")
cache.invalidate("user456")
```

### Saga Pattern

```kotlin
import ro.sorinirimies.arrow.resiliencekit.*

// Define saga with compensation
val saga = Saga<OrderContext>()
    .step(
        forward = { ctx -> orderService.createOrder(ctx.order) },
        compensate = { ctx -> orderService.cancelOrder(ctx.orderId) }
    )
    .step(
        forward = { ctx -> paymentService.processPayment(ctx.payment) },
        compensate = { ctx -> paymentService.refund(ctx.paymentId) }
    )
    .step(
        forward = { ctx -> inventoryService.reserveItems(ctx.items) },
        compensate = { ctx -> inventoryService.releaseItems(ctx.items) }
    )

// Execute saga
val result = saga.execute(orderContext)
```

### Combining Patterns

Patterns can be easily combined for robust resilience:

```kotlin
import ro.sorinirimies.arrow.resiliencekit.*

// Combine circuit breaker + retry + timeout
suspend fun fetchDataResilient(): Result<Data> = 
    circuitBreaker.execute {
        retryWithExponentialBackoff(retries = 3) {
            withTimeout(10.seconds) {
                apiClient.fetchData()
            }
        }
    }

// Rate limiting + bulkhead
suspend fun processRequest(request: Request): Response =
    rateLimiter.execute {
        withBulkhead(BulkheadConfig(maxConcurrentCalls = 100)) {
            processService.handle(request)
        }
    }
```

## Documentation

### User Documentation
- **[API Documentation](https://sorinirimies.github.io/arrow-resilience-kit/)** - Complete API reference (auto-generated)
- **[Installation Guide](INSTALLATION.md)** - Detailed setup and troubleshooting
- **[Changelog](CHANGELOG.md)** - Version history and release notes

### Developer Documentation
- **[Contributing Guide](CONTRIBUTING.md)** - How to contribute to the project
- **[Development Setup](docs/guides/DEVELOPMENT.md)** - Local development setup
- **[Documentation Guide](docs/guides/DOCUMENTATION_SETUP.md)** - Working with Dokka and GitHub Pages

### Maintainer Documentation
- **[Release Guide](RELEASE.md)** - Complete release process
- **[Pre-Release Checklist](PRE_RELEASE_CHECKLIST.md)** - Step-by-step release checklist
- **[Token Setup](TOKEN_SETUP_GUIDE.md)** - GitHub token configuration
- **[Git-Cliff Guide](docs/guides/GIT_CLIFF_GUIDE.md)** - Changelog generation

## Project Structure

```
arrow-resilience-kit/
├── src/
│   ├── commonMain/kotlin/           # Shared Kotlin code
│   │   └── ro/sorinirimies/arrow/resiliencekit/
│   ├── commonTest/kotlin/           # Shared tests
│   ├── jvmMain/kotlin/              # JVM-specific code
│   ├── jsMain/kotlin/               # JS-specific code
│   └── nativeMain/kotlin/           # Native-specific code
├── docs/                            # Auto-generated API documentation (Dokka)
│   └── guides/                      # Documentation guides
├── gradle/                          # Gradle configuration
│   ├── libs.versions.toml          # Version catalog
│   └── publishing.gradle.kts       # Publishing configuration
├── .github/workflows/               # CI/CD workflows
│   ├── ci.yml                      # Continuous Integration
│   ├── publish.yml                 # Release and publishing
│   └── docs.yml                    # Documentation deployment
├── build.gradle.kts                # Build configuration
├── README.md                       # This file
├── INSTALLATION.md                 # Installation guide
├── CONTRIBUTING.md                 # Contribution guidelines
├── CHANGELOG.md                    # Version history
└── RELEASE.md                      # Release process
```

## Development

### Prerequisites

- JDK 17 or higher
- Gradle 8.5+ (included via wrapper)

### Building

```bash
./gradlew build
```

### Running Tests

```bash
./gradlew test           # Run tests
./gradlew allTests       # All platforms
```

### Generating Documentation

```bash
./gradlew dokkaHtml      # Generate API docs
./gradlew prepareDocs    # Prepare for GitHub Pages
```

Documentation will be generated in `build/docs/` and can be copied to `docs/` for GitHub Pages deployment.

### Using Just (Task Runner)

If you have [just](https://github.com/casey/just) installed:

```bash
just build              # Build project
just test               # Run tests
just doc                # Generate documentation
just doc-open           # Generate and open docs
just changelog          # Generate changelog
just release 0.1.0      # Complete release workflow
```

See `justfile` for all available commands.

## Dependencies

- **Kotlin** 1.9.22
- **Arrow-kt** 1.2.1
- **Kotlinx Coroutines** 1.7.3
- **Kotlinx DateTime** 0.4.1

See [gradle/libs.versions.toml](gradle/libs.versions.toml) for the complete dependency list.

## Contributing

Contributions are welcome! We appreciate your help in making this library better.

### How to Contribute

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Make your changes
4. Run tests: `./gradlew test`
5. Commit using [Conventional Commits](https://www.conventionalcommits.org/):
   - `feat:` - New features
   - `fix:` - Bug fixes
   - `docs:` - Documentation changes
   - `refactor:` - Code refactoring
   - `test:` - Test changes
   - `chore:` - Maintenance tasks
6. Push to your fork (`git push origin feature/amazing-feature`)
7. Open a Pull Request

See [CONTRIBUTING.md](CONTRIBUTING.md) for detailed guidelines.

## Versioning

This project follows [Semantic Versioning](https://semver.org/):
- **MAJOR** version for incompatible API changes
- **MINOR** version for new functionality in a backward compatible manner
- **PATCH** version for backward compatible bug fixes

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Credits

Built with:
- [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html) - Write once, run everywhere
- [Arrow-kt](https://arrow-kt.io/) - Functional programming for Kotlin
- [Kotlinx Coroutines](https://github.com/Kotlin/kotlinx.coroutines) - Asynchronous programming
- [Dokka](https://kotlin.github.io/dokka/) - API documentation

Inspired by resilience libraries like [Resilience4j](https://resilience4j.readme.io/) and [Hystrix](https://github.com/Netflix/Hystrix).

## Support

- **Issues**: [GitHub Issues](https://github.com/sorinirimies/arrow-resilience-kit/issues)
- **Discussions**: [GitHub Discussions](https://github.com/sorinirimies/arrow-resilience-kit/discussions)
- **Documentation**: [API Docs](https://sorinirimies.github.io/arrow-resilience-kit/)

## Roadmap

- [ ] Additional retry strategies (fibonacci backoff, decorrelated jitter)
- [ ] Metrics and monitoring integration
- [ ] Spring Boot integration
- [ ] Ktor integration
- [ ] More comprehensive examples and tutorials
- [ ] Performance benchmarks

## Acknowledgments

Special thanks to:
- The Arrow-kt team for the amazing functional programming library
- The Kotlin team for Kotlin Multiplatform
- All contributors and users of this library

---

**Ready to build resilient applications?** Get started with the [Installation Guide](INSTALLATION.md)!

**Questions?** Open a [discussion](https://github.com/sorinirimies/arrow-resilience-kit/discussions) on GitHub.