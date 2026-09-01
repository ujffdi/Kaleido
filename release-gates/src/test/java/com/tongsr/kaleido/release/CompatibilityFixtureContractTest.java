package com.tongsr.kaleido.release;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

public final class CompatibilityFixtureContractTest {
    private final Path root = Path.of(System.getProperty("kaleido.repository.root"));

    @Test
    public void portalMetadataDeclaresProvenConfigurationCacheCompatibility()
            throws Exception {
        var build = Files.readString(root.resolve("kaleido-gradle-plugin/build.gradle.kts"));
        assertTrue(build.contains("compatibility {"));
        assertTrue(build.contains("configurationCache = true"));
    }

    @Test
    public void supplyChainEvidenceDeclaresCandidateAndMetadataInputs() throws Exception {
        var build = Files.readString(root.resolve("release-gates/build.gradle.kts"));
        assertTrue(build.contains("withPropertyName(\"candidateArtifacts\")"));
        assertTrue(build.contains("withPropertyName(\"pluginRuntimeClasspath\")"));
        assertTrue(build.contains("withPropertyName(\"releaseMetadata\")"));
        assertFalse(build.contains("outputs.dir(evidenceDirectory)"));
    }

    @Test
    public void releaseRunnersBindAnExplicitFinalCandidateVersion() throws Exception {
        for (var relative : List.of(
                "scripts/release/validate-public-docs.sh",
                "scripts/release/run-compatibility-row.sh",
                "scripts/release/run-runtime-row.sh")) {
            var script = Files.readString(root.resolve(relative));
            assertFalse(relative, script.contains("0.1.0-dev"));
            assertTrue(relative, script.contains("candidate_version")
                    || script.contains("version=\"$1\""));
        }
        var compatibility = Files.readString(
                root.resolve("scripts/release/run-compatibility-row.sh"));
        assertTrue(compatibility.contains("io[.]github[.]ujffdi[.]kaleido"));
        assertTrue(compatibility.contains("-PkaleidoVersion=\"$candidate_version\""));
    }

    @Test
    public void releaseDossierRunnerVerifiesCandidateAndIndependentSignatures() throws Exception {
        var cache = Files.readString(root.resolve(
                "scripts/release/validate-cache-candidate.sh"));
        assertTrue(cache.contains("candidate.sha256=$candidate"));
        assertTrue(cache.contains("configurationCacheIsReusedWithoutCapturingGradleModelObjects"));
        assertTrue(cache.contains("threeRelocatedWorkspacesHaveByteIdenticalDeterministicBoundaries"));

        var dossier = Files.readString(root.resolve(
                "scripts/release/assemble-release-dossier.sh"));
        assertTrue(dossier.contains("gpg --batch --status-fd 1 --verify"));
        assertTrue(dossier.contains("approval signing keys must be independent"));
        assertTrue(dossier.contains("--manifest-signature"));
        assertTrue(dossier.contains("--approval-signature"));
    }

    @Test
    public void portalPublicationClosesEveryManifestAssetBeforeUpload() throws Exception {
        var manifest = Files.readString(root.resolve(
                "scripts/release/create-signed-release-manifest.sh"));
        assertTrue(manifest.contains("kaleido-gradle-plugin-$version-javadoc.jar"));
        assertTrue(manifest.contains("build/publications/pluginMaven/pom-default.xml"));
        assertTrue(manifest.contains("build/publications/pluginMaven/module.json"));
        assertTrue(manifest.contains(
                "build/publications/kaleidoPluginMarkerMaven/pom-default.xml"));

        for (var relative : List.of(
                "scripts/release/validate-portal-candidate.sh",
                "scripts/release/publish-immutable-candidate.sh")) {
            var script = Files.readString(root.resolve(relative));
            assertTrue(relative, script.contains("verify_manifest_assets"));
            assertTrue(relative, script.contains("manifest asset digest mismatch"));
            assertTrue(relative, script.contains("portal_credentials_available"));
            assertTrue(relative, script.contains("gradle[.]publish[.]key"));
        }
        var publish = Files.readString(root.resolve(
                "scripts/release/publish-immutable-candidate.sh"));
        assertTrue(publish.contains("source work tree is not clean"));
        assertTrue(publish.contains("--validate-only"));
        assertTrue(publish.contains("kaleido-$version.cdx.json"));
        assertTrue(publish.contains("--draft"));

        var postPublication = Files.readString(root.resolve(
                "scripts/release/verify-public-publication.sh"));
        assertTrue(postPublication.contains("kaleidoPluginMarkerMaven/pom-default.xml"));
        assertTrue(postPublication.contains("post-publication-record.properties"));
        assertTrue(postPublication.contains("-PsampleAgpVersion=9.2.0"));
        assertTrue(postPublication.contains("-PsampleKaleidoVersion=\"$version\""));
        assertFalse(postPublication.contains("matrixPluginRepository"));
        assertFalse(postPublication.contains("-PmatrixAgp"));
        assertFalse(postPublication.contains("-PmatrixKaleido"));

        var finalize = Files.readString(root.resolve(
                "scripts/release/finalize-public-release.sh"));
        assertTrue(finalize.contains("finalize-verified-public-release"));
        assertTrue(finalize.contains("FinalReleaseDossierCli"));
        assertTrue(finalize.contains("--draft=false"));
    }

