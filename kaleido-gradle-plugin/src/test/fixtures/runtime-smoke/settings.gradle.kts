pluginManagement {
    repositories {
        maven { url = uri("../../../../build/functional-test-repository") }
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

rootProject.name = "kaleido-runtime-smoke"
include(":app")
