plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.dokka)
    `maven-publish`
    signing
}

// Apply publishing configuration
apply(from = "gradle/publishing.gradle.kts")

group = "ro.sorinirmies.arrow"
version = "0.1.0-SNAPSHOT"

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

tasks.dokkaHtml.configure {
    outputDirectory.set(layout.buildDirectory.dir("documentation/html"))
}