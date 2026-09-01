plugins {
    id("com.android.application")
    id("com.tongsr.kaleido")
}

android {
    namespace = "com.tongsr.kaleido.matrix.kotlin"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "com.tongsr.kaleido.matrix.kotlin"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}
