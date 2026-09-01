package com.tongsr.kaleido.gradle;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Test;

public final class PublishKaleidoReleaseTaskTest {
    @Test
    public void canonicalSetIsStableCompleteAndPathIndependent() throws Exception {
        var signed = "signed-aab".getBytes(StandardCharsets.UTF_8);
        var first = PublishKaleidoReleaseTask.assemble(
                context(), evidence("build/"), signed, signing(signed));
        var relocated = PublishKaleidoReleaseTask.assemble(
                context(), evidence("build/"), signed, signing(signed));

        assertEquals(first.setId(), relocated.setId());
        assertArrayEquals(first.files().get("release-evidence-set-manifest.properties"),
                relocated.files().get("release-evidence-set-manifest.properties"));
        assertArrayEquals(first.files().get("artifact-report.txt"),
                relocated.files().get("artifact-report.txt"));
        assertTrue(first.files().keySet().containsAll(java.util.List.of(
                "mappings/raw-kaleido-mapping.txt",
                "mappings/raw-r8-mapping.txt",
                "mappings/composed-mapping.txt",
                "mappings/resource-mapping.txt",
                "publication/signing-receipt.properties",
                "publication/compose-final-dex-receipt.properties",
                "deterministic-evidence-manifest.properties",
                "release-evidence-set-manifest.properties",
                "artifact-report.txt")));
        var report = new String(first.files().get("artifact-report.txt"),
                StandardCharsets.UTF_8);
        assertEquals(10, report.lines().filter(line -> line.contains("|PASS")).count());
        assertTrue(report.contains("proofLimitations="));
    }

    @Test
    public void missingOrMutatedStagingEvidenceFailsClosed() throws Exception {
        var signed = "signed-aab".getBytes(StandardCharsets.UTF_8);
        var missing = new TreeMap<>(evidence("build/"));
        missing.remove("build/intermediates/kaleido/release/r8/raw-r8-mapping.txt");
        var missingFailure = assertThrows(IllegalArgumentException.class, () ->
                PublishKaleidoReleaseTask.assemble(
                        context(), missing, signed, signing(signed)));
        assertTrue(missingFailure.getMessage().contains("raw-r8-mapping.txt"));

        var mutation = assertThrows(IllegalArgumentException.class, () ->
                PublishKaleidoReleaseTask.assemble(context(), evidence("build/"),
                        "mutated".getBytes(StandardCharsets.UTF_8), signing(signed)));
        assertTrue(mutation.getMessage().contains("Signed AAB digest"));

        var absolute = new TreeMap<>(evidence("build/"));
        absolute.put("/Users/person/secret.txt", "secret".getBytes(StandardCharsets.UTF_8));
        var pathFailure = assertThrows(IllegalArgumentException.class, () ->
                PublishKaleidoReleaseTask.assemble(
                        context(), absolute, signed, signing(signed)));
        assertTrue(pathFailure.getMessage().contains("not project-relative"));
    }

    private static PublishKaleidoReleaseTask.Context context() {
        return new PublishKaleidoReleaseTask.Context(":app", "release", "0.1.0-dev");
    }

    private static Map<String, byte[]> evidence(String prefix) throws Exception {
        var unsigned = "unsigned-aab".getBytes(StandardCharsets.UTF_8);
        var values = new TreeMap<String, byte[]>();
        values.put(prefix + "intermediates/kaleido/release/adoption-plan.properties", bytes("""
                schema=AdoptionPlan.v1
                project=:app
                variant.name=release
                applicationId=example.app
                profile=SAFE
                """));
        values.put(prefix + "intermediates/kaleido/release/generated-inventory.properties",
                bytes("""
                        schema=GeneratedInventory.v1
                        classes=1
                        components.activities=0
                        file=java/A.java|abc
                        """));
        values.put(prefix + "intermediates/kaleido/release/class-rewrite/"
                + "raw-kaleido-mapping.txt", bytes("example.app.A -> example.app.B\n"));
        values.put(prefix + "intermediates/kaleido/release/r8/raw-r8-mapping.txt",
                bytes("# compiler: R8\nexample.app.B -> a:\n"));
        values.put(prefix + "intermediates/kaleido/release/r8/composed-mapping.txt",
                bytes("example.app.A -> a:\n"));
        values.put(prefix + "intermediates/kaleido/release/bundle-rewrite/"
                + "resource-mapping.txt", bytes("schema=ResourceMapping.v1\n"));
        values.put(prefix + "intermediates/kaleido/release/bundle-rewrite/"
                + "unsigned-candidate.aab", unsigned);
        values.put(prefix + "intermediates/kaleido/release/bundle-rewrite/"
                + "unsigned-candidate.sha256", bytes(sha256(unsigned) + "\n"));
        values.put(prefix + "intermediates/kaleido/release/compose/"
                + "final-dex-receipt.properties", bytes("""
                        schema=ComposeFinalDexReceipt.v1
                        project=:app
                        variant=release
                        facades=0
                        functions=0
                        mappingResolved=true
                        incomingBytecodeEdges=0
                        finalDexRetained=true
                        """));
        return Map.copyOf(values);
    }

    private static byte[] signing(byte[] signed) throws Exception {
        var unsigned = "unsigned-aab".getBytes(StandardCharsets.UTF_8);
        return bytes("""
                schema=SigningReceipt.v1
                project=:app
                variant=release
                source=ENVIRONMENT
                unsignedAabSha256=%s
                signedAabSha256=%s
                certificateSha256=%s
                signatureCoverageValidated=true
                certificateMatched=true
                bundletoolValidated=true
                codeTransparencyEntries=0
                """.formatted(sha256(unsigned), sha256(signed), "a".repeat(64)));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
