import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "flight-search"

include(
    "app",
    "common",
    "db",
    "collector",
    "collector-wizz",
    "collector-ryanair",
    "collector-vueling",
    "collector-transavia",
    "search",
    "api"
)