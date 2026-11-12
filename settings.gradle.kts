// settings.gradle.kts — the first file Gradle reads.
// It says WHERE to download plugins and libraries from, and WHICH modules make up the project.

pluginManagement {
    repositories {
        google()            // Android Gradle Plugin, AndroidX libraries
        mavenCentral()      // Kotlin and most other open-source libraries
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Modules must not declare their own repositories; everything comes from here.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PillionTalk"

// The project has one module for now: the app itself.
include(":app")
