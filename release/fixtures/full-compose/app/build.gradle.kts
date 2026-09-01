plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.tongsr.kaleido")
}

android {
    namespace = "com.tongsr.kaleido.matrix.compose"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "com.tongsr.kaleido.matrix.compose"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures { compose = true }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}

dependencies {
    implementation("androidx.compose.runtime:runtime:1.10.0")
}

kaleido {
    profile.set(com.tongsr.kaleido.gradle.dsl.KaleidoProfile.FULL)
    generation {
        compose {
            enabled.set(true)
        }
    }
}
