// Publish to Maven Central via the Sonatype Central Portal Publisher API —
// no third-party Gradle plugin (previously com.vanniktech.maven.publish).
// Apply this script in build.gradle.kts with: apply(from = "gradle/central-portal.gradle.kts")
//
// This does exactly what that plugin's `publishToMavenCentral()` did, reverse
// engineered from its bundled `com.vanniktech:central-portal` jar (decompiled
// class files — Central Portal has no official Gradle plugin, only a Maven
// plugin and this HTTP API):
//   1. Stage all Maven publications to a local directory repo (below).
//   2. Zip that directory into a "deployment bundle".
//   3. POST the bundle to https://central.sonatype.com/api/v1/publisher/upload
//      with publishingType=AUTOMATIC, so Sonatype auto-validates and
//      publishes it (equivalent to the plugin's automaticRelease(true)).
//
// Confirmed request shape (POST /api/v1/publisher/upload?name=..&publishingType=..):
//   - Authorization: Bearer <base64(username:password)>
//   - Accept: application/json
//   - multipart/form-data, single part named "bundle", content-type
//     application/octet-stream, filename = the zip's file name
//   - 2xx response body is the plain-text deploymentId
//
// Credentials: same property names the old plugin used, so CI secrets don't
// need to change — mavenCentralUsername / mavenCentralPassword (a Central
// Portal user token, set via ORG_GRADLE_PROJECT_* env vars in CI, see
// .github/workflows/release.yml). GPG signing is unchanged, handled by
// gradle/publishing.gradle.kts (GPG_SIGNING_KEY / GPG_PASSPHRASE).

import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

val centralStagingDir = layout.buildDirectory.dir("central-staging")

// Central Portal requires a javadoc jar to exist alongside every published
// artifact set, even for Kotlin/Native targets that have no real API docs.
// Dokka is intentionally pinned to v1 here (see build.gradle.kts), so this
// is an empty placeholder — the real, fully-featured HTML docs are still
// published separately to GitHub Pages via dokkaHtml/prepareDocs.
val emptyJavadocJar = tasks.register<Jar>("emptyJavadocJar") {
    archiveClassifier.set("javadoc")
}

configure<PublishingExtension> {
    repositories {
        maven {
            name = "CentralPortalStaging"
            url = uri(centralStagingDir)
        }
    }

    publications.withType<MavenPublication>().configureEach {
        artifact(emptyJavadocJar)
    }
}

val zipCentralPortalBundle = tasks.register<Zip>("zipCentralPortalBundle") {
    group = "publishing"
    description = "Zips the staged Maven Central deployment bundle."
    dependsOn("publishAllPublicationsToCentralPortalStagingRepository")
    from(centralStagingDir)
    archiveFileName.set("central-bundle.zip")
    destinationDirectory.set(layout.buildDirectory.dir("central-portal"))
}

tasks.register("publishToCentralPortal") {
    group = "publishing"
    description = "Uploads the deployment bundle to Sonatype Central Portal (publishingType=AUTOMATIC)."
    dependsOn(zipCentralPortalBundle)

    doLast {
        val username = project.findProperty("mavenCentralUsername") as String?
        val password = project.findProperty("mavenCentralPassword") as String?
        require(!username.isNullOrBlank() && !password.isNullOrBlank()) {
            "mavenCentralUsername/mavenCentralPassword are required " +
                "(a Central Portal user token, not your login password) — " +
                "set via ORG_GRADLE_PROJECT_mavenCentralUsername/Password."
        }

        val bundle = zipCentralPortalBundle.get().archiveFile.get().asFile
        val deploymentName = "${project.group}-${project.name}-${project.version}"
        val boundary = "----ArrowResilienceKitBoundary${System.currentTimeMillis()}"

        val head = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"bundle\"; filename=\"${bundle.name}\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val body = head + bundle.readBytes() + tail

        val token = Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
        val uploadUrl = "https://central.sonatype.com/api/v1/publisher/upload" +
            "?name=$deploymentName&publishingType=AUTOMATIC"

        val request = HttpRequest.newBuilder()
            .uri(URI.create(uploadUrl))
            .timeout(Duration.ofMinutes(5))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()

        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            throw GradleException(
                "Central Portal upload failed (HTTP ${response.statusCode()}): ${response.body()}",
            )
        }

        println("Central Portal deployment created: ${response.body()}")
        println("Track validation/publishing status at https://central.sonatype.com/publishing/deployments")
    }
}
