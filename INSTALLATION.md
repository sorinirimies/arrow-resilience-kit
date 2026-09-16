# Installation

**Coordinates:**

| | |
|---|---|
| **Group ID** | `ro.sorinirmies.arrow` |
| **Artifact ID** | `arrow-resilience-kit` |
| **Version** | `0.5.0` |

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
    implementation("ro.sorinirmies.arrow:arrow-resilience-kit:0.5.0")
}
```

### Maven

```xml
<dependency>
    <groupId>ro.sorinirmies.arrow</groupId>
    <artifactId>arrow-resilience-kit</artifactId>
    <version>0.5.0</version>
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
    implementation("com.github.sorinirimies:arrow-resilience-kit:0.5.0")
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
    <version>0.5.0</version>
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
    implementation("ro.sorinirmies.arrow:arrow-resilience-kit:0.5.0")
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
    <groupId>ro.sorinirmies.arrow</groupId>
    <artifactId>arrow-resilience-kit</artifactId>
    <version>0.5.0</version>
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
    implementation("ro.sorinirmies.arrow:arrow-resilience-kit:0.5.0")
}
```

---

## Swift Package Manager (iOS)

Every GitHub release also builds and attaches a prebuilt `ArrowResilienceKit.xcframework` (iOS device arm64 + simulator arm64/x86_64), consumable via [`Package.swift`](Package.swift):

```swift
dependencies: [
    .package(url: "https://github.com/sorinirimies/arrow-resilience-kit", from: "0.5.0")
]
```

Kotlin/Native automatically bridges `suspend` functions to Objective-C completion-handler methods, which Swift imports as native `async`/`await` functions — no wrapper code needed on either side.

Built and attached by the `xcframework` job in `.github/workflows/release.yml` (macOS-only — Kotlin/Native's iOS targets require Xcode, so this isn't wired into the Gitea workflow, whose runner is Linux).
