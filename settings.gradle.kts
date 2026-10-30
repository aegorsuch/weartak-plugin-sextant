pluginManagement {
    repositories {
        // Gradle aborts on a Maven Central HTTP 429 rather than trying the next repository.
        val takUser = listOf("ARTIFACTORY_CONSUME_USER", "ARTIFACTORY_USER", "TAK_USERNAME")
            .firstNotNullOfOrNull { System.getenv(it)?.takeIf(String::isNotBlank) }
        val takPass = listOf("ARTIFACTORY_CONSUME_PASSWORD", "ARTIFACTORY_PASSWORD", "TAK_PASSWORD")
            .firstNotNullOfOrNull { System.getenv(it)?.takeIf(String::isNotBlank) }
        if (takUser != null && takPass != null) {
            maven {
                name = "takArtifactory"
                url = uri("https://artifacts.tak.gov/artifactory/maven-release")
                credentials {
                    username = takUser
                    password = takPass
                }
                println("takArtifactory for plugins: ${if (takUser != null && takPass != null) "enabled" else "disabled (no credentials in environment)"}")
            }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// pluginManagement is compiled before this scope, so credential selection repeats above.
val takArtifactoryUrl = "https://artifacts.tak.gov/artifactory/maven-release"
val takUser = listOf("ARTIFACTORY_CONSUME_USER", "ARTIFACTORY_USER", "TAK_USERNAME")
    .firstNotNullOfOrNull { System.getenv(it)?.takeIf(String::isNotBlank) }
val takPass = listOf("ARTIFACTORY_CONSUME_PASSWORD", "ARTIFACTORY_PASSWORD", "TAK_PASSWORD")
    .firstNotNullOfOrNull { System.getenv(it)?.takeIf(String::isNotBlank) }
if (takUser != null && takPass != null) {
    // Robolectric downloads outside Gradle's repositories.
    gradle.extra["takArtifactoryUrl"] = takArtifactoryUrl
    gradle.extra["takArtifactoryUser"] = takUser
    gradle.extra["takArtifactoryPassword"] = takPass
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (takUser != null && takPass != null) {
            maven {
                name = "takArtifactory"
                url = uri(takArtifactoryUrl)
                credentials {
                    username = takUser
                    password = takPass
                }
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "weartak-plugin-sextant"
include(":app")
