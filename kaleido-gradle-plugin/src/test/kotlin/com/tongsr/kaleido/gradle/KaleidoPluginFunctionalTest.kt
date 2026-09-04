package com.tongsr.kaleido.gradle

import com.android.aapt.Resources
import com.android.tools.r8.retrace.ProguardMapProducer
import com.android.tools.r8.retrace.ProguardMappingSupplier
import com.android.tools.r8.retrace.Retrace
import com.android.tools.r8.retrace.RetraceCommand
import com.tongsr.kaleido.gradle.dsl.KaleidoProfile
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.KeyStore
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Base64
import java.util.HashMap
import java.util.HexFormat
import java.util.TreeMap
import java.util.jar.JarFile
import java.util.zip.ZipFile
import org.gradle.api.GradleException
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KaleidoPluginFunctionalTest {
    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    companion object {
        @Volatile
        private var sharedSigningEnvironment: Map<String, String>? = null
    }


    @Test
    fun packagedPluginFinalizesOrdinaryReleaseBundle() {
        var projectDirectory = temporaryFolder.newFolder("consumer").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")

        val result = runner(projectDirectory).withArguments("bundleRelease", "--stacktrace").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":app:bundleRelease")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS,
                result.task(":app:generateKaleidoReleaseSafeContent")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS,
                result.task(":app:rewriteKaleidoReleaseManifestReferences")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS,
                result.task(":app:rewriteKaleidoReleaseClassesAndManifest")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS,
                result.task(":app:generateKaleidoReleaseR8Configuration")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS,
                result.task(":app:composeKaleidoReleaseR8Mappings")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:finalizeKaleidoReleaseBundle")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS,
                result.task(":app:publishKaleidoReleaseReleaseEvidence")!!.outcome)
        assertTrue(Files.isRegularFile(projectDirectory.resolve(
                "app/build/outputs/bundle/release/app-release.aab")))
        assertTrue(result.output.contains(
                "KLD-ADOPTION-002 project=:app variant=release stage=adoption"))
        assertTrue(result.output.contains("buildType=release flavors=[]"))
        var plan = Files.readString(adoptionPlan(projectDirectory, "release"))
        assertTrue(plan.contains("profile=SAFE\n"))
        assertTrue(plan.contains("defaultsVersion=SafeDefaults.v1\n"))
        assertTrue(plan.contains("generation.packageBase=example.consumer.kaleido.generated\n"))
        assertTrue(plan.contains("generation.packageCount=4\n"))
        assertTrue(plan.contains("generation.classesPerPackage=4\n"))
        assertTrue(plan.contains("generation.methodsPerClass=4\n"))
        assertTrue(plan.contains("generation.layoutCount=8\n"))
        assertTrue(plan.contains("generation.drawableCount=16\n"))
        assertTrue(plan.contains("generation.stringCount=32\n"))
        assertTrue(plan.contains("generation.activityCount=0\n"))
        assertTrue(plan.contains("generation.compose.enabled=false\n"))
        assertTrue(plan.matches(Regex("(?s).*resources.prefix=kld_[0-9a-f]{8}_\\n.*")))
        var inventory = Files.readString(generatedInventory(projectDirectory, "release"))
        assertTrue(inventory.startsWith("schema=GeneratedInventory.v1\n"))
        assertTrue(inventory.contains("classes=16\n"))
        assertTrue(inventory.contains("methods=64\n"))
        assertTrue(inventory.contains("layouts=8\n"))
        assertTrue(inventory.contains("drawables=16\n"))
        assertTrue(inventory.contains("strings=32\n"))
        assertTrue(inventory.contains("components.activities=0\n"))
        assertFalse(inventory.contains("component=activity|"))
        assertFalse(inventory.contains(projectDirectory.toString()))
        var inventoryFiles = inventory.lines()
                .filter { line -> line.startsWith("file=") }
                .toList()
        assertEquals(inventoryFiles.sorted(), inventoryFiles)
        assertEquals(16, countFiles(generatedRoot(projectDirectory, "release").resolve("kotlin"),
                ".kt"))
        assertEquals(8, countFiles(generatedRoot(projectDirectory, "release").resolve("res/layout"),
                ".xml"))
        assertEquals(16, countFiles(
                generatedRoot(projectDirectory, "release").resolve("res/drawable"), ".xml"))
        assertReleaseEvidenceSet(projectDirectory, "release", "example.consumer.MainActivity")
        var rawMapping = Files.readString(classRewriteRoot(projectDirectory, "release")
                .resolve("raw-kaleido-mapping.txt"))
        var mainActivityTarget = mappedTarget(rawMapping, "example.consumer.MainActivity")
        assertTrue(mainActivityTarget.matches(Regex("example\\.consumer\\.k[0-9a-f]{6}\\.C[0-9a-f]{10}")))
        assertEquals(17, rawMapping.lines().filter { line -> line.contains(" -> ") }.count())
        assertTrue(Files.isRegularFile(classRewriteRoot(projectDirectory, "release")
                .resolve("class-rewrite-plan.pb")))
        assertTrue(Files.isRegularFile(classRewriteRoot(projectDirectory, "release")
                .resolve("transform-receipt.pb")))
        var r8Root = r8Root(projectDirectory, "release")
        var rules = Files.readString(r8Root.resolve("config/rules/kaleido-r8.keep"))
        assertTrue(rules.contains("-keep,allowoptimization class "
                + mainActivityTarget + " { *; }"))
        var classDictionary = Files.readString(
                r8Root.resolve("config/dictionaries/class.txt"))
        assertEquals(
            R8ConfigurationEngine.DICTIONARY_SIZE + 1,
            classDictionary.lines().count { line -> line.isNotEmpty() },
        )
        assertArrayEquals(
                Files.readAllBytes(projectDirectory.resolve(
                        "app/build/outputs/mapping/release/mapping.txt")),
                Files.readAllBytes(r8Root.resolve("raw-r8-mapping.txt")))
        var composed = Files.readString(r8Root.resolve("composed-mapping.txt"))
        var capturedRawR8 = Files.readString(r8Root.resolve("raw-r8-mapping.txt"))
        var generatedKaleidoIdentity = rawMapping.lines()
                .filter { line -> line.startsWith("example.consumer.kaleido.generated.") }
                .map { line -> line.substring(line.indexOf(" -> ") + 4) }
                .first()
        var generatedFinalIdentity = mappedFinal(capturedRawR8, generatedKaleidoIdentity)
        assertTrue(classDictionary.lines().any { token ->
            token == generatedFinalIdentity.substring(generatedFinalIdentity.lastIndexOf('.') + 1)
        })
        var finalMainActivity = mappedFinal(composed, "example.consumer.MainActivity")
        assertTrue(retrace(composed,
                "    at " + finalMainActivity + ".unknown(Unknown Source:1)")
                .contains("example.consumer.MainActivity"))
        var mappingMetadata = Files.readString(r8Root.resolve("mapping-metadata.properties"))
        assertTrue(mappingMetadata.contains("rawR8Compiler=R8\n"))
        assertTrue(mappingMetadata.matches(Regex("(?s).*rawR8CompilerVersion=[^\\n]+\\n.*")))
        assertTrue(mappingMetadata.contains("rawR8MappingVersion=2.2\n"))
        assertTrue(mappingMetadata.matches(Regex("(?s).*rawR8PgMapId=[0-9a-f]{64}\\n.*")))
        assertTrue(mappingMetadata.matches(Regex("(?s).*composedSha256=[0-9a-f]{64}\\n.*")))
        assertTrue(mappingMetadata.contains(
                "retraceToolCoordinates=com.android.tools.build:builder:"
                        + testAgpVersion() + "\n"))
        var bundleRewrite = bundleRewriteRoot(projectDirectory, "release")
        assertTrue(Files.isRegularFile(bundleRewrite.resolve("bundle-rewrite-plan.pb")))
        assertTrue(Files.isRegularFile(bundleRewrite.resolve("transform-receipt.pb")))
        var bundlePlan = BundleRewriteArtifacts.decodePlan(Files.readAllBytes(
                bundleRewrite.resolve("bundle-rewrite-plan.pb")), ":app", "release")
        assertTrue(bundlePlan.references.stream().anyMatch { reference ->
                !reference.originalValue.equals(reference.targetValue) })
        assertTrue(bundlePlan.resources.stream().allMatch { resource -> resource.id != 0 })
        var resourceMapping = Files.readString(bundleRewrite.resolve("resource-mapping.txt"))
        assertTrue(resourceMapping.startsWith("schema=KaleidoResourceMapping.v1\n"))
        assertTrue(resourceMapping.contains("style/AppTheme -> example.consumer:style/k"))
        var signedInput = projectDirectory.resolve(
                "app/build/intermediates/bundle/release/signReleaseBundle/app-release.aab")
        var finalBundle = unsignedBundle(projectDirectory, "release")
        assertArrayEquals(zipEntryBytes(signedInput, "base/dex/classes.dex"),
                zipEntryBytes(finalBundle, "base/dex/classes.dex"))
        var originalResources = resourceNames(signedInput)
        var finalResources = resourceNames(finalBundle)
        assertEquals(originalResources.keys, finalResources.keys)
        assertNotEquals(originalResources, finalResources)
        assertTrue(originalResources.containsValue("style/AppTheme"))
        assertFalse(finalResources.containsValue("style/AppTheme"))
        ZipFile(finalBundle.toFile()).use { zip ->
            assertFalse(zip.stream().anyMatch { entry ->
                    entry.name.startsWith("META-INF/")
                            && (entry.name.endsWith(".SF")
                                || entry.name.endsWith(".RSA")
                                || entry.name.equals("META-INF/MANIFEST.MF")) })
        }
        ZipFile(projectDirectory.resolve(
                "app/build/outputs/bundle/release/app-release.aab").toFile()).use { zip ->
            var compiledManifest = Resources.XmlNode.parseFrom(
                    zip.getInputStream(zip.getEntry("base/manifest/AndroidManifest.xml")))
            assertEquals(mainActivityTarget, findManifestComponentName(compiledManifest, "activity"))
            var dex = zip.getInputStream(zip.getEntry("base/dex/classes.dex")).readAllBytes()
            assertTrue(contains(dex, ("L" + mainActivityTarget.replace('.', '/') + ";")
                    .toByteArray(StandardCharsets.UTF_8)))
            assertFalse(contains(dex, "Lexample/consumer/MainActivity;"
                    .toByteArray(StandardCharsets.UTF_8)))
        }
    }

    @Test
    fun generatedTreeIsByteStableAndSeedSensitive() {
        var projectDirectory = temporaryFolder.newFolder("deterministic-generation").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                kaleido { seed.set(providers.environmentVariable("KALEIDO_GENERATION_SEED")) }
                """)
        var environment = HashMap(testSigningEnvironment())
        environment.put("KALEIDO_GENERATION_SEED", "stable-seed-a")

        runner(projectDirectory).withEnvironment(environment)
                .withArguments("generateKaleidoReleaseSafeContent", "--rerun-tasks", "--stacktrace")
                .build()
        var first = snapshotTree(generatedRoot(projectDirectory, "release"))
        runner(projectDirectory).withEnvironment(environment)
                .withArguments("generateKaleidoReleaseSafeContent", "--rerun-tasks", "--stacktrace")
                .build()
        assertEquals(first, snapshotTree(generatedRoot(projectDirectory, "release")))

        environment.put("KALEIDO_GENERATION_SEED", "stable-seed-b")
        runner(projectDirectory).withEnvironment(environment)
                .withArguments("generateKaleidoReleaseSafeContent", "--rerun-tasks", "--stacktrace")
                .build()
        var changed = snapshotTree(generatedRoot(projectDirectory, "release"))
        assertEquals(first.size, changed.size)
        assertFalse(first == changed)
    }

    @Test
    fun r8InputsRawMappingAndCompositionAreByteStableAndSeedSensitive() {
        var projectDirectory = temporaryFolder.newFolder("deterministic-r8").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                kaleido { seed.set(providers.environmentVariable("KALEIDO_R8_SEED")) }
                """)
        var environment = HashMap(testSigningEnvironment())
        environment.put("KALEIDO_R8_SEED", "stable-r8-seed-a")

        runner(projectDirectory).withEnvironment(environment)
                .withArguments("bundleRelease", "--rerun-tasks", "--stacktrace").build()
        var first = snapshotTree(r8Root(projectDirectory, "release"))
        var firstBundleRewrite = snapshotTree(bundleRewriteRoot(projectDirectory, "release"))
        var firstBundle = sha256(Files.readAllBytes(
                unsignedBundle(projectDirectory, "release")))
        runner(projectDirectory).withEnvironment(environment)
                .withArguments("bundleRelease", "--rerun-tasks", "--stacktrace").build()
        assertEquals(first, snapshotTree(r8Root(projectDirectory, "release")))
        assertEquals(firstBundleRewrite,
                snapshotTree(bundleRewriteRoot(projectDirectory, "release")))
        assertEquals(firstBundle, sha256(Files.readAllBytes(
                unsignedBundle(projectDirectory, "release"))))

        environment.put("KALEIDO_R8_SEED", "stable-r8-seed-b")
        runner(projectDirectory).withEnvironment(environment)
                .withArguments("bundleRelease", "--rerun-tasks", "--stacktrace").build()
        var changed = snapshotTree(r8Root(projectDirectory, "release"))
        assertNotEquals(first.get("config/dictionaries/class.txt"),
                changed.get("config/dictionaries/class.txt"))
        assertNotEquals(first.get("composed-mapping.txt"), changed.get("composed-mapping.txt"))
        assertNotEquals(firstBundleRewrite.get("resource-mapping.txt"),
                snapshotTree(bundleRewriteRoot(projectDirectory, "release"))
                        .get("resource-mapping.txt"))
        assertNotEquals(firstBundle, sha256(Files.readAllBytes(
                unsignedBundle(projectDirectory, "release"))))
    }

    @Test
    fun independentNormalizedBuildsProduceIdenticalUnsignedBundlesAndMappings() {
        var firstProject = temporaryFolder.newFolder("independent-build-a").toPath()
        var secondProject = temporaryFolder.newFolder("independent-build-b").toPath()
        for (projectDirectory in listOf(firstProject, secondProject)) {
            writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")
            append(projectDirectory.resolve("app/build.gradle.kts"), """

                    kaleido { seed.set(providers.provider { "independent-canonical-seed" }) }
                    """)
            write(projectDirectory.resolve("app/src/main/res/values/content.xml"), """
                    <resources><string name="independent_label">same</string></resources>
                    """)
            runner(projectDirectory).withArguments("bundleRelease", "--stacktrace").build()
        }

        assertArrayEquals(Files.readAllBytes(unsignedBundle(firstProject, "release")),
                Files.readAllBytes(unsignedBundle(secondProject, "release")))
        assertArrayEquals(Files.readAllBytes(bundleRewriteRoot(firstProject, "release")
                        .resolve("resource-mapping.txt")),
                Files.readAllBytes(bundleRewriteRoot(secondProject, "release")
                        .resolve("resource-mapping.txt")))
    }

    @Test
    fun applyingBeforeAndroidApplicationFailsWithStableDiagnostic() {
        var projectDirectory = temporaryFolder.newFolder("invalid-order").toPath()
        writeConsumer(projectDirectory, "io.github.ujffdi.kaleido", "com.android.application")

        val result = runner(projectDirectory).withArguments("tasks", "--stacktrace").buildAndFail()

        assertTrue(result.output.contains(
                "KLD-ADOPTION-001 project=:app variant=<none> stage=adoption"))
        assertTrue(result.output.contains(
                "repair=Apply com.tongsr.kaleido after com.android.application"))
    }

    @Test
    fun flavoredReleaseVariantsAreIndependentlyFinalized() {
        var projectDirectory = temporaryFolder.newFolder("flavored").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                android {
                    flavorDimensions += "tier"
                    productFlavors {
                        create("free") { dimension = "tier" }
                        create("paid") { dimension = "tier" }
                    }
                }
                """)

        val result = runner(projectDirectory)
                .withArguments("bundleFreeRelease", "bundlePaidRelease", "--stacktrace")
                .build()

        assertEquals(TaskOutcome.SUCCESS,
                result.task(":app:finalizeKaleidoFreeReleaseBundle")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS,
                result.task(":app:finalizeKaleidoPaidReleaseBundle")!!.outcome)
        assertTrue(result.output.contains("variant=freeRelease"))
        assertTrue(result.output.contains("variant=paidRelease"))
        var freeInventory = Files.readString(generatedInventory(projectDirectory, "freeRelease"))
        var paidInventory = Files.readString(generatedInventory(projectDirectory, "paidRelease"))
        assertFalse(freeInventory == paidInventory)
        assertTrue(freeInventory.contains("classes=16\n"))
        assertTrue(paidInventory.contains("classes=16\n"))
        var freeBefore = Files.readAllBytes(unsignedBundle(projectDirectory, "freeRelease"))
        var paidBefore = Files.readAllBytes(unsignedBundle(projectDirectory, "paidRelease"))
        write(projectDirectory.resolve("app/src/free/res/values/free-only.xml"),
                "<resources><string name=\"free_only\">free</string></resources>\n")
        runner(projectDirectory)
                .withArguments("bundleFreeRelease", "bundlePaidRelease", "--stacktrace")
                .build()
        assertNotEquals(sha256(freeBefore), sha256(Files.readAllBytes(
                unsignedBundle(projectDirectory, "freeRelease"))))
        assertArrayEquals(paidBefore,
                Files.readAllBytes(unsignedBundle(projectDirectory, "paidRelease")))
    }

    @Test
    fun ordinaryLibrariesDependenciesAndNativePayloadAreAccepted() {
        var projectDirectory = temporaryFolder.newFolder("ordinary-dependencies").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")
        append(projectDirectory.resolve("settings.gradle.kts"), "\ninclude(\":library\")\n")
        write(projectDirectory.resolve("library/build.gradle.kts"), """
                plugins { id("com.android.library") version %s }
                android {
                    namespace = "example.library"
                    compileSdk = 36
                }
                """.format(quoted(testAgpVersion())))
        write(projectDirectory.resolve("library/src/main/AndroidManifest.xml"), "<manifest />\n")
        write(projectDirectory.resolve("library/src/main/java/example/library/LibraryType.java"), """
                package example.library;
                public final class LibraryType { private LibraryType() {} }
                """)
        write(projectDirectory.resolve("library/src/main/res/values/library.xml"), """
                <resources><string name="library_label">library</string></resources>
                """)
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                dependencies {
                    implementation(project(":library"))
                    implementation("androidx.annotation:annotation:1.9.1")
                }
                """)
        var nativePayload = projectDirectory.resolve(
                "app/src/main/jniLibs/arm64-v8a/libfixture.so")
        Files.createDirectories(nativePayload.getParent())
        Files.write(nativePayload, "fixture-native-payload".toByteArray(StandardCharsets.UTF_8))

        var result = runner(projectDirectory)
                .withArguments("bundleRelease", "--stacktrace").build()

        var bundle = projectDirectory.resolve("app/build/outputs/bundle/release/app-release.aab")
        assertTrue(Files.isRegularFile(bundle))
        ZipFile(bundle.toFile()).use { zip ->
            assertTrue(zip.getEntry("base/lib/arm64-v8a/libfixture.so") != null)
        }
        assertEquals(
            "string/library_label",
            resourceNames(bundle).values.first { value -> value == "string/library_label" },
        )
        assertFalse(Files.readString(bundleRewriteRoot(projectDirectory, "release")
                .resolve("resource-mapping.txt")).contains("library_label ->"))
        var signedInput = projectDirectory.resolve(
                "app/build/intermediates/bundle/release/signReleaseBundle/app-release.aab")
        assertArrayEquals(zipEntryBytes(signedInput,
                        "base/lib/arm64-v8a/libfixture.so"),
                zipEntryBytes(bundle, "base/lib/arm64-v8a/libfixture.so"))
        var rawMapping = Files.readString(classRewriteRoot(projectDirectory, "release")
                .resolve("raw-kaleido-mapping.txt"))
        assertFalse(rawMapping.contains("example.library.LibraryType"))
    }

    @Test
    fun protectedManifestClassRemainsUnchanged() {
        var projectDirectory = temporaryFolder.newFolder("protected-manifest-class").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                kaleido {
                    protection { originalClassNames.add("example.consumer.MainActivity") }
                }
                """)

        runner(projectDirectory).withArguments("bundleRelease", "--stacktrace").build()

        var rawMapping = Files.readString(classRewriteRoot(projectDirectory, "release")
                .resolve("raw-kaleido-mapping.txt"))
        assertFalse(rawMapping.contains("example.consumer.MainActivity ->"))
        ZipFile(projectDirectory.resolve(
                "app/build/outputs/bundle/release/app-release.aab").toFile()).use { zip ->
            var compiledManifest = Resources.XmlNode.parseFrom(
                    zip.getInputStream(zip.getEntry("base/manifest/AndroidManifest.xml")))
            assertEquals("example.consumer.MainActivity",
                    findManifestComponentName(compiledManifest, "activity"))
        }
    }

    @Test
    fun builtInKotlinComponentFamilyMetadataIsRewrittenAndBundles() {
        var projectDirectory = temporaryFolder.newFolder("kotlin-component-family").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")
        Files.delete(projectDirectory.resolve(
                "app/src/main/java/example/consumer/MainActivity.java"))
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
                """)

        runner(projectDirectory).withArguments("bundleRelease", "--stacktrace").build()

        var rawMapping = Files.readString(classRewriteRoot(projectDirectory, "release")
                .resolve("raw-kaleido-mapping.txt"))
        var target = mappedTarget(rawMapping, "example.consumer.MainActivity")
        assertTrue(rawMapping.contains("example.consumer.MainActivity\$Companion -> "
                + target + "\$C"))
        assertTrue(rawMapping.contains("example.consumer.MainActivity\$Payload -> "
                + target + "\$C"))
        var composed = Files.readString(r8Root(projectDirectory, "release")
                .resolve("composed-mapping.txt"))
        var finalIdentity = mappedFinal(composed, "example.consumer.MainActivity")
        assertTrue(retrace(composed,
                "    at " + finalIdentity + ".onCreate(MainActivity.kt:10)")
                .contains("example.consumer.MainActivity"))
        ZipFile(projectDirectory.resolve(
                "app/build/outputs/bundle/release/app-release.aab").toFile()).use { zip ->
            var manifest = Resources.XmlNode.parseFrom(
                    zip.getInputStream(zip.getEntry("base/manifest/AndroidManifest.xml")))
            assertEquals(target, findManifestComponentName(manifest, "activity"))
        }
    }

    @Test
    fun typedClassEscapeHatchProtectsExactIdentityAndRecordsEvidence() {
        var projectDirectory = temporaryFolder.newFolder("typed-class-protection").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")
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
                """)

        runner(projectDirectory).withArguments("bundleRelease", "--stacktrace").build()

        var rawMapping = Files.readString(classRewriteRoot(projectDirectory, "release")
                .resolve("raw-kaleido-mapping.txt"))
        assertFalse(rawMapping.contains("example.consumer.MainActivity ->"))
        var plan = ClassRewriteArtifacts.decodePlan(Files.readAllBytes(
                classRewriteRoot(projectDirectory, "release")
                        .resolve("class-rewrite-plan.pb")), ":app", "release")
        val decision = plan.decisions.first { item ->
            item.original == "example.consumer.MainActivity"
        }
        assertEquals("PROTECTED", decision.action)
        assertTrue(decision.reason.contains("escape-hatch:reflection-main"))
        var rules = Files.readString(classRewriteRoot(projectDirectory, "release")
                .resolve("rules/protection.keep"))
        assertTrue(rules.contains(
                "-keep,allowoptimization class example.consumer.MainActivity { *; }"))
        assertTrue(rules.contains("-keepattributes RuntimeVisibleAnnotations"))
    }

    @Test
    fun zeroMatchAndGlobalEscapeHatchesFailClosed() {
        var zeroMatch = temporaryFolder.newFolder("zero-match-protection").toPath()
        writeConsumer(zeroMatch, "com.android.application", "io.github.ujffdi.kaleido")
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
                """)
        var zeroFailure = runner(zeroMatch)
                .withArguments("bundleRelease", "--stacktrace").buildAndFail()
        assertTrue(zeroFailure.output.contains("KLD-PROTECTION-001"))
        assertTrue(zeroFailure.output.contains("resolves to zero PROJECT classes"))

        var global = temporaryFolder.newFolder("global-protection").toPath()
        writeConsumer(global, "com.android.application", "io.github.ujffdi.kaleido")
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
                """)
        var globalFailure = runner(global)
                .withArguments("bundleRelease", "--stacktrace").buildAndFail()
        assertTrue(globalFailure.output.contains("KLD-PROTECTION-001"))
        assertTrue(globalFailure.output.contains("global, raw, or invalid"))
    }

    @Test
    fun semanticLayoutClassReferenceAndDefinitionCloseTogether() {
        var projectDirectory = temporaryFolder.newFolder("semantic-layout").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")
        write(projectDirectory.resolve(
                "app/src/main/java/example/consumer/CustomView.java"), """
                package example.consumer;

                public final class CustomView extends android.view.View {
                    public CustomView(android.content.Context context) { super(context); }
                }
                """)
        write(projectDirectory.resolve("app/src/main/res/layout/custom_view.xml"), """
                <example.consumer.CustomView
                    xmlns:android="http://schemas.android.com/apk/res/android"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"
                    android:tag="example.consumer.CustomView" />
                """)

        runner(projectDirectory).withArguments("bundleRelease", "--stacktrace").build()

        var mapping = Files.readString(classRewriteRoot(projectDirectory, "release")
                .resolve("raw-kaleido-mapping.txt"))
        var target = mappedTarget(mapping, "example.consumer.CustomView")
        var plan = ClassRewriteArtifacts.decodePlan(Files.readAllBytes(
                classRewriteRoot(projectDirectory, "release")
                        .resolve("class-rewrite-plan.pb")), ":app", "release")
        assertTrue(plan.manifestSites.stream().anyMatch { site ->
                site.location.contains("layout/custom_view.xml/element")
                        && site.target == target })
        ZipFile(projectDirectory.resolve(
                "app/build/outputs/bundle/release/app-release.aab").toFile()).use { zip ->
            var resourceMapping = Files.readString(bundleRewriteRoot(projectDirectory, "release")
                    .resolve("resource-mapping.txt"))
            var entry = zip.getEntry(mappedResourcePath(
                    resourceMapping, "base/res/layout/custom_view.xml"))
            assertTrue(entry != null)
            var xml = Resources.XmlNode.parseFrom(zip.getInputStream(entry))
            assertTrue(containsXmlElement(xml, target))
            assertFalse(containsXmlElement(xml, "example.consumer.CustomView"))
            assertTrue(containsXmlAttributeValue(xml, "tag", "example.consumer.CustomView"))
        }
    }

    @Test
    fun exactReflectionAndNativeDeclarationsCreateMinimalProtection() {
        var projectDirectory = temporaryFolder.newFolder("inferred-protection").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")
        write(projectDirectory.resolve(
                "app/src/main/java/example/consumer/ReflectiveTarget.java"), """
                package example.consumer;

                public final class ReflectiveTarget {
                    public native Payload nativeValue(Payload input);
                }
                """)
        write(projectDirectory.resolve(
                "app/src/main/java/example/consumer/Payload.java"), """
                package example.consumer;

                public final class Payload {}
                """)
        write(projectDirectory.resolve(
                "app/src/main/java/example/consumer/MainActivity.java"), """
                package example.consumer;

                import android.app.Activity;

                public final class MainActivity extends Activity {
                    public static Class<?> reflectiveTarget() throws ClassNotFoundException {
                        return Class.forName("example.consumer.ReflectiveTarget");
                    }
                }
                """)

        runner(projectDirectory).withArguments("bundleRelease", "--stacktrace").build()

        var plan = ClassRewriteArtifacts.decodePlan(Files.readAllBytes(
                classRewriteRoot(projectDirectory, "release")
                        .resolve("class-rewrite-plan.pb")), ":app", "release")
        val target = plan.decisions.first { item ->
            item.original == "example.consumer.ReflectiveTarget"
        }
        val payload = plan.decisions.first { item ->
            item.original == "example.consumer.Payload"
        }
        assertEquals("PROTECTED", target.action)
        assertTrue(target.reason.contains("inferred-exact-reflection"))
        assertTrue(target.reason.contains("inferred-native-declaration"))
        assertEquals("PROTECTED", payload.action)
        assertTrue(payload.reason.contains("native-descriptor-closure"))
        var rules = Files.readString(classRewriteRoot(projectDirectory, "release")
                .resolve("rules/protection.keep"))
        assertTrue(rules.contains("example.consumer.ReflectiveTarget"))
        assertTrue(rules.contains("example.consumer.Payload"))
    }

    @Test
    fun manifestNativeActivityIsProtectedBeforeSemanticRewrite() {
        var projectDirectory = temporaryFolder.newFolder("manifest-native-protection").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")
        write(projectDirectory.resolve(
                "app/src/main/java/example/consumer/MainActivity.java"), """
                package example.consumer;

                import android.app.Activity;

                public final class MainActivity extends Activity {
                    public static native int nativeAnswer();
                }
                """)

        runner(projectDirectory).withArguments("bundleRelease", "--stacktrace").build()

        var plan = ClassRewriteArtifacts.decodePlan(Files.readAllBytes(
                classRewriteRoot(projectDirectory, "release")
                        .resolve("class-rewrite-plan.pb")), ":app", "release")
        val activity = plan.decisions.first { item ->
            item.original == "example.consumer.MainActivity"
        }
        assertEquals("PROTECTED", activity.action)
        assertTrue(activity.reason.contains("inferred-native-declaration"))
        ZipFile(projectDirectory.resolve(
                "app/build/outputs/bundle/release/app-release.aab").toFile()).use { zip ->
            var manifest = Resources.XmlNode.parseFrom(
                    zip.getInputStream(zip.getEntry("base/manifest/AndroidManifest.xml")))
            assertEquals("example.consumer.MainActivity",
                    findManifestComponentName(manifest, "activity"))
        }
    }

    @Test
    fun resourceProtectionRejectsToolsDiscardConflict() {
        var projectDirectory = temporaryFolder.newFolder("resource-protection-conflict").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")
        write(projectDirectory.resolve("app/src/main/res/values/protected.xml"), """
                <resources xmlns:tools="http://schemas.android.com/tools"
                    tools:discard="@string/stable_name">
                    <string name="stable_name">stable</string>
                </resources>
                """)
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
                """)

        var failure = runner(projectDirectory)
                .withArguments("bundleRelease", "--stacktrace").buildAndFail()

        assertTrue(failure.output.contains("KLD-PROTECTION-001"))
        assertTrue(failure.output.contains(
                "tools:discard conflicts with protected resource stable_name"))
        assertNoPublishedOutputs(projectDirectory.resolve("app"))
    }

    @Test
    fun resourceNameAndPackagedPathProtectionRemainUnchanged() {
        var projectDirectory = temporaryFolder.newFolder("resource-protection").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")
        write(projectDirectory.resolve("app/src/main/res/values/protected.xml"), """
                <resources><string name="stable_label">stable</string></resources>
                """)
        write(projectDirectory.resolve("app/src/main/res/layout/stable_screen.xml"), """
                <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent" />
                """)
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
                """)

        runner(projectDirectory).withArguments("bundleRelease", "--stacktrace").build()

        var bundle = projectDirectory.resolve("app/build/outputs/bundle/release/app-release.aab")
        assertTrue(resourceNames(bundle).containsValue("string/stable_label"))
        assertTrue(resourceNames(bundle).containsValue("layout/stable_screen"))
        ZipFile(bundle.toFile()).use { zip ->
            assertTrue(zip.getEntry("base/res/layout/stable_screen.xml") != null)
        }
        var mapping = Files.readString(bundleRewriteRoot(projectDirectory, "release")
                .resolve("resource-mapping.txt"))
        assertFalse(mapping.contains("stable_label ->"))
        assertFalse(mapping.contains("stable_screen ->"))
        assertFalse(mapping.contains("path=base/res/layout/stable_screen.xml ->"))
    }

    @Test
    fun compatiblePayloadsDeduplicateWithoutMergingIdsOrQualifiers() {
        var projectDirectory = temporaryFolder.newFolder("resource-deduplication").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")
        var png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUB"
                        + "AScY42YAAAAASUVORK5CYII=")
        Files.createDirectories(projectDirectory.resolve("app/src/main/res/drawable"))
        Files.write(projectDirectory.resolve("app/src/main/res/drawable/duplicate_a.png"), png)
        Files.write(projectDirectory.resolve("app/src/main/res/drawable/duplicate_b.png"), png)
        Files.write(projectDirectory.resolve("app/src/main/res/drawable/duplicate_c.png"), png)
        Files.createDirectories(projectDirectory.resolve("app/src/main/res/drawable-hdpi"))
        Files.createDirectories(projectDirectory.resolve("app/src/main/res/drawable-xhdpi"))
        Files.write(projectDirectory.resolve(
                "app/src/main/res/drawable-hdpi/qualified.png"), png)
        Files.write(projectDirectory.resolve(
                "app/src/main/res/drawable-xhdpi/qualified.png"), png)
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
                """)

        var result = runner(projectDirectory)
                .withArguments("bundleRelease", "--stacktrace").build()

        var signedInput = projectDirectory.resolve(
                "app/build/intermediates/bundle/release/signReleaseBundle/app-release.aab")
        var output = projectDirectory.resolve("app/build/outputs/bundle/release/app-release.aab")
        var originals = resourceNames(signedInput)
        var idA = resourceId(originals, "drawable/duplicate_a")
        var idB = resourceId(originals, "drawable/duplicate_b")
        var idC = resourceId(originals, "drawable/duplicate_c")
        var qualifiedId = resourceId(originals, "drawable/qualified")
        var finalPaths = resourceFilePaths(output)
        assertNotEquals(idA, idB)
        assertEquals(finalPaths.get(idA), finalPaths.get(idB))
        assertEquals(1, finalPaths.getValue(idA).size)
        assertEquals(listOf("res/drawable/duplicate_c.png"), finalPaths.getValue(idC))
        assertEquals(2, finalPaths.getValue(qualifiedId).size)
        assertEquals(2, finalPaths.getValue(qualifiedId).distinct().count())
        assertArrayEquals(zipEntryBytes(signedInput,
                        "base/res/drawable/duplicate_c.png"),
                zipEntryBytes(output, "base/res/drawable/duplicate_c.png"))
        val sharedPath = "base/" + finalPaths.getValue(idA)[0]
        ZipFile(output.toFile()).use { zip ->
            assertTrue(zip.getEntry(sharedPath) != null)
            assertEquals(1, zip.stream().filter { entry -> entry.name == sharedPath }.count())
        }
        var plan = BundleRewriteArtifacts.decodePlan(Files.readAllBytes(
                bundleRewriteRoot(projectDirectory, "release")
                        .resolve("bundle-rewrite-plan.pb")), ":app", "release")
        assertEquals(2, plan.resources.stream()
                .filter { resource -> resource.id == idA || resource.id == idB }
                .filter { resource -> resource.action.endsWith("_DEDUP") }
                .count())
        assertTrue(result.output.contains(
                "KLD-RESOURCE-002 project=:app variant=release stage=bundle-rewrite"))
    }

    @Test
    fun toolsKeepAndPublicResourcesBecomeNameProtectionRequirements() {
        var projectDirectory = temporaryFolder.newFolder("automatic-resource-protection").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")
        write(projectDirectory.resolve("app/src/main/res/values/public.xml"), """
                <resources>
                    <string name="public_label">public</string>
                    <public type="string" name="public_label" />
                    <string name="kept_one">one</string>
                    <string name="kept_two">two</string>
                </resources>
                """)
        write(projectDirectory.resolve("app/src/main/res/raw/kaleido_keep.xml"), """
                <resources xmlns:tools="http://schemas.android.com/tools"
                    tools:keep="@string/kept_*" />
                """)

        var result = runner(projectDirectory)
                .withArguments("bundleRelease", "--stacktrace").build()

        var names = resourceNames(projectDirectory.resolve(
                "app/build/outputs/bundle/release/app-release.aab"))
        assertTrue(names.containsValue("string/public_label"))
        assertTrue(names.containsValue("string/kept_one"))
        assertTrue(names.containsValue("string/kept_two"))
        assertTrue(result.output.contains(
                "KLD-RESOURCE-001 project=:app variant=release stage=bundle-rewrite"))
        var mapping = Files.readString(bundleRewriteRoot(projectDirectory, "release")
                .resolve("resource-mapping.txt"))
        assertFalse(mapping.contains("public_label ->"))
        assertFalse(mapping.contains("kept_one ->"))
        assertFalse(mapping.contains("kept_two ->"))
    }

    @Test
    fun exactGetIdentifierTargetBecomesNameProtectionRequirement() {
        var projectDirectory = temporaryFolder.newFolder("get-identifier-protection").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")
        write(projectDirectory.resolve("app/src/main/res/values/runtime.xml"), """
                <resources><string name="runtime_label">runtime</string></resources>
                """)
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
                """)

        runner(projectDirectory).withArguments("bundleRelease", "--stacktrace").build()

        var bundle = projectDirectory.resolve("app/build/outputs/bundle/release/app-release.aab")
        assertTrue(resourceNames(bundle).containsValue("string/runtime_label"))
        var evidence = Files.readString(projectDirectory.resolve(
                "app/build/intermediates/kaleido/release/class-rewrite/"
                        + "resource-protection.properties"))
        assertTrue(evidence.contains(
                "resource=string/runtime_label|exact-getIdentifier|"
                        + "example.consumer.MainActivity#runtimeLabel()I"))
        var mapping = Files.readString(bundleRewriteRoot(projectDirectory, "release")
                .resolve("resource-mapping.txt"))
        assertFalse(mapping.contains("runtime_label ->"))
    }

    @Test
    fun nonApplicationTargetFailsWithTopologyDiagnostic() {
        var projectDirectory = temporaryFolder.newFolder("library-target").toPath()
        writeNonApplicationConsumer(projectDirectory)

        val result = runner(projectDirectory).withArguments("tasks", "--stacktrace").buildAndFail()

        assertTrue(result.output.contains(
                "KLD-TOPOLOGY-001 project=:library variant=<none> stage=adoption"))
        assertNoPublishedOutputs(projectDirectory.resolve("library"))
    }

    @Test
    fun dynamicFeatureDeclarationFailsBeforeOutput() {
        var projectDirectory = temporaryFolder.newFolder("dynamic-feature").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                android { dynamicFeatures += setOf(":feature") }
                """)

        val result = runner(projectDirectory)
                .withArguments("bundleRelease", "--stacktrace")
                .buildAndFail()

        assertTrue(result.output.contains("KLD-TOPOLOGY-002"))
        assertNoPublishedOutputs(projectDirectory.resolve("app"))
    }

    @Test
    fun assetPackDeclarationFailsBeforeOutput() {
        var projectDirectory = temporaryFolder.newFolder("asset-pack").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                android { assetPacks += setOf(":assets") }
                """)

        val result = runner(projectDirectory)
                .withArguments("bundleRelease", "--stacktrace")
                .buildAndFail()

        assertTrue(result.output.contains("KLD-TOPOLOGY-003"))
        assertNoPublishedOutputs(projectDirectory.resolve("app"))
    }

    @Test
    fun nonMinifiedReleaseFailsBeforeOutput() {
        var projectDirectory = temporaryFolder.newFolder("not-minified").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")
        var buildFile = projectDirectory.resolve("app/build.gradle.kts")
        Files.writeString(
                buildFile,
                Files.readString(buildFile).replace(
                        "isMinifyEnabled = true", "isMinifyEnabled = false"),
                StandardCharsets.UTF_8)

        val result = runner(projectDirectory)
                .withArguments("bundleRelease", "--stacktrace")
                .buildAndFail()

        assertTrue(result.output.contains("KLD-TOPOLOGY-006"))
        assertNoPublishedOutputs(projectDirectory.resolve("app"))
    }

    @Test
    fun disabledReleaseVariantFailsAdoptionTask() {
        var projectDirectory = temporaryFolder.newFolder("disabled-release").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                androidComponents {
                    beforeVariants(selector().withBuildType("release")) { builder ->
                        builder.enable = false
                    }
                }
                """)

        val result = runner(projectDirectory)
                .withArguments("bundle", "--stacktrace")
                .buildAndFail()

        assertTrue(result.output.contains("KLD-TOPOLOGY-004"))
        assertNoPublishedOutputs(projectDirectory.resolve("app"))
    }

    @Test
    fun confirmedExternalDexLoadingFailsBeforeFinalBundleOutput() {
        var projectDirectory = temporaryFolder.newFolder("external-code").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")
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
                """)

        val result = runner(projectDirectory)
                .withArguments("bundleRelease", "--stacktrace")
                .buildAndFail()

        assertTrue(result.output.contains("KLD-TOPOLOGY-007"))
        assertTrue(result.output.contains("target=DexClassLoader"))
        assertNoPublishedOutputs(projectDirectory.resolve("app"))
    }

    @Test
    fun explicitSeedProviderIsLazyNormalizedAndNeverEmitted() {
        var projectDirectory = temporaryFolder.newFolder("explicit-seed").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                kaleido {
                    seed.set(providers.environmentVariable("KALEIDO_TEST_SEED"))
                }
                """)

        runner(projectDirectory).withArguments("tasks", "--stacktrace").build()

        var rawSeed = "RAW-SEED-Cafe\u0301"
        var environment = HashMap(testSigningEnvironment())
        environment.put("KALEIDO_TEST_SEED", rawSeed)
        val result = runner(projectDirectory)
                .withEnvironment(environment)
                .withArguments("bundleRelease", "--stacktrace")
                .build()

        var plan = Files.readString(adoptionPlan(projectDirectory, "release"))
        assertTrue(plan.contains("seed.fingerprint="
                + SeedDerivation.fingerprint(rawSeed) + "\n"))
        assertFalse(plan.contains(rawSeed))
        assertFalse(result.output.contains(rawSeed))
        Files.walk(releaseEvidenceSet(projectDirectory, "release")).use { paths ->
            for (path in paths.filter(Files::isRegularFile)
                    .filter { file -> file.toString().matches(Regex(
                            ".*\\.(txt|properties|java|kt|xml)$")) }
                    .toList()) {
                var text = Files.readString(path)
                assertFalse(text.contains(rawSeed))
                assertFalse(text.contains(projectDirectory.toAbsolutePath().toString()))
                assertFalse(text.contains(System.getProperty("user.home")))
            }
        }
    }

    @Test
    fun missingExplicitSeedProviderFailsWithStableDiagnostic() {
        var projectDirectory = temporaryFolder.newFolder("missing-seed").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                kaleido {
                    seed.set(providers.environmentVariable("ABSENT_KALEIDO_TEST_SEED"))
                }
                """)

        val result = runner(projectDirectory)
                .withArguments("bundleRelease", "--stacktrace")
                .buildAndFail()

        assertTrue(result.output.contains("KLD-CONFIG-001"))
        assertTrue(result.output.contains("target=seed"))
        assertFalse(Files.exists(adoptionPlan(projectDirectory, "release")))
        assertNoPublishedOutputs(projectDirectory.resolve("app"))
    }

    @Test
    fun fullProfileOnlyUnlocksExplicitControls() {
        var projectDirectory = temporaryFolder.newFolder("full-profile").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")
        write(projectDirectory.resolve("app/unused-strings.txt"), "unused_label\n")
        write(projectDirectory.resolve("app/src/main/res/values/full.xml"), """
                <resources>
                    <string name="used_label">default</string>
                    <string name="unused_label">unused-default</string>
                </resources>
                """)
        write(projectDirectory.resolve("app/src/main/res/values-en/full.xml"), """
                <resources>
                    <string name="used_label">english</string>
                    <string name="unused_label">unused-english</string>
                </resources>
                """)
        write(projectDirectory.resolve("app/src/main/res/values-fr/full.xml"), """
                <resources>
                    <string name="used_label">francais</string>
                    <string name="unused_label">unused-francais</string>
                </resources>
                """)
        write(projectDirectory.resolve(
                "app/src/main/jniLibs/x86_64/libobsolete.so"), "fixture native\n")
        write(projectDirectory.resolve(
                "app/src/main/jniLibs/x86_64/libkeep.so"), "fixture retained native\n")
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
                """)

        runner(projectDirectory).withArguments("bundleRelease", "--stacktrace").build()

        var outputBundle = unsignedBundle(projectDirectory, "release")
        var firstBundle = Files.readAllBytes(outputBundle)
        var firstControlPlan = Files.readAllBytes(bundleRewriteRoot(projectDirectory, "release")
                .resolve("bundle-rewrite-plan.pb"))
        runner(projectDirectory).withArguments(
                "bundleRelease", "--rerun-tasks", "--stacktrace").build()
        assertArrayEquals(firstBundle, Files.readAllBytes(outputBundle))
        assertArrayEquals(firstControlPlan, Files.readAllBytes(
                bundleRewriteRoot(projectDirectory, "release")
                        .resolve("bundle-rewrite-plan.pb")))

        var plan = Files.readString(adoptionPlan(projectDirectory, "release"))
        assertTrue(plan.contains("profile=FULL\n"))
        assertTrue(plan.contains("generation.activityCount=1\n"))
        assertTrue(plan.contains("resources.nativeLibrariesToDelete=libobsolete.so\n"))
        assertTrue(plan.contains("resources.replaceUnusedStrings=true\n"))
        assertTrue(plan.contains("resources.retainedLanguages=en\n"))
        var bundlePlan = BundleRewriteArtifacts.decodePlan(Files.readAllBytes(
                bundleRewriteRoot(projectDirectory, "release")
                        .resolve("bundle-rewrite-plan.pb")), ":app", "release")
        assertTrue(bundlePlan.controls.stream().anyMatch { control ->
                control.kind.equals("DELETE_NATIVE") })
        assertTrue(bundlePlan.controls.stream().anyMatch { control ->
                control.kind.equals("REPLACE_UNUSED_STRING") })
        assertTrue(bundlePlan.controls.stream().anyMatch { control ->
                control.kind.equals("FILTER_LANGUAGE") })
        ZipFile(outputBundle.toFile()).use { zip ->
            assertTrue(zip.stream().noneMatch { entry ->
                    entry.name.endsWith("/libobsolete.so") })
            assertTrue(zip.stream().anyMatch { entry ->
                    entry.name.endsWith("/libkeep.so") })
        }
    }

    @Test
    fun fullProfileActivitiesAreInertMappedAndAbsentWhenUnconfigured() {
        var configured = temporaryFolder.newFolder("full-components").toPath()
        writeConsumer(configured, "com.android.application", "io.github.ujffdi.kaleido")
        append(configured.resolve("app/build.gradle.kts"), """

                kaleido {
                    profile.set(com.tongsr.kaleido.gradle.dsl.KaleidoProfile.FULL)
                    generation { activityCount.set(2) }
                }
                """)

        runner(configured).withArguments("bundleRelease", "--stacktrace").build()

        var inventory = Files.readString(generatedInventory(configured, "release"))
        assertTrue(inventory.contains("components.schema=FullComponentGeneration.v1\n"))
        assertTrue(inventory.contains("components.activities=2\n"))
        var originals = inventory.lines()
                .filter { line -> line.startsWith("component=activity|") }
                .map { line -> line.split('|')[1] }
                .sorted().toList()
        assertEquals(2, originals.size)
        var rawMapping = Files.readString(classRewriteRoot(configured, "release")
                .resolve("raw-kaleido-mapping.txt"))
        var composedMapping = Files.readString(r8Root(configured, "release")
                .resolve("composed-mapping.txt"))
        var finalManifest = Resources.XmlNode.parseFrom(zipEntryBytes(
                unsignedBundle(configured, "release"),
                "base/manifest/AndroidManifest.xml"))
        for (original in originals) {
            var generatedSource = generatedRoot(configured, "release").resolve("kotlin")
                    .resolve(original.replace('.', '/') + ".kt")
            var source = Files.readString(generatedSource)
            assertTrue(source.contains(" : android.app.Activity()"))
            assertFalse(source.contains("Intent"))
            assertFalse(source.contains("Log"))
            var rewritten = mappedTarget(rawMapping, original)
            assertEquals(rewritten, mappedFinal(composedMapping, original))
            var element = findManifestElement(finalManifest, "activity", rewritten)
            assertTrue(element != null)
            assertEquals("false", manifestAttribute(element!!, "exported"))
            assertFalse(element.childList.any { child -> containsXmlElement(child, "intent-filter") })
        }
        assertFalse(containsXmlElement(finalManifest, "uses-permission"))
        assertFalse(containsXmlElement(finalManifest, "service"))
        assertFalse(containsXmlElement(finalManifest, "receiver"))
        assertFalse(containsXmlElement(finalManifest, "provider"))
        assertReleaseEvidenceSet(configured, "release", originals[0])

        var unconfigured = temporaryFolder.newFolder("full-components-unconfigured").toPath()
        writeConsumer(unconfigured, "com.android.application", "io.github.ujffdi.kaleido")
        append(unconfigured.resolve("app/build.gradle.kts"), """

                kaleido {
                    profile.set(com.tongsr.kaleido.gradle.dsl.KaleidoProfile.FULL)
                }
                """)
        runner(unconfigured).withArguments("bundleRelease", "--stacktrace").build()
        var absentInventory = Files.readString(generatedInventory(unconfigured, "release"))
        assertTrue(absentInventory.contains("components.activities=0\n"))
        assertFalse(absentInventory.contains("component=activity|"))
        var absentManifest = Resources.XmlNode.parseFrom(zipEntryBytes(
                unsignedBundle(unconfigured, "release"),
                "base/manifest/AndroidManifest.xml"))
        assertEquals(1, manifestElementNames(absentManifest, "activity").size)
    }

    @Test
    fun fullProfileActivityCollisionFailsBeforeGeneratedMutation() {
        var projectDirectory = temporaryFolder.newFolder("full-component-collision").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")
        var rawSeed = "full-component-collision-seed"
        var expected = FullComponentGenerationEngine.plan(
                functionalFullComponentPlan(rawSeed, 1).values, setOf(), setOf())
        var identity = expected.activities[0]
        var packageName = identity.substring(0, identity.lastIndexOf('.'))
        var className = identity.substring(identity.lastIndexOf('.') + 1)
        write(projectDirectory.resolve("app/src/main/java")
                .resolve(identity.replace('.', '/') + ".java"), """
                package %s;

                public final class %s extends android.app.Activity {}
                """.format(packageName, className))
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                kaleido {
                    seed.set(providers.provider { "%s" })
                    profile.set(com.tongsr.kaleido.gradle.dsl.KaleidoProfile.FULL)
                    generation { activityCount.set(1) }
                }
                """.format(rawSeed))

        var failure = runner(projectDirectory)
                .withArguments("bundleRelease", "--stacktrace").buildAndFail()

        assertTrue(failure.output.contains("KLD-COMPONENT-001"))
        assertTrue(failure.output.contains(identity))
        assertFalse(Files.exists(generatedInventory(projectDirectory, "release")))
        assertNoPublishedOutputs(projectDirectory.resolve("app"))
    }

    @Test
    fun fullResourceControlFailsBeforeMutationAtProtectionBoundary() {
        var projectDirectory = temporaryFolder.newFolder("full-protected-control").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")
        write(projectDirectory.resolve("app/unused-strings.txt"), "protected_label\n")
        write(projectDirectory.resolve("app/src/main/res/values/protected.xml"), """
                <resources><string name="protected_label">stable</string></resources>
                """)
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                kaleido {
                    profile.set(com.tongsr.kaleido.gradle.dsl.KaleidoProfile.FULL)
                    resources {
                        confirmedUnusedStringsFile.set(
                            layout.projectDirectory.file("unused-strings.txt"))
                    }
                    protection { resourceNames.add("protected_label") }
                }
                """)

        var failure = runner(projectDirectory)
                .withArguments("bundleRelease", "--stacktrace").buildAndFail()

        assertTrue(failure.output.contains("KLD-BUNDLE-001"))
        assertTrue(failure.output.contains(
                "Unused-string replacement intersects a Protection Requirement"))
        assertNoPublishedOutputs(projectDirectory.resolve("app"))
    }

    @Test
    fun invalidDslFailsBeforeKaleidoOutput() {
        var projectDirectory = temporaryFolder.newFolder("invalid-dsl").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                kaleido { generation { packageCount.set(0) } }
                """)

        val result = runner(projectDirectory)
                .withArguments("bundleRelease", "--stacktrace")
                .buildAndFail()

        assertTrue(result.output.contains("KLD-CONFIG-001"))
        assertTrue(result.output.contains("target=generation.packageCount"))
        assertFalse(Files.exists(adoptionPlan(projectDirectory, "release")))
        assertNoPublishedOutputs(projectDirectory.resolve("app"))
    }

    @Test
    fun safeProfileCannotSelectFullOnlyControls() {
        var projectDirectory = temporaryFolder.newFolder("safe-full-control").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                kaleido { resources { replaceUnusedStrings.set(true) } }
                """)

        val result = runner(projectDirectory)
                .withArguments("bundleRelease", "--stacktrace")
                .buildAndFail()

        assertTrue(result.output.contains("KLD-CONFIG-001"))
        assertTrue(result.output.contains("target=profile"))
        assertNoPublishedOutputs(projectDirectory.resolve("app"))
    }

    @Test
    fun completeTopLevelSigningSourceSignsAndVerifiesExactCandidate() {
        var projectDirectory = temporaryFolder.newFolder("complete-signing").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")
        var keyStore = projectDirectory.resolve("app/upload.p12")
        var password = "kaleido-signing-sentinel"
        createTestKeyStore(keyStore, password, "upload")
        var certificate = testCertificateSha256(keyStore, password, "upload")
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
                """.format(password, password, certificate))

        var result = runner(projectDirectory)
                .withArguments("bundleRelease", "--stacktrace").build()

        assertFalse(result.output.contains(password))
        assertFalse(result.output.contains(keyStore.toString()))
        var receipt = Files.readString(projectDirectory.resolve(
                "app/build/intermediates/kaleido/release/signing/"
                        + "signing-receipt.properties"))
        assertTrue(receipt.contains("source=TOP_LEVEL_DSL\n"))
        assertTrue(receipt.contains("certificateSha256=" + certificate + "\n"))
        assertTrue(receipt.contains("signatureCoverageValidated=true\n"))
        assertTrue(receipt.contains("bundletoolValidated=true\n"))
        var publicSignedBundle = projectDirectory.resolve(
                "app/build/outputs/bundle/release/app-release.aab")
        JarFile(publicSignedBundle.toFile(), true).use { jar ->
            assertTrue(jar.stream().anyMatch { entry ->
                    entry.name.startsWith("META-INF/KALEIDO.") })
        }
        var stagedSignedBundle = projectDirectory.resolve(
                "app/build/intermediates/kaleido/release/signing/"
                        + "staged-signed-candidate.aab")
        JarFile(stagedSignedBundle.toFile(), true).use { jar ->
            assertTrue(jar.stream().anyMatch { entry ->
                    entry.name.startsWith("META-INF/KALEIDO.") })
            for (entry in jar.stream().filter { item ->
                    !item.isDirectory() && !item.name.startsWith("META-INF/") }.toList()) {
                jar.getInputStream(entry).use { input -> input.readAllBytes() }
                assertTrue(entry.certificates != null && entry.certificates.isNotEmpty())
            }
        }
        assertArrayEquals(Files.readAllBytes(stagedSignedBundle),
                Files.readAllBytes(publicSignedBundle))
        assertReleaseEvidenceSet(projectDirectory, "release", "example.consumer.MainActivity")
        var corrupted = projectDirectory.resolve("app/build/corrupted-signed.aab")
        Files.copy(stagedSignedBundle, corrupted)
        java.nio.file.FileSystems.newFileSystem(
            corrupted,
            emptyMap<String, Any>(),
        ).use { fileSystem ->
            Files.writeString(fileSystem.getPath("/unsigned-entry.txt"), "tampered")
        }
        val corruption = assertThrows(GradleException::class.java) {
            KaleidoSignAndVerifyBundleTask.verifySignedBundle(
                corrupted, certificate, emptyMap(), ":app", "release")
        }
        assertTrue(corruption.message!!.contains(
                "entry without signature coverage"))
    }

    @Test
    fun composeGeneratorCompilesRuntimeOnlyGraphAndRetainsMappedInventory() {
        var projectDirectory = temporaryFolder.newFolder("compose-generation").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")
        enableComposeConsumer(projectDirectory, true, true, true)
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
                """)

        runner(projectDirectory).withArguments("bundleRelease", "--stacktrace").build()

        var generated = generatedRoot(projectDirectory, "release").resolve("kotlin")
        var sources = Files.walk(generated)
                .filter(Files::isRegularFile)
                .filter { path -> path.toString().replace('\\', '/').contains("/compose/") }
                .sorted()
                .toList()
        assertEquals(2, sources.size)
        assertEquals(16, Files.walk(generated)
                .filter(Files::isRegularFile)
                .filter { path -> !path.toString().replace('\\', '/').contains("/compose/") }
                .count())
        for (source in sources) {
            var text = Files.readString(source)
            assertTrue(text.contains("import androidx.compose.runtime.Composable"))
            assertFalse(text.matches(Regex("(?s).*(androidx\\.compose\\.(ui|foundation|material)|"
                    + "Preview|android\\.|kotlinx\\.|java\\.(io|net)).*")))
        }
        var generatedInventory = Files.readString(generatedInventory(projectDirectory, "release"))
        assertTrue(generatedInventory.contains("compose.enabled=true\n"))
        assertTrue(generatedInventory.contains("compose.facades=2\n"))
        assertTrue(generatedInventory.contains("compose.functions=6\n"))
        assertTrue(generatedInventory.matches(Regex(
                "(?s).*compose.runtimeArtifact=androidx\\.compose\\.runtime:"
                        + "runtime(-android)?:[^\\n]+\\n.*")))

        var compiledInventory = Files.readString(classRewriteRoot(projectDirectory, "release")
                .resolve("compose-compiled-inventory.properties"))
        assertTrue(compiledInventory.contains("enabled=true\n"))
        assertTrue(compiledInventory.contains("facades=2\n"))
        assertTrue(compiledInventory.contains("functions=6\n"))
        assertEquals(6, compiledInventory.lines().filter { line ->
                line.startsWith("method=")
                        && line.contains("Landroidx/compose/runtime/Composer;") }.count())
        var composeRules = Files.readString(classRewriteRoot(projectDirectory, "release")
                .resolve("rules/compose.keep"))
        assertEquals(2, composeRules.lines().filter { line ->
                line.startsWith("-keep,allowoptimization,allowobfuscation class ") }.count())
        assertFalse(composeRules.contains("allowshrinking"))

        var composedMapping = Files.readString(r8Root(projectDirectory, "release")
                .resolve("composed-mapping.txt"))
        for (facadeLine in compiledInventory.lines()
                .filter { line -> line.startsWith("facade=") }.toList()) {
            val original = facadeLine.substring("facade=".length, facadeLine.indexOf('|'))
            assertTrue(composedMapping.contains(original + " -> "))
        }
        var finalManifest = zipEntryBytes(unsignedBundle(projectDirectory, "release"),
                "base/manifest/AndroidManifest.xml")
        assertFalse(String(finalManifest, StandardCharsets.ISO_8859_1)
                .contains("KldCompose_"))
        var finalDexReceipt = Files.readString(projectDirectory.resolve(
                "app/build/intermediates/kaleido/release/compose/"
                        + "final-dex-receipt.properties"))
        assertTrue(finalDexReceipt.contains("facades=2\n"))
        assertTrue(finalDexReceipt.contains("functions=6\n"))
        assertTrue(finalDexReceipt.contains("incomingBytecodeEdges=0\n"))
        assertTrue(finalDexReceipt.contains("finalDexRetained=true\n"))
        assertReleaseEvidenceSet(projectDirectory, "release", "example.consumer.MainActivity")
    }

    @Test
    fun composeGeneratorRejectsMissingPrerequisitesAndExcessiveScale() {
        var missingFeature = temporaryFolder.newFolder("compose-missing-feature").toPath()
        writeConsumer(missingFeature, "com.android.application", "io.github.ujffdi.kaleido")
        enableComposeConsumer(missingFeature, true, false, true)
        appendComposeEnabled(missingFeature, 1, 1)
        var featureFailure = runner(missingFeature)
                .withArguments("bundleRelease", "--stacktrace").buildAndFail()
        assertTrue(featureFailure.output.contains(
                "Compose Generator requires buildFeatures.compose to be true"))

        var missingCompiler = temporaryFolder.newFolder("compose-missing-compiler").toPath()
        writeConsumer(missingCompiler, "com.android.application", "io.github.ujffdi.kaleido")
        enableComposeConsumer(missingCompiler, false, true, true)
        appendComposeEnabled(missingCompiler, 1, 1)
        var compilerFailure = runner(missingCompiler)
                .withArguments("bundleRelease", "--stacktrace").buildAndFail()
        assertTrue(compilerFailure.output.contains(
                "Compose Generator requires org.jetbrains.kotlin.plugin.compose"))

        var missingRuntime = temporaryFolder.newFolder("compose-missing-runtime").toPath()
        writeConsumer(missingRuntime, "com.android.application", "io.github.ujffdi.kaleido")
        enableComposeConsumer(missingRuntime, true, true, false)
        appendComposeEnabled(missingRuntime, 1, 1)
        var runtimeFailure = runner(missingRuntime)
                .withArguments("bundleRelease", "--stacktrace").buildAndFail()
        assertTrue(runtimeFailure.output.contains(
                "Compose Runtime is not resolvable on the Release compile classpath"))

        var excessive = temporaryFolder.newFolder("compose-excessive").toPath()
        writeConsumer(excessive, "com.android.application", "io.github.ujffdi.kaleido")
        enableComposeConsumer(excessive, true, true, true)
        appendComposeEnabled(excessive, 64, 9)
        var scaleFailure = runner(excessive)
                .withArguments("bundleRelease", "--stacktrace").buildAndFail()
        assertTrue(scaleFailure.output.contains("Compose function total exceeds 512"))
    }

    @Test
    fun fullComposeGeneratorSucceedsAndConsumerIncomingEdgeFailsClosed() {
        var full = temporaryFolder.newFolder("full-compose-generation").toPath()
        writeConsumer(full, "com.android.application", "io.github.ujffdi.kaleido")
        enableComposeConsumer(full, true, true, true)
        append(full.resolve("app/build.gradle.kts"), """

                kaleido {
                    profile.set(com.tongsr.kaleido.gradle.dsl.KaleidoProfile.FULL)
                    generation { compose { enabled.set(true); fileCount.set(1); functionsPerFile.set(1) } }
                }
                """)
        runner(full).withArguments("bundleRelease", "--stacktrace").build()
        var fullReceipt = Files.readString(full.resolve(
                "app/build/intermediates/kaleido/release/compose/"
                        + "final-dex-receipt.properties"))
        assertTrue(fullReceipt.contains("facades=1\n"))
        assertTrue(fullReceipt.contains("functions=1\n"))

        var incoming = temporaryFolder.newFolder("compose-incoming-edge").toPath()
        writeConsumer(incoming, "com.android.application", "io.github.ujffdi.kaleido")
        enableComposeConsumer(incoming, true, true, true)
        var rawSeed = "incoming-compose-seed"
        append(incoming.resolve("app/build.gradle.kts"), """

                kaleido {
                    seed.set(providers.provider { "%s" })
                    generation { compose { enabled.set(true); fileCount.set(1); functionsPerFile.set(1) } }
                }
                """.format(rawSeed))
        val composePlan = HashMap<String, String>()
        composePlan.put("generation.packageBase", "example.consumer.kaleido.generated")
        composePlan.put("generation.compose.enabled", "true")
        composePlan.put("generation.compose.fileCount", "1")
        composePlan.put("generation.compose.functionsPerFile", "1")
        composePlan.put("seed.domain.generation-compose", SeedDerivation.derive(
                SeedDerivation.fingerprint(rawSeed), "generation-compose", "release|release|"))
        var generated = ComposeGenerationEngine.plan(composePlan)
        var facade = generated.facades[0]
        var function = generated.functions[0].name
        write(incoming.resolve("app/src/main/java/example/consumer/ComposeCaller.java"), """
                package example.consumer;

                import androidx.compose.runtime.Composer;

                public final class ComposeCaller {
                    public static int call(Composer composer) {
                        return %s.%s(1, composer, 0);
                    }
                }
                """.format(facade, function))
        var incomingFailure = runner(incoming)
                .withArguments("bundleRelease", "--stacktrace").buildAndFail()
        assertTrue(incomingFailure.output.contains(
                "Consumer bytecode has an incoming edge to generated Compose code"))
    }

    @Test
    fun partialExactVariantSigningFailsWithoutFallingThroughOrLeakingSecrets() {
        var projectDirectory = temporaryFolder.newFolder("partial-signing").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")
        var sentinel = "partial-signing-sentinel"
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
                """.format(sentinel, sentinel, "0".repeat(64)))

        var failure = runner(projectDirectory)
                .withArguments("bundleRelease", "--stacktrace").buildAndFail()

        assertTrue(failure.output.contains("KLD-SIGNING-001"))
        assertTrue(failure.output.contains(
                "Higher-precedence signing source is partial"))
        assertFalse(failure.output.contains(sentinel))
        assertFalse(Files.exists(projectDirectory.resolve(
                "app/build/intermediates/kaleido/release/signing/"
                        + "signing-receipt.properties")))
    }

    @Test
    fun failedReplacementPreservesPriorPublishedBundleAndEvidence() {
        var projectDirectory = temporaryFolder.newFolder("atomic-publication-preserve").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")
        runner(projectDirectory).withArguments("bundleRelease", "--stacktrace").build()
        var publicBundle = projectDirectory.resolve(
                "app/build/outputs/bundle/release/app-release.aab")
        var priorBundle = Files.readAllBytes(publicBundle)
        var priorEvidence = snapshotTree(releaseEvidenceSet(projectDirectory, "release"))

        var wrongCertificate = HashMap(testSigningEnvironment())
        wrongCertificate.put("KALEIDO_UPLOAD_CERTIFICATE_SHA256", "0".repeat(64))
        var failure = runner(projectDirectory).withEnvironment(wrongCertificate)
                .withArguments("bundleRelease", "--stacktrace").buildAndFail()

        assertTrue(failure.output.contains(
                "Selected signing certificate differs from the expected digest"))
        assertArrayEquals(priorBundle, Files.readAllBytes(publicBundle))
        assertEquals(priorEvidence,
                snapshotTree(releaseEvidenceSet(projectDirectory, "release")))
        assertFalse(Files.exists(releaseEvidenceSet(projectDirectory, "release")
                .resolveSibling("release-evidence-set.publication-staging")))
    }

    @Test
    fun environmentSigningRejectsMissingWrongCertificateAndWrongAlias() {
        var projectDirectory = temporaryFolder.newFolder("invalid-environment-signing").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")

        var missing = HashMap(testSigningEnvironment())
        missing.keys.removeIf { key -> key.startsWith("KALEIDO_UPLOAD_") }
        var missingFailure = runner(projectDirectory).withEnvironment(missing)
                .withArguments("bundleRelease", "--stacktrace").buildAndFail()
        assertTrue(missingFailure.output.contains(
                "No complete upload-signing source is configured"))

        var wrongCertificate = HashMap(testSigningEnvironment())
        wrongCertificate.put("KALEIDO_UPLOAD_CERTIFICATE_SHA256", "0".repeat(64))
        var certificateFailure = runner(projectDirectory).withEnvironment(wrongCertificate)
                .withArguments("bundleRelease", "--stacktrace").buildAndFail()
        assertTrue(certificateFailure.output.contains(
                "Selected signing certificate differs from the expected digest"))

        var wrongAlias = HashMap(testSigningEnvironment())
        wrongAlias.put("KALEIDO_UPLOAD_KEY_ALIAS", "missing-alias")
        var aliasFailure = runner(projectDirectory).withEnvironment(wrongAlias)
                .withArguments("bundleRelease", "--stacktrace").buildAndFail()
        assertTrue(aliasFailure.output.contains(
                "Selected signing source cannot resolve one private-key entry"))
        assertFalse(aliasFailure.output.contains(
                testSigningEnvironment().getValue("KALEIDO_UPLOAD_STORE_PASSWORD")))

        var wrongPassword = HashMap(testSigningEnvironment())
        wrongPassword.put("KALEIDO_UPLOAD_STORE_PASSWORD", "wrong-password-sentinel")
        var passwordFailure = runner(projectDirectory).withEnvironment(wrongPassword)
                .withArguments("bundleRelease", "--stacktrace").buildAndFail()
        assertTrue(passwordFailure.output.contains(
                "Selected signing source cannot resolve one private-key entry"))
        assertFalse(passwordFailure.output.contains("wrong-password-sentinel"))

        var candidate = projectDirectory.resolve("mutated-candidate.aab")
        var digest = projectDirectory.resolve("mutated-candidate.sha256")
        Files.writeString(candidate, "candidate")
        Files.writeString(digest, "0".repeat(64))
        val mutation = assertThrows(GradleException::class.java) {
            KaleidoSignAndVerifyBundleTask.verifyUnsignedCandidate(
                candidate, digest, ":app", "release")
        }
        assertTrue(mutation.message!!.contains(
                "Unsigned candidate digest differs from canonicalization evidence"))
    }

    @Test
    fun configurationCacheIsReusedWithoutCapturingGradleModelObjects() {
        var projectDirectory = temporaryFolder.newFolder("configuration-cache").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")
        append(projectDirectory.resolve("app/build.gradle.kts"), """

                kaleido {
                    protection { originalClassNames.add("example.consumer.MainActivity") }
                    signing {
                        storePassword.set(providers.environmentVariable("UNUSED_SIGNING_SECRET"))
                    }
                }
                """)

        runner(projectDirectory)
                .withArguments("bundleRelease", "--configuration-cache",
                        "--configuration-cache-problems=fail", "--stacktrace")
                .build()
        val second = runner(projectDirectory)
                .withArguments("bundleRelease", "--configuration-cache",
                        "--configuration-cache-problems=fail", "--stacktrace")
                .build()

        assertTrue(second.output.contains("Reusing configuration cache"))
        assertTrue(Files.isRegularFile(adoptionPlan(projectDirectory, "release")))
        assertDeterministicTaskOutcomes(second, setOf(TaskOutcome.UP_TO_DATE))
        assertAlwaysValidatedTaskOutcomes(second)
    }

    @Test
    fun noCleanBuildKeepsDeterministicStagesUpToDateAndRevalidatesSensitiveStages() {
        var projectDirectory = temporaryFolder.newFolder("no-clean-up-to-date").toPath()
        writeConsumer(projectDirectory, "com.android.application", "io.github.ujffdi.kaleido")

        runner(projectDirectory)
                .withArguments("bundleRelease", "--no-build-cache", "--stacktrace").build()
        var second = runner(projectDirectory)
                .withArguments("bundleRelease", "--no-build-cache", "--stacktrace").build()

        assertDeterministicTaskOutcomes(second, setOf(TaskOutcome.UP_TO_DATE))
        assertAlwaysValidatedTaskOutcomes(second)
    }

    @Test
    fun relocatedConsumerRestoresDeterministicStagesFromConsumerBuildCache() {
        var cache = temporaryFolder.newFolder("consumer-build-cache").toPath()
        var first = temporaryFolder.newFolder("cache-source").toPath()
        var relocated = temporaryFolder.newFolder("cache-relocated").toPath()
        writeConsumer(first, "com.android.application", "io.github.ujffdi.kaleido")
        writeConsumer(relocated, "com.android.application", "io.github.ujffdi.kaleido")
        configureLocalBuildCache(first, cache)
        configureLocalBuildCache(relocated, cache)

        runner(first).withArguments("bundleRelease", "--build-cache", "--stacktrace").build()
        var sameWorkspaceRestore = runner(first).withArguments(
                "clean", "bundleRelease", "--build-cache", "--stacktrace").build()
        assertEquals(TaskOutcome.FROM_CACHE,
                sameWorkspaceRestore.task(":app:generateKaleidoReleaseSafeContent")!!.outcome)
        assertEquals(TaskOutcome.FROM_CACHE,
                sameWorkspaceRestore.task(":app:finalizeKaleidoReleaseBundle")!!.outcome)
        assertAlwaysValidatedTaskOutcomes(sameWorkspaceRestore)
        var restored = runner(relocated)
                .withArguments("bundleRelease", "--build-cache", "--stacktrace").build()

        assertDeterministicTaskOutcomes(restored,
                setOf(TaskOutcome.FROM_CACHE, TaskOutcome.UP_TO_DATE))
        assertEquals(TaskOutcome.FROM_CACHE,
                restored.task(":app:generateKaleidoReleaseSafeContent")!!.outcome)
        assertEquals(TaskOutcome.FROM_CACHE,
                restored.task(":app:finalizeKaleidoReleaseBundle")!!.outcome)
        assertAlwaysValidatedTaskOutcomes(restored)
    }

    @Test
    fun threeRelocatedWorkspacesHaveByteIdenticalDeterministicBoundaries() {
        var workspaces = listOf(
                temporaryFolder.newFolder("repro-a").toPath(),
                temporaryFolder.newFolder("repro-b").toPath(),
                temporaryFolder.newFolder("repro-c").toPath())
        var languages = listOf("tr", "de", "ja")
        var countries = listOf("TR", "DE", "JP")
        var zones = listOf("Pacific/Kiritimati", "Europe/Berlin", "Asia/Tokyo")
        var workers = listOf("1", "2", "4")
        var snapshots = ArrayList<Map<String, String>>()
        for (index in workspaces.indices) {
            var workspace = workspaces[index]
            writeConsumer(workspace, "com.android.application", "io.github.ujffdi.kaleido")
            val firstName = if (index % 2 == 0) "ExtraA" else "ExtraB"
            val secondName = if (index % 2 == 0) "ExtraB" else "ExtraA"
            writeExtraClass(workspace, firstName)
            writeExtraClass(workspace, secondName)
            runner(workspace).withArguments(
                    "bundleRelease", "--no-build-cache", "--max-workers=" + workers[index],
                    "-Duser.language=" + languages[index],
                    "-Duser.country=" + countries[index],
                    "-Duser.timezone=" + zones[index], "--stacktrace").build()
            snapshots.add(deterministicBoundarySnapshot(workspace, "release"))
        }

        assertEquals(snapshots[0], snapshots[1])
        assertEquals(snapshots[0], snapshots[2])
    }


    private fun createTestKeyStore(path: Path, password: String, alias: String) {
        Files.createDirectories(path.parent)
        val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool")
        val process = ProcessBuilder(
            keytool.toString(), "-genkeypair", "-noprompt",
            "-alias", alias, "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "3650", "-dname", "CN=Kaleido Test",
            "-storetype", "PKCS12", "-keystore", path.toString(),
            "-storepass", password, "-keypass", password,
        ).redirectErrorStream(true).start()
        val output = String(process.inputStream.readAllBytes(), StandardCharsets.UTF_8)
        check(process.waitFor() == 0) { "keytool failed: $output" }
    }

    private fun testCertificateSha256(path: Path, password: String, alias: String): String {
        val keyStore = KeyStore.getInstance("PKCS12")
        Files.newInputStream(path).use { input ->
            keyStore.load(input, password.toCharArray())
        }
        return sha256(keyStore.getCertificate(alias).encoded)
    }

    private fun runner(projectDirectory: Path): GradleRunner {
        return GradleRunner.create()
            .withProjectDir(projectDirectory.toFile())
            .withGradleVersion(testGradleVersion())
            .withEnvironment(testSigningEnvironment())
            .forwardOutput()
    }

    private fun assertDeterministicTaskOutcomes(result: BuildResult, accepted: Set<TaskOutcome>) {
        for (task in listOf(
            ":app:resolveKaleidoReleaseAdoptionPlan",
            ":app:generateKaleidoReleaseSafeContent",
            ":app:rewriteKaleidoReleaseSemanticXml",
            ":app:rewriteKaleidoReleaseManifestReferences",
            ":app:rewriteKaleidoReleaseClassesAndManifest",
            ":app:generateKaleidoReleaseR8Configuration",
            ":app:composeKaleidoReleaseR8Mappings",
            ":app:finalizeKaleidoReleaseBundle",
        )) {
            assertTrue(
                task + " outcome was " + result.task(task)!!.outcome,
                result.task(task)!!.outcome in accepted,
            )
        }
    }

    private fun assertAlwaysValidatedTaskOutcomes(result: BuildResult) {
        for (task in listOf(
            ":app:verifyKaleidoReleaseComposeFinalDex",
            ":app:signAndVerifyKaleidoReleaseBundle",
            ":app:publishKaleidoReleaseReleaseEvidence",
        )) {
            assertEquals(task, TaskOutcome.SUCCESS, result.task(task)!!.outcome)
        }
    }

    private fun configureLocalBuildCache(projectDirectory: Path, cache: Path) {
        append(
            projectDirectory.resolve("settings.gradle.kts"),
            """

                buildCache {
                    local { directory = file(%s) }
                }
                """.format(quoted(cache.toString())),
        )
    }

    private fun writeExtraClass(projectDirectory: Path, name: String) {
        write(
            projectDirectory.resolve("app/src/main/java/example/consumer/$name.java"),
            """
                package example.consumer;

                final class %s {
                    static int value() { return %d; }
                }
                """.format(name, if (name == "ExtraA") 1 else 2),
        )
    }

    private fun deterministicBoundarySnapshot(
        projectDirectory: Path,
        variant: String,
    ): Map<String, String> {
        val root = releaseEvidenceSet(projectDirectory, variant)
        val values = TreeMap<String, String>()
        values.putAll(snapshotTree(root.resolve("deterministic")))
        values["@deterministic-manifest"] = sha256(
            Files.readAllBytes(root.resolve("deterministic-evidence-manifest.properties")),
        )
        return values.toMap()
    }

    @Synchronized
    private fun testSigningEnvironment(): MutableMap<String, String> {
        sharedSigningEnvironment?.let { return HashMap(it) }
        try {
            val environment = HashMap(System.getenv())
            val keyStore = Path.of("build/test-signing/upload.p12").toAbsolutePath()
            val password = "shared-kaleido-test-signing"
            if (!Files.isRegularFile(keyStore)) {
                createTestKeyStore(keyStore, password, "upload")
            }
            environment["KALEIDO_UPLOAD_KEYSTORE"] = keyStore.toString()
            environment["KALEIDO_UPLOAD_STORE_PASSWORD"] = password
            environment["KALEIDO_UPLOAD_KEY_ALIAS"] = "upload"
            environment["KALEIDO_UPLOAD_KEY_PASSWORD"] = password
            environment["KALEIDO_UPLOAD_CERTIFICATE_SHA256"] =
                testCertificateSha256(keyStore, password, "upload")
            val frozen = environment.toMap()
            sharedSigningEnvironment = frozen
            return HashMap(frozen)
        } catch (failure: Exception) {
            throw IllegalStateException("Unable to create shared test signing identity", failure)
        }
    }

    private fun writeNonApplicationConsumer(projectDirectory: Path) {
        val repository = Path.of(System.getProperty("kaleido.test.repository"))
        val sdk = System.getProperty("kaleido.test.sdk")
        write(
            projectDirectory.resolve("settings.gradle.kts"),
            """
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
                """.format(quoted(repository.toString())),
        )
        write(projectDirectory.resolve("local.properties"), "sdk.dir=$sdk\n")
        write(
            projectDirectory.resolve("gradle.properties"),
            "org.gradle.jvmargs=-Xmx1g -XX:MaxMetaspaceSize=1g\n",
        )
        write(
            projectDirectory.resolve("library/build.gradle.kts"),
            """
                plugins {
                    id("com.android.library") version %s
                    id("io.github.ujffdi.kaleido") version %s
                }
                android {
                    namespace = "example.library"
                    compileSdk = 36
                }
                """.format(quoted(testAgpVersion()), quoted(testPluginVersion())),
        )
        write(projectDirectory.resolve("library/src/main/AndroidManifest.xml"), "<manifest />\n")
    }

    private fun writeConsumer(projectDirectory: Path, firstPlugin: String, secondPlugin: String) {
        val repository = Path.of(System.getProperty("kaleido.test.repository"))
        val sdk = System.getProperty("kaleido.test.sdk")
        write(
            projectDirectory.resolve("settings.gradle.kts"),
            """
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
                """.format(quoted(repository.toString())),
        )
        write(projectDirectory.resolve("local.properties"), "sdk.dir=$sdk\n")
        write(
            projectDirectory.resolve("gradle.properties"),
            "org.gradle.jvmargs=-Xmx1g -XX:MaxMetaspaceSize=1g\n",
        )
        write(
            projectDirectory.resolve("app/build.gradle.kts"),
            """
                plugins {
                    id(%s) version %s
                    id(%s) version %s
                }

                version = "consumer-project-version"

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
                """.format(
                quoted(firstPlugin),
                pluginVersion(firstPlugin),
                quoted(secondPlugin),
                pluginVersion(secondPlugin),
            ),
        )
        write(
            projectDirectory.resolve("app/src/main/AndroidManifest.xml"),
            """
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
                """,
        )
        write(
            projectDirectory.resolve("app/src/main/java/example/consumer/MainActivity.java"),
            """
                package example.consumer;

                import android.app.Activity;

                public final class MainActivity extends Activity {}
                """,
        )
        write(
            projectDirectory.resolve("app/src/main/res/values/styles.xml"),
            """
                <resources>
                    <style name="AppTheme" parent="android:style/Theme.Material.Light.NoActionBar" />
                </resources>
                """,
        )
    }

    private fun enableComposeConsumer(
        projectDirectory: Path,
        compilerPlugin: Boolean,
        buildFeature: Boolean,
        runtimeDependency: Boolean,
    ) {
        val buildFile = projectDirectory.resolve("app/build.gradle.kts")
        var script = Files.readString(buildFile)
        if (compilerPlugin) {
            val kaleidoDeclaration =
                "id(\"io.github.ujffdi.kaleido\") version " + quoted(testPluginVersion())
            script = script.replace(
                kaleidoDeclaration,
                "id(\"org.jetbrains.kotlin.plugin.compose\") version \"2.2.10\"\n" +
                    "    " + kaleidoDeclaration,
            )
        }
        if (buildFeature) {
            script = script.replace("android {", "android {\n    buildFeatures { compose = true }")
        }
        if (runtimeDependency) {
            script += "\ndependencies { implementation(\"androidx.compose.runtime:runtime:1.10.0\") }\n"
        }
        Files.writeString(buildFile, script)
    }

    private fun appendComposeEnabled(projectDirectory: Path, files: Int, functions: Int) {
        append(
            projectDirectory.resolve("app/build.gradle.kts"),
            """

                kaleido {
                    generation {
                        compose {
                            enabled.set(true)
                            fileCount.set(%d)
                            functionsPerFile.set(%d)
                        }
                    }
                }
                """.format(files, functions),
        )
    }

    private fun pluginVersion(pluginId: String): String {
        return quoted(
            if (pluginId == "io.github.ujffdi.kaleido") testPluginVersion() else testAgpVersion(),
        )
    }

    private fun testPluginVersion(): String =
        System.getProperty("kaleido.test.plugin.version", "0.1.1-dev")

    private fun testAgpVersion(): String =
        System.getProperty("kaleido.test.agp", "9.2.0")

    private fun testGradleVersion(): String =
        System.getProperty("kaleido.test.gradle", "9.4.1")

    private fun functionalFullComponentPlan(rawSeed: String, activityCount: Int): AdoptionPlan {
        return AdoptionPlanFactory.create(
            AdoptionPlanFactory.Input(
                ":app",
                "release",
                "release",
                emptyList(),
                "example.consumer",
                KaleidoProfile.FULL,
                "example.consumer.kaleido.generated",
                4,
                4,
                4,
                8,
                16,
                32,
                activityCount,
                false,
                false,
                false,
                4,
                4,
                emptySet(),
                emptySet(),
                false,
                emptySet(),
                emptySet(),
                emptySet(),
                emptySet(),
                SeedDerivation.fingerprint(rawSeed),
            ),
        )
    }

    private fun quoted(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun adoptionPlan(projectDirectory: Path, variant: String): Path =
        projectDirectory.resolve(
            "app/build/intermediates/kaleido/$variant/adoption-plan.properties",
        )

    private fun generatedRoot(projectDirectory: Path, variant: String): Path =
        projectDirectory.resolve("app/build/generated/kaleido/$variant")

    private fun generatedInventory(projectDirectory: Path, variant: String): Path =
        projectDirectory.resolve(
            "app/build/intermediates/kaleido/$variant/generated-inventory.properties",
        )

    private fun classRewriteRoot(projectDirectory: Path, variant: String): Path =
        projectDirectory.resolve("app/build/intermediates/kaleido/$variant/class-rewrite")

    private fun r8Root(projectDirectory: Path, variant: String): Path =
        projectDirectory.resolve("app/build/intermediates/kaleido/$variant/r8")

    private fun bundleRewriteRoot(projectDirectory: Path, variant: String): Path =
        projectDirectory.resolve("app/build/intermediates/kaleido/$variant/bundle-rewrite")

    private fun unsignedBundle(projectDirectory: Path, variant: String): Path =
        bundleRewriteRoot(projectDirectory, variant).resolve("unsigned-candidate.aab")

    private fun releaseEvidenceSet(projectDirectory: Path, variant: String): Path =
        projectDirectory.resolve("app/build/reports/kaleido/$variant/release-evidence-set")

    private fun assertReleaseEvidenceSet(
        projectDirectory: Path,
        variant: String,
        retraceIdentity: String,
    ) {
        val root = releaseEvidenceSet(projectDirectory, variant)
        val manifest = simpleProperties(
            Files.readString(root.resolve("release-evidence-set-manifest.properties")),
        )
        assertEquals("ReleaseEvidenceSetManifest.v1", manifest["schema"])
        assertEquals(":app", manifest["project"])
        assertEquals(variant, manifest["variant"])
        assertEquals("PUBLISHED", manifest["publicationResult"])
        assertEquals(testPluginVersion(), manifest["pluginVersion"])
        assertFalse(manifest.values.contains("consumer-project-version"))
        val publicBundle = projectDirectory.resolve(
            "app/build/outputs/bundle/$variant/app-$variant.aab",
        )
        assertEquals(manifest["signedAabSha256"], sha256(Files.readAllBytes(publicBundle)))
        JarFile(publicBundle.toFile(), true).use { jar ->
            assertTrue(jar.stream().anyMatch { entry -> entry.name.startsWith("META-INF/KALEIDO.") })
        }

        val deterministicManifest = Files.readAllLines(
            root.resolve("deterministic-evidence-manifest.properties"),
        )
        assertEquals("schema=DeterministicEvidenceManifest.v1", deterministicManifest[0])
        for (line in deterministicManifest.subList(1, deterministicManifest.size)) {
            if (line.isBlank()) continue
            val separator = line.lastIndexOf('|')
            val logical = line.substring("file=".length, separator)
            val expected = line.substring(separator + 1)
            assertEquals(
                expected,
                sha256(Files.readAllBytes(root.resolve("deterministic").resolve(logical))),
            )
        }
        assertEquals(
            manifest["deterministicEvidenceSha256"],
            sha256(Files.readAllBytes(root.resolve("deterministic-evidence-manifest.properties"))),
        )
        assertEquals(
            manifest["rawKaleidoMappingSha256"],
            sha256(Files.readAllBytes(root.resolve("mappings/raw-kaleido-mapping.txt"))),
        )
        assertEquals(
            manifest["rawR8MappingSha256"],
            sha256(Files.readAllBytes(root.resolve("mappings/raw-r8-mapping.txt"))),
        )
        assertEquals(
            manifest["composedMappingSha256"],
            sha256(Files.readAllBytes(root.resolve("mappings/composed-mapping.txt"))),
        )
        assertEquals(
            manifest["resourceMappingSha256"],
            sha256(Files.readAllBytes(root.resolve("mappings/resource-mapping.txt"))),
        )
        val identity = "schema=" + manifest["schema"] + "\n" +
            "project=" + manifest["project"] + "\n" +
            "variant=" + manifest["variant"] + "\n" +
            "applicationId=" + manifest["applicationId"] + "\n" +
            "unsignedAabSha256=" + manifest["unsignedAabSha256"] + "\n" +
            "signedAabSha256=" + manifest["signedAabSha256"] + "\n" +
            "rawKaleidoMappingSha256=" + manifest["rawKaleidoMappingSha256"] + "\n" +
            "rawR8MappingSha256=" + manifest["rawR8MappingSha256"] + "\n" +
            "composedMappingSha256=" + manifest["composedMappingSha256"] + "\n" +
            "resourceMappingSha256=" + manifest["resourceMappingSha256"] + "\n" +
            "deterministicEvidenceSha256=" + manifest["deterministicEvidenceSha256"] + "\n" +
            "certificateSha256=" + manifest["certificateSha256"] + "\n"
        assertEquals(
            manifest["releaseEvidenceSetId"],
            sha256(identity.toByteArray(StandardCharsets.UTF_8)),
        )
        val report = Files.readString(root.resolve("artifact-report.txt"))
        assertTrue(
            report.startsWith(
                "schemaUri=https://schemas.tongsr.com/kaleido/artifact-report/1.0\n",
            ),
        )
        assertEquals(
            10,
            report.lineSequence().count { line -> line.matches(Regex("stage\\.[0-9]{2}=.+\\|PASS")) },
        )
        assertTrue(report.contains("proofLimitations="))
        assertTrue(report.contains("pluginVersion=${testPluginVersion()}\n"))
        assertFalse(report.contains("pluginVersion=consumer-project-version"))
        assertFalse(report.contains(projectDirectory.toAbsolutePath().toString()))
        assertFalse(report.contains(System.getProperty("user.home")))
        val composed = Files.readString(root.resolve("mappings/composed-mapping.txt"))
        val finalIdentity = mappedFinal(composed, retraceIdentity)
        assertTrue(
            retrace(composed, "    at $finalIdentity.<init>(Unknown Source)")
                .contains(retraceIdentity),
        )
    }

    private fun simpleProperties(text: String): Map<String, String> {
        val values = TreeMap<String, String>()
        text.lineSequence().filter { line -> line.isNotBlank() }.forEach { line ->
            val separator = line.indexOf('=')
            values[line.substring(0, separator)] = line.substring(separator + 1)
        }
        return values.toMap()
    }

    private fun mappedTarget(mapping: String, original: String): String {
        return mapping.lineSequence()
            .filter { line -> line.startsWith("$original -> ") }
            .map { line -> line.substring("$original -> ".length) }
            .first()
    }

    private fun mappedFinal(mapping: String, original: String): String {
        return mapping.lineSequence()
            .filter { line -> line.startsWith("$original -> ") && line.endsWith(":") }
            .map { line -> line.substring("$original -> ".length, line.length - 1) }
            .first()
    }

    private fun retrace(mapping: String, stackLine: String): String {
        val output = ArrayList<String>()
        val supplier = ProguardMappingSupplier.builder()
            .setProguardMapProducer(ProguardMapProducer.fromString(mapping))
            .setLoadAllDefinitions(true)
            .build()
        Retrace.run(
            RetraceCommand.builder()
                .setMappingSupplier(supplier)
                .setStackTrace(listOf(stackLine))
                .setRetracedStackTraceConsumer { output.addAll(it) }
                .build(),
        )
        return output.joinToString("\n")
    }

    private fun mappedResourcePath(mapping: String, original: String): String {
        return mapping.lineSequence()
            .filter { line -> line.startsWith("path=$original -> ") }
            .map { line -> line.substring("path=$original -> ".length) }
            .first()
    }

    private fun zipEntryBytes(archive: Path, entryName: String): ByteArray {
        ZipFile(archive.toFile()).use { zip ->
            val entry = zip.getEntry(entryName) ?: throw IOException("Missing ZIP entry $entryName")
            return zip.getInputStream(entry).readAllBytes()
        }
    }

    private fun resourceNames(bundle: Path): Map<Int, String> {
        val values = TreeMap<Int, String>(Integer::compareUnsigned)
        ZipFile(bundle.toFile()).use { zip ->
            val table = Resources.ResourceTable.parseFrom(
                zip.getInputStream(zip.getEntry("base/resources.pb")),
            )
            for (pkg in table.packageList) {
                for (type in pkg.typeList) {
                    for (entry in type.entryList) {
                        val id = (pkg.packageId.id shl 24) or
                            (type.typeId.id shl 16) or
                            entry.entryId.id
                        values[id] = type.name + "/" + entry.name
                    }
                }
            }
        }
        return values.toMap()
    }

    private fun resourceId(resources: Map<Int, String>, identity: String): Int {
        return resources.entries.first { entry -> entry.value == identity }.key
    }

    private fun resourceFilePaths(bundle: Path): Map<Int, List<String>> {
        val values = TreeMap<Int, List<String>>(Integer::compareUnsigned)
        ZipFile(bundle.toFile()).use { zip ->
            val table = Resources.ResourceTable.parseFrom(
                zip.getInputStream(zip.getEntry("base/resources.pb")),
            )
            for (pkg in table.packageList) {
                for (type in pkg.typeList) {
                    for (entry in type.entryList) {
                        val id = (pkg.packageId.id shl 24) or
                            (type.typeId.id shl 16) or
                            entry.entryId.id
                        val paths = sortedSetOf<String>()
                        for (config in entry.configValueList) {
                            if (config.hasValue() && config.value.hasItem() && config.value.item.hasFile()) {
                                paths.add(config.value.item.file.path)
                            }
                        }
                        if (paths.isNotEmpty()) values[id] = paths.toList()
                    }
                }
            }
        }
        return values.toMap()
    }

    private fun findManifestComponentName(node: Resources.XmlNode, elementName: String): String? {
        if (!node.hasElement()) return null
        val element = node.element
        if (elementName == element.name) {
            return element.attributeList
                .filter { attribute ->
                    attribute.namespaceUri == "http://schemas.android.com/apk/res/android"
                }
                .filter { attribute -> attribute.name == "name" }
                .map { attribute -> attribute.value }
                .firstOrNull()
        }
        for (child in element.childList) {
            val found = findManifestComponentName(child, elementName)
            if (found != null) return found
        }
        return null
    }

    private fun findManifestElement(
        node: Resources.XmlNode,
        elementName: String,
        androidName: String,
    ): Resources.XmlElement? {
        if (!node.hasElement()) return null
        val element = node.element
        if (elementName == element.name && androidName == manifestAttribute(element, "name")) {
            return element
        }
        for (child in element.childList) {
            val found = findManifestElement(child, elementName, androidName)
            if (found != null) return found
        }
        return null
    }

    private fun manifestAttribute(element: Resources.XmlElement, name: String): String? {
        return element.attributeList
            .filter { attribute ->
                attribute.namespaceUri == "http://schemas.android.com/apk/res/android"
            }
            .filter { attribute -> attribute.name == name }
            .map { attribute -> attribute.value }
            .firstOrNull()
    }

    private fun manifestElementNames(node: Resources.XmlNode, elementName: String): List<String?> {
        val values = ArrayList<String?>()
        collectManifestElementNames(node, elementName, values)
        return values.toList()
    }

    private fun collectManifestElementNames(
        node: Resources.XmlNode,
        elementName: String,
        values: MutableList<String?>,
    ) {
        if (!node.hasElement()) return
        val element = node.element
        if (elementName == element.name) {
            values.add(manifestAttribute(element, "name"))
        }
        element.childList.forEach { child ->
            collectManifestElementNames(child, elementName, values)
        }
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        outer@ for (index in 0..(haystack.size - needle.size)) {
            for (offset in needle.indices) {
                if (haystack[index + offset] != needle[offset]) {
                    continue@outer
                }
            }
            return true
        }
        return false
    }

    private fun containsXmlElement(node: Resources.XmlNode, name: String): Boolean {
        if (!node.hasElement()) return false
        if (name == node.element.name) return true
        return node.element.childList.any { child -> containsXmlElement(child, name) }
    }

    private fun containsXmlAttributeValue(
        node: Resources.XmlNode,
        attributeName: String,
        value: String,
    ): Boolean {
        if (!node.hasElement()) return false
        if (node.element.attributeList.any { attribute ->
                attributeName == attribute.name && value == attribute.value
            }
        ) {
            return true
        }
        return node.element.childList.any { child ->
            containsXmlAttributeValue(child, attributeName, value)
        }
    }

    private fun countFiles(root: Path, suffix: String): Long {
        Files.walk(root).use { paths ->
            return paths.filter { Files.isRegularFile(it) }
                .filter { path -> path.fileName.toString().endsWith(suffix) }
                .count()
        }
    }

    private fun snapshotTree(root: Path): Map<String, String> {
        val snapshot = TreeMap<String, String>()
        Files.walk(root).use { paths ->
            for (path in paths.filter { Files.isRegularFile(it) }.sorted().toList()) {
                snapshot[
                    root.relativize(path).toString().replace(java.io.File.separatorChar, '/'),
                ] = sha256(Files.readAllBytes(path))
            }
        }
        return snapshot.toMap()
    }

    private fun sha256(content: ByteArray): String {
        return try {
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))
        } catch (impossible: NoSuchAlgorithmException) {
            throw IllegalStateException("SHA-256 is unavailable", impossible)
        }
    }

    private fun assertNoPublishedOutputs(moduleDirectory: Path) {
        val bundleDirectory = moduleDirectory.resolve("build/outputs/bundle")
        if (Files.exists(bundleDirectory)) {
            Files.walk(bundleDirectory).use { paths ->
                assertFalse(
                    paths.anyMatch { path ->
                        Files.isRegularFile(path) && path.fileName.toString().endsWith(".aab")
                    },
                )
            }
        }
        assertFalse(Files.exists(moduleDirectory.resolve("build/reports/kaleido")))
    }

    private fun append(path: Path, content: String) {
        val trimmed = content.trimIndent()
        val normalized = if (content.startsWith("\n") && !trimmed.startsWith("\n")) {
            "\n$trimmed"
        } else {
            trimmed
        }
        Files.writeString(path, normalized, StandardCharsets.UTF_8, StandardOpenOption.APPEND)
    }

    private fun write(path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content.trimIndent(), StandardCharsets.UTF_8)
    }
}
