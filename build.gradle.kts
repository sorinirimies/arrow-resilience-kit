plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    alias(libs.plugins.detekt)
    `maven-publish`
    signing
}

// Apply publishing configuration
apply(from = "gradle/publishing.gradle.kts")

// Maven Central (Central Portal). No third-party Gradle plugin (previously
// com.vanniktech.maven.publish) — hand-rolled against the same Central
// Portal Publisher API that plugin uses under the hood, using only the JDK's
// built-in java.net.http.HttpClient. See gradle/central-portal.gradle.kts.
// Needs `mavenCentralUsername`/`mavenCentralPassword` (a Central Portal user
// token, NOT your login password) and a GPG key — see INSTALLATION.md for
// the one-time setup (Central Portal namespace verification is a manual,
// human-only step).
apply(from = "gradle/central-portal.gradle.kts")

group = "com.sorinirmies.arrow"
version = "0.7.1"

repositories {
    mavenCentral()
    // See settings.gradle.kts for why this fallback exists.
    maven { url = uri("https://cache-redirector.jetbrains.com/repo1.maven.org/maven2") }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("config/detekt/detekt.yml"))
    basePath = projectDir.absolutePath
    source.setFrom(
        files(
            "src/commonMain/kotlin",
            "src/commonTest/kotlin",
            "src/jvmMain/kotlin",
            "src/jvmTest/kotlin"
        )
    )
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    reports {
        html.required.set(true)
        xml.required.set(false)
        txt.required.set(false)
        sarif.required.set(false)
    }
}

kotlin {
    jvmToolchain(17)

    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        // Lets consumers on an older embedded Kotlin compiler (e.g. Gradle's kotlin-dsl /
        // precompiled-script-plugin machinery, which is pinned to whatever Kotlin version
        // ships with that Gradle release) still read this library's metadata. Bump this only
        // when a language feature newer than 2.2 is actually needed.
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
    }

    jvm {
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    js {
        browser {
            testTask {
                enabled = false // No browser available in CI; use Node.js tests only
            }
        }
        nodejs()
    }

    linuxX64()
    // macosX64 (Intel Mac) dropped: Arrow 2.2.3+ no longer publishes klibs for
    // this target (last version that did was 2.2.2; see arrow-kt/arrow release
    // notes). Matches the wider Kotlin/Native ecosystem's deprioritization of
    // Intel macOS following Apple's transition to Apple Silicon.
    macosArm64()

    // iOS: also assembled into a single ArrowResilienceKit.xcframework (see the
    // `assembleArrowResilienceKitXCFramework` task) for Swift/Xcode consumers —
    // see Package.swift and the `xcframework` job in .github/workflows/release.yml.
    val xcf = org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFrameworkConfig(project, "ArrowResilienceKit")
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ArrowResilienceKit"
            xcf.add(this)
        }
    }

    sourceSets {
        getByName("commonMain") {
            dependencies {
                // Explicit, low-pinned stdlib -- see gradle.properties'
                // kotlin.stdlib.default.dependency=false and the kotlin-stdlib
                // version comment in gradle/libs.versions.toml.
                api(libs.kotlin.stdlib)
                api(libs.kotlinx.coroutines.core)
                api(libs.arrow.core)
                api(libs.arrow.fx.coroutines)
                api(libs.arrow.fx.stm)
                api(libs.arrow.resilience)
                api(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlin.logging)
            }
        }

        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.kotest.assertions.core)
            }
        }

        getByName("jvmMain") {
            dependencies {
                // Optional: only needed if you use MicrometerBridge. compileOnly so
                // consumers who don't touch it aren't forced to pull Micrometer in.
                compileOnly(libs.micrometer.core)
            }
        }
        getByName("jvmTest") {
            dependencies {
                implementation(libs.logback.classic)
                implementation(libs.micrometer.core)
            }
        }
    }

    // Explicit API mode for better library hygiene
    explicitApi()
}

// Configure Dokka for better documentation (Dokka V2 DSL)
dokka {
    moduleName.set("Arrow Resilience Kit")

    dokkaSourceSets.named("commonMain") {
        includes.from("Module.md")

        sourceLink {
            localDirectory.set(file("src/commonMain/kotlin"))
            remoteUrl("https://github.com/sorinirimies/arrow-resilience-kit/tree/main/src/commonMain/kotlin")
            remoteLineSuffix.set("#L")
        }

        // Package documentation
        perPackageOption {
            matchingRegex.set(".*")
            suppress.set(false)
            reportUndocumented.set(true)
            skipDeprecated.set(false)
        }

        // External documentation links
        externalDocumentationLinks.register("arrow") {
            url("https://arrow-kt.io/docs/")
        }

        externalDocumentationLinks.register("kotlinx-coroutines") {
            url("https://kotlinlang.org/api/kotlinx.coroutines/")
        }
    }

    pluginsConfiguration.html {
        customStyleSheets.from(file("config/dokka/custom-styles.css"))
        footerMessage.set("© 2026 Arrow Resilience Kit")
        separateInheritedMembers.set(true)
    }

    dokkaPublications.html {
        outputDirectory.set(layout.buildDirectory.dir("docs"))
    }
}

// Task to prepare docs for GitHub Pages
tasks.register<Copy>("prepareDocs") {
    dependsOn(tasks.named("dokkaGeneratePublicationHtml"))
    from(layout.buildDirectory.dir("docs"))
    into(file("docs"))

    doLast {
        // Create .nojekyll to bypass Jekyll processing
        file("docs/.nojekyll").writeText("")

        println("Documentation prepared in docs/ directory")
        println("Commit and push docs/ to publish to GitHub Pages")
    }
}
