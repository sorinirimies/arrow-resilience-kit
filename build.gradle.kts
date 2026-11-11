plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
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

group = "io.github.sorinirimies.arrow"
version = "0.11.2"

repositories {
    mavenCentral()
    // See settings.gradle.kts for why this fallback exists.
    maven { url = uri("https://cache-redirector.jetbrains.com/repo1.maven.org/maven2") }
}

// Security: pin transitive dependencies (build tooling + lettuce's netty) to patched
// releases flagged by the osv-scanner workflow. Does not change published POM versions.
configurations.configureEach {
    resolutionStrategy.eachDependency {
        val patched = when {
            requested.group == "io.netty" && requested.version?.startsWith("4.1.") == true -> "4.1.137.Final"
            requested.group.startsWith("com.fasterxml.jackson") && requested.module.name != "jackson-annotations" &&
                requested.version?.startsWith("2.15.") == true -> "2.18.11"
            // Dokka's HTML generator resolves 2.22.2 (GHSA-7hhh-6rmp-j9qf, GHSA-p6pp-m3f8-5c89,
            // GHSA-cxp5-3px4-pw24, GHSA-wv8q-qhhj-9h54). Only patches below 2.22.3 are raised.
            requested.group.startsWith("com.fasterxml.jackson") && requested.module.name != "jackson-annotations" &&
                requested.version?.startsWith("2.22.") == true &&
                (requested.version?.removePrefix("2.22.")?.toIntOrNull() ?: 3) < 3 -> "2.22.3"
            requested.group == "com.fasterxml.jackson" && requested.name == "jackson-bom" -> "2.18.11"
            requested.group == "io.opentelemetry" && requested.version == "1.41.0" -> "1.67.0"
            requested.group == "org.bouncycastle" && requested.version == "1.84" -> "1.85"
            requested.group == "org.freemarker" && requested.name == "freemarker" -> "2.3.35"
            requested.group == "org.jsoup" && requested.name == "jsoup" -> "1.23.2"
            else -> null
        }
        if (patched != null) useVersion(patched)
    }
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
            "src/jvmTest/kotlin",
            "src/jsMain/kotlin",
            "src/jsTest/kotlin"
        )
    )
}

