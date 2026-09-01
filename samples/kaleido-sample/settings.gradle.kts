pluginManagement {
    repositories {
        providers.gradleProperty("samplePluginRepository").orNull?.let { repository ->
            maven { url = uri(repository) }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    resolutionStrategy.eachPlugin {
        when (requested.id.id) {
            "com.android.application" ->
                useVersion(providers.gradleProperty("sampleAgpVersion").getOrElse("9.2.0"))
            "io.github.ujffdi.kaleido" ->
                useVersion(providers.gradleProperty("sampleKaleidoVersion").getOrElse("0.1.0"))
            "org.jetbrains.kotlin.plugin.compose" -> useVersion("2.2.10")
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "kaleido-comprehensive-sample"
include(":baseline", ":app")
