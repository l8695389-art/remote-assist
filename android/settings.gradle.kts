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
        // Mirror that still publishes prebuilt Google WebRTC AARs for Android
        // (upstream org.webrtc artifacts are no longer published to Maven Central).
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "RemoteAssist"
include(":app")