    @Test
    public void fixturesAndSamplesResolveThePackagedMarkerWithoutImplementationShortcuts()
            throws Exception {
        for (var relative : List.of(
                "release/fixtures/java-safe",
                "release/fixtures/kotlin-safe",
                "release/fixtures/full-compose",
                "release/fixtures/native-resource")) {
            var fixture = root.resolve(relative);
            var settings = Files.readString(fixture.resolve("settings.gradle.kts"));
            var build = Files.readString(fixture.resolve("app/build.gradle.kts"));
            assertTrue(relative, settings.contains("matrixPluginRepository"));
            assertTrue(relative, settings.contains("io.github.ujffdi.kaleido"));
            assertTrue(relative, build.contains("id(\"io.github.ujffdi.kaleido\")"));
            assertFalse(relative, settings.contains("includeBuild"));
            assertFalse(relative, settings.contains("implementationClass"));
            assertFalse(relative, build.contains("implementationClass"));
        }
        var sample = root.resolve("samples/kaleido-sample");
        var settings = Files.readString(sample.resolve("settings.gradle.kts"));
        var build = Files.readString(sample.resolve("app/build.gradle.kts"));
        assertTrue(settings.contains("samplePluginRepository"));
        assertTrue(settings.contains("io.github.ujffdi.kaleido"));
        assertTrue(build.contains("id(\"io.github.ujffdi.kaleido\")"));
        assertFalse(settings.contains("includeBuild"));
        assertFalse(settings.contains("implementationClass"));
        assertFalse(build.contains("implementationClass"));
    }

    @Test
    public void fixturesCoverJavaBuiltInKotlinFullComposeAndSampleContracts()
            throws Exception {
        var javaFixture = root.resolve("release/fixtures/java-safe");
        assertTrue(Files.isRegularFile(javaFixture.resolve(
                "app/src/main/java/com/tongsr/kaleido/matrix/java/MainActivity.java")));
        assertFalse(Files.exists(javaFixture.resolve("app/src/main/kotlin")));

        var kotlinFixture = root.resolve("release/fixtures/kotlin-safe");
        assertTrue(Files.isRegularFile(kotlinFixture.resolve(
                "app/src/main/kotlin/com/tongsr/kaleido/matrix/kotlin/MainActivity.kt")));
        var kotlinBuild = Files.readString(kotlinFixture.resolve("app/build.gradle.kts"));
        assertFalse(kotlinBuild.contains("org.jetbrains.kotlin.android"));

        var composeBuild = Files.readString(root.resolve(
                "release/fixtures/full-compose/app/build.gradle.kts"));
        assertTrue(composeBuild.contains("KaleidoProfile.FULL"));
        assertTrue(composeBuild.contains("enabled.set(true)"));
        assertTrue(composeBuild.contains("org.jetbrains.kotlin.plugin.compose"));

        var nativeBuild = Files.readString(root.resolve(
                "release/fixtures/native-resource/app/build.gradle.kts"));
        assertTrue(nativeBuild.contains("ndkVersion = \"25.1.8937393\""));
        assertTrue(Files.isRegularFile(root.resolve(
                "release/fixtures/native-resource/app/src/main/cpp/native_probe.cpp")));

        assertTrue(Files.isRegularFile(root.resolve(
                "samples/kaleido-sample/app/src/main/kotlin/com/tongsr/kaleido/sample/"
                        + "MainActivity.kt")));
    }