// JVM test coverage (Kover). `check` runs koverVerify: line coverage below the
// floor fails the build. Raise the floor as coverage improves (was 87.8%).
kover {
    reports {
        verify {
            rule("Minimum line coverage") {
                minBound(85)
            }
        }
    }
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

    // Public API dump (api/) checked by `check`: an unintended binary-incompatible
    // change fails CI. After an intended API change run `./gradlew updateKotlinAbi`.
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()

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
        binaries.library()
        generateTypeScriptDefinitions()
        compilations["main"].packageJson {
            customField("description", "Kotlin Multiplatform resilience patterns built on Arrow-kt -- JS/TypeScript facade")
            customField("license", "MIT")
            customField("homepage", "https://github.com/sorinirimies/arrow-resilience-kit")
            customField(
                "repository",
                mapOf(
                    "type" to "git",
                    "url" to "git+https://github.com/sorinirimies/arrow-resilience-kit.git",
                ),
            )
            customField(
                "bugs",
                mapOf("url" to "https://github.com/sorinirimies/arrow-resilience-kit/issues"),
            )
            customField(
                "keywords",
                listOf("resilience", "circuit-breaker", "retry", "bulkhead", "rate-limiter", "arrow-kt", "kotlin"),
            )
            customField("author", "Sorin Albu-Irimies")
            // Entry points generated next to the UMD bundle (see jsNodeProductionLibraryDistribution
            // below): index.cjs (CommonJS) and index.mjs (ES modules -- Node can't see named
            // exports through the UMD wrapper, so `import { CircuitBreaker } from ...` needs it).
            customField("main", "index.cjs")
            customField("module", "index.mjs")
            customField(
                "exports",
                mapOf(
                    "." to mapOf(
                        "types" to "./arrow-resilience-kit.d.ts",
                        "import" to "./index.mjs",
                        "require" to "./index.cjs",
                    ),
                    "./*" to "./*",
                ),
            )
            customField("sideEffects", false)
        }
    }

    linuxX64()
    mingwX64()
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
    }
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

        getByName("wasmJsMain") {
            dependencies {
                // Kotlin/Wasm klibs require the stdlib ABI to match the compiler exactly,
                // so unlike other targets this one can't use the low-pinned stdlib.
                api("org.jetbrains.kotlin:kotlin-stdlib:${project.extensions.getByType<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension>().coreLibrariesVersion}")
            }
        }

        getByName("jvmMain") {
            dependencies {
                // Optional: only needed if you use MicrometerBridge. compileOnly so
                // consumers who don't touch it aren't forced to pull Micrometer in.
                compileOnly(libs.micrometer.core)
                // Optional integrations (Ktor plugin, Spring Boot auto-config, Redis
                // SharedStateStore): compileOnly so consumers only pull what they use.
                compileOnly(libs.ktor.server.core)
                compileOnly(libs.lettuce.core)
                compileOnly(libs.spring.boot.autoconfigure)
            }
        }
        getByName("jvmTest") {
            dependencies {
                implementation(libs.logback.classic)
                implementation(libs.micrometer.core)
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.test.host)
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

// Completes the npm package in build/dist/js/productionLibrary:
//  - index.cjs: CommonJS entry; also keeps `X.Companion.create(...)` (the only way to create
//    instances before `create` became static via @JsStatic) working at runtime.
//  - index.mjs: ES-module entry re-exporting every top-level export by name.
//  - appends src/jsMain/typescript/extra.d.ts to the generated .d.ts (types for the plain
//    options objects, e.g. RetryOptions, which Kotlin's d.ts generator can't emit for
//    external interfaces).
//  - README.md (src/jsMain/npm/README.md: the JS/TS documentation shown on npmjs.com) and LICENSE.
tasks.named("jsNodeProductionLibraryDistribution") {
    // The task copies into the directory without clearing it, so files of removed dependencies
    // (e.g. a dropped library's .js) would linger and get published from a local build.
    inputs.files("src/jsMain/npm/README.md", "src/jsMain/typescript/extra.d.ts", "LICENSE")
    doFirst { delete(layout.buildDirectory.dir("dist/js/productionLibrary")) }
    doLast {
        val dist = layout.buildDirectory.dir("dist/js/productionLibrary").get().asFile
        val dts = dist.resolve("arrow-resilience-kit.d.ts")
        file("src/jsMain/npm/README.md").copyTo(dist.resolve("README.md"), overwrite = true)
        file("LICENSE").copyTo(dist.resolve("LICENSE"), overwrite = true)
        // kotlin-logging reads process.env.KOTLIN_LOGGING_STARTUP_MESSAGE only to decide whether to
        // print its startup banner, which JsLogging turns off anyway. That single read makes package
        // scanners (Socket) flag the package for "environment variable access", so pin it to
        // "banner disabled". Fails the build if kotlin-logging changes the code, rather than
        // silently shipping the read again.
        val logging = dist.resolve("kotlin-logging.js")
        val envRead = Regex(
            """typeof process !== 'undefined' && process\.env && \(process\.env\.KOTLIN_LOGGING_STARTUP_MESSAGE === 'false' """ +
                """\|\| process\.env\.KOTLIN_LOGGING_STARTUP_MESSAGE === false\)"""
        )
        val patched = logging.readText().replace(envRead, "true /* banner off; no environment access */")
        check("process.env" !in patched) { "kotlin-logging.js changed: update the process.env patch in build.gradle.kts" }
        logging.writeText(patched)
        val extra = file("src/jsMain/typescript/extra.d.ts").readText()
        if (!dts.readText().contains("Appended by build.gradle.kts")) dts.appendText(extra)
        val names = Regex("""^export declare (?:abstract )?(?:class|function|const|namespace|interface) (\w+)""", RegexOption.MULTILINE)
            .findAll(dts.readText()).map { it.groupValues[1] }
            .filter { it != "RetryOptions" }
            .distinct().sorted().toList()
        check(names.isNotEmpty()) { "No exports found in ${dts.name}" }
        dist.resolve("index.cjs").writeText(
            "// Generated by build.gradle.kts: CommonJS entry over the UMD bundle.\n" +
                "const kit = require(\"./arrow-resilience-kit.js\");\n" +
                "// Compatibility: `X.Companion.create(...)` -> the static `X.create(...)`.\n" +
                "for (const value of Object.values(kit)) {\n" +
                "    const companion = typeof value === \"function\" ? value.Companion : undefined;\n" +
                "    if (companion && typeof value.create === \"function\" && typeof companion.create !== \"function\") {\n" +
                "        companion.create = value.create;\n" +
                "    }\n" +
                "}\n" +
                "module.exports = kit;\n"
        )
        dist.resolve("index.mjs").writeText(
            "// Generated by build.gradle.kts: ES-module entry over the UMD bundle.\n" +
                "import kit from \"./index.cjs\";\n" +
                "export const { ${names.joinToString(", ")} } = kit;\n" +
                "export default kit;\n"
        )
    }
}

// CI hosts that can't reach nodejs.org / github.com (the self-hosted Gitea Act
// Runner) can't let the Kotlin plugin download Node.js/Yarn. Set
// USE_SYSTEM_NODE=true to use the `node`/`yarn` already on PATH instead.
if (System.getenv("USE_SYSTEM_NODE") == "true") {
    rootProject.plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin> {
        rootProject.extensions.getByType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec>().download.set(false)
    }
}
