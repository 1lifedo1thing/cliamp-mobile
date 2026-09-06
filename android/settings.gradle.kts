/**
 * Google Maven is normally reached via dl.google.com, which some networks and
 * proxies filter. The URL is overridable through the `googleMavenRepositoryUrl`
 * Gradle property (e.g. in ~/.gradle/gradle.properties) so restricted networks
 * can point at a reachable mirror without editing this file.
 */
pluginManagement {
    repositories {
        maven {
            url = uri(providers.gradleProperty("googleMavenRepositoryUrl")
                .getOrElse("https://dl.google.com/dl/android/maven2/"))
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri(providers.gradleProperty("googleMavenRepositoryUrl")
                .getOrElse("https://dl.google.com/dl/android/maven2/"))
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "cliamp"
include(":app")
