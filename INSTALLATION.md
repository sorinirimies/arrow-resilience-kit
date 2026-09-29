# Installation

[![Maven Central](https://img.shields.io/maven-central/v/com.sorinirmies.arrow/arrow-resilience-kit?label=Maven%20Central)](https://central.sonatype.com/artifact/com.sorinirmies.arrow/arrow-resilience-kit)
[![GitHub Release](https://img.shields.io/github/v/release/sorinirimies/arrow-resilience-kit?label=latest)](https://github.com/sorinirimies/arrow-resilience-kit/releases/latest)
[![JitPack](https://jitpack.io/v/sorinirimies/arrow-resilience-kit.svg)](https://jitpack.io/#sorinirimies/arrow-resilience-kit)

**Coordinates:**

| | |
|---|---|
| **Group ID** | `com.sorinirmies.arrow` |
| **Artifact ID** | `arrow-resilience-kit` |
| **Version** | see the badges above — they always reflect the current published version |

Every snippet below uses `<version>` as a placeholder — substitute whatever
the badges show. If you'd rather never touch this file again, use a
[dynamic Gradle version](https://docs.gradle.org/current/userguide/dynamic_versions.html)
instead of a pinned one, e.g. `implementation("com.sorinirmies.arrow:arrow-resilience-kit:+")`
or `:latest.release`. Convenient, but not reproducible — pin an exact
version for anything you actually ship.

> Also published to Maven Central (see below) — that's the recommended way
> to consume this library; JitPack and GitHub Packages remain available too.

---

## Maven Central

Published via the [Central Portal](https://central.sonatype.com/). No token required to consume it.

### Gradle (Kotlin DSL)

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("com.sorinirmies.arrow:arrow-resilience-kit:<version>")
}
```

### Maven

```xml
<dependency>
    <groupId>com.sorinirmies.arrow</groupId>
    <artifactId>arrow-resilience-kit</artifactId>
    <version>x.y.z</version>
</dependency>
```

---

## JitPack

JitPack builds on-demand from any tag or commit on GitHub.

### Gradle (Kotlin DSL)

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.sorinirimies:arrow-resilience-kit:<version>")
}
```

### Maven

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.sorinirimies</groupId>
    <artifactId>arrow-resilience-kit</artifactId>
    <version>x.y.z</version>
</dependency>
```

---

## GitHub Packages

Requires a GitHub Personal Access Token (PAT) with `read:packages` scope.

### Gradle (Kotlin DSL)

```kotlin
repositories {
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/sorinirimies/arrow-resilience-kit")
        credentials {
            username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
            password = project.findProperty("gpr.token") as String? ?: System.getenv("GITHUB_PACKAGES_TOKEN")
        }
    }
}

dependencies {
    implementation("com.sorinirmies.arrow:arrow-resilience-kit:<version>")
}
```

### Maven

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/sorinirimies/arrow-resilience-kit</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.sorinirmies.arrow</groupId>
    <artifactId>arrow-resilience-kit</artifactId>
    <version>x.y.z</version>
</dependency>
```

Add your credentials to `~/.m2/settings.xml`:

```xml
<servers>
    <server>
        <id>github</id>
        <username>YOUR_GITHUB_USERNAME</username>
        <password>YOUR_GITHUB_TOKEN</password>
    </server>
</servers>
```

---

## Gitea Packages (self-hosted)

Published from the Gitea-triggered release workflow to this project's self-hosted Gitea instance's own Maven package registry.

### Gradle (Kotlin DSL)

```kotlin
repositories {
    maven {
        name = "GiteaPackages"
        url = uri("http://192.168.1.44:3000/api/packages/sorin/maven")
        credentials(HttpHeaderCredentials::class) {
            name = "Authorization"
            value = "token YOUR_GITEA_ACCESS_TOKEN"
        }
        authentication {
            create<HttpHeaderAuthentication>("header")
        }
    }
}

dependencies {
    implementation("com.sorinirmies.arrow:arrow-resilience-kit:<version>")
}
```

---

## Swift Package Manager (iOS)

Every GitHub release also builds and attaches a prebuilt `ArrowResilienceKit.xcframework` (iOS device arm64 + simulator arm64/x86_64), consumable via [`Package.swift`](Package.swift):

```swift
dependencies: [
    .package(url: "https://github.com/sorinirimies/arrow-resilience-kit", from: "<version>")
]
```

`Package.swift` in this repo always points at the exact latest release —
its `binaryTarget` url/checksum are rewritten automatically by CI on every
release (see `scripts/update_package_swift.nu`), so if you reference the
repo directly via `.package(url: "https://github.com/sorinirimies/arrow-resilience-kit", from: "<version>")`
there is nothing to keep in sync by hand.

Kotlin/Native automatically bridges `suspend` functions to Objective-C completion-handler methods, which Swift imports as native `async`/`await` functions — no wrapper code needed on either side.

Built and attached by the `xcframework` job in `.github/workflows/release.yml` (macOS-only — Kotlin/Native's iOS targets require Xcode, so this isn't wired into the Gitea workflow, whose runner is Linux).
