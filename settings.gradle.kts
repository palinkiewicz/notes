pluginManagement {
    repositories {
        google {
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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Notes"

include(":app")

// Pure-Kotlin JVM modules: no Android dependency, unit-testable with plain JUnit.
include(":core:model")
include(":core:format")
include(":core:ink")

// Android library modules.
include(":core:data")
include(":core:ui")
include(":feature:editor")
include(":feature:library")
