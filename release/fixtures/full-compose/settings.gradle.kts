pluginManagement {
    repositories {
        maven { url = uri(providers.gradleProperty("matrixPluginRepository").get()) }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    resolutionStrategy.eachPlugin {
        when (requested.id.id) {
            "com.android.application" ->
                useVersion(providers.gradleProperty("matrixAgp").get())
            "io.github.ujffdi.kaleido" ->
                useVersion(providers.gradleProperty("matrixKaleido").get())
            "org.jetbrains.kotlin.plugin.compose" ->
                useVersion(providers.gradleProperty("matrixKotlin").get())
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "kaleido-matrix-full-compose"
include(":app")
