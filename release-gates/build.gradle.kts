plugins {
    application
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

application {
    mainClass = "com.tongsr.kaleido.release.CompatibilityMatrixCli"
}

dependencies {
    implementation("com.android.tools.build:bundletool:1.18.1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    systemProperty("kaleido.repository.root", rootProject.projectDir.absolutePath)
}

val pluginRuntime = project(":kaleido-gradle-plugin").configurations.named("runtimeClasspath")

tasks.register<JavaExec>("generateSupplyChainEvidence") {
    dependsOn(":kaleido-gradle-plugin:jar", ":kaleido-gradle-plugin:sourcesJar",
        ":kaleido-gradle-plugin:publishAllPublicationsToFunctionalTestRepository")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.tongsr.kaleido.release.SupplyChainEvidenceCli"
    val evidenceDirectory = rootProject.layout.buildDirectory.dir("release-gates/supply-chain")
    outputs.dir(evidenceDirectory)
    doFirst {
        val pluginVersion = project(":kaleido-gradle-plugin").version.toString()
        args = listOf(
            "--output", evidenceDirectory.get().asFile.absolutePath,
            "--version", pluginVersion,
            "--candidate", rootProject.file(
                "kaleido-gradle-plugin/build/libs/kaleido-gradle-plugin-$pluginVersion.jar").absolutePath,
            "--sources", rootProject.file(
                "kaleido-gradle-plugin/build/libs/kaleido-gradle-plugin-$pluginVersion-sources.jar").absolutePath,
            "--marker", rootProject.file(
                "kaleido-gradle-plugin/build/functional-test-repository/com/tongsr/kaleido/" +
                    "com.tongsr.kaleido.gradle.plugin/$pluginVersion/" +
                    "com.tongsr.kaleido.gradle.plugin-$pluginVersion.pom").absolutePath,
            "--license", rootProject.file("LICENSE").absolutePath,
            "--notice", rootProject.file("NOTICE").absolutePath,
            "--third-party", rootProject.file("THIRD_PARTY_NOTICES.md").absolutePath,
            "--provenance", rootProject.file(
                "release/provenance/upstream-components.properties").absolutePath,
            "--verification", rootProject.file("gradle/verification-metadata.xml").absolutePath,
        )
        pluginRuntime.get().resolvedConfiguration.resolvedArtifacts
            .sortedBy { "${it.moduleVersion.id.group}:${it.name}:${it.moduleVersion.id.version}" }
            .forEach {
                args("--component",
                    "${it.moduleVersion.id.group}:${it.name}:${it.moduleVersion.id.version}|" +
                        it.file.absolutePath)
            }
    }
}

tasks.register("verifyLocalSizeBudgets") {
    dependsOn(":kaleido-gradle-plugin:jar")
    val output = rootProject.layout.buildDirectory.file("release-gates/performance/local-size-gate.properties")
    outputs.file(output)
    doLast {
        val pluginVersion = project(":kaleido-gradle-plugin").version.toString()
        val pluginJar = rootProject.file(
            "kaleido-gradle-plugin/build/libs/kaleido-gradle-plugin-$pluginVersion.jar")
        val dependencyBytes = pluginRuntime.get().resolvedConfiguration.resolvedArtifacts
            .map { it.file }
            .distinct()
            .sumOf { it.length() }
        val pluginLimit = 10L * 1024 * 1024
        val dependencyLimit = 50L * 1024 * 1024
        require(pluginJar.length() <= pluginLimit) { "KLD-PERF-001 plugin JAR exceeds 10 MiB" }
        require(dependencyBytes <= dependencyLimit) {
            "KLD-PERF-001 newly resolved dependencies exceed 50 MiB"
        }
        val target = output.get().asFile
        target.parentFile.mkdirs()
        target.writeText(
            "schema=KaleidoLocalSizeGate.v1\n" +
                "plugin.jarBytes=${pluginJar.length()}\n" +
                "plugin.limitBytes=$pluginLimit\n" +
                "dependencies.newBytes=$dependencyBytes\n" +
                "dependencies.limitBytes=$dependencyLimit\n" +
                "verdict=PASS\n"
        )
    }
}
