package com.tongsr.kaleido.gradle;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.android.aapt.Resources;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.gradle.api.GradleException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class BundleRewriteContractTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void protobufPlanIsCanonicalAndRejectsUnknownMajor() throws Exception {
        var first = plan(BundleRewriteArtifacts.PLAN_SCHEMA);
        var reordered = new BundleRewriteArtifacts.Plan(
                first.schema(), first.producer(), first.project(), first.variant(),
                first.inputAabSha256(), first.resourceTableSha256(),
                List.of(first.resources().get(1), first.resources().get(0)),
                List.of(first.entries().get(1), first.entries().get(0)),
                List.of(first.expectedOutputs().get(1), first.expectedOutputs().get(0)),
                List.of(first.references().get(1), first.references().get(0)),
                first.controls());

        assertArrayEquals(BundleRewriteArtifacts.encodePlan(first),
                BundleRewriteArtifacts.encodePlan(reordered));
        assertEquals(first, BundleRewriteArtifacts.decodePlan(
                BundleRewriteArtifacts.encodePlan(first), ":app", "release"));

        var failure = assertThrows(GradleException.class, () ->
                BundleRewriteArtifacts.decodePlan(BundleRewriteArtifacts.encodePlan(
                        plan("BundleRewritePlan.v2")), ":app", "release"));
        assertTrue(failure.getMessage().contains("KLD-BUNDLE-001"));
        assertTrue(failure.getMessage().contains("Unknown Bundle Rewrite Plan major"));
    }

    @Test
    public void plannerRejectsDanglingFileReferenceBeforeMutation() throws Exception {
        var bundle = temporaryFolder.newFile("dangling.aab").toPath();
        var file = Resources.FileReference.newBuilder()
                .setPath("res/layout/screen.xml").build();
        var value = Resources.Value.newBuilder().setItem(
                Resources.Item.newBuilder().setFile(file)).build();
        var entry = Resources.Entry.newBuilder()
                .setEntryId(Resources.EntryId.newBuilder().setId(0))
                .setName("screen")
                .addConfigValue(Resources.ConfigValue.newBuilder().setValue(value))
                .build();
        var type = Resources.Type.newBuilder()
                .setTypeId(Resources.TypeId.newBuilder().setId(1))
                .setName("layout").addEntry(entry).build();
        var table = Resources.ResourceTable.newBuilder()
                .addPackage(Resources.Package.newBuilder()
                        .setPackageId(Resources.PackageId.newBuilder().setId(0x7f))
                        .setPackageName("example.app").addType(type))
                .build();
        try (var zip = new ZipOutputStream(Files.newOutputStream(bundle))) {
            zip.putNextEntry(new ZipEntry("base/resources.pb"));
            zip.write(table.toByteArray());
            zip.closeEntry();
        }

        var failure = assertThrows(GradleException.class, () -> BundleRewriteModule.plan(
                bundle, new BundleRewriteModule.Context(":app", "release"),
                Set.of(new BundleRewriteModule.ResourceKey("layout", "screen")),
                Set.of(), Set.of(), List.of(), "a".repeat(64),
                BundleRewriteModule.FullControls.none()));
        assertTrue(failure.getMessage().contains("dangling file reference"));
    }

    @Test
    public void sharedPhysicalPathAcrossLogicalAliasesIsNeverDeleted() throws Exception {
        var bundle = temporaryFolder.newFile("shared-alias.aab").toPath();
        var file = Resources.FileReference.newBuilder()
                .setPath("res/drawable/shared.png")
                .setType(Resources.FileReference.Type.PNG).build();
        var value = Resources.Value.newBuilder().setItem(
                Resources.Item.newBuilder().setFile(file)).build();
        var type = Resources.Type.newBuilder()
                .setTypeId(Resources.TypeId.newBuilder().setId(1)).setName("drawable");
        for (var index = 0; index < 2; index++) {
            type.addEntry(Resources.Entry.newBuilder()
                    .setEntryId(Resources.EntryId.newBuilder().setId(index))
                    .setName("alias_" + index)
                    .addConfigValue(Resources.ConfigValue.newBuilder().setValue(value)));
        }
        var table = Resources.ResourceTable.newBuilder()
                .addPackage(Resources.Package.newBuilder()
                        .setPackageId(Resources.PackageId.newBuilder().setId(0x7f))
                        .setPackageName("example.app").addType(type)).build();
        try (var zip = new ZipOutputStream(Files.newOutputStream(bundle))) {
            zip.putNextEntry(new ZipEntry("base/resources.pb"));
            zip.write(table.toByteArray());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("base/res/drawable/shared.png"));
            zip.write(new byte[] {1, 2, 3});
            zip.closeEntry();
        }

        var plan = BundleRewriteModule.plan(
                bundle, new BundleRewriteModule.Context(":app", "release"),
                Set.of(new BundleRewriteModule.ResourceKey("drawable", "alias_0"),
                        new BundleRewriteModule.ResourceKey("drawable", "alias_1")),
                Set.of(), Set.of(), List.of(), "a".repeat(64),
                BundleRewriteModule.FullControls.none());

        assertEquals(2, plan.resources().size());
        var payload = plan.entries().stream().filter(entry ->
                entry.inputPath().equals("base/res/drawable/shared.png")).findFirst().orElseThrow();
        assertEquals("base/res/drawable/shared.png", payload.outputPath());
        assertEquals("COPY", payload.action());
    }

    @Test
    public void permittedApplicationMetadataDeletionIsExplicitInPlan() throws Exception {
        var bundle = temporaryFolder.newFile("metadata-control.aab").toPath();
        var table = Resources.ResourceTable.newBuilder().build();
        try (var zip = new ZipOutputStream(Files.newOutputStream(bundle))) {
            zip.putNextEntry(new ZipEntry("base/resources.pb"));
            zip.write(table.toByteArray());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("base/root/NOTICE.txt"));
            zip.write("notice".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        var plan = BundleRewriteModule.plan(
                bundle, new BundleRewriteModule.Context(":app", "release"),
                Set.of(), Set.of(), Set.of(), List.of(), "a".repeat(64),
                new BundleRewriteModule.FullControls(
                        Set.of(), Set.of("META-INF/NOTICE.txt"), Set.of(), Set.of(),
                        Set.of(), Set.of("META-INF/NOTICE.txt")));

        assertEquals("DELETE_METADATA", plan.controls().get(0).kind());
        var metadata = plan.entries().stream().filter(entry ->
                entry.inputPath().equals("base/root/NOTICE.txt")).findFirst().orElseThrow();
        assertEquals("", metadata.outputPath());
        assertEquals("DELETE_FULL_CONTROL", metadata.action());
    }

    @Test
    public void nativeDeletionRejectsModeledLoadLibraryReferenceBeforeMutation()
            throws Exception {
        var bundle = temporaryFolder.newFile("native-reference.aab").toPath();
        try (var zip = new ZipOutputStream(Files.newOutputStream(bundle))) {
            zip.putNextEntry(new ZipEntry("base/resources.pb"));
            zip.write(Resources.ResourceTable.newBuilder().build().toByteArray());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("base/dex/classes.dex"));
            zip.write("prefix-obsolete-suffix".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("base/lib/x86_64/libobsolete.so"));
            zip.write(new byte[] {1});
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("base/lib/x86_64/libkeep.so"));
            zip.write(new byte[] {2});
            zip.closeEntry();
        }

        var failure = assertThrows(GradleException.class, () -> BundleRewriteModule.plan(
                bundle, new BundleRewriteModule.Context(":app", "release"),
                Set.of(), Set.of(), Set.of(), List.of(), "a".repeat(64),
                new BundleRewriteModule.FullControls(
                        Set.of("libobsolete.so"), Set.of(), Set.of(), Set.of(),
                        Set.of("libobsolete.so", "libkeep.so"), Set.of())));

        assertTrue(failure.getMessage().contains(
                "Native deletion intersects a modeled code loading reference"));
    }

    private static BundleRewriteArtifacts.Plan plan(String schema) {
        return new BundleRewriteArtifacts.Plan(
                schema, BundleRewriteArtifacts.PRODUCER, ":app", "release",
                "a".repeat(64), "b".repeat(64),
                List.of(
                        new BundleRewriteArtifacts.ResourceDecision(
                                0x7f010001, "example.app", "drawable", "icon", "ka",
                                "REWRITE_NAME_AND_PATH", "eligible",
                                List.of("res/drawable/icon.xml"),
                                List.of("res/drawable/ka.xml"), false, false),
                        new BundleRewriteArtifacts.ResourceDecision(
                                0x7f020001, "example.app", "string", "label", "kb",
                                "REWRITE_NAME", "eligible", List.of(), List.of(),
                                false, false)),
                List.of(
                        new BundleRewriteArtifacts.EntryDecision(
                                "base/resources.pb", "c".repeat(64), ZipEntry.DEFLATED,
                                "base/resources.pb", "REWRITE_RESOURCE_TABLE", false),
                        new BundleRewriteArtifacts.EntryDecision(
                                "base/dex/classes.dex", "d".repeat(64), ZipEntry.STORED,
                                "base/dex/classes.dex", "COPY", true)),
                List.of("base/resources.pb", "base/dex/classes.dex"),
                List.of(
                        new BundleRewriteArtifacts.ReferenceDecision(
                                "base/res/layout/screen.xml", "$.element.attribute[1]",
                                "RESOURCE_REFERENCE", 0x7f020001,
                                "string/label", "string/kb"),
                        new BundleRewriteArtifacts.ReferenceDecision(
                                "base/res/layout/screen.xml", "$.element.attribute[0]",
                                "RAW_XML_ATTRIBUTE", 0x7f020001,
                                "@string/label", "@string/kb")),
                List.of());
    }
}
