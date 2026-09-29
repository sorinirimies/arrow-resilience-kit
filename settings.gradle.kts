rootProject.name = "arrow-resilience-kit"

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
        // Fallback for CI hosts without github.com access (e.g. the self-hosted
        // Gitea Act Runner): org.jetbrains.kotlin:kotlin-compiler-embeddable
        // (a detekt transitive dependency) is hosted as an oversized artifact
        // on GitHub Releases, and even Maven Central's own resolver redirects
        // there directly -- so it 404s with 'No address associated with
        // hostname' wherever github.com is unreachable. JetBrains' own
        // cache-redirector mirrors the same Central content through
        // artifacts-caching-proxy.aws.intellij.net instead, sidestepping
        // github.com entirely. Gradle falls back to this automatically
        // whenever mavenCentral() itself fails to resolve an artifact.
        maven { url = uri("https://cache-redirector.jetbrains.com/repo1.maven.org/maven2") }
    }
}

pluginManagement {
    repositories {
        mavenCentral()
        maven { url = uri("https://cache-redirector.jetbrains.com/repo1.maven.org/maven2") }
        gradlePluginPortal()
    }
}
