plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.ujffdi.kaleido")
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

kaleido {
    profile.set(com.tongsr.kaleido.gradle.dsl.KaleidoProfile.FULL)
    generation {
        packageCount.set(4)
        classesPerPackage.set(4)
        methodsPerClass.set(4)
        layoutCount.set(8)
        drawableCount.set(16)
        stringCount.set(32)
        activityCount.set(1)
        compose {
            enabled.set(true)
            fileCount.set(4)
            functionsPerFile.set(4)
        }
    }
    resources {
        nativeLibrariesToDelete.add("libobsolete.so")
        metadataToDelete.add("META-INF/DEPENDENCIES")
        confirmedUnusedStringsFile.set(layout.projectDirectory.file("unused-strings.txt"))
        retainedLanguages.add("zh-CN")
    }
    protection {
        resourceNames.add("sample_status")
        packagedPaths.add("res/layout/protected_probe_layout.xml")
        classes("runtime-probe") {
            exact("com.tongsr.kaleido.sample.RuntimeProtectedEntry")
            dimensions.addAll(
                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.REACHABILITY,
                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.ORIGINAL_IDENTITY,
                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.DESCRIPTOR_CLOSURE,
                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.RUNTIME_ATTRIBUTES,
            )
            reason.set("The resource probe loads this class by its exact runtime name")
        }
        resources("stable-layout") {
            exact("protected_probe_layout")
            dimensions.addAll(
                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.RESOURCE_NAME,
                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.PACKAGED_PATH,
            )
            reason.set("The comparison keeps one standalone layout name and path stable")
        }
    }
}
