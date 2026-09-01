plugins {
    id("com.android.application") version "9.2.1"
    id("com.tongsr.kaleido") version "0.1.0-dev"
}

android {
    namespace = "example.kaleido.runtime"
    compileSdk = 36

    defaultConfig {
        applicationId = "example.kaleido.runtime"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}

kaleido {
    profile.set(com.tongsr.kaleido.gradle.dsl.KaleidoProfile.FULL)
    generation { activityCount.set(2) }
}
