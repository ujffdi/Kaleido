package com.tongsr.kaleido.gradle;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.gradle.api.GradleException;
import org.junit.Test;

public final class ClassRewriteContractTest {
    @Test(timeout = 10000)
    public void adversarialLargeInventoryAndPrefixCollisionsRemainBounded() {
        var roots = new java.util.LinkedHashSet<String>();
        var reserved = new java.util.LinkedHashSet<String>();
        var stream = "complexity-probe";
        for (int index = 0; index < 10_000; index++) {
            var name = "com.example.large.C" + index;
            roots.add(name);
            reserved.add(name);
            var digest = SeedDerivation.derive(stream, "class-identity", name);
            reserved.add("com.example.large.k" + digest.substring(0, 6)
                    + ".C" + digest.substring(0, 10));
        }
        var mapping = RewriteClassesAndManifestTask.allocateMapping(
                roots, java.util.Set.of(), reserved, stream);
        assertEquals(roots.size(), mapping.size());
        assertEquals(mapping.size(), new java.util.HashSet<>(mapping.values()).size());
    }

    @Test
    public void protobufPlanIsCanonicalAndRejectsUnknownMajor() throws Exception {
        var first = plan(ClassRewriteArtifacts.PLAN_SCHEMA);
        var reordered = new ClassRewriteArtifacts.Plan(
                first.schema(), first.producer(), first.project(), first.variant(),
                first.adoptionPlanSha256(), first.manifestSha256(),
                List.of(first.inputs().get(1), first.inputs().get(0)),
                List.of(first.decisions().get(1), first.decisions().get(0)),
                List.of(first.manifestSites().get(1), first.manifestSites().get(0)),
                List.of(first.expectedOutputs().get(1), first.expectedOutputs().get(0)));

        assertArrayEquals(ClassRewriteArtifacts.encodePlan(first),
                ClassRewriteArtifacts.encodePlan(reordered));
        var decoded = ClassRewriteArtifacts.decodePlan(
                ClassRewriteArtifacts.encodePlan(first), ":app", "release");
        assertEquals(first, decoded);

        var failure = assertThrows(GradleException.class, () ->
                ClassRewriteArtifacts.decodePlan(
                        ClassRewriteArtifacts.encodePlan(plan("ClassRewritePlan.v2")),
                        ":app", "release"));
        assertTrue(failure.getMessage().contains("KLD-CLASS-001"));
        assertTrue(failure.getMessage().contains("Unknown Class Rewrite Plan major"));
    }

    @Test
    public void identityAllocationIsStableProtectedAndCollisionExtending() {
        var roots = Set.of("example.app.MainActivity", "example.app.MainActivity$Nested");
        var stream = SeedDerivation.fingerprint("class-stream");
        var first = RewriteClassesAndManifestTask.allocateMapping(
                roots, Set.of(), roots, stream);
        var repeated = RewriteClassesAndManifestTask.allocateMapping(
                roots, Set.of(), roots, stream);
        assertEquals(first, repeated);
        assertTrue(first.get("example.app.MainActivity$Nested")
                .startsWith(first.get("example.app.MainActivity") + "$C"));

        var reserved = new java.util.HashSet<>(roots);
        reserved.add(first.get("example.app.MainActivity"));
        var extended = RewriteClassesAndManifestTask.allocateMapping(
                roots, Set.of(), reserved, stream);
        assertNotEquals(first.get("example.app.MainActivity"),
                extended.get("example.app.MainActivity"));
        assertFalse(RewriteClassesAndManifestTask.allocateMapping(
                roots, Set.of("example.app.MainActivity"), roots, stream)
                .containsKey("example.app.MainActivity"));
    }

    @Test
    public void manifestRegistryRewritesClassSitesButNotAliasIdentity() {
        var xml = """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="example.app">
                    <application android:name=".App">
                        <activity android:name=".MainActivity" />
                        <activity-alias android:name=".StableAlias"
                            android:targetActivity=".MainActivity" />
                        <meta-data android:name="example.app.MainActivity"
                            android:value="example.app.MainActivity" />
                    </application>
                </manifest>
                """;
        var document = RewriteClassesAndManifestTask.parseManifest(
                xml.getBytes(StandardCharsets.UTF_8), ":app", "release");
        var references = RewriteClassesAndManifestTask.manifestReferences(
                document, "example.app");

        assertTrue(references.stream().anyMatch(reference ->
                reference.location().contains("activity[0]@android:name")));
        assertTrue(references.stream().anyMatch(reference ->
                reference.location().contains("activity-alias[0]@android:targetActivity")));
        assertFalse(references.stream().anyMatch(reference ->
                reference.location().contains("activity-alias[0]@android:name")));
        assertFalse(references.stream().anyMatch(reference ->
                reference.location().contains("meta-data")));
    }

    private static ClassRewriteArtifacts.Plan plan(String schema) {
        return new ClassRewriteArtifacts.Plan(
                schema,
                ClassRewriteArtifacts.PRODUCER,
                ":app",
                "release",
                "a".repeat(64),
                "b".repeat(64),
                List.of(
                        new ClassRewriteArtifacts.InputArtifact("directory/b", "d".repeat(64)),
                        new ClassRewriteArtifacts.InputArtifact("directory/a", "c".repeat(64))),
                List.of(
                        new ClassRewriteArtifacts.ClassDecision("example.B", "directory/b!B.class",
                                "f".repeat(64), "UNTOUCHED", "example.B", "outside"),
                        new ClassRewriteArtifacts.ClassDecision("example.A", "directory/a!A.class",
                                "e".repeat(64), "REWRITE", "example.k.Ca", "root")),
                List.of(
                        new ClassRewriteArtifacts.ManifestSite("manifest/activity[1]@android:name",
                                ".B", ".k.Cb"),
                        new ClassRewriteArtifacts.ManifestSite("manifest/activity[0]@android:name",
                                ".A", ".k.Ca")),
                List.of("example.k.Cb", "example.k.Ca"));
    }
}
