plugins {
    id("com.android.application")
    id("io.github.ujffdi.kaleido")
}

android {
    namespace = "com.tongsr.kaleido.matrix.nativeprobe"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "25.1.8937393"
    defaultConfig {
        applicationId = "com.tongsr.kaleido.matrix.nativeprobe"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake { cppFlags += "-std=c++17" }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}
