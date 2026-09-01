import org.gradle.plugin.compatibility.compatibility
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-gradle-plugin`
    `maven-publish`
    alias(libs.plugins.kotlin.jvm)
    id("com.gradle.plugin-publish") version "2.1.1"
}

group = "io.github.ujffdi"
version = providers.gradleProperty("kaleidoVersion").getOrElse("0.1.0-dev")
val kaleidoWebsite = providers.gradleProperty("kaleidoWebsite")
    .getOrElse("https://github.com/ujffdi/Kaleido")
val kaleidoVcsUrl = providers.gradleProperty("kaleidoVcsUrl")
    .getOrElse("https://github.com/ujffdi/Kaleido.git")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
    withSourcesJar()
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        javaParameters = true
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

tasks.processResources {
    from(rootProject.file("LICENSE")) { into("META-INF") }
    from(rootProject.file("NOTICE")) { into("META-INF") }
    from(rootProject.file("THIRD_PARTY_NOTICES.md")) { into("META-INF") }
}

tasks.named<Jar>("sourcesJar") {
    from(rootProject.file("LICENSE")) { into("META-INF") }
    from(rootProject.file("NOTICE")) { into("META-INF") }
    from(rootProject.file("THIRD_PARTY_NOTICES.md")) { into("META-INF") }
}

dependencies {
    compileOnly("com.android.tools.build:gradle:9.2.0")
    implementation("com.android.tools.build:bundletool:1.18.1")
    implementation("org.ow2.asm:asm-commons:9.10.1")
    implementation("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.10")
    compileOnly("com.android.tools.build:aapt2-proto:9.2.0-15009934")
    compileOnly("com.google.guava:guava:32.0.1-jre")
    compileOnly("com.google.protobuf:protobuf-java:3.22.3")

    testImplementation(gradleTestKit())
    testImplementation("com.android.tools.build:gradle:9.2.0")
    testImplementation("com.android.tools.build:aapt2-proto:9.2.0-15009934")
    testImplementation("com.google.protobuf:protobuf-java:3.22.3")
    testImplementation("junit:junit:4.13.2")
}

gradlePlugin {
    plugins {
        create("kaleido") {
            id = "io.github.ujffdi.kaleido"
            implementationClass = "com.tongsr.kaleido.gradle.KaleidoPlugin"
            displayName = "Kaleido"
            description = "Deterministic Android release hardening pipeline"
            website = kaleidoWebsite
            vcsUrl = kaleidoVcsUrl
            tags = listOf("android", "release", "reproducible-builds", "security")
            compatibility {
                features {
                    configurationCache = true
                }
            }
        }
    }
}

val functionalTestRepository = layout.buildDirectory.dir("functional-test-repository")

publishing {
    repositories {
        maven {
            name = "functionalTest"
            url = uri(functionalTestRepository)
        }
    }
}

publishing.publications.withType<MavenPublication>().configureEach {
    pom {
        name = "Kaleido Gradle Plugin"
        description = "Deterministic Android release hardening pipeline"
        url = kaleidoWebsite
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }
        scm {
            connection = "scm:git:$kaleidoVcsUrl"
            developerConnection = "scm:git:$kaleidoVcsUrl"
            url = kaleidoVcsUrl
        }
    }
}

tasks.test {
    dependsOn(tasks.named("publishAllPublicationsToFunctionalTestRepository"))
    systemProperty("kaleido.test.repository", functionalTestRepository.get().asFile.absolutePath)
    systemProperty("kaleido.test.plugin.version", project.version.toString())
    systemProperty("kaleido.test.sdk", rootProject.file("local.properties").readLines()
        .first { it.startsWith("sdk.dir=") }
        .substringAfter("sdk.dir="))
    systemProperty("kaleido.test.agp", providers.gradleProperty("kaleidoTestAgp")
        .getOrElse("9.2.0"))
    systemProperty("kaleido.test.gradle", providers.gradleProperty("kaleidoTestGradle")
        .getOrElse("9.4.1"))
}
