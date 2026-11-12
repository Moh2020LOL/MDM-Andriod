pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // youtubedl-android's fork is on Maven Central; JitPack is its older home.
        maven("https://jitpack.io")
    }
}

rootProject.name = "MisterDownloadManager"
include(":app")
