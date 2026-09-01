package com.tongsr.kaleido.gradle;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.android.aapt.Resources;
import com.android.tools.r8.retrace.ProguardMapProducer;
import com.android.tools.r8.retrace.ProguardMappingSupplier;
import com.android.tools.r8.retrace.Retrace;
import com.android.tools.r8.retrace.RetraceCommand;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipFile;
import java.util.jar.JarFile;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class KaleidoPluginFunctionalTest {
    private static Map<String, String> sharedSigningEnvironment;
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void packagedPluginFinalizesOrdinaryReleaseBundle() throws IOException {
        var projectDirectory = temporaryFolder.newFolder("consumer").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");

        BuildResult result = runner(projectDirectory).withArguments("bundleRelease", "--stacktrace").build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":app:bundleRelease").getOutcome());
        assertEquals(TaskOutcome.SUCCESS,
                result.task(":app:generateKaleidoReleaseSafeContent").getOutcome());
        assertEquals(TaskOutcome.SUCCESS,
                result.task(":app:rewriteKaleidoReleaseManifestReferences").getOutcome());
        assertEquals(TaskOutcome.SUCCESS,
                result.task(":app:rewriteKaleidoReleaseClassesAndManifest").getOutcome());
        assertEquals(TaskOutcome.SUCCESS,
                result.task(":app:generateKaleidoReleaseR8Configuration").getOutcome());
        assertEquals(TaskOutcome.SUCCESS,
                result.task(":app:composeKaleidoReleaseR8Mappings").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:finalizeKaleidoReleaseBundle").getOutcome());
        assertEquals(TaskOutcome.SUCCESS,
                result.task(":app:publishKaleidoReleaseReleaseEvidence").getOutcome());
        assertTrue(Files.isRegularFile(projectDirectory.resolve(
                "app/build/outputs/bundle/release/app-release.aab")));
        assertTrue(result.getOutput().contains(
                "KLD-ADOPTION-002 project=:app variant=release stage=adoption"));
        assertTrue(result.getOutput().contains("buildType=release flavors=[]"));
        var plan = Files.readString(adoptionPlan(projectDirectory, "release"));
        assertTrue(plan.contains("profile=SAFE\n"));
        assertTrue(plan.contains("defaultsVersion=SafeDefaults.v1\n"));
        assertTrue(plan.contains("generation.packageBase=example.consumer.kaleido.generated\n"));
        assertTrue(plan.contains("generation.packageCount=4\n"));
        assertTrue(plan.contains("generation.classesPerPackage=4\n"));
        assertTrue(plan.contains("generation.methodsPerClass=4\n"));
        assertTrue(plan.contains("generation.layoutCount=8\n"));
        assertTrue(plan.contains("generation.drawableCount=16\n"));
        assertTrue(plan.contains("generation.stringCount=32\n"));
        assertTrue(plan.contains("generation.activityCount=0\n"));
        assertTrue(plan.contains("generation.compose.enabled=false\n"));
        assertTrue(plan.matches("(?s).*resources.prefix=kld_[0-9a-f]{8}_\\n.*"));
        var inventory = Files.readString(generatedInventory(projectDirectory, "release"));
        assertTrue(inventory.startsWith("schema=GeneratedInventory.v1\n"));
        assertTrue(inventory.contains("classes=16\n"));
        assertTrue(inventory.contains("methods=64\n"));
        assertTrue(inventory.contains("layouts=8\n"));
        assertTrue(inventory.contains("drawables=16\n"));
        assertTrue(inventory.contains("strings=32\n"));
        assertTrue(inventory.contains("components.activities=0\n"));
        assertFalse(inventory.contains("component=activity|"));
        assertFalse(inventory.contains(projectDirectory.toString()));
        var inventoryFiles = inventory.lines()
                .filter(line -> line.startsWith("file="))
                .toList();
        assertEquals(inventoryFiles.stream().sorted().toList(), inventoryFiles);
        assertEquals(16, countFiles(generatedRoot(projectDirectory, "release").resolve("java"),
                ".java"));
        assertEquals(8, countFiles(generatedRoot(projectDirectory, "release").resolve("res/layout"),
                ".xml"));
        assertEquals(16, countFiles(
                generatedRoot(projectDirectory, "release").resolve("res/drawable"), ".xml"));
        assertReleaseEvidenceSet(projectDirectory, "release", "example.consumer.MainActivity");
        var rawMapping = Files.readString(classRewriteRoot(projectDirectory, "release")
                .resolve("raw-kaleido-mapping.txt"));
        var mainActivityTarget = mappedTarget(rawMapping, "example.consumer.MainActivity");
        assertTrue(mainActivityTarget.matches("example\\.consumer\\.k[0-9a-f]{6}\\.C[0-9a-f]{10}"));
        assertEquals(17, rawMapping.lines().filter(line -> line.contains(" -> ")).count());
        assertTrue(Files.isRegularFile(classRewriteRoot(projectDirectory, "release")
                .resolve("class-rewrite-plan.pb")));
        assertTrue(Files.isRegularFile(classRewriteRoot(projectDirectory, "release")
                .resolve("transform-receipt.pb")));
        var r8Root = r8Root(projectDirectory, "release");
        var rules = Files.readString(r8Root.resolve("config/rules/kaleido-r8.keep"));
        assertTrue(rules.contains("-keep,allowoptimization class "
                + mainActivityTarget + " { *; }"));
        var classDictionary = Files.readString(
                r8Root.resolve("config/dictionaries/class.txt"));
        assertEquals(R8ConfigurationEngine.DICTIONARY_SIZE + 1,
                classDictionary.lines().count());
        assertArrayEquals(
                Files.readAllBytes(projectDirectory.resolve(
                        "app/build/outputs/mapping/release/mapping.txt")),
                Files.readAllBytes(r8Root.resolve("raw-r8-mapping.txt")));
        var composed = Files.readString(r8Root.resolve("composed-mapping.txt"));
        var capturedRawR8 = Files.readString(r8Root.resolve("raw-r8-mapping.txt"));
        var generatedKaleidoIdentity = rawMapping.lines()
                .filter(line -> line.startsWith("example.consumer.kaleido.generated."))
                .map(line -> line.substring(line.indexOf(" -> ") + 4))
                .findFirst().orElseThrow();
        var generatedFinalIdentity = mappedFinal(capturedRawR8, generatedKaleidoIdentity);
        assertTrue(classDictionary.lines().anyMatch(token -> token.equals(
                generatedFinalIdentity.substring(generatedFinalIdentity.lastIndexOf('.') + 1))));
        var finalMainActivity = mappedFinal(composed, "example.consumer.MainActivity");
        assertTrue(retrace(composed,
                "    at " + finalMainActivity + ".unknown(Unknown Source:1)")
                .contains("example.consumer.MainActivity"));
        var mappingMetadata = Files.readString(r8Root.resolve("mapping-metadata.properties"));
        assertTrue(mappingMetadata.contains("rawR8Compiler=R8\n"));
        assertTrue(mappingMetadata.matches("(?s).*rawR8CompilerVersion=[^\\n]+\\n.*"));
        assertTrue(mappingMetadata.contains("rawR8MappingVersion=2.2\n"));
        assertTrue(mappingMetadata.matches("(?s).*rawR8PgMapId=[0-9a-f]{64}\\n.*"));
        assertTrue(mappingMetadata.matches("(?s).*composedSha256=[0-9a-f]{64}\\n.*"));
        assertTrue(mappingMetadata.contains(
                "retraceToolCoordinates=com.android.tools.build:builder:"
                        + testAgpVersion() + "\n"));
        var bundleRewrite = bundleRewriteRoot(projectDirectory, "release");
        assertTrue(Files.isRegularFile(bundleRewrite.resolve("bundle-rewrite-plan.pb")));
        assertTrue(Files.isRegularFile(bundleRewrite.resolve("transform-receipt.pb")));
        var bundlePlan = BundleRewriteArtifacts.decodePlan(Files.readAllBytes(
                bundleRewrite.resolve("bundle-rewrite-plan.pb")), ":app", "release");
        assertTrue(bundlePlan.references().stream().anyMatch(reference ->
                !reference.originalValue().equals(reference.targetValue())));
        assertTrue(bundlePlan.resources().stream().allMatch(resource -> resource.id() != 0));
        var resourceMapping = Files.readString(bundleRewrite.resolve("resource-mapping.txt"));
        assertTrue(resourceMapping.startsWith("schema=KaleidoResourceMapping.v1\n"));
        assertTrue(resourceMapping.contains("style/AppTheme -> example.consumer:style/k"));
        var signedInput = projectDirectory.resolve(
                "app/build/intermediates/bundle/release/signReleaseBundle/app-release.aab");
        var finalBundle = unsignedBundle(projectDirectory, "release");
        assertArrayEquals(zipEntryBytes(signedInput, "base/dex/classes.dex"),
                zipEntryBytes(finalBundle, "base/dex/classes.dex"));
        var originalResources = resourceNames(signedInput);
        var finalResources = resourceNames(finalBundle);
        assertEquals(originalResources.keySet(), finalResources.keySet());
        assertNotEquals(originalResources, finalResources);
        assertTrue(originalResources.containsValue("style/AppTheme"));
        assertFalse(finalResources.containsValue("style/AppTheme"));
        try (var zip = new ZipFile(finalBundle.toFile())) {
            assertFalse(zip.stream().anyMatch(entry ->
                    entry.getName().startsWith("META-INF/")
                            && (entry.getName().endsWith(".SF")
                                || entry.getName().endsWith(".RSA")
                                || entry.getName().equals("META-INF/MANIFEST.MF"))));
        }
        try (var zip = new ZipFile(projectDirectory.resolve(
                "app/build/outputs/bundle/release/app-release.aab").toFile())) {
            var compiledManifest = Resources.XmlNode.parseFrom(
                    zip.getInputStream(zip.getEntry("base/manifest/AndroidManifest.xml")));
            assertEquals(mainActivityTarget, findManifestComponentName(compiledManifest, "activity"));
            var dex = zip.getInputStream(zip.getEntry("base/dex/classes.dex")).readAllBytes();
            assertTrue(contains(dex, ("L" + mainActivityTarget.replace('.', '/') + ";")
                    .getBytes(StandardCharsets.UTF_8)));
            assertFalse(contains(dex, "Lexample/consumer/MainActivity;"
                    .getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    public void generatedTreeIsByteStableAndSeedSensitive() throws IOException {
        var projectDirectory = temporaryFolder.newFolder("deterministic-generation").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                kaleido { seed.set(providers.environmentVariable("KALEIDO_GENERATION_SEED")) }
                """);
        var environment = new HashMap<>(testSigningEnvironment());
        environment.put("KALEIDO_GENERATION_SEED", "stable-seed-a");

        runner(projectDirectory).withEnvironment(environment)
                .withArguments("generateKaleidoReleaseSafeContent", "--rerun-tasks", "--stacktrace")
                .build();
        var first = snapshotTree(generatedRoot(projectDirectory, "release"));
        runner(projectDirectory).withEnvironment(environment)
                .withArguments("generateKaleidoReleaseSafeContent", "--rerun-tasks", "--stacktrace")
                .build();
        assertEquals(first, snapshotTree(generatedRoot(projectDirectory, "release")));

        environment.put("KALEIDO_GENERATION_SEED", "stable-seed-b");
        runner(projectDirectory).withEnvironment(environment)
                .withArguments("generateKaleidoReleaseSafeContent", "--rerun-tasks", "--stacktrace")
                .build();
        var changed = snapshotTree(generatedRoot(projectDirectory, "release"));
        assertEquals(first.size(), changed.size());
        assertFalse(first.equals(changed));
    }

    @Test
    public void r8InputsRawMappingAndCompositionAreByteStableAndSeedSensitive()
            throws IOException {
        var projectDirectory = temporaryFolder.newFolder("deterministic-r8").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                kaleido { seed.set(providers.environmentVariable("KALEIDO_R8_SEED")) }
                """);
        var environment = new HashMap<>(testSigningEnvironment());
        environment.put("KALEIDO_R8_SEED", "stable-r8-seed-a");

        runner(projectDirectory).withEnvironment(environment)
                .withArguments("bundleRelease", "--rerun-tasks", "--stacktrace").build();
        var first = snapshotTree(r8Root(projectDirectory, "release"));
        var firstBundleRewrite = snapshotTree(bundleRewriteRoot(projectDirectory, "release"));
        var firstBundle = sha256(Files.readAllBytes(
                unsignedBundle(projectDirectory, "release")));
        runner(projectDirectory).withEnvironment(environment)
                .withArguments("bundleRelease", "--rerun-tasks", "--stacktrace").build();
        assertEquals(first, snapshotTree(r8Root(projectDirectory, "release")));
        assertEquals(firstBundleRewrite,
                snapshotTree(bundleRewriteRoot(projectDirectory, "release")));
        assertEquals(firstBundle, sha256(Files.readAllBytes(
                unsignedBundle(projectDirectory, "release"))));

        environment.put("KALEIDO_R8_SEED", "stable-r8-seed-b");
        runner(projectDirectory).withEnvironment(environment)
                .withArguments("bundleRelease", "--rerun-tasks", "--stacktrace").build();
        var changed = snapshotTree(r8Root(projectDirectory, "release"));
        assertNotEquals(first.get("config/dictionaries/class.txt"),
                changed.get("config/dictionaries/class.txt"));
        assertNotEquals(first.get("composed-mapping.txt"), changed.get("composed-mapping.txt"));
        assertNotEquals(firstBundleRewrite.get("resource-mapping.txt"),
                snapshotTree(bundleRewriteRoot(projectDirectory, "release"))
                        .get("resource-mapping.txt"));
        assertNotEquals(firstBundle, sha256(Files.readAllBytes(
                unsignedBundle(projectDirectory, "release"))));
    }

    @Test
    public void independentNormalizedBuildsProduceIdenticalUnsignedBundlesAndMappings()
            throws IOException {
        var firstProject = temporaryFolder.newFolder("independent-build-a").toPath();
        var secondProject = temporaryFolder.newFolder("independent-build-b").toPath();
        for (var projectDirectory : List.of(firstProject, secondProject)) {
            writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");
            append(projectDirectory.resolve("app/build.gradle.kts"), """

                    kaleido { seed.set(providers.provider { "independent-canonical-seed" }) }
                    """);
            write(projectDirectory.resolve("app/src/main/res/values/content.xml"), """
                    <resources><string name="independent_label">same</string></resources>
                    """);
            runner(projectDirectory).withArguments("bundleRelease", "--stacktrace").build();
        }

        assertArrayEquals(Files.readAllBytes(unsignedBundle(firstProject, "release")),
                Files.readAllBytes(unsignedBundle(secondProject, "release")));
        assertArrayEquals(Files.readAllBytes(bundleRewriteRoot(firstProject, "release")
                        .resolve("resource-mapping.txt")),
                Files.readAllBytes(bundleRewriteRoot(secondProject, "release")
                        .resolve("resource-mapping.txt")));
    }

    @Test
    public void applyingBeforeAndroidApplicationFailsWithStableDiagnostic() throws IOException {
        var projectDirectory = temporaryFolder.newFolder("invalid-order").toPath();
        writeConsumer(projectDirectory, "com.tongsr.kaleido", "com.android.application");

        BuildResult result = runner(projectDirectory).withArguments("tasks", "--stacktrace").buildAndFail();

        assertTrue(result.getOutput().contains(
                "KLD-ADOPTION-001 project=:app variant=<none> stage=adoption"));
        assertTrue(result.getOutput().contains(
                "repair=Apply com.tongsr.kaleido after com.android.application"));
    }

    @Test
    public void flavoredReleaseVariantsAreIndependentlyFinalized() throws IOException {
        var projectDirectory = temporaryFolder.newFolder("flavored").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                android {
                    flavorDimensions += "tier"
                    productFlavors {
                        create("free") { dimension = "tier" }
                        create("paid") { dimension = "tier" }
                    }
                }
                """);

        BuildResult result = runner(projectDirectory)
                .withArguments("bundleFreeRelease", "bundlePaidRelease", "--stacktrace")
                .build();

        assertEquals(TaskOutcome.SUCCESS,
                result.task(":app:finalizeKaleidoFreeReleaseBundle").getOutcome());
        assertEquals(TaskOutcome.SUCCESS,
                result.task(":app:finalizeKaleidoPaidReleaseBundle").getOutcome());
        assertTrue(result.getOutput().contains("variant=freeRelease"));
        assertTrue(result.getOutput().contains("variant=paidRelease"));
        var freeInventory = Files.readString(generatedInventory(projectDirectory, "freeRelease"));
        var paidInventory = Files.readString(generatedInventory(projectDirectory, "paidRelease"));
        assertFalse(freeInventory.equals(paidInventory));
        assertTrue(freeInventory.contains("classes=16\n"));
        assertTrue(paidInventory.contains("classes=16\n"));
        var freeBefore = Files.readAllBytes(unsignedBundle(projectDirectory, "freeRelease"));
        var paidBefore = Files.readAllBytes(unsignedBundle(projectDirectory, "paidRelease"));
        write(projectDirectory.resolve("app/src/free/res/values/free-only.xml"),
                "<resources><string name=\"free_only\">free</string></resources>\n");
        runner(projectDirectory)
                .withArguments("bundleFreeRelease", "bundlePaidRelease", "--stacktrace")
                .build();
        assertNotEquals(sha256(freeBefore), sha256(Files.readAllBytes(
                unsignedBundle(projectDirectory, "freeRelease"))));
        assertArrayEquals(paidBefore,
                Files.readAllBytes(unsignedBundle(projectDirectory, "paidRelease")));
    }

    @Test
    public void ordinaryLibrariesDependenciesAndNativePayloadAreAccepted() throws IOException {
        var projectDirectory = temporaryFolder.newFolder("ordinary-dependencies").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");
        append(projectDirectory.resolve("settings.gradle.kts"), "\ninclude(\":library\")\n");
        write(projectDirectory.resolve("library/build.gradle.kts"), """
                plugins { id("com.android.library") version %s }
                android {
                    namespace = "example.library"
                    compileSdk = 36
                }
                """.formatted(quoted(testAgpVersion())));
        write(projectDirectory.resolve("library/src/main/AndroidManifest.xml"), "<manifest />\n");
        write(projectDirectory.resolve("library/src/main/java/example/library/LibraryType.java"), """
                package example.library;
                public final class LibraryType { private LibraryType() {} }
                """);
        write(projectDirectory.resolve("library/src/main/res/values/library.xml"), """
                <resources><string name="library_label">library</string></resources>
                """);
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                dependencies {
                    implementation(project(":library"))
                    implementation("androidx.annotation:annotation:1.9.1")
                }
                """);
        var nativePayload = projectDirectory.resolve(
                "app/src/main/jniLibs/arm64-v8a/libfixture.so");
        Files.createDirectories(nativePayload.getParent());
        Files.write(nativePayload, "fixture-native-payload".getBytes(StandardCharsets.UTF_8));

        var result = runner(projectDirectory)
                .withArguments("bundleRelease", "--stacktrace").build();

        var bundle = projectDirectory.resolve("app/build/outputs/bundle/release/app-release.aab");
        assertTrue(Files.isRegularFile(bundle));
        try (var zip = new ZipFile(bundle.toFile())) {
            assertTrue(zip.getEntry("base/lib/arm64-v8a/libfixture.so") != null);
        }
        assertEquals("string/library_label", resourceNames(bundle).values().stream()
                .filter(value -> value.equals("string/library_label"))
                .findFirst().orElseThrow());
        assertFalse(Files.readString(bundleRewriteRoot(projectDirectory, "release")
                .resolve("resource-mapping.txt")).contains("library_label ->"));
        var signedInput = projectDirectory.resolve(
                "app/build/intermediates/bundle/release/signReleaseBundle/app-release.aab");
        assertArrayEquals(zipEntryBytes(signedInput,
                        "base/lib/arm64-v8a/libfixture.so"),
                zipEntryBytes(bundle, "base/lib/arm64-v8a/libfixture.so"));
        var rawMapping = Files.readString(classRewriteRoot(projectDirectory, "release")
                .resolve("raw-kaleido-mapping.txt"));
        assertFalse(rawMapping.contains("example.library.LibraryType"));
    }

    @Test
    public void protectedManifestClassRemainsUnchanged() throws IOException {
        var projectDirectory = temporaryFolder.newFolder("protected-manifest-class").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                kaleido {
                    protection { originalClassNames.add("example.consumer.MainActivity") }
                }
                """);

        runner(projectDirectory).withArguments("bundleRelease", "--stacktrace").build();

        var rawMapping = Files.readString(classRewriteRoot(projectDirectory, "release")
                .resolve("raw-kaleido-mapping.txt"));
        assertFalse(rawMapping.contains("example.consumer.MainActivity ->"));
        try (var zip = new ZipFile(projectDirectory.resolve(
                "app/build/outputs/bundle/release/app-release.aab").toFile())) {
            var compiledManifest = Resources.XmlNode.parseFrom(
                    zip.getInputStream(zip.getEntry("base/manifest/AndroidManifest.xml")));
            assertEquals("example.consumer.MainActivity",
                    findManifestComponentName(compiledManifest, "activity"));
        }
    }

    @Test
    public void builtInKotlinComponentFamilyMetadataIsRewrittenAndBundles() throws IOException {
        var projectDirectory = temporaryFolder.newFolder("kotlin-component-family").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");
        Files.delete(projectDirectory.resolve(
                "app/src/main/java/example/consumer/MainActivity.java"));
        write(projectDirectory.resolve(
                "app/src/main/kotlin/example/consumer/MainActivity.kt"), """
                package example.consumer

                import android.app.Activity
                import android.os.Bundle

                class MainActivity : Activity() {
                    override fun onCreate(state: Bundle?) {
                        super.onCreate(state)
                        check(Payload("ready").value == Companion.status())
                    }

                    data class Payload(val value: String)

                    companion object {
                        fun status(): String = "ready"
                    }
                }
                """);

        runner(projectDirectory).withArguments("bundleRelease", "--stacktrace").build();

        var rawMapping = Files.readString(classRewriteRoot(projectDirectory, "release")
                .resolve("raw-kaleido-mapping.txt"));
        var target = mappedTarget(rawMapping, "example.consumer.MainActivity");
        assertTrue(rawMapping.contains("example.consumer.MainActivity$Companion -> "
                + target + "$C"));
        assertTrue(rawMapping.contains("example.consumer.MainActivity$Payload -> "
                + target + "$C"));
        var composed = Files.readString(r8Root(projectDirectory, "release")
                .resolve("composed-mapping.txt"));
        var finalIdentity = mappedFinal(composed, "example.consumer.MainActivity");
        assertTrue(retrace(composed,
                "    at " + finalIdentity + ".onCreate(MainActivity.kt:10)")
                .contains("example.consumer.MainActivity"));
        try (var zip = new ZipFile(projectDirectory.resolve(
                "app/build/outputs/bundle/release/app-release.aab").toFile())) {
            var manifest = Resources.XmlNode.parseFrom(
                    zip.getInputStream(zip.getEntry("base/manifest/AndroidManifest.xml")));
            assertEquals(target, findManifestComponentName(manifest, "activity"));
        }
    }

    @Test
    public void typedClassEscapeHatchProtectsExactIdentityAndRecordsEvidence() throws IOException {
        var projectDirectory = temporaryFolder.newFolder("typed-class-protection").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                kaleido {
                    protection {
                        classes("reflection-main") {
                            exact("example.consumer.MainActivity")
                            dimensions.addAll(
                                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.REACHABILITY,
                                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.ORIGINAL_IDENTITY,
                                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.DESCRIPTOR_CLOSURE,
                                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.RUNTIME_ATTRIBUTES
                            )
                            reason.set("Runtime framework constructs this Activity by exact name")
                        }
                    }
                }
                """);

        runner(projectDirectory).withArguments("bundleRelease", "--stacktrace").build();

        var rawMapping = Files.readString(classRewriteRoot(projectDirectory, "release")
                .resolve("raw-kaleido-mapping.txt"));
        assertFalse(rawMapping.contains("example.consumer.MainActivity ->"));
        var plan = ClassRewriteArtifacts.decodePlan(Files.readAllBytes(
                classRewriteRoot(projectDirectory, "release")
                        .resolve("class-rewrite-plan.pb")), ":app", "release");
        var decision = plan.decisions().stream()
                .filter(item -> item.original().equals("example.consumer.MainActivity"))
                .findFirst().orElseThrow();
        assertEquals("PROTECTED", decision.action());
        assertTrue(decision.reason().contains("escape-hatch:reflection-main"));
        var rules = Files.readString(classRewriteRoot(projectDirectory, "release")
                .resolve("rules/protection.keep"));
        assertTrue(rules.contains(
                "-keep,allowoptimization class example.consumer.MainActivity { *; }"));
        assertTrue(rules.contains("-keepattributes RuntimeVisibleAnnotations"));
    }

    @Test
    public void zeroMatchAndGlobalEscapeHatchesFailClosed() throws IOException {
        var zeroMatch = temporaryFolder.newFolder("zero-match-protection").toPath();
        writeConsumer(zeroMatch, "com.android.application", "com.tongsr.kaleido");
        append(zeroMatch.resolve("app/build.gradle.kts"), """

                kaleido {
                    protection {
                        classes("stale-rule") {
                            exact("example.consumer.DoesNotExist")
                            dimensions.add(
                                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.ORIGINAL_IDENTITY)
                            reason.set("Fixture must reject stale declarations")
                        }
                    }
                }
                """);
        var zeroFailure = runner(zeroMatch)
                .withArguments("bundleRelease", "--stacktrace").buildAndFail();
        assertTrue(zeroFailure.getOutput().contains("KLD-PROTECTION-001"));
        assertTrue(zeroFailure.getOutput().contains("resolves to zero PROJECT classes"));

        var global = temporaryFolder.newFolder("global-protection").toPath();
        writeConsumer(global, "com.android.application", "com.tongsr.kaleido");
        append(global.resolve("app/build.gradle.kts"), """

                kaleido {
                    protection {
                        classes("global-rule") {
                            prefix("*")
                            dimensions.add(
                                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.ORIGINAL_IDENTITY)
                            reason.set("Fixture must reject a global bypass")
                        }
                    }
                }
                """);
        var globalFailure = runner(global)
                .withArguments("bundleRelease", "--stacktrace").buildAndFail();
        assertTrue(globalFailure.getOutput().contains("KLD-PROTECTION-001"));
        assertTrue(globalFailure.getOutput().contains("global, raw, or invalid"));
    }

    @Test
    public void semanticLayoutClassReferenceAndDefinitionCloseTogether() throws IOException {
        var projectDirectory = temporaryFolder.newFolder("semantic-layout").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");
        write(projectDirectory.resolve(
                "app/src/main/java/example/consumer/CustomView.java"), """
                package example.consumer;

                public final class CustomView extends android.view.View {
                    public CustomView(android.content.Context context) { super(context); }
                }
                """);
        write(projectDirectory.resolve("app/src/main/res/layout/custom_view.xml"), """
                <example.consumer.CustomView
                    xmlns:android="http://schemas.android.com/apk/res/android"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"
                    android:tag="example.consumer.CustomView" />
                """);

        runner(projectDirectory).withArguments("bundleRelease", "--stacktrace").build();

        var mapping = Files.readString(classRewriteRoot(projectDirectory, "release")
                .resolve("raw-kaleido-mapping.txt"));
        var target = mappedTarget(mapping, "example.consumer.CustomView");
        var plan = ClassRewriteArtifacts.decodePlan(Files.readAllBytes(
                classRewriteRoot(projectDirectory, "release")
                        .resolve("class-rewrite-plan.pb")), ":app", "release");
        assertTrue(plan.manifestSites().stream().anyMatch(site ->
                site.location().contains("layout/custom_view.xml/element")
                        && site.target().equals(target)));
        try (var zip = new ZipFile(projectDirectory.resolve(
                "app/build/outputs/bundle/release/app-release.aab").toFile())) {
            var resourceMapping = Files.readString(bundleRewriteRoot(projectDirectory, "release")
                    .resolve("resource-mapping.txt"));
            var entry = zip.getEntry(mappedResourcePath(
                    resourceMapping, "base/res/layout/custom_view.xml"));
            assertTrue(entry != null);
            var xml = Resources.XmlNode.parseFrom(zip.getInputStream(entry));
            assertTrue(containsXmlElement(xml, target));
            assertFalse(containsXmlElement(xml, "example.consumer.CustomView"));
            assertTrue(containsXmlAttributeValue(xml, "tag", "example.consumer.CustomView"));
        }
    }

    @Test
    public void exactReflectionAndNativeDeclarationsCreateMinimalProtection() throws IOException {
        var projectDirectory = temporaryFolder.newFolder("inferred-protection").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");
        write(projectDirectory.resolve(
                "app/src/main/java/example/consumer/ReflectiveTarget.java"), """
                package example.consumer;

                public final class ReflectiveTarget {
                    public native Payload nativeValue(Payload input);
                }
                """);
        write(projectDirectory.resolve(
                "app/src/main/java/example/consumer/Payload.java"), """
                package example.consumer;

                public final class Payload {}
                """);
        write(projectDirectory.resolve(
                "app/src/main/java/example/consumer/MainActivity.java"), """
                package example.consumer;

                import android.app.Activity;

                public final class MainActivity extends Activity {
                    public static Class<?> reflectiveTarget() throws ClassNotFoundException {
                        return Class.forName("example.consumer.ReflectiveTarget");
                    }
                }
                """);

        runner(projectDirectory).withArguments("bundleRelease", "--stacktrace").build();

        var plan = ClassRewriteArtifacts.decodePlan(Files.readAllBytes(
                classRewriteRoot(projectDirectory, "release")
                        .resolve("class-rewrite-plan.pb")), ":app", "release");
        var target = plan.decisions().stream()
                .filter(item -> item.original().equals("example.consumer.ReflectiveTarget"))
                .findFirst().orElseThrow();
        var payload = plan.decisions().stream()
                .filter(item -> item.original().equals("example.consumer.Payload"))
                .findFirst().orElseThrow();
        assertEquals("PROTECTED", target.action());
        assertTrue(target.reason().contains("inferred-exact-reflection"));
        assertTrue(target.reason().contains("inferred-native-declaration"));
        assertEquals("PROTECTED", payload.action());
        assertTrue(payload.reason().contains("native-descriptor-closure"));
        var rules = Files.readString(classRewriteRoot(projectDirectory, "release")
                .resolve("rules/protection.keep"));
        assertTrue(rules.contains("example.consumer.ReflectiveTarget"));
        assertTrue(rules.contains("example.consumer.Payload"));
    }

    @Test
    public void manifestNativeActivityIsProtectedBeforeSemanticRewrite() throws Exception {
        var projectDirectory = temporaryFolder.newFolder("manifest-native-protection").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");
        write(projectDirectory.resolve(
                "app/src/main/java/example/consumer/MainActivity.java"), """
                package example.consumer;

                import android.app.Activity;

                public final class MainActivity extends Activity {
                    public static native int nativeAnswer();
                }
                """);

        runner(projectDirectory).withArguments("bundleRelease", "--stacktrace").build();

        var plan = ClassRewriteArtifacts.decodePlan(Files.readAllBytes(
                classRewriteRoot(projectDirectory, "release")
                        .resolve("class-rewrite-plan.pb")), ":app", "release");
        var activity = plan.decisions().stream()
                .filter(item -> item.original().equals("example.consumer.MainActivity"))
                .findFirst().orElseThrow();
        assertEquals("PROTECTED", activity.action());
        assertTrue(activity.reason().contains("inferred-native-declaration"));
        try (var zip = new ZipFile(projectDirectory.resolve(
                "app/build/outputs/bundle/release/app-release.aab").toFile())) {
            var manifest = Resources.XmlNode.parseFrom(
                    zip.getInputStream(zip.getEntry("base/manifest/AndroidManifest.xml")));
            assertEquals("example.consumer.MainActivity",
                    findManifestComponentName(manifest, "activity"));
        }
    }

    @Test
    public void resourceProtectionRejectsToolsDiscardConflict() throws IOException {
        var projectDirectory = temporaryFolder.newFolder("resource-protection-conflict").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");
        write(projectDirectory.resolve("app/src/main/res/values/protected.xml"), """
                <resources xmlns:tools="http://schemas.android.com/tools"
                    tools:discard="@string/stable_name">
                    <string name="stable_name">stable</string>
                </resources>
                """);
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                kaleido {
                    protection {
                        resources("stable-resource") {
                            exact("stable_name")
                            dimensions.add(
                                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.RESOURCE_NAME)
                            reason.set("Runtime lookup requires the stable entry name")
                        }
                    }
                }
                """);

        var failure = runner(projectDirectory)
                .withArguments("bundleRelease", "--stacktrace").buildAndFail();

        assertTrue(failure.getOutput().contains("KLD-PROTECTION-001"));
        assertTrue(failure.getOutput().contains(
                "tools:discard conflicts with protected resource stable_name"));
        assertNoPublishedOutputs(projectDirectory.resolve("app"));
    }

    @Test
    public void resourceNameAndPackagedPathProtectionRemainUnchanged() throws IOException {
        var projectDirectory = temporaryFolder.newFolder("resource-protection").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");
        write(projectDirectory.resolve("app/src/main/res/values/protected.xml"), """
                <resources><string name="stable_label">stable</string></resources>
                """);
        write(projectDirectory.resolve("app/src/main/res/layout/stable_screen.xml"), """
                <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent" />
                """);
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                kaleido {
                    protection {
                        resourceNames.add("stable_label")
                        packagedPaths.add("res/layout/stable_screen.xml")
                        resources("stable-screen") {
                            exact("stable_screen")
                            dimensions.addAll(
                                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.RESOURCE_NAME,
                                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.PACKAGED_PATH
                            )
                            reason.set("Runtime protocol requires this exact name and path")
                        }
                    }
                }
                """);

        runner(projectDirectory).withArguments("bundleRelease", "--stacktrace").build();

        var bundle = projectDirectory.resolve("app/build/outputs/bundle/release/app-release.aab");
        assertTrue(resourceNames(bundle).containsValue("string/stable_label"));
        assertTrue(resourceNames(bundle).containsValue("layout/stable_screen"));
        try (var zip = new ZipFile(bundle.toFile())) {
            assertTrue(zip.getEntry("base/res/layout/stable_screen.xml") != null);
        }
        var mapping = Files.readString(bundleRewriteRoot(projectDirectory, "release")
                .resolve("resource-mapping.txt"));
        assertFalse(mapping.contains("stable_label ->"));
        assertFalse(mapping.contains("stable_screen ->"));
        assertFalse(mapping.contains("path=base/res/layout/stable_screen.xml ->"));
    }

    @Test
    public void compatiblePayloadsDeduplicateWithoutMergingIdsOrQualifiers()
            throws IOException {
        var projectDirectory = temporaryFolder.newFolder("resource-deduplication").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");
        var png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUB"
                        + "AScY42YAAAAASUVORK5CYII=");
        Files.createDirectories(projectDirectory.resolve("app/src/main/res/drawable"));
        Files.write(projectDirectory.resolve("app/src/main/res/drawable/duplicate_a.png"), png);
        Files.write(projectDirectory.resolve("app/src/main/res/drawable/duplicate_b.png"), png);
        Files.write(projectDirectory.resolve("app/src/main/res/drawable/duplicate_c.png"), png);
        Files.createDirectories(projectDirectory.resolve("app/src/main/res/drawable-hdpi"));
        Files.createDirectories(projectDirectory.resolve("app/src/main/res/drawable-xhdpi"));
        Files.write(projectDirectory.resolve(
                "app/src/main/res/drawable-hdpi/qualified.png"), png);
        Files.write(projectDirectory.resolve(
                "app/src/main/res/drawable-xhdpi/qualified.png"), png);
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                kaleido {
                    protection {
                        resources("protected-duplicate") {
                            exact("duplicate_c")
                            dimensions.addAll(
                                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.RESOURCE_NAME,
                                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.PACKAGED_PATH
                            )
                            reason.set("Fixture proves protected duplicates stay independent")
                        }
                    }
                }
                """);

        var result = runner(projectDirectory)
                .withArguments("bundleRelease", "--stacktrace").build();

        var signedInput = projectDirectory.resolve(
                "app/build/intermediates/bundle/release/signReleaseBundle/app-release.aab");
        var output = projectDirectory.resolve("app/build/outputs/bundle/release/app-release.aab");
        var originals = resourceNames(signedInput);
        var idA = resourceId(originals, "drawable/duplicate_a");
        var idB = resourceId(originals, "drawable/duplicate_b");
        var idC = resourceId(originals, "drawable/duplicate_c");
        var qualifiedId = resourceId(originals, "drawable/qualified");
        var finalPaths = resourceFilePaths(output);
        assertNotEquals(idA, idB);
        assertEquals(finalPaths.get(idA), finalPaths.get(idB));
        assertEquals(1, finalPaths.get(idA).size());
        assertEquals(List.of("res/drawable/duplicate_c.png"), finalPaths.get(idC));
        assertEquals(2, finalPaths.get(qualifiedId).size());
        assertEquals(2, finalPaths.get(qualifiedId).stream().distinct().count());
        assertArrayEquals(zipEntryBytes(signedInput,
                        "base/res/drawable/duplicate_c.png"),
                zipEntryBytes(output, "base/res/drawable/duplicate_c.png"));
        var sharedPath = "base/" + finalPaths.get(idA).get(0);
        try (var zip = new ZipFile(output.toFile())) {
            assertTrue(zip.getEntry(sharedPath) != null);
            assertEquals(1, zip.stream().filter(entry -> entry.getName().equals(sharedPath)).count());
        }
        var plan = BundleRewriteArtifacts.decodePlan(Files.readAllBytes(
                bundleRewriteRoot(projectDirectory, "release")
                        .resolve("bundle-rewrite-plan.pb")), ":app", "release");
        assertEquals(2, plan.resources().stream()
                .filter(resource -> resource.id() == idA || resource.id() == idB)
                .filter(resource -> resource.action().endsWith("_DEDUP"))
                .count());
        assertTrue(result.getOutput().contains(
                "KLD-RESOURCE-002 project=:app variant=release stage=bundle-rewrite"));
    }

    @Test
    public void toolsKeepAndPublicResourcesBecomeNameProtectionRequirements()
            throws IOException {
        var projectDirectory = temporaryFolder.newFolder("automatic-resource-protection").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");
        write(projectDirectory.resolve("app/src/main/res/values/public.xml"), """
                <resources>
                    <string name="public_label">public</string>
                    <public type="string" name="public_label" />
                    <string name="kept_one">one</string>
                    <string name="kept_two">two</string>
                </resources>
                """);
        write(projectDirectory.resolve("app/src/main/res/raw/kaleido_keep.xml"), """
                <resources xmlns:tools="http://schemas.android.com/tools"
                    tools:keep="@string/kept_*" />
                """);

        var result = runner(projectDirectory)
                .withArguments("bundleRelease", "--stacktrace").build();

        var names = resourceNames(projectDirectory.resolve(
                "app/build/outputs/bundle/release/app-release.aab"));
        assertTrue(names.containsValue("string/public_label"));
        assertTrue(names.containsValue("string/kept_one"));
        assertTrue(names.containsValue("string/kept_two"));
        assertTrue(result.getOutput().contains(
                "KLD-RESOURCE-001 project=:app variant=release stage=bundle-rewrite"));
        var mapping = Files.readString(bundleRewriteRoot(projectDirectory, "release")
                .resolve("resource-mapping.txt"));
        assertFalse(mapping.contains("public_label ->"));
        assertFalse(mapping.contains("kept_one ->"));
        assertFalse(mapping.contains("kept_two ->"));
    }

    @Test
    public void exactGetIdentifierTargetBecomesNameProtectionRequirement()
            throws IOException {
        var projectDirectory = temporaryFolder.newFolder("get-identifier-protection").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");
        write(projectDirectory.resolve("app/src/main/res/values/runtime.xml"), """
                <resources><string name="runtime_label">runtime</string></resources>
                """);
        write(projectDirectory.resolve(
                "app/src/main/java/example/consumer/MainActivity.java"), """
                package example.consumer;

                import android.app.Activity;

                public final class MainActivity extends Activity {
                    public int runtimeLabel() {
                        return getResources().getIdentifier(
                                "runtime_label", "string", getPackageName());
                    }
                }
                """);

        runner(projectDirectory).withArguments("bundleRelease", "--stacktrace").build();

        var bundle = projectDirectory.resolve("app/build/outputs/bundle/release/app-release.aab");
        assertTrue(resourceNames(bundle).containsValue("string/runtime_label"));
        var evidence = Files.readString(projectDirectory.resolve(
                "app/build/intermediates/kaleido/release/class-rewrite/"
                        + "resource-protection.properties"));
        assertTrue(evidence.contains(
                "resource=string/runtime_label|exact-getIdentifier|"
                        + "example.consumer.MainActivity#runtimeLabel()I"));
        var mapping = Files.readString(bundleRewriteRoot(projectDirectory, "release")
                .resolve("resource-mapping.txt"));
        assertFalse(mapping.contains("runtime_label ->"));
    }

    @Test
    public void nonApplicationTargetFailsWithTopologyDiagnostic() throws IOException {
        var projectDirectory = temporaryFolder.newFolder("library-target").toPath();
        writeNonApplicationConsumer(projectDirectory);

        BuildResult result = runner(projectDirectory).withArguments("tasks", "--stacktrace").buildAndFail();

        assertTrue(result.getOutput().contains(
                "KLD-TOPOLOGY-001 project=:library variant=<none> stage=adoption"));
        assertNoPublishedOutputs(projectDirectory.resolve("library"));
    }

    @Test
    public void dynamicFeatureDeclarationFailsBeforeOutput() throws IOException {
        var projectDirectory = temporaryFolder.newFolder("dynamic-feature").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                android { dynamicFeatures += setOf(":feature") }
                """);

        BuildResult result = runner(projectDirectory)
                .withArguments("bundleRelease", "--stacktrace")
                .buildAndFail();

        assertTrue(result.getOutput().contains("KLD-TOPOLOGY-002"));
        assertNoPublishedOutputs(projectDirectory.resolve("app"));
    }

    @Test
    public void assetPackDeclarationFailsBeforeOutput() throws IOException {
        var projectDirectory = temporaryFolder.newFolder("asset-pack").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                android { assetPacks += setOf(":assets") }
                """);

        BuildResult result = runner(projectDirectory)
                .withArguments("bundleRelease", "--stacktrace")
                .buildAndFail();

        assertTrue(result.getOutput().contains("KLD-TOPOLOGY-003"));
        assertNoPublishedOutputs(projectDirectory.resolve("app"));
    }

    @Test
    public void nonMinifiedReleaseFailsBeforeOutput() throws IOException {
        var projectDirectory = temporaryFolder.newFolder("not-minified").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");
        var buildFile = projectDirectory.resolve("app/build.gradle.kts");
        Files.writeString(
                buildFile,
                Files.readString(buildFile).replace(
                        "isMinifyEnabled = true", "isMinifyEnabled = false"),
                StandardCharsets.UTF_8);

        BuildResult result = runner(projectDirectory)
                .withArguments("bundleRelease", "--stacktrace")
                .buildAndFail();

        assertTrue(result.getOutput().contains("KLD-TOPOLOGY-006"));
        assertNoPublishedOutputs(projectDirectory.resolve("app"));
    }

    @Test
    public void disabledReleaseVariantFailsAdoptionTask() throws IOException {
        var projectDirectory = temporaryFolder.newFolder("disabled-release").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                androidComponents {
                    beforeVariants(selector().withBuildType("release")) { builder ->
                        builder.enable = false
                    }
                }
                """);

        BuildResult result = runner(projectDirectory)
                .withArguments("bundle", "--stacktrace")
                .buildAndFail();

        assertTrue(result.getOutput().contains("KLD-TOPOLOGY-004"));
        assertNoPublishedOutputs(projectDirectory.resolve("app"));
    }

    @Test
    public void confirmedExternalDexLoadingFailsBeforeFinalBundleOutput() throws IOException {
        var projectDirectory = temporaryFolder.newFolder("external-code").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");
        write(projectDirectory.resolve("app/src/main/java/example/consumer/MainActivity.java"), """
                package example.consumer;

                import android.app.Activity;
                import android.os.Bundle;
                import dalvik.system.DexClassLoader;

                public final class MainActivity extends Activity {
                    @Override protected void onCreate(Bundle state) {
                        super.onCreate(state);
                        if (getIntent().getBooleanExtra("load_external", false)) {
                            try {
                                new DexClassLoader(
                                        getCodeCacheDir() + "/external.jar",
                                        getCodeCacheDir().getPath(),
                                        null,
                                        getClassLoader()).loadClass("example.External");
                            } catch (ClassNotFoundException ignored) {
                                throw new IllegalStateException("External code missing", ignored);
                            }
                        }
                    }
                }
                """);

        BuildResult result = runner(projectDirectory)
                .withArguments("bundleRelease", "--stacktrace")
                .buildAndFail();

        assertTrue(result.getOutput().contains("KLD-TOPOLOGY-007"));
        assertTrue(result.getOutput().contains("target=DexClassLoader"));
        assertNoPublishedOutputs(projectDirectory.resolve("app"));
    }

    @Test
    public void explicitSeedProviderIsLazyNormalizedAndNeverEmitted() throws IOException {
        var projectDirectory = temporaryFolder.newFolder("explicit-seed").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                kaleido {
                    seed.set(providers.environmentVariable("KALEIDO_TEST_SEED"))
                }
                """);

        runner(projectDirectory).withArguments("tasks", "--stacktrace").build();

        var rawSeed = "RAW-SEED-Cafe\u0301";
        var environment = new HashMap<>(testSigningEnvironment());
        environment.put("KALEIDO_TEST_SEED", rawSeed);
        BuildResult result = runner(projectDirectory)
                .withEnvironment(environment)
                .withArguments("bundleRelease", "--stacktrace")
                .build();

        var plan = Files.readString(adoptionPlan(projectDirectory, "release"));
        assertTrue(plan.contains("seed.fingerprint="
                + SeedDerivation.fingerprint(rawSeed) + "\n"));
        assertFalse(plan.contains(rawSeed));
        assertFalse(result.getOutput().contains(rawSeed));
        try (var paths = Files.walk(releaseEvidenceSet(projectDirectory, "release"))) {
            for (var path : paths.filter(Files::isRegularFile)
                    .filter(file -> file.toString().matches(
                            ".*\\.(txt|properties|java|kt|xml)$"))
                    .toList()) {
                var text = Files.readString(path);
                assertFalse(text.contains(rawSeed));
                assertFalse(text.contains(projectDirectory.toAbsolutePath().toString()));
                assertFalse(text.contains(System.getProperty("user.home")));
            }
        }
    }

    @Test
    public void missingExplicitSeedProviderFailsWithStableDiagnostic() throws IOException {
        var projectDirectory = temporaryFolder.newFolder("missing-seed").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                kaleido {
                    seed.set(providers.environmentVariable("ABSENT_KALEIDO_TEST_SEED"))
                }
                """);

        BuildResult result = runner(projectDirectory)
                .withArguments("bundleRelease", "--stacktrace")
                .buildAndFail();

        assertTrue(result.getOutput().contains("KLD-CONFIG-001"));
        assertTrue(result.getOutput().contains("target=seed"));
        assertFalse(Files.exists(adoptionPlan(projectDirectory, "release")));
        assertNoPublishedOutputs(projectDirectory.resolve("app"));
    }

    @Test
    public void fullProfileOnlyUnlocksExplicitControls() throws IOException {
        var projectDirectory = temporaryFolder.newFolder("full-profile").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");
        write(projectDirectory.resolve("app/unused-strings.txt"), "unused_label\n");
        write(projectDirectory.resolve("app/src/main/res/values/full.xml"), """
                <resources>
                    <string name="used_label">default</string>
                    <string name="unused_label">unused-default</string>
                </resources>
                """);
        write(projectDirectory.resolve("app/src/main/res/values-en/full.xml"), """
                <resources>
                    <string name="used_label">english</string>
                    <string name="unused_label">unused-english</string>
                </resources>
                """);
        write(projectDirectory.resolve("app/src/main/res/values-fr/full.xml"), """
                <resources>
                    <string name="used_label">francais</string>
                    <string name="unused_label">unused-francais</string>
                </resources>
                """);
        write(projectDirectory.resolve(
                "app/src/main/jniLibs/x86_64/libobsolete.so"), "fixture native\n");
        write(projectDirectory.resolve(
                "app/src/main/jniLibs/x86_64/libkeep.so"), "fixture retained native\n");
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                kaleido {
                    profile.set(com.tongsr.kaleido.gradle.dsl.KaleidoProfile.FULL)
                    generation { activityCount.set(1) }
                    resources {
                        nativeLibrariesToDelete.add("libobsolete.so")
                        confirmedUnusedStringsFile.set(
                            layout.projectDirectory.file("unused-strings.txt"))
                        retainedLanguages.add("en")
                    }
                }
                """);

        runner(projectDirectory).withArguments("bundleRelease", "--stacktrace").build();

        var outputBundle = unsignedBundle(projectDirectory, "release");
        var firstBundle = Files.readAllBytes(outputBundle);
        var firstControlPlan = Files.readAllBytes(bundleRewriteRoot(projectDirectory, "release")
                .resolve("bundle-rewrite-plan.pb"));
        runner(projectDirectory).withArguments(
                "bundleRelease", "--rerun-tasks", "--stacktrace").build();
        assertArrayEquals(firstBundle, Files.readAllBytes(outputBundle));
        assertArrayEquals(firstControlPlan, Files.readAllBytes(
                bundleRewriteRoot(projectDirectory, "release")
                        .resolve("bundle-rewrite-plan.pb")));

        var plan = Files.readString(adoptionPlan(projectDirectory, "release"));
        assertTrue(plan.contains("profile=FULL\n"));
        assertTrue(plan.contains("generation.activityCount=1\n"));
        assertTrue(plan.contains("resources.nativeLibrariesToDelete=libobsolete.so\n"));
        assertTrue(plan.contains("resources.replaceUnusedStrings=true\n"));
        assertTrue(plan.contains("resources.retainedLanguages=en\n"));
        var bundlePlan = BundleRewriteArtifacts.decodePlan(Files.readAllBytes(
                bundleRewriteRoot(projectDirectory, "release")
                        .resolve("bundle-rewrite-plan.pb")), ":app", "release");
        assertTrue(bundlePlan.controls().stream().anyMatch(control ->
                control.kind().equals("DELETE_NATIVE")));
        assertTrue(bundlePlan.controls().stream().anyMatch(control ->
                control.kind().equals("REPLACE_UNUSED_STRING")));
        assertTrue(bundlePlan.controls().stream().anyMatch(control ->
                control.kind().equals("FILTER_LANGUAGE")));
        try (var zip = new ZipFile(outputBundle.toFile())) {
            assertTrue(zip.stream().noneMatch(entry ->
                    entry.getName().endsWith("/libobsolete.so")));
            assertTrue(zip.stream().anyMatch(entry ->
                    entry.getName().endsWith("/libkeep.so")));
        }
    }

    @Test
    public void fullProfileActivitiesAreInertMappedAndAbsentWhenUnconfigured()
            throws IOException {
        var configured = temporaryFolder.newFolder("full-components").toPath();
        writeConsumer(configured, "com.android.application", "com.tongsr.kaleido");
        append(configured.resolve("app/build.gradle.kts"), """

                kaleido {
                    profile.set(com.tongsr.kaleido.gradle.dsl.KaleidoProfile.FULL)
                    generation { activityCount.set(2) }
                }
                """);

        runner(configured).withArguments("bundleRelease", "--stacktrace").build();

        var inventory = Files.readString(generatedInventory(configured, "release"));
        assertTrue(inventory.contains("components.schema=FullComponentGeneration.v1\n"));
        assertTrue(inventory.contains("components.activities=2\n"));
        var originals = inventory.lines()
                .filter(line -> line.startsWith("component=activity|"))
                .map(line -> line.split("\\|", -1)[1])
                .sorted().toList();
        assertEquals(2, originals.size());
        var rawMapping = Files.readString(classRewriteRoot(configured, "release")
                .resolve("raw-kaleido-mapping.txt"));
        var composedMapping = Files.readString(r8Root(configured, "release")
                .resolve("composed-mapping.txt"));
        var finalManifest = Resources.XmlNode.parseFrom(zipEntryBytes(
                unsignedBundle(configured, "release"),
                "base/manifest/AndroidManifest.xml"));
        for (var original : originals) {
            var generatedSource = generatedRoot(configured, "release").resolve("java")
                    .resolve(original.replace('.', '/') + ".java");
            var source = Files.readString(generatedSource);
            assertTrue(source.contains("extends android.app.Activity"));
            assertFalse(source.contains("Intent"));
            assertFalse(source.contains("Log"));
            var rewritten = mappedTarget(rawMapping, original);
            assertEquals(rewritten, mappedFinal(composedMapping, original));
            var element = findManifestElement(finalManifest, "activity", rewritten);
            assertTrue(element != null);
            assertEquals("false", manifestAttribute(element, "exported"));
            assertFalse(element.getChildList().stream()
                    .anyMatch(child -> containsXmlElement(child, "intent-filter")));
        }
        assertFalse(containsXmlElement(finalManifest, "uses-permission"));
        assertFalse(containsXmlElement(finalManifest, "service"));
        assertFalse(containsXmlElement(finalManifest, "receiver"));
        assertFalse(containsXmlElement(finalManifest, "provider"));
        assertReleaseEvidenceSet(configured, "release", originals.get(0));

        var unconfigured = temporaryFolder.newFolder("full-components-unconfigured").toPath();
        writeConsumer(unconfigured, "com.android.application", "com.tongsr.kaleido");
        append(unconfigured.resolve("app/build.gradle.kts"), """

                kaleido {
                    profile.set(com.tongsr.kaleido.gradle.dsl.KaleidoProfile.FULL)
                }
                """);
        runner(unconfigured).withArguments("bundleRelease", "--stacktrace").build();
        var absentInventory = Files.readString(generatedInventory(unconfigured, "release"));
        assertTrue(absentInventory.contains("components.activities=0\n"));
        assertFalse(absentInventory.contains("component=activity|"));
        var absentManifest = Resources.XmlNode.parseFrom(zipEntryBytes(
                unsignedBundle(unconfigured, "release"),
                "base/manifest/AndroidManifest.xml"));
        assertEquals(1, manifestElementNames(absentManifest, "activity").size());
    }

    @Test
    public void fullProfileActivityCollisionFailsBeforeGeneratedMutation()
            throws IOException {
        var projectDirectory = temporaryFolder.newFolder("full-component-collision").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");
        var rawSeed = "full-component-collision-seed";
        var expected = FullComponentGenerationEngine.plan(
                functionalFullComponentPlan(rawSeed, 1).values(), Set.of(), Set.of());
        var identity = expected.activities().get(0);
        var packageName = identity.substring(0, identity.lastIndexOf('.'));
        var className = identity.substring(identity.lastIndexOf('.') + 1);
        write(projectDirectory.resolve("app/src/main/java")
                .resolve(identity.replace('.', '/') + ".java"), """
                package %s;

                public final class %s extends android.app.Activity {}
                """.formatted(packageName, className));
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                kaleido {
                    seed.set(providers.provider { "%s" })
                    profile.set(com.tongsr.kaleido.gradle.dsl.KaleidoProfile.FULL)
                    generation { activityCount.set(1) }
                }
                """.formatted(rawSeed));

        var failure = runner(projectDirectory)
                .withArguments("bundleRelease", "--stacktrace").buildAndFail();

        assertTrue(failure.getOutput().contains("KLD-COMPONENT-001"));
        assertTrue(failure.getOutput().contains(identity));
        assertFalse(Files.exists(generatedInventory(projectDirectory, "release")));
        assertNoPublishedOutputs(projectDirectory.resolve("app"));
    }

    @Test
    public void fullResourceControlFailsBeforeMutationAtProtectionBoundary()
            throws IOException {
        var projectDirectory = temporaryFolder.newFolder("full-protected-control").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");
        write(projectDirectory.resolve("app/unused-strings.txt"), "protected_label\n");
        write(projectDirectory.resolve("app/src/main/res/values/protected.xml"), """
                <resources><string name="protected_label">stable</string></resources>
                """);
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                kaleido {
                    profile.set(com.tongsr.kaleido.gradle.dsl.KaleidoProfile.FULL)
                    resources {
                        confirmedUnusedStringsFile.set(
                            layout.projectDirectory.file("unused-strings.txt"))
                    }
                    protection { resourceNames.add("protected_label") }
                }
                """);

        var failure = runner(projectDirectory)
                .withArguments("bundleRelease", "--stacktrace").buildAndFail();

        assertTrue(failure.getOutput().contains("KLD-BUNDLE-001"));
        assertTrue(failure.getOutput().contains(
                "Unused-string replacement intersects a Protection Requirement"));
        assertNoPublishedOutputs(projectDirectory.resolve("app"));
    }

    @Test
    public void invalidDslFailsBeforeKaleidoOutput() throws IOException {
        var projectDirectory = temporaryFolder.newFolder("invalid-dsl").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                kaleido { generation { packageCount.set(0) } }
                """);

        BuildResult result = runner(projectDirectory)
                .withArguments("bundleRelease", "--stacktrace")
                .buildAndFail();

        assertTrue(result.getOutput().contains("KLD-CONFIG-001"));
        assertTrue(result.getOutput().contains("target=generation.packageCount"));
        assertFalse(Files.exists(adoptionPlan(projectDirectory, "release")));
        assertNoPublishedOutputs(projectDirectory.resolve("app"));
    }

    @Test
    public void safeProfileCannotSelectFullOnlyControls() throws IOException {
        var projectDirectory = temporaryFolder.newFolder("safe-full-control").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                kaleido { resources { replaceUnusedStrings.set(true) } }
                """);

        BuildResult result = runner(projectDirectory)
                .withArguments("bundleRelease", "--stacktrace")
                .buildAndFail();

        assertTrue(result.getOutput().contains("KLD-CONFIG-001"));
        assertTrue(result.getOutput().contains("target=profile"));
        assertNoPublishedOutputs(projectDirectory.resolve("app"));
    }

    @Test
    public void completeTopLevelSigningSourceSignsAndVerifiesExactCandidate()
            throws Exception {
        var projectDirectory = temporaryFolder.newFolder("complete-signing").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");
        var keyStore = projectDirectory.resolve("app/upload.p12");
        var password = "kaleido-signing-sentinel";
        createTestKeyStore(keyStore, password, "upload");
        var certificate = testCertificateSha256(keyStore, password, "upload");
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                kaleido {
                    signing {
                        keyStoreFile.set(layout.projectDirectory.file("upload.p12"))
                        storePassword.set(providers.provider { "%s" })
                        keyAlias.set("upload")
                        keyPassword.set(providers.provider { "%s" })
                        expectedCertificateSha256.set("%s")
                    }
                }
                """.formatted(password, password, certificate));

        var result = runner(projectDirectory)
                .withArguments("bundleRelease", "--stacktrace").build();

        assertFalse(result.getOutput().contains(password));
        assertFalse(result.getOutput().contains(keyStore.toString()));
        var receipt = Files.readString(projectDirectory.resolve(
                "app/build/intermediates/kaleido/release/signing/"
                        + "signing-receipt.properties"));
        assertTrue(receipt.contains("source=TOP_LEVEL_DSL\n"));
        assertTrue(receipt.contains("certificateSha256=" + certificate + "\n"));
        assertTrue(receipt.contains("signatureCoverageValidated=true\n"));
        assertTrue(receipt.contains("bundletoolValidated=true\n"));
        var publicSignedBundle = projectDirectory.resolve(
                "app/build/outputs/bundle/release/app-release.aab");
        try (var jar = new JarFile(publicSignedBundle.toFile(), true)) {
            assertTrue(jar.stream().anyMatch(entry ->
                    entry.getName().startsWith("META-INF/KALEIDO.")));
        }
        var stagedSignedBundle = projectDirectory.resolve(
                "app/build/intermediates/kaleido/release/signing/"
                        + "staged-signed-candidate.aab");
        try (var jar = new JarFile(stagedSignedBundle.toFile(), true)) {
            assertTrue(jar.stream().anyMatch(entry ->
                    entry.getName().startsWith("META-INF/KALEIDO.")));
            for (var entry : jar.stream().filter(item ->
                    !item.isDirectory() && !item.getName().startsWith("META-INF/")).toList()) {
                try (var input = jar.getInputStream(entry)) { input.readAllBytes(); }
                assertTrue(entry.getCertificates() != null
                        && entry.getCertificates().length > 0);
            }
        }
        assertArrayEquals(Files.readAllBytes(stagedSignedBundle),
                Files.readAllBytes(publicSignedBundle));
        assertReleaseEvidenceSet(projectDirectory, "release", "example.consumer.MainActivity");
        var corrupted = projectDirectory.resolve("app/build/corrupted-signed.aab");
        Files.copy(stagedSignedBundle, corrupted);
        try (var fileSystem = java.nio.file.FileSystems.newFileSystem(
                corrupted, Map.of())) {
            Files.writeString(fileSystem.getPath("/unsigned-entry.txt"), "tampered");
        }
        var corruption = assertThrows(org.gradle.api.GradleException.class, () ->
                KaleidoSignAndVerifyBundleTask.verifySignedBundle(
                        corrupted, certificate, Map.of(), ":app", "release"));
        assertTrue(corruption.getMessage().contains(
                "entry without signature coverage"));
    }

    @Test
    public void composeGeneratorCompilesRuntimeOnlyGraphAndRetainsMappedInventory()
            throws Exception {
        var projectDirectory = temporaryFolder.newFolder("compose-generation").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");
        enableComposeConsumer(projectDirectory, true, true, true);
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                kaleido {
                    generation {
                        compose {
                            enabled.set(true)
                            fileCount.set(2)
                            functionsPerFile.set(3)
                        }
                    }
                }
                """);

        runner(projectDirectory).withArguments("bundleRelease", "--stacktrace").build();

        var generated = generatedRoot(projectDirectory, "release").resolve("kotlin");
        var sources = Files.walk(generated).filter(Files::isRegularFile).sorted().toList();
        assertEquals(2, sources.size());
        for (var source : sources) {
            var text = Files.readString(source);
            assertTrue(text.contains("import androidx.compose.runtime.Composable"));
            assertFalse(text.matches("(?s).*(androidx\\.compose\\.(ui|foundation|material)|"
                    + "Preview|android\\.|kotlinx\\.|java\\.(io|net)).*"));
        }
        var generatedInventory = Files.readString(generatedInventory(projectDirectory, "release"));
        assertTrue(generatedInventory.contains("compose.enabled=true\n"));
        assertTrue(generatedInventory.contains("compose.facades=2\n"));
        assertTrue(generatedInventory.contains("compose.functions=6\n"));
        assertTrue(generatedInventory.matches(
                "(?s).*compose.runtimeArtifact=androidx\\.compose\\.runtime:"
                        + "runtime(-android)?:[^\\n]+\\n.*"));

        var compiledInventory = Files.readString(classRewriteRoot(projectDirectory, "release")
                .resolve("compose-compiled-inventory.properties"));
        assertTrue(compiledInventory.contains("enabled=true\n"));
        assertTrue(compiledInventory.contains("facades=2\n"));
        assertTrue(compiledInventory.contains("functions=6\n"));
        assertEquals(6, compiledInventory.lines().filter(line ->
                line.startsWith("method=")
                        && line.contains("Landroidx/compose/runtime/Composer;")).count());
        var composeRules = Files.readString(classRewriteRoot(projectDirectory, "release")
                .resolve("rules/compose.keep"));
        assertEquals(2, composeRules.lines().filter(line ->
                line.startsWith("-keep,allowoptimization,allowobfuscation class ")).count());
        assertFalse(composeRules.contains("allowshrinking"));

        var composedMapping = Files.readString(r8Root(projectDirectory, "release")
                .resolve("composed-mapping.txt"));
        for (var facadeLine : compiledInventory.lines()
                .filter(line -> line.startsWith("facade=")).toList()) {
            var original = facadeLine.substring("facade=".length(), facadeLine.indexOf('|'));
            assertTrue(composedMapping.contains(original + " -> "));
        }
        var finalManifest = zipEntryBytes(unsignedBundle(projectDirectory, "release"),
                "base/manifest/AndroidManifest.xml");
        assertFalse(new String(finalManifest, StandardCharsets.ISO_8859_1)
                .contains("KldCompose_"));
        var finalDexReceipt = Files.readString(projectDirectory.resolve(
                "app/build/intermediates/kaleido/release/compose/"
                        + "final-dex-receipt.properties"));
        assertTrue(finalDexReceipt.contains("facades=2\n"));
        assertTrue(finalDexReceipt.contains("functions=6\n"));
        assertTrue(finalDexReceipt.contains("incomingBytecodeEdges=0\n"));
        assertTrue(finalDexReceipt.contains("finalDexRetained=true\n"));
        assertReleaseEvidenceSet(projectDirectory, "release", "example.consumer.MainActivity");
    }

    @Test
    public void composeGeneratorRejectsMissingPrerequisitesAndExcessiveScale()
            throws Exception {
        var missingFeature = temporaryFolder.newFolder("compose-missing-feature").toPath();
        writeConsumer(missingFeature, "com.android.application", "com.tongsr.kaleido");
        enableComposeConsumer(missingFeature, true, false, true);
        appendComposeEnabled(missingFeature, 1, 1);
        var featureFailure = runner(missingFeature)
                .withArguments("bundleRelease", "--stacktrace").buildAndFail();
        assertTrue(featureFailure.getOutput().contains(
                "Compose Generator requires buildFeatures.compose to be true"));

        var missingCompiler = temporaryFolder.newFolder("compose-missing-compiler").toPath();
        writeConsumer(missingCompiler, "com.android.application", "com.tongsr.kaleido");
        enableComposeConsumer(missingCompiler, false, true, true);
        appendComposeEnabled(missingCompiler, 1, 1);
        var compilerFailure = runner(missingCompiler)
                .withArguments("bundleRelease", "--stacktrace").buildAndFail();
        assertTrue(compilerFailure.getOutput().contains(
                "Compose Generator requires org.jetbrains.kotlin.plugin.compose"));

        var missingRuntime = temporaryFolder.newFolder("compose-missing-runtime").toPath();
        writeConsumer(missingRuntime, "com.android.application", "com.tongsr.kaleido");
        enableComposeConsumer(missingRuntime, true, true, false);
        appendComposeEnabled(missingRuntime, 1, 1);
        var runtimeFailure = runner(missingRuntime)
                .withArguments("bundleRelease", "--stacktrace").buildAndFail();
        assertTrue(runtimeFailure.getOutput().contains(
                "Compose Runtime is not resolvable on the Release compile classpath"));

        var excessive = temporaryFolder.newFolder("compose-excessive").toPath();
        writeConsumer(excessive, "com.android.application", "com.tongsr.kaleido");
        enableComposeConsumer(excessive, true, true, true);
        appendComposeEnabled(excessive, 64, 9);
        var scaleFailure = runner(excessive)
                .withArguments("bundleRelease", "--stacktrace").buildAndFail();
        assertTrue(scaleFailure.getOutput().contains("Compose function total exceeds 512"));
    }

    @Test
    public void fullComposeGeneratorSucceedsAndConsumerIncomingEdgeFailsClosed()
            throws Exception {
        var full = temporaryFolder.newFolder("full-compose-generation").toPath();
        writeConsumer(full, "com.android.application", "com.tongsr.kaleido");
        enableComposeConsumer(full, true, true, true);
        append(full.resolve("app/build.gradle.kts"), """

                kaleido {
                    profile.set(com.tongsr.kaleido.gradle.dsl.KaleidoProfile.FULL)
                    generation { compose { enabled.set(true); fileCount.set(1); functionsPerFile.set(1) } }
                }
                """);
        runner(full).withArguments("bundleRelease", "--stacktrace").build();
        var fullReceipt = Files.readString(full.resolve(
                "app/build/intermediates/kaleido/release/compose/"
                        + "final-dex-receipt.properties"));
        assertTrue(fullReceipt.contains("facades=1\n"));
        assertTrue(fullReceipt.contains("functions=1\n"));

        var incoming = temporaryFolder.newFolder("compose-incoming-edge").toPath();
        writeConsumer(incoming, "com.android.application", "com.tongsr.kaleido");
        enableComposeConsumer(incoming, true, true, true);
        var rawSeed = "incoming-compose-seed";
        append(incoming.resolve("app/build.gradle.kts"), """

                kaleido {
                    seed.set(providers.provider { "%s" })
                    generation { compose { enabled.set(true); fileCount.set(1); functionsPerFile.set(1) } }
                }
                """.formatted(rawSeed));
        var composePlan = new HashMap<String, String>();
        composePlan.put("generation.packageBase", "example.consumer.kaleido.generated");
        composePlan.put("generation.compose.enabled", "true");
        composePlan.put("generation.compose.fileCount", "1");
        composePlan.put("generation.compose.functionsPerFile", "1");
        composePlan.put("seed.domain.generation-compose", SeedDerivation.derive(
                SeedDerivation.fingerprint(rawSeed), "generation-compose", "release|release|"));
        var generated = ComposeGenerationEngine.plan(composePlan);
        var facade = generated.facades().get(0);
        var function = generated.functions().get(0).name();
        write(incoming.resolve("app/src/main/java/example/consumer/ComposeCaller.java"), """
                package example.consumer;

                import androidx.compose.runtime.Composer;

                public final class ComposeCaller {
                    public static int call(Composer composer) {
                        return %s.%s(1, composer, 0);
                    }
                }
                """.formatted(facade, function));
        var incomingFailure = runner(incoming)
                .withArguments("bundleRelease", "--stacktrace").buildAndFail();
        assertTrue(incomingFailure.getOutput().contains(
                "Consumer bytecode has an incoming edge to generated Compose code"));
    }

    @Test
    public void partialExactVariantSigningFailsWithoutFallingThroughOrLeakingSecrets()
            throws IOException {
        var projectDirectory = temporaryFolder.newFolder("partial-signing").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");
        var sentinel = "partial-signing-sentinel";
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                kaleido {
                    signing("release") { keyAlias.set("partial") }
                    signing {
                        keyStoreFile.set(layout.projectDirectory.file("missing.p12"))
                        storePassword.set(providers.provider { "%s" })
                        keyAlias.set("fallback")
                        keyPassword.set(providers.provider { "%s" })
                        expectedCertificateSha256.set("%s")
                    }
                }
                """.formatted(sentinel, sentinel, "0".repeat(64)));

        var failure = runner(projectDirectory)
                .withArguments("bundleRelease", "--stacktrace").buildAndFail();

        assertTrue(failure.getOutput().contains("KLD-SIGNING-001"));
        assertTrue(failure.getOutput().contains(
                "Higher-precedence signing source is partial"));
        assertFalse(failure.getOutput().contains(sentinel));
        assertFalse(Files.exists(projectDirectory.resolve(
                "app/build/intermediates/kaleido/release/signing/"
                        + "signing-receipt.properties")));
    }

    @Test
    public void failedReplacementPreservesPriorPublishedBundleAndEvidence()
            throws IOException {
        var projectDirectory = temporaryFolder.newFolder("atomic-publication-preserve").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");
        runner(projectDirectory).withArguments("bundleRelease", "--stacktrace").build();
        var publicBundle = projectDirectory.resolve(
                "app/build/outputs/bundle/release/app-release.aab");
        var priorBundle = Files.readAllBytes(publicBundle);
        var priorEvidence = snapshotTree(releaseEvidenceSet(projectDirectory, "release"));

        var wrongCertificate = new HashMap<>(testSigningEnvironment());
        wrongCertificate.put("KALEIDO_UPLOAD_CERTIFICATE_SHA256", "0".repeat(64));
        var failure = runner(projectDirectory).withEnvironment(wrongCertificate)
                .withArguments("bundleRelease", "--stacktrace").buildAndFail();

        assertTrue(failure.getOutput().contains(
                "Selected signing certificate differs from the expected digest"));
        assertArrayEquals(priorBundle, Files.readAllBytes(publicBundle));
        assertEquals(priorEvidence,
                snapshotTree(releaseEvidenceSet(projectDirectory, "release")));
        assertFalse(Files.exists(releaseEvidenceSet(projectDirectory, "release")
                .resolveSibling("release-evidence-set.publication-staging")));
    }

    @Test
    public void environmentSigningRejectsMissingWrongCertificateAndWrongAlias()
            throws IOException {
        var projectDirectory = temporaryFolder.newFolder("invalid-environment-signing").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");

        var missing = new HashMap<>(testSigningEnvironment());
        missing.keySet().removeIf(key -> key.startsWith("KALEIDO_UPLOAD_"));
        var missingFailure = runner(projectDirectory).withEnvironment(missing)
                .withArguments("bundleRelease", "--stacktrace").buildAndFail();
        assertTrue(missingFailure.getOutput().contains(
                "No complete upload-signing source is configured"));

        var wrongCertificate = new HashMap<>(testSigningEnvironment());
        wrongCertificate.put("KALEIDO_UPLOAD_CERTIFICATE_SHA256", "0".repeat(64));
        var certificateFailure = runner(projectDirectory).withEnvironment(wrongCertificate)
                .withArguments("bundleRelease", "--stacktrace").buildAndFail();
        assertTrue(certificateFailure.getOutput().contains(
                "Selected signing certificate differs from the expected digest"));

        var wrongAlias = new HashMap<>(testSigningEnvironment());
        wrongAlias.put("KALEIDO_UPLOAD_KEY_ALIAS", "missing-alias");
        var aliasFailure = runner(projectDirectory).withEnvironment(wrongAlias)
                .withArguments("bundleRelease", "--stacktrace").buildAndFail();
        assertTrue(aliasFailure.getOutput().contains(
                "Selected signing source cannot resolve one private-key entry"));
        assertFalse(aliasFailure.getOutput().contains(
                testSigningEnvironment().get("KALEIDO_UPLOAD_STORE_PASSWORD")));

        var wrongPassword = new HashMap<>(testSigningEnvironment());
        wrongPassword.put("KALEIDO_UPLOAD_STORE_PASSWORD", "wrong-password-sentinel");
        var passwordFailure = runner(projectDirectory).withEnvironment(wrongPassword)
                .withArguments("bundleRelease", "--stacktrace").buildAndFail();
        assertTrue(passwordFailure.getOutput().contains(
                "Selected signing source cannot resolve one private-key entry"));
        assertFalse(passwordFailure.getOutput().contains("wrong-password-sentinel"));

        var candidate = projectDirectory.resolve("mutated-candidate.aab");
        var digest = projectDirectory.resolve("mutated-candidate.sha256");
        Files.writeString(candidate, "candidate");
        Files.writeString(digest, "0".repeat(64));
        var mutation = assertThrows(org.gradle.api.GradleException.class, () ->
                KaleidoSignAndVerifyBundleTask.verifyUnsignedCandidate(
                        candidate, digest, ":app", "release"));
        assertTrue(mutation.getMessage().contains(
                "Unsigned candidate digest differs from canonicalization evidence"));
    }

    @Test
    public void configurationCacheIsReusedWithoutCapturingGradleModelObjects() throws IOException {
        var projectDirectory = temporaryFolder.newFolder("configuration-cache").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                kaleido {
                    protection { originalClassNames.add("example.consumer.MainActivity") }
                    signing {
                        storePassword.set(providers.environmentVariable("UNUSED_SIGNING_SECRET"))
                    }
                }
                """);

        runner(projectDirectory)
                .withArguments("bundleRelease", "--configuration-cache",
                        "--configuration-cache-problems=fail", "--stacktrace")
                .build();
        BuildResult second = runner(projectDirectory)
                .withArguments("bundleRelease", "--configuration-cache",
                        "--configuration-cache-problems=fail", "--stacktrace")
                .build();

        assertTrue(second.getOutput().contains("Reusing configuration cache"));
        assertTrue(Files.isRegularFile(adoptionPlan(projectDirectory, "release")));
        assertDeterministicTaskOutcomes(second, Set.of(TaskOutcome.UP_TO_DATE));
        assertAlwaysValidatedTaskOutcomes(second);
    }

    @Test
    public void noCleanBuildKeepsDeterministicStagesUpToDateAndRevalidatesSensitiveStages()
            throws IOException {
        var projectDirectory = temporaryFolder.newFolder("no-clean-up-to-date").toPath();
        writeConsumer(projectDirectory, "com.android.application", "com.tongsr.kaleido");

        runner(projectDirectory)
                .withArguments("bundleRelease", "--no-build-cache", "--stacktrace").build();
        var second = runner(projectDirectory)
                .withArguments("bundleRelease", "--no-build-cache", "--stacktrace").build();

        assertDeterministicTaskOutcomes(second, Set.of(TaskOutcome.UP_TO_DATE));
        assertAlwaysValidatedTaskOutcomes(second);
    }

    @Test
    public void relocatedConsumerRestoresDeterministicStagesFromConsumerBuildCache()
            throws IOException {
        var cache = temporaryFolder.newFolder("consumer-build-cache").toPath();
        var first = temporaryFolder.newFolder("cache-source").toPath();
        var relocated = temporaryFolder.newFolder("cache-relocated").toPath();
        writeConsumer(first, "com.android.application", "com.tongsr.kaleido");
        writeConsumer(relocated, "com.android.application", "com.tongsr.kaleido");
        configureLocalBuildCache(first, cache);
        configureLocalBuildCache(relocated, cache);

        runner(first).withArguments("bundleRelease", "--build-cache", "--stacktrace").build();
        var sameWorkspaceRestore = runner(first).withArguments(
                "clean", "bundleRelease", "--build-cache", "--stacktrace").build();
        assertEquals(TaskOutcome.FROM_CACHE,
                sameWorkspaceRestore.task(":app:generateKaleidoReleaseSafeContent").getOutcome());
        assertEquals(TaskOutcome.FROM_CACHE,
                sameWorkspaceRestore.task(":app:finalizeKaleidoReleaseBundle").getOutcome());
        assertAlwaysValidatedTaskOutcomes(sameWorkspaceRestore);
        var restored = runner(relocated)
                .withArguments("bundleRelease", "--build-cache", "--stacktrace").build();

        assertDeterministicTaskOutcomes(restored,
                Set.of(TaskOutcome.FROM_CACHE, TaskOutcome.UP_TO_DATE));
        assertEquals(TaskOutcome.FROM_CACHE,
                restored.task(":app:generateKaleidoReleaseSafeContent").getOutcome());
        assertEquals(TaskOutcome.FROM_CACHE,
                restored.task(":app:finalizeKaleidoReleaseBundle").getOutcome());
        assertAlwaysValidatedTaskOutcomes(restored);
    }

    @Test
    public void threeRelocatedWorkspacesHaveByteIdenticalDeterministicBoundaries()
            throws IOException {
        var workspaces = List.of(
                temporaryFolder.newFolder("repro-a").toPath(),
                temporaryFolder.newFolder("repro-b").toPath(),
                temporaryFolder.newFolder("repro-c").toPath());
        var languages = List.of("tr", "de", "ja");
        var countries = List.of("TR", "DE", "JP");
        var zones = List.of("Pacific/Kiritimati", "Europe/Berlin", "Asia/Tokyo");
        var workers = List.of("1", "2", "4");
        var snapshots = new java.util.ArrayList<Map<String, String>>();
        for (var index = 0; index < workspaces.size(); index++) {
            var workspace = workspaces.get(index);
            writeConsumer(workspace, "com.android.application", "com.tongsr.kaleido");
            var firstName = index % 2 == 0 ? "ExtraA" : "ExtraB";
            var secondName = index % 2 == 0 ? "ExtraB" : "ExtraA";
            writeExtraClass(workspace, firstName);
            writeExtraClass(workspace, secondName);
            runner(workspace).withArguments(
                    "bundleRelease", "--no-build-cache", "--max-workers=" + workers.get(index),
                    "-Duser.language=" + languages.get(index),
                    "-Duser.country=" + countries.get(index),
                    "-Duser.timezone=" + zones.get(index), "--stacktrace").build();
            snapshots.add(deterministicBoundarySnapshot(workspace, "release"));
        }

        assertEquals(snapshots.get(0), snapshots.get(1));
        assertEquals(snapshots.get(0), snapshots.get(2));
    }

    private static void createTestKeyStore(Path path, String password, String alias)
            throws IOException, InterruptedException {
        Files.createDirectories(path.getParent());
        var keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        var process = new ProcessBuilder(
                keytool.toString(), "-genkeypair", "-noprompt",
                "-alias", alias, "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "3650", "-dname", "CN=Kaleido Test",
                "-storetype", "PKCS12", "-keystore", path.toString(),
                "-storepass", password, "-keypass", password)
                .redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IOException("keytool failed: " + output);
    }

    private static String testCertificateSha256(
            Path path, String password, String alias) throws Exception {
        var keyStore = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(path)) {
            keyStore.load(input, password.toCharArray());
        }
        return sha256(keyStore.getCertificate(alias).getEncoded());
    }

    private GradleRunner runner(Path projectDirectory) {
        return GradleRunner.create()
                .withProjectDir(projectDirectory.toFile())
                .withGradleVersion(testGradleVersion())
                .withEnvironment(testSigningEnvironment())
                .forwardOutput();
    }

    private static void assertDeterministicTaskOutcomes(
            BuildResult result, Set<TaskOutcome> accepted) {
        for (var task : List.of(
                ":app:resolveKaleidoReleaseAdoptionPlan",
                ":app:generateKaleidoReleaseSafeContent",
                ":app:rewriteKaleidoReleaseSemanticXml",
                ":app:rewriteKaleidoReleaseManifestReferences",
                ":app:rewriteKaleidoReleaseClassesAndManifest",
                ":app:generateKaleidoReleaseR8Configuration",
                ":app:composeKaleidoReleaseR8Mappings",
                ":app:finalizeKaleidoReleaseBundle")) {
            assertTrue(task + " outcome was " + result.task(task).getOutcome(),
                    accepted.contains(result.task(task).getOutcome()));
        }
    }

    private static void assertAlwaysValidatedTaskOutcomes(BuildResult result) {
        for (var task : List.of(
                ":app:verifyKaleidoReleaseComposeFinalDex",
                ":app:signAndVerifyKaleidoReleaseBundle",
                ":app:publishKaleidoReleaseReleaseEvidence")) {
            assertEquals(task, TaskOutcome.SUCCESS, result.task(task).getOutcome());
        }
    }

    private static void configureLocalBuildCache(Path projectDirectory, Path cache)
            throws IOException {
        append(projectDirectory.resolve("settings.gradle.kts"), """

                buildCache {
                    local { directory = file(%s) }
                }
                """.formatted(quoted(cache.toString())));
    }

    private static void writeExtraClass(Path projectDirectory, String name) throws IOException {
        write(projectDirectory.resolve(
                "app/src/main/java/example/consumer/" + name + ".java"), """
                package example.consumer;

                final class %s {
                    static int value() { return %d; }
                }
                """.formatted(name, name.equals("ExtraA") ? 1 : 2));
    }

    private static Map<String, String> deterministicBoundarySnapshot(
            Path projectDirectory, String variant) throws IOException {
        var root = releaseEvidenceSet(projectDirectory, variant);
        var values = new TreeMap<String, String>();
        values.putAll(snapshotTree(root.resolve("deterministic")));
        values.put("@deterministic-manifest", sha256(Files.readAllBytes(
                root.resolve("deterministic-evidence-manifest.properties"))));
        return Map.copyOf(values);
    }

    private static synchronized Map<String, String> testSigningEnvironment() {
        if (sharedSigningEnvironment != null) return sharedSigningEnvironment;
        try {
            var environment = new HashMap<>(System.getenv());
            var keyStore = Path.of("build/test-signing/upload.p12").toAbsolutePath();
            var password = "shared-kaleido-test-signing";
            if (!Files.isRegularFile(keyStore)) createTestKeyStore(keyStore, password, "upload");
            environment.put("KALEIDO_UPLOAD_KEYSTORE", keyStore.toString());
            environment.put("KALEIDO_UPLOAD_STORE_PASSWORD", password);
            environment.put("KALEIDO_UPLOAD_KEY_ALIAS", "upload");
            environment.put("KALEIDO_UPLOAD_KEY_PASSWORD", password);
            environment.put("KALEIDO_UPLOAD_CERTIFICATE_SHA256",
                    testCertificateSha256(keyStore, password, "upload"));
            sharedSigningEnvironment = Map.copyOf(environment);
            return sharedSigningEnvironment;
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to create shared test signing identity", failure);
        }
    }

    private void writeNonApplicationConsumer(Path projectDirectory) throws IOException {
        var repository = Path.of(System.getProperty("kaleido.test.repository"));
        var sdk = System.getProperty("kaleido.test.sdk");
        write(projectDirectory.resolve("settings.gradle.kts"), """
                pluginManagement {
                    repositories {
                        maven { url = uri(%s) }
                        google()
                        mavenCentral()
                        gradlePluginPortal()
                    }
                }
                dependencyResolutionManagement {
                    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                    repositories { google(); mavenCentral() }
                }
                rootProject.name = "library-consumer"
                include(":library")
                """.formatted(quoted(repository.toString())));
        write(projectDirectory.resolve("local.properties"), "sdk.dir=" + sdk + "\n");
        write(projectDirectory.resolve("gradle.properties"),
                "org.gradle.jvmargs=-Xmx1g -XX:MaxMetaspaceSize=1g\n");
        write(projectDirectory.resolve("library/build.gradle.kts"), """
                plugins {
                    id("com.android.library") version %s
                    id("com.tongsr.kaleido") version "0.1.0-dev"
                }
                android {
                    namespace = "example.library"
                    compileSdk = 36
                }
                """.formatted(quoted(testAgpVersion())));
        write(projectDirectory.resolve("library/src/main/AndroidManifest.xml"), "<manifest />\n");
    }

    private void writeConsumer(Path projectDirectory, String firstPlugin, String secondPlugin)
            throws IOException {
        var repository = Path.of(System.getProperty("kaleido.test.repository"));
        var sdk = System.getProperty("kaleido.test.sdk");
        write(projectDirectory.resolve("settings.gradle.kts"), """
                pluginManagement {
                    repositories {
                        maven { url = uri(%s) }
                        google()
                        mavenCentral()
                        gradlePluginPortal()
                    }
                }
                dependencyResolutionManagement {
                    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                    repositories { google(); mavenCentral() }
                }
                rootProject.name = "consumer"
                include(":app")
                """.formatted(quoted(repository.toString())));
        write(projectDirectory.resolve("local.properties"), "sdk.dir=" + sdk + "\n");
        write(projectDirectory.resolve("gradle.properties"),
                "org.gradle.jvmargs=-Xmx1g -XX:MaxMetaspaceSize=1g\n");
        write(projectDirectory.resolve("app/build.gradle.kts"), """
                plugins {
                    id(%s) version %s
                    id(%s) version %s
                }

                android {
                    namespace = "example.consumer"
                    compileSdk = 36
                    defaultConfig {
                        applicationId = "example.consumer"
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
                """.formatted(
                        quoted(firstPlugin), pluginVersion(firstPlugin),
                        quoted(secondPlugin), pluginVersion(secondPlugin)));
        write(projectDirectory.resolve("app/src/main/AndroidManifest.xml"), """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application android:theme="@style/AppTheme">
                        <activity android:name=".MainActivity" android:exported="true">
                            <intent-filter>
                                <action android:name="android.intent.action.MAIN" />
                                <category android:name="android.intent.category.LAUNCHER" />
                            </intent-filter>
                        </activity>
                    </application>
                </manifest>
                """);
        write(projectDirectory.resolve("app/src/main/java/example/consumer/MainActivity.java"), """
                package example.consumer;

                import android.app.Activity;

                public final class MainActivity extends Activity {}
                """);
        write(projectDirectory.resolve("app/src/main/res/values/styles.xml"), """
                <resources>
                    <style name="AppTheme" parent="android:style/Theme.Material.Light.NoActionBar" />
                </resources>
                """);
    }

    private static void enableComposeConsumer(
            Path projectDirectory, boolean compilerPlugin, boolean buildFeature,
            boolean runtimeDependency)
            throws IOException {
        var buildFile = projectDirectory.resolve("app/build.gradle.kts");
        var script = Files.readString(buildFile);
        if (compilerPlugin) {
            script = script.replace(
                    "id(\"com.tongsr.kaleido\") version \"0.1.0-dev\"",
                    "id(\"org.jetbrains.kotlin.plugin.compose\") version \"2.2.10\"\n"
                            + "    id(\"com.tongsr.kaleido\") version \"0.1.0-dev\"");
        }
        if (buildFeature) {
            script = script.replace("android {", "android {\n    buildFeatures { compose = true }");
        }
        if (runtimeDependency) {
            script += "\ndependencies { implementation(\"androidx.compose.runtime:runtime:1.10.0\") }\n";
        }
        Files.writeString(buildFile, script);
    }

    private static void appendComposeEnabled(
            Path projectDirectory, int files, int functions) throws IOException {
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                kaleido {
                    generation {
                        compose {
                            enabled.set(true)
                            fileCount.set(%d)
                            functionsPerFile.set(%d)
                        }
                    }
                }
                """.formatted(files, functions));
    }

    private static String pluginVersion(String pluginId) {
        return quoted(pluginId.equals("com.tongsr.kaleido")
                ? "0.1.0-dev" : testAgpVersion());
    }

    private static String testAgpVersion() {
        return System.getProperty("kaleido.test.agp", "9.2.1");
    }

    private static String testGradleVersion() {
        return System.getProperty("kaleido.test.gradle", "9.4.1");
    }

    private static AdoptionPlan functionalFullComponentPlan(String rawSeed, int activityCount) {
        return AdoptionPlanFactory.create(new AdoptionPlanFactory.Input(
                ":app", "release", "release", List.of(), "example.consumer",
                com.tongsr.kaleido.gradle.dsl.KaleidoProfile.FULL,
                "example.consumer.kaleido.generated", 4, 4, 4, 8, 16, 32,
                activityCount, false, false, false, 4, 4, Set.of(), Set.of(), false,
                Set.of(), Set.of(), Set.of(), Set.of(), SeedDerivation.fingerprint(rawSeed)));
    }

    private static String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Path adoptionPlan(Path projectDirectory, String variant) {
        return projectDirectory.resolve(
                "app/build/intermediates/kaleido/" + variant + "/adoption-plan.properties");
    }

    private static Path generatedRoot(Path projectDirectory, String variant) {
        return projectDirectory.resolve("app/build/generated/kaleido/" + variant);
    }

    private static Path generatedInventory(Path projectDirectory, String variant) {
        return projectDirectory.resolve(
                "app/build/intermediates/kaleido/" + variant
                        + "/generated-inventory.properties");
    }

    private static Path classRewriteRoot(Path projectDirectory, String variant) {
        return projectDirectory.resolve(
                "app/build/intermediates/kaleido/" + variant + "/class-rewrite");
    }

    private static Path r8Root(Path projectDirectory, String variant) {
        return projectDirectory.resolve(
                "app/build/intermediates/kaleido/" + variant + "/r8");
    }

    private static Path bundleRewriteRoot(Path projectDirectory, String variant) {
        return projectDirectory.resolve(
                "app/build/intermediates/kaleido/" + variant + "/bundle-rewrite");
    }

    private static Path unsignedBundle(Path projectDirectory, String variant) {
        return bundleRewriteRoot(projectDirectory, variant).resolve("unsigned-candidate.aab");
    }

    private static Path releaseEvidenceSet(Path projectDirectory, String variant) {
        return projectDirectory.resolve(
                "app/build/reports/kaleido/" + variant + "/release-evidence-set");
    }

    private static void assertReleaseEvidenceSet(
            Path projectDirectory, String variant, String retraceIdentity) throws IOException {
        var root = releaseEvidenceSet(projectDirectory, variant);
        var manifest = simpleProperties(Files.readString(
                root.resolve("release-evidence-set-manifest.properties")));
        assertEquals("ReleaseEvidenceSetManifest.v1", manifest.get("schema"));
        assertEquals(":app", manifest.get("project"));
        assertEquals(variant, manifest.get("variant"));
        assertEquals("PUBLISHED", manifest.get("publicationResult"));
        var publicBundle = projectDirectory.resolve(
                "app/build/outputs/bundle/" + variant + "/app-" + variant + ".aab");
        assertEquals(manifest.get("signedAabSha256"), sha256(Files.readAllBytes(publicBundle)));
        try (var jar = new JarFile(publicBundle.toFile(), true)) {
            assertTrue(jar.stream().anyMatch(entry ->
                    entry.getName().startsWith("META-INF/KALEIDO.")));
        }

        var deterministicManifest = Files.readAllLines(
                root.resolve("deterministic-evidence-manifest.properties"));
        assertEquals("schema=DeterministicEvidenceManifest.v1",
                deterministicManifest.get(0));
        for (var line : deterministicManifest.subList(1, deterministicManifest.size())) {
            if (line.isBlank()) continue;
            var separator = line.lastIndexOf('|');
            var logical = line.substring("file=".length(), separator);
            var expected = line.substring(separator + 1);
            assertEquals(expected, sha256(Files.readAllBytes(
                    root.resolve("deterministic").resolve(logical))));
        }
        assertEquals(manifest.get("deterministicEvidenceSha256"), sha256(Files.readAllBytes(
                root.resolve("deterministic-evidence-manifest.properties"))));
        assertEquals(manifest.get("rawKaleidoMappingSha256"), sha256(Files.readAllBytes(
                root.resolve("mappings/raw-kaleido-mapping.txt"))));
        assertEquals(manifest.get("rawR8MappingSha256"), sha256(Files.readAllBytes(
                root.resolve("mappings/raw-r8-mapping.txt"))));
        assertEquals(manifest.get("composedMappingSha256"), sha256(Files.readAllBytes(
                root.resolve("mappings/composed-mapping.txt"))));
        assertEquals(manifest.get("resourceMappingSha256"), sha256(Files.readAllBytes(
                root.resolve("mappings/resource-mapping.txt"))));
        var identity = "schema=" + manifest.get("schema") + "\n"
                + "project=" + manifest.get("project") + "\n"
                + "variant=" + manifest.get("variant") + "\n"
                + "applicationId=" + manifest.get("applicationId") + "\n"
                + "unsignedAabSha256=" + manifest.get("unsignedAabSha256") + "\n"
                + "signedAabSha256=" + manifest.get("signedAabSha256") + "\n"
                + "rawKaleidoMappingSha256=" + manifest.get("rawKaleidoMappingSha256") + "\n"
                + "rawR8MappingSha256=" + manifest.get("rawR8MappingSha256") + "\n"
                + "composedMappingSha256=" + manifest.get("composedMappingSha256") + "\n"
                + "resourceMappingSha256=" + manifest.get("resourceMappingSha256") + "\n"
                + "deterministicEvidenceSha256="
                + manifest.get("deterministicEvidenceSha256") + "\n"
                + "certificateSha256=" + manifest.get("certificateSha256") + "\n";
        assertEquals(manifest.get("releaseEvidenceSetId"),
                sha256(identity.getBytes(StandardCharsets.UTF_8)));
        var report = Files.readString(root.resolve("artifact-report.txt"));
        assertTrue(report.startsWith(
                "schemaUri=https://schemas.tongsr.com/kaleido/artifact-report/1.0\n"));
        assertEquals(10, report.lines().filter(line ->
                line.matches("stage\\.[0-9]{2}=.+\\|PASS")).count());
        assertTrue(report.contains("proofLimitations="));
        assertFalse(report.contains(projectDirectory.toAbsolutePath().toString()));
        assertFalse(report.contains(System.getProperty("user.home")));
        var composed = Files.readString(root.resolve("mappings/composed-mapping.txt"));
        var finalIdentity = mappedFinal(composed, retraceIdentity);
        assertTrue(retrace(composed, "    at " + finalIdentity + ".<init>(Unknown Source)")
                .contains(retraceIdentity));
    }

    private static Map<String, String> simpleProperties(String text) {
        var values = new TreeMap<String, String>();
        text.lines().filter(line -> !line.isBlank()).forEach(line -> {
            var separator = line.indexOf('=');
            values.put(line.substring(0, separator), line.substring(separator + 1));
        });
        return Map.copyOf(values);
    }

    private static String mappedTarget(String mapping, String original) {
        return mapping.lines()
                .filter(line -> line.startsWith(original + " -> "))
                .map(line -> line.substring((original + " -> ").length()))
                .findFirst()
                .orElseThrow();
    }

    private static String mappedFinal(String mapping, String original) {
        return mapping.lines()
                .filter(line -> line.startsWith(original + " -> ") && line.endsWith(":"))
                .map(line -> line.substring((original + " -> ").length(), line.length() - 1))
                .findFirst()
                .orElseThrow();
    }

    private static String retrace(String mapping, String stackLine) {
        var output = new java.util.ArrayList<String>();
        var supplier = ProguardMappingSupplier.builder()
                .setProguardMapProducer(ProguardMapProducer.fromString(mapping))
                .setLoadAllDefinitions(true)
                .build();
        Retrace.run(RetraceCommand.builder()
                .setMappingSupplier(supplier)
                .setStackTrace(List.of(stackLine))
                .setRetracedStackTraceConsumer(output::addAll)
                .build());
        return String.join("\n", output);
    }

    private static String mappedResourcePath(String mapping, String original) {
        return mapping.lines()
                .filter(line -> line.startsWith("path=" + original + " -> "))
                .map(line -> line.substring(("path=" + original + " -> ").length()))
                .findFirst().orElseThrow();
    }

    private static byte[] zipEntryBytes(Path archive, String entryName) throws IOException {
        try (var zip = new ZipFile(archive.toFile())) {
            var entry = zip.getEntry(entryName);
            if (entry == null) throw new IOException("Missing ZIP entry " + entryName);
            return zip.getInputStream(entry).readAllBytes();
        }
    }

    private static Map<Integer, String> resourceNames(Path bundle) throws IOException {
        var values = new TreeMap<Integer, String>(Integer::compareUnsigned);
        try (var zip = new ZipFile(bundle.toFile())) {
            var table = Resources.ResourceTable.parseFrom(
                    zip.getInputStream(zip.getEntry("base/resources.pb")));
            for (var pkg : table.getPackageList()) {
                for (var type : pkg.getTypeList()) {
                    for (var entry : type.getEntryList()) {
                        var id = (pkg.getPackageId().getId() << 24)
                                | (type.getTypeId().getId() << 16)
                                | entry.getEntryId().getId();
                        values.put(id, type.getName() + "/" + entry.getName());
                    }
                }
            }
        }
        return Map.copyOf(values);
    }

    private static int resourceId(Map<Integer, String> resources, String identity) {
        return resources.entrySet().stream().filter(entry -> entry.getValue().equals(identity))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
    }

    private static Map<Integer, List<String>> resourceFilePaths(Path bundle) throws IOException {
        var values = new TreeMap<Integer, List<String>>(Integer::compareUnsigned);
        try (var zip = new ZipFile(bundle.toFile())) {
            var table = Resources.ResourceTable.parseFrom(
                    zip.getInputStream(zip.getEntry("base/resources.pb")));
            for (var pkg : table.getPackageList()) {
                for (var type : pkg.getTypeList()) {
                    for (var entry : type.getEntryList()) {
                        var id = (pkg.getPackageId().getId() << 24)
                                | (type.getTypeId().getId() << 16)
                                | entry.getEntryId().getId();
                        var paths = new java.util.TreeSet<String>();
                        for (var config : entry.getConfigValueList()) {
                            if (config.hasValue() && config.getValue().hasItem()
                                    && config.getValue().getItem().hasFile()) {
                                paths.add(config.getValue().getItem().getFile().getPath());
                            }
                        }
                        if (!paths.isEmpty()) values.put(id, List.copyOf(paths));
                    }
                }
            }
        }
        return Map.copyOf(values);
    }

    private static String findManifestComponentName(Resources.XmlNode node, String elementName) {
        if (!node.hasElement()) {
            return null;
        }
        var element = node.getElement();
        if (elementName.equals(element.getName())) {
            return element.getAttributeList().stream()
                    .filter(attribute -> "http://schemas.android.com/apk/res/android"
                            .equals(attribute.getNamespaceUri()))
                    .filter(attribute -> "name".equals(attribute.getName()))
                    .map(Resources.XmlAttribute::getValue)
                    .findFirst()
                    .orElse(null);
        }
        for (var child : element.getChildList()) {
            var found = findManifestComponentName(child, elementName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static Resources.XmlElement findManifestElement(
            Resources.XmlNode node, String elementName, String androidName) {
        if (!node.hasElement()) return null;
        var element = node.getElement();
        if (elementName.equals(element.getName())
                && androidName.equals(manifestAttribute(element, "name"))) {
            return element;
        }
        for (var child : element.getChildList()) {
            var found = findManifestElement(child, elementName, androidName);
            if (found != null) return found;
        }
        return null;
    }

    private static String manifestAttribute(Resources.XmlElement element, String name) {
        return element.getAttributeList().stream()
                .filter(attribute -> "http://schemas.android.com/apk/res/android"
                        .equals(attribute.getNamespaceUri()))
                .filter(attribute -> name.equals(attribute.getName()))
                .map(Resources.XmlAttribute::getValue)
                .findFirst().orElse(null);
    }

    private static List<String> manifestElementNames(
            Resources.XmlNode node, String elementName) {
        var values = new java.util.ArrayList<String>();
        collectManifestElementNames(node, elementName, values);
        return List.copyOf(values);
    }

    private static void collectManifestElementNames(
            Resources.XmlNode node, String elementName, List<String> values) {
        if (!node.hasElement()) return;
        var element = node.getElement();
        if (elementName.equals(element.getName())) {
            values.add(manifestAttribute(element, "name"));
        }
        element.getChildList().forEach(child ->
                collectManifestElementNames(child, elementName, values));
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer: for (var index = 0; index <= haystack.length - needle.length; index++) {
            for (var offset = 0; offset < needle.length; offset++) {
                if (haystack[index + offset] != needle[offset]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static boolean containsXmlElement(Resources.XmlNode node, String name) {
        if (!node.hasElement()) return false;
        if (name.equals(node.getElement().getName())) return true;
        return node.getElement().getChildList().stream()
                .anyMatch(child -> containsXmlElement(child, name));
    }

    private static boolean containsXmlAttributeValue(
            Resources.XmlNode node, String attributeName, String value) {
        if (!node.hasElement()) return false;
        if (node.getElement().getAttributeList().stream().anyMatch(attribute ->
                attributeName.equals(attribute.getName()) && value.equals(attribute.getValue()))) {
            return true;
        }
        return node.getElement().getChildList().stream()
                .anyMatch(child -> containsXmlAttributeValue(child, attributeName, value));
    }

    private static long countFiles(Path root, String suffix) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .count();
        }
    }

    private static Map<String, String> snapshotTree(Path root) throws IOException {
        var snapshot = new TreeMap<String, String>();
        try (var paths = Files.walk(root)) {
            for (var path : paths.filter(Files::isRegularFile).sorted().toList()) {
                snapshot.put(root.relativize(path).toString()
                        .replace(java.io.File.separatorChar, '/'), sha256(Files.readAllBytes(path)));
            }
        }
        return Map.copyOf(snapshot);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void assertNoPublishedOutputs(Path moduleDirectory) throws IOException {
        var bundleDirectory = moduleDirectory.resolve("build/outputs/bundle");
        if (Files.exists(bundleDirectory)) {
            try (var paths = Files.walk(bundleDirectory)) {
                assertFalse(paths.anyMatch(path ->
                        Files.isRegularFile(path) && path.getFileName().toString().endsWith(".aab")));
            }
        }
        assertFalse(Files.exists(moduleDirectory.resolve("build/reports/kaleido")));
    }

    private static void append(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
