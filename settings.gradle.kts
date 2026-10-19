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
    }
}

rootProject.name = "MyPhotoLibrary"

include(":core")
// CORE_ONLY=1 compila e verifica solo il motore (Kotlin puro), senza Android SDK
if (System.getenv("CORE_ONLY") == null) {
    include(":app")
}