    @Test
    public void publicSampleIsSelfContainedAndCoversTheComprehensiveContract()
            throws Exception {
        var sample = root.resolve("samples/kaleido-sample");
        assertTrue(Files.isRegularFile(sample.resolve("README.md")));
        var settings = Files.readString(sample.resolve("settings.gradle.kts"));
        assertTrue(settings.contains("gradleProperty(\"sampleAgpVersion\")"));
        assertTrue(settings.contains("gradleProperty(\"sampleKaleidoVersion\")"));
        assertTrue(settings.contains("gradleProperty(\"samplePluginRepository\").orNull"));
        assertTrue(settings.contains("getOrElse(\"9.2.0\")"));
        assertTrue(settings.contains("getOrElse(\"0.1.0\")"));
        assertTrue(settings.contains("include(\":baseline\", \":app\")"));
        assertFalse(settings.contains("matrixPluginRepository"));
        assertFalse(settings.contains("matrixAgp"));
        assertFalse(settings.contains("matrixKaleido"));

        var guide = Files.readString(sample.resolve("README.md"));
        assertTrue(guide.contains("## English"));
        assertTrue(guide.contains("## 中文"));

        var baselineBuild = Files.readString(sample.resolve("baseline/build.gradle.kts"));
        assertFalse(baselineBuild.contains("io.github.ujffdi.kaleido"));
        assertTrue(baselineBuild.contains("../app/src/main/kotlin"));
        assertTrue(baselineBuild.contains("../app/src/main/res"));

        var composeBuild = Files.readString(sample.resolve("app/build.gradle.kts"));
        assertTrue(composeBuild.contains("KaleidoProfile.FULL"));
        assertTrue(composeBuild.contains("enabled.set(true)"));
        assertTrue(composeBuild.contains("activityCount.set(1)"));
        assertTrue(composeBuild.contains("resourceNames.add(\"sample_status\")"));
        assertTrue(composeBuild.contains("nativeLibrariesToDelete.add(\"libobsolete.so\")"));
        assertTrue(composeBuild.contains("metadataToDelete.add(\"META-INF/DEPENDENCIES\")"));
        assertTrue(composeBuild.contains("confirmedUnusedStringsFile.set("));
        assertTrue(composeBuild.contains("retainedLanguages.add(\"zh-CN\")"));
        assertTrue(composeBuild.contains("org.jetbrains.kotlin.plugin.compose"));
        assertTrue(composeBuild.contains("androidx.compose.material3:material3"));

        var composeActivity = Files.readString(sample.resolve(
                "app/src/main/kotlin/com/tongsr/kaleido/sample/"
                        + "MainActivity.kt"));
        assertTrue(composeActivity.contains("ComponentActivity"));
        assertTrue(composeActivity.contains("MaterialTheme"));
        assertTrue(composeActivity.contains("KALEIDO_RESOURCE_PROBE_PASS"));

        var samplesIndex = Files.readString(root.resolve("samples/README.md"));
        assertTrue(samplesIndex.contains("kaleido-sample"));
        assertFalse(samplesIndex.contains("kaleido-full-compose-sample"));
        assertFalse(samplesIndex.contains("kaleido-resource-comparison"));

        var adoption = Files.readString(root.resolve("docs/public/adoption.md"));
        assertTrue(adoption.contains("samples/kaleido-sample"));
        assertFalse(adoption.contains("samples/kaleido-full-compose-sample"));

    }

    @Test
    public void publicSampleValidationBuildsBothModulesAndClosesReleaseEvidence()
            throws Exception {
        var validation = Files.readString(
                root.resolve("scripts/release/validate-public-docs.sh"));
        assertTrue(validation.contains("samples/kaleido-sample"));
        assertFalse(validation.contains("samples/kaleido-full-compose-sample"));
        assertTrue(validation.contains("(root / \"samples\").glob(\"**/*.md\")"));
        assertTrue(validation.contains("-PsamplePluginRepository=\"$plugin_repository\""));
        assertTrue(validation.contains("-PsampleAgpVersion=9.2.0"));
        assertTrue(validation.contains("-PsampleKaleidoVersion=\"$version\""));
        assertTrue(validation.contains("app/build/outputs/bundle/release/app-release.aab"));
        assertTrue(validation.contains(
                "baseline/build/outputs/bundle/release/baseline-release.aab"));
        assertTrue(validation.contains("release-evidence-set-manifest.properties"));
        assertTrue(validation.contains("publicationResult=PUBLISHED"));
        assertTrue(validation.contains("artifact-report.txt"));
        assertTrue(validation.contains("mappings/composed-mapping.txt"));
    }

    @Test
    public void compatibilityRunnerStartsFromAFreshWorkspace() throws Exception {
        var runner = Files.readString(
                root.resolve("scripts/release/run-compatibility-row.sh"));
        assertTrue(runner.contains("rm -rf \"$matrix_work\""));
    }
}
