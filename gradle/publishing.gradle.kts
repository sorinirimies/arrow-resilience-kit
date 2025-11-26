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
                description.set("Resilience patterns for Kotlin Multiplatform using Arrow")
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
                        email.set("sorin.irimies@gmail.com")
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
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/sorinirimies/arrow-resilience-kit")
            credentials {
                username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
                password = project.findProperty("gpr.token") as String? ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

// Optional: Configure signing for releases
configure<SigningExtension> {
    val signingKey = project.findProperty("signingKey") as String? ?: System.getenv("SIGNING_KEY")
    val signingPassword = project.findProperty("signingPassword") as String? ?: System.getenv("SIGNING_PASSWORD")
    
    if (signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(extensions.getByType<PublishingExtension>().publications)
    }
}