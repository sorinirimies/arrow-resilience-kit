plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.dokka)
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

// Dokka configuration
subprojects {
    apply(plugin = "org.jetbrains.dokka")
}

repositories {
    mavenCentral()
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
    // TODO: Enable once all public APIs have explicit visibility modifiers
    // explicitApi()
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
                    "footerMessage": "© 2024 Arrow Resilience Kit"
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