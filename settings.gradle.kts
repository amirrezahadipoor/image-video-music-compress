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
        // Poolakey (Cafe Bazaar IAP SDK) is hosted on JitPack
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Compressly"
include(":app")
include(":baselineprofile")
