pluginManagement {
    repositories {
        val user = sequenceOf("ARTIFACTORY_CONSUME_USER", "ARTIFACTORY_USER", "TAK_USERNAME")
            .mapNotNull { System.getenv(it)?.takeIf(String::isNotBlank) }.firstOrNull()
        val password = sequenceOf("ARTIFACTORY_CONSUME_PASSWORD", "ARTIFACTORY_PASSWORD", "TAK_PASSWORD")
            .mapNotNull { System.getenv(it)?.takeIf(String::isNotBlank) }.firstOrNull()
        println("TAK Artifactory plugin repository: ${if (user != null && password != null) "enabled" else "disabled"}")
        if (user != null && password != null) {
            maven {
                name = "takArtifactory"
                url = uri("https://artifacts.tak.gov/artifactory/maven-release")
                credentials {
                    username = user
                    this.password = password
                }
            }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val user = sequenceOf("ARTIFACTORY_CONSUME_USER", "ARTIFACTORY_USER", "TAK_USERNAME")
            .mapNotNull { System.getenv(it)?.takeIf(String::isNotBlank) }.firstOrNull()
        val password = sequenceOf("ARTIFACTORY_CONSUME_PASSWORD", "ARTIFACTORY_PASSWORD", "TAK_PASSWORD")
            .mapNotNull { System.getenv(it)?.takeIf(String::isNotBlank) }.firstOrNull()
        println("TAK Artifactory dependency repository: ${if (user != null && password != null) "enabled" else "disabled"}")
        if (user != null && password != null) {
            maven {
                name = "takArtifactory"
                url = uri("https://artifacts.tak.gov/artifactory/maven-release")
                credentials {
                    username = user
                    this.password = password
                }
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "weartak-plugin-sextant"
include(":app")
