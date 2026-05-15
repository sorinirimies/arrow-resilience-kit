// Publishing configuration for Arrow Resilience Kit
// Apply this script in build.gradle.kts with: apply(from = "gradle/publishing.gradle.kts")

import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.PublishingExtension
import org.gradle.plugins.signing.SigningExtension

configure<PublishingExtension> {
    publications {
        withType<MavenPublication> {
            pom {
                name.set("Arrow Resilience Kit")
                description.set("Kotlin Multiplatform resilience patterns built on Arrow")
                url.set("https://github.com/sorinirimies/arrow-resilience-kit")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }

                developers {
                    developer {
                        id.set("sorinirimies")
                        name.set("Sorin Albu-Irimies")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/sorinirimies/arrow-resilience-kit.git")
                    developerConnection.set("scm:git:ssh://github.com/sorinirimies/arrow-resilience-kit.git")
                    url.set("https://github.com/sorinirimies/arrow-resilience-kit")
                }
            }
        }
    }

    repositories {
        maven {
            name = "MavenCentral"
            url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            credentials {
                username = System.getenv("MAVEN_USERNAME") ?: ""
                password = System.getenv("MAVEN_PASSWORD") ?: ""
            }
        }
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/sorinirimies/arrow-resilience-kit")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: ""
                password = System.getenv("PACKAGES_PUBLISH") ?: ""
            }
        }
    }
}

// Optional GPG signing (required for Maven Central, skip for local/JitPack)
configure<SigningExtension> {
    val signingKey = System.getenv("GPG_SIGNING_KEY")
    val signingPassphrase = System.getenv("GPG_PASSPHRASE")
    if (!signingKey.isNullOrBlank()) {
        useInMemoryPgpKeys(signingKey, signingPassphrase)
        sign(extensions.getByType<PublishingExtension>().publications)
    }
}
