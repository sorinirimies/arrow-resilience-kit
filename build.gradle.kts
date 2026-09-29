plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    alias(libs.plugins.detekt)
    `maven-publish`
    signing
}

// Note: Dokka optimization warnings are expected and harmless
// These are informational warnings from Gradle about Dokka's internal URL usage
// They don't affect documentation generation or build success
// See: https://github.com/Kotlin/dokka/issues/1933

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

group = "ro.sorinirmies.arrow"
version = "0.5.6"

repositories {
    mavenCentral()
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
        val commonMain by getting {
            dependencies {
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

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.kotest.assertions.core)
            }
        }

        val jvmMain by getting {
            dependencies {
                // Optional: only needed if you use MicrometerBridge. compileOnly so
                // consumers who don't touch it aren't forced to pull Micrometer in.
                compileOnly(libs.micrometer.core)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(libs.logback.classic)
                implementation(libs.micrometer.core)
            }
        }
    }

    // Explicit API mode for better library hygiene
    explicitApi()
}

// Configure Dokka for better documentation
tasks.dokkaHtml.configure {
    outputDirectory.set(layout.buildDirectory.dir("docs"))

    dokkaSourceSets {
        named("commonMain") {
            moduleName.set("Arrow Resilience Kit")

            includes.from("Module.md")

            sourceLink {
                localDirectory.set(file("src/commonMain/kotlin"))
                remoteUrl.set(uri("https://github.com/sorinirimies/arrow-resilience-kit/tree/main/src/commonMain/kotlin").toURL())
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
            externalDocumentationLink {
                url.set(uri("https://arrow-kt.io/docs/").toURL())
            }

            externalDocumentationLink {
                url.set(uri("https://kotlinlang.org/api/kotlinx.coroutines/").toURL())
            }
        }
    }

    pluginsMapConfiguration.set(
        mapOf(
            "org.jetbrains.dokka.base.DokkaBase" to """
                {
                    "customStyleSheets": ["${file("config/dokka/custom-styles.css")}"],
                    "customAssets": [],
                    "separateInheritedMembers": true,
                    "footerMessage": "© 2026 Arrow Resilience Kit"
                }
            """
        )
    )
}

// Task to prepare docs for GitHub Pages
tasks.register<Copy>("prepareDocs") {
    dependsOn(tasks.dokkaHtml)
    from(layout.buildDirectory.dir("docs"))
    into(file("docs"))

    doLast {
        // Create .nojekyll to bypass Jekyll processing
        file("docs/.nojekyll").writeText("")

        println("Documentation prepared in docs/ directory")
        println("Commit and push docs/ to publish to GitHub Pages")
    }
}
