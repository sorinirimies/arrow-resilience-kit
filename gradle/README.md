# Gradle Configuration Directory

This directory contains Gradle configuration files for the Arrow Resilience Kit project.

## Files

### `libs.versions.toml`
**Version Catalog** - Centralized dependency management

Defines all project dependencies, their versions, and plugin versions in a single location. This provides:
- Type-safe dependency accessors via `libs.` prefix
- Single source of truth for versions
- Easier dependency updates across the project
- Shared configuration for multi-module projects

**Usage in build.gradle.kts:**
```kotlin
dependencies {
    api(libs.arrow.core)
    implementation(libs.kotlinx.coroutines.core)
}
```

**Structure:**
- `[versions]` - Version numbers
- `[libraries]` - Library coordinates with version references
- `[plugins]` - Gradle plugin declarations

### `publishing.gradle.kts`
**Publishing Configuration** - Maven publication setup

Contains all publishing-related configuration for GitHub Packages:
- POM metadata (name, description, URL, licenses, developers, SCM)
- Repository configuration (GitHub Packages)
- Credential resolution (gradle.properties → environment variables)
- GPG signing configuration (optional)

**Applied in main build.gradle.kts:**
```kotlin
apply(from = "gradle/publishing.gradle.kts")
```

**Benefits of separation:**
- Cleaner main build file
- Reusable configuration
- Easier to maintain and update
- Clear separation of concerns

### `wrapper/`
**Gradle Wrapper** - Project-specific Gradle distribution

Contains the Gradle wrapper files that ensure all developers use the same Gradle version:
- `gradle-wrapper.jar` - Wrapper executable
- `gradle-wrapper.properties` - Wrapper configuration

**Never modify manually** - Update using:
```bash
./gradlew wrapper --gradle-version 8.5
```

## Configuration Flow

```
settings.gradle.kts
    ↓
build.gradle.kts
    ├─→ plugins {} block (includes maven-publish, signing)
    ├─→ apply(from = "gradle/publishing.gradle.kts")
    └─→ dependencies use libs.* from libs.versions.toml
```

## Modifying Dependencies

### Adding a New Dependency

1. **Add version to libs.versions.toml:**
```toml
[versions]
new-library = "1.0.0"

[libraries]
new-library = { module = "com.example:library", version.ref = "new-library" }
```

2. **Use in build.gradle.kts:**
```kotlin
dependencies {
    implementation(libs.new.library)
}
```

### Updating Versions

Simply update the version in `libs.versions.toml`:
```toml
[versions]
arrow = "1.2.2"  # Updated from 1.2.1
```

All references automatically use the new version.

## Modifying Publishing

Edit `publishing.gradle.kts` to change:
- POM metadata
- Repository URLs
- Credential handling
- Signing configuration

Changes are automatically picked up by the main build.

## Best Practices

1. **Always use the version catalog** for dependencies
   - ✅ `implementation(libs.arrow.core)`
   - ❌ `implementation("io.arrow-kt:arrow-core:1.2.1")`

2. **Keep versions in one place** (libs.versions.toml)
   - Don't hardcode versions in build scripts

3. **Use semantic versioning**
   - MAJOR.MINOR.PATCH format
   - `-SNAPSHOT` for development versions

4. **Document custom configurations**
   - Add comments for non-obvious settings

5. **Don't commit secrets**
   - Never commit tokens in gradle.properties
   - Use environment variables for CI/CD

## References

- [Gradle Version Catalogs](https://docs.gradle.org/current/userguide/platforms.html)
- [Maven Publishing](https://docs.gradle.org/current/userguide/publishing_maven.html)
- [GitHub Packages](https://docs.github.com/en/packages)