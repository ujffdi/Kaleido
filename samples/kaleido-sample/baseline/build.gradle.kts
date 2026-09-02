plugins {
    id("com.android.application")
    id("com.getkeepsafe.dexcount")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tongsr.kaleido.sample"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.tongsr.kaleido.sample"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures { compose = true }

    sourceSets.getByName("main") {
        manifest.srcFile("../app/src/main/AndroidManifest.xml")
        java.directories.clear()
        java.directories.add("../app/src/main/java")
        kotlin.directories.clear()
        kotlin.directories.add("../app/src/main/kotlin")
        resources.directories.clear()
        resources.directories.add("../app/src/main/resources")
        res.directories.clear()
        res.directories.add("../app/src/main/res")
        assets.directories.clear()
        assets.directories.add("../app/src/main/assets")
        jniLibs.directories.clear()
        jniLibs.directories.add("../app/src/main/jniLibs")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
}
