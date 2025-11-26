# Arrow Resilience Kit - Project Structure

## Directory Structure

```
arrow-resilience-kit/
├── .github/
│   └── workflows/
│       ├── ci.yml                          # Continuous Integration workflow
│       └── publish.yml                     # Publishing workflow
│
├── gradle/
│   ├── wrapper/
│   │   ├── gradle-wrapper.jar
│   │   └── gradle-wrapper.properties
│   ├── libs.versions.toml                  # Version catalog (dependencies)
│   ├── publishing.gradle.kts               # Publishing configuration
│   └── README.md                           # Gradle config documentation
│
├── src/
│   ├── commonMain/kotlin/ro/sorinirmies/arrow/resiliencekit/
│   │   ├── Bulkhead.kt
│   │   ├── Cache.kt
│   │   ├── CircuitBreaker.kt
│   │   ├── RateLimiter.kt
│   │   ├── RetryRepeat.kt
│   │   ├── Saga.kt
│   │   └── TimeLimiter.kt
│   │
│   ├── commonTest/kotlin/ro/sorinirmies/arrow/resiliencekit/
│   │   ├── BulkheadTest.kt
│   │   ├── CacheTest.kt
│   │   ├── CircuitBreakerTest.kt
│   │   ├── RateLimiterTest.kt
│   │   ├── RetryRepeatTest.kt
│   │   ├── SagaTest.kt
│   │   └── TimeLimiterTest.kt
│   │
│   ├── jvmMain/kotlin/
│   └── jvmTest/kotlin/
│
├── .gitignore                              # Git ignore rules
├── build.gradle.kts                        # Main build configuration
├── CONTRIBUTING.md                         # Contributor guidelines
├── gradle.properties                       # Gradle properties
├── gradlew                                 # Gradle wrapper (Unix)
├── gradlew.bat                            # Gradle wrapper (Windows)
├── LICENSE                                 # MIT License
├── PROJECT_STRUCTURE.md                    # This file
├── PUBLISHING.md                           # Publishing guide
├── QUICK_START.md                          # Quick start guide
├── README.md                               # Project documentation
├── settings.gradle.kts                     # Settings configuration
└── SETUP_SUMMARY.md                        # Setup summary
```

## Key Files Explained

### Build Configuration

| File | Purpose |
|------|---------|
| `build.gradle.kts` | Main build script - defines project structure, targets, and dependencies |
| `settings.gradle.kts` | Project settings - configures repositories and plugin management |
| `gradle.properties` | Gradle configuration - JVM args, build options |
| `gradle/libs.versions.toml` | Version catalog - centralized dependency management |
| `gradle/publishing.gradle.kts` | Publishing configuration - Maven/GitHub Packages setup |

### Source Code

| Directory | Purpose |
|-----------|---------|
| `src/commonMain/` | Common multiplatform code |
| `src/commonTest/` | Common multiplatform tests |
| `src/jvmMain/` | JVM-specific implementations |
| `src/jvmTest/` | JVM-specific tests |

### Documentation

| File | Purpose |
|------|---------|
| `README.md` | Project overview and installation instructions |
| `CONTRIBUTING.md` | How to contribute to the project |
| `PUBLISHING.md` | Detailed publishing guide for maintainers |
| `QUICK_START.md` | Quick reference for common tasks |
| `SETUP_SUMMARY.md` | Complete setup and configuration overview |
| `gradle/README.md` | Gradle configuration documentation |

### CI/CD

| File | Purpose |
|------|---------|
| `.github/workflows/ci.yml` | Runs tests on push/PR |
| `.github/workflows/publish.yml` | Publishes to GitHub Packages on release |

## Configuration Flow

```
┌─────────────────────┐
│ settings.gradle.kts │  ← Project name, repositories
└──────────┬──────────┘
           │
           ↓
┌─────────────────────┐
│  build.gradle.kts   │  ← Main build logic
└──────────┬──────────┘
           │
           ├─→ Plugins: kotlin-multiplatform, dokka, maven-publish, signing
           │
           ├─→ Dependencies from libs.versions.toml (version catalog)
           │
           └─→ apply(from = "gradle/publishing.gradle.kts")
                       │
                       └─→ Publishing configuration (POM, repositories, signing)
```

## Modular Design Benefits

### 1. Version Catalog (`gradle/libs.versions.toml`)
- ✅ Single source of truth for versions
- ✅ Type-safe accessors (`libs.arrow.core`)
- ✅ Easy to update across all modules
- ✅ Reusable in multi-module projects

### 2. Separate Publishing Script (`gradle/publishing.gradle.kts`)
- ✅ Cleaner main build file
- ✅ Reusable configuration
- ✅ Easier to maintain and test
- ✅ Clear separation of concerns

### 3. Comprehensive Documentation
- ✅ Multiple entry points for different audiences
- ✅ Quick start for developers
- ✅ Detailed guides for contributors
- ✅ Publishing instructions for maintainers

## Target Platforms

- **JVM**: Java 17+, JUnit Platform
- **JS**: Browser + Node.js (IR backend)
- **Native**: Linux x64, macOS x64, macOS ARM64

## Published Artifacts

When published, creates the following artifacts:

- `arrow-resilience-kit-jvm-{version}.jar` - JVM library
- `arrow-resilience-kit-js-{version}.jar` - JavaScript library
- `arrow-resilience-kit-linuxx64-{version}.klib` - Linux native library
- `arrow-resilience-kit-{version}.module` - Gradle metadata
- `arrow-resilience-kit-{version}.pom` - Maven POM

## Maven Coordinates

```xml
<dependency>
    <groupId>ro.sorinirmies.arrow</groupId>
    <artifactId>arrow-resilience-kit</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Next Steps

1. See `QUICK_START.md` for building and testing
2. See `CONTRIBUTING.md` for development guidelines
3. See `PUBLISHING.md` for publishing instructions
