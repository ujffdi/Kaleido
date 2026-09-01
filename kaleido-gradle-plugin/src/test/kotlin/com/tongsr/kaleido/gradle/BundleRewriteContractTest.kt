package com.tongsr.kaleido.gradle

import com.android.aapt.Resources
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.gradle.api.GradleException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BundleRewriteContractTest {
    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun protobufPlanIsCanonicalAndRejectsUnknownMajor() {
        val first = plan(BundleRewriteArtifacts.PLAN_SCHEMA)
        val reordered = BundleRewriteArtifacts.Plan(
            first.schema,
            first.producer,
            first.project,
            first.variant,
            first.inputAabSha256,
            first.resourceTableSha256,
            listOf(first.resources[1], first.resources[0]),
            listOf(first.entries[1], first.entries[0]),
            listOf(first.expectedOutputs[1], first.expectedOutputs[0]),
            listOf(first.references[1], first.references[0]),
            first.controls,
        )

        assertArrayEquals(
            BundleRewriteArtifacts.encodePlan(first),
            BundleRewriteArtifacts.encodePlan(reordered),
        )
        assertEquals(
            first,
            BundleRewriteArtifacts.decodePlan(
                BundleRewriteArtifacts.encodePlan(first),
                ":app",
                "release",
            ),
        )

        val failure = assertThrows(GradleException::class.java) {
            BundleRewriteArtifacts.decodePlan(
                BundleRewriteArtifacts.encodePlan(plan("BundleRewritePlan.v2")),
                ":app",
                "release",
            )
        }
        assertTrue(failure.message!!.contains("KLD-BUNDLE-001"))
        assertTrue(failure.message!!.contains("Unknown Bundle Rewrite Plan major"))
    }

    @Test
    fun plannerRejectsDanglingFileReferenceBeforeMutation() {
        val bundle = temporaryFolder.newFile("dangling.aab").toPath()
        val file = Resources.FileReference.newBuilder()
            .setPath("res/layout/screen.xml").build()
        val value = Resources.Value.newBuilder().setItem(
            Resources.Item.newBuilder().setFile(file),
        ).build()
        val entry = Resources.Entry.newBuilder()
            .setEntryId(Resources.EntryId.newBuilder().setId(0))
            .setName("screen")
            .addConfigValue(Resources.ConfigValue.newBuilder().setValue(value))
            .build()
        val type = Resources.Type.newBuilder()
            .setTypeId(Resources.TypeId.newBuilder().setId(1))
            .setName("layout").addEntry(entry).build()
        val table = Resources.ResourceTable.newBuilder()
            .addPackage(
                Resources.Package.newBuilder()
                    .setPackageId(Resources.PackageId.newBuilder().setId(0x7f))
                    .setPackageName("example.app").addType(type),
            )
            .build()
        ZipOutputStream(Files.newOutputStream(bundle)).use { zip ->
            zip.putNextEntry(ZipEntry("base/resources.pb"))
            zip.write(table.toByteArray())
            zip.closeEntry()
        }

        val failure = assertThrows(GradleException::class.java) {
            BundleRewriteModule.plan(
                bundle,
                BundleRewriteModule.Context(":app", "release"),
                setOf(BundleRewriteModule.ResourceKey("layout", "screen")),
                setOf(),
                setOf(),
                listOf(),
                "a".repeat(64),
                BundleRewriteModule.FullControls.none(),
            )
        }
        assertTrue(failure.message!!.contains("dangling file reference"))
    }

    @Test
    fun sharedPhysicalPathAcrossLogicalAliasesIsNeverDeleted() {
        val bundle = temporaryFolder.newFile("shared-alias.aab").toPath()
        val file = Resources.FileReference.newBuilder()
            .setPath("res/drawable/shared.png")
            .setType(Resources.FileReference.Type.PNG).build()
        val value = Resources.Value.newBuilder().setItem(
            Resources.Item.newBuilder().setFile(file),
        ).build()
        val type = Resources.Type.newBuilder()
            .setTypeId(Resources.TypeId.newBuilder().setId(1)).setName("drawable")
        for (index in 0 until 2) {
            type.addEntry(
                Resources.Entry.newBuilder()
                    .setEntryId(Resources.EntryId.newBuilder().setId(index))
                    .setName("alias_" + index)
                    .addConfigValue(Resources.ConfigValue.newBuilder().setValue(value)),
            )
        }
        val table = Resources.ResourceTable.newBuilder()
            .addPackage(
                Resources.Package.newBuilder()
                    .setPackageId(Resources.PackageId.newBuilder().setId(0x7f))
                    .setPackageName("example.app").addType(type),
            ).build()
        ZipOutputStream(Files.newOutputStream(bundle)).use { zip ->
            zip.putNextEntry(ZipEntry("base/resources.pb"))
            zip.write(table.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("base/res/drawable/shared.png"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }

        val plan = BundleRewriteModule.plan(
            bundle,
            BundleRewriteModule.Context(":app", "release"),
            setOf(
                BundleRewriteModule.ResourceKey("drawable", "alias_0"),
                BundleRewriteModule.ResourceKey("drawable", "alias_1"),
            ),
            setOf(),
            setOf(),
            listOf(),
            "a".repeat(64),
            BundleRewriteModule.FullControls.none(),
        )

        assertEquals(2, plan.resources.size)
        val payload = plan.entries.first { entry ->
            entry.inputPath == "base/res/drawable/shared.png"
        }
        assertEquals("base/res/drawable/shared.png", payload.outputPath)
        assertEquals("COPY", payload.action)
    }

    @Test
    fun permittedApplicationMetadataDeletionIsExplicitInPlan() {
        val bundle = temporaryFolder.newFile("metadata-control.aab").toPath()
        val table = Resources.ResourceTable.newBuilder().build()
        ZipOutputStream(Files.newOutputStream(bundle)).use { zip ->
            zip.putNextEntry(ZipEntry("base/resources.pb"))
            zip.write(table.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("base/root/NOTICE.txt"))
            zip.write("notice".toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }

        val plan = BundleRewriteModule.plan(
            bundle,
            BundleRewriteModule.Context(":app", "release"),
            setOf(),
            setOf(),
            setOf(),
            listOf(),
            "a".repeat(64),
            BundleRewriteModule.FullControls(
                setOf(),
                setOf("META-INF/NOTICE.txt"),
                setOf(),
                setOf(),
                setOf(),
                setOf("META-INF/NOTICE.txt"),
            ),
        )

        assertEquals("DELETE_METADATA", plan.controls[0].kind)
        val metadata = plan.entries.first { entry ->
            entry.inputPath == "base/root/NOTICE.txt"
        }
        assertEquals("", metadata.outputPath)
        assertEquals("DELETE_FULL_CONTROL", metadata.action)
    }

    @Test
    fun nativeDeletionRejectsModeledLoadLibraryReferenceBeforeMutation() {
        val bundle = temporaryFolder.newFile("native-reference.aab").toPath()
        ZipOutputStream(Files.newOutputStream(bundle)).use { zip ->
            zip.putNextEntry(ZipEntry("base/resources.pb"))
            zip.write(Resources.ResourceTable.newBuilder().build().toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("base/dex/classes.dex"))
            zip.write("prefix-obsolete-suffix".toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("base/lib/x86_64/libobsolete.so"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("base/lib/x86_64/libkeep.so"))
            zip.write(byteArrayOf(2))
            zip.closeEntry()
        }

        val failure = assertThrows(GradleException::class.java) {
            BundleRewriteModule.plan(
                bundle,
                BundleRewriteModule.Context(":app", "release"),
                setOf(),
                setOf(),
                setOf(),
                listOf(),
                "a".repeat(64),
                BundleRewriteModule.FullControls(
                    setOf("libobsolete.so"),
                    setOf(),
                    setOf(),
                    setOf(),
                    setOf("libobsolete.so", "libkeep.so"),
                    setOf(),
                ),
            )
        }

        assertTrue(
            failure.message!!.contains(
                "Native deletion intersects a modeled code loading reference",
            ),
        )
    }

    private fun plan(schema: String): BundleRewriteArtifacts.Plan =
        BundleRewriteArtifacts.Plan(
            schema,
            BundleRewriteArtifacts.PRODUCER,
            ":app",
            "release",
            "a".repeat(64),
            "b".repeat(64),
            listOf(
                BundleRewriteArtifacts.ResourceDecision(
                    0x7f010001,
                    "example.app",
                    "drawable",
                    "icon",
                    "ka",
                    "REWRITE_NAME_AND_PATH",
                    "eligible",
                    listOf("res/drawable/icon.xml"),
                    listOf("res/drawable/ka.xml"),
                    false,
                    false,
                ),
                BundleRewriteArtifacts.ResourceDecision(
                    0x7f020001,
                    "example.app",
                    "string",
                    "label",
                    "kb",
                    "REWRITE_NAME",
                    "eligible",
                    listOf(),
                    listOf(),
                    false,
                    false,
                ),
            ),
            listOf(
                BundleRewriteArtifacts.EntryDecision(
                    "base/resources.pb",
                    "c".repeat(64),
                    ZipEntry.DEFLATED,
                    "base/resources.pb",
                    "REWRITE_RESOURCE_TABLE",
                    false,
                ),
                BundleRewriteArtifacts.EntryDecision(
                    "base/dex/classes.dex",
                    "d".repeat(64),
                    ZipEntry.STORED,
                    "base/dex/classes.dex",
                    "COPY",
                    true,
                ),
            ),
            listOf("base/resources.pb", "base/dex/classes.dex"),
            listOf(
                BundleRewriteArtifacts.ReferenceDecision(
                    "base/res/layout/screen.xml",
                    "$.element.attribute[1]",
                    "RESOURCE_REFERENCE",
                    0x7f020001,
                    "string/label",
                    "string/kb",
                ),
                BundleRewriteArtifacts.ReferenceDecision(
                    "base/res/layout/screen.xml",
                    "$.element.attribute[0]",
                    "RAW_XML_ATTRIBUTE",
                    0x7f020001,
                    "@string/label",
                    "@string/kb",
                ),
            ),
            listOf(),
        )
}
