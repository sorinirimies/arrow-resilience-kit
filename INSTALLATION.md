# Installation

**Coordinates:**

| | |
|---|---|
| **Group ID** | `ro.sorinirmies.arrow` |
| **Artifact ID** | `arrow-resilience-kit` |
| **Version** | `0.2.0` |

---

## Maven Central

### Gradle (Kotlin DSL)

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("ro.sorinirmies.arrow:arrow-resilience-kit:0.2.0")
}
```

### Maven

```xml
<dependency>
    <groupId>ro.sorinirmies.arrow</groupId>
    <artifactId>arrow-resilience-kit</artifactId>
    <version>0.2.0</version>
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
    implementation("com.github.sorinirimies:arrow-resilience-kit:0.2.0")
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
    <version>0.2.0</version>
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
    implementation("ro.sorinirmies.arrow:arrow-resilience-kit:0.2.0")
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
    <version>0.2.0</version>
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
