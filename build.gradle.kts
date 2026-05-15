plugins {
    alias(libs.plugins.kotlin.multiplatform)
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

group = "ro.sorinirmies.arrow"
version = "0.2.0"

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
    jvm {
        jvmToolchain(17)
        withJava()
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    js(IR) {
        browser()
        nodejs()
    }

    linuxX64()
    macosX64()
    macosArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.kotlinx.coroutines.core)
                api(libs.arrow.core)
                api(libs.arrow.fx.coroutines)
                api(libs.arrow.fx.stm)
                api(libs.arrow.resilience)
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

        val jvmMain by getting
        val jvmTest by getting {
            dependencies {
                implementation(libs.logback.classic)
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
                    "customStyleSheets": [],
                    "customAssets": [],
                    "separateInheritedMembers": false,
                    "footerMessage": "© 2025 Arrow Resilience Kit"
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
