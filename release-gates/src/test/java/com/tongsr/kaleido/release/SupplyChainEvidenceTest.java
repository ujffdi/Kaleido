package com.tongsr.kaleido.release;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

public final class SupplyChainEvidenceTest {
    @Test
    public void cycloneDx17IsDeterministicCompleteAndDigestBound() throws Exception {
        var components = List.of(
                new SupplyChainEvidenceCli.Component(
                        "org.ow2.asm", "asm", "9.10.1", "a".repeat(64), "BSD-3-Clause"),
                new SupplyChainEvidenceCli.Component(
                        "com.android.tools.build", "bundletool", "1.18.1",
                        "b".repeat(64), "Apache-2.0"));
        var sources = Path.of(System.getProperty("kaleido.repository.root"), "LICENSE");
        var marker = Path.of(System.getProperty("kaleido.repository.root"), "NOTICE");
        var first = SupplyChainEvidenceCli.sbom(
                "1.0.0", "c".repeat(64), sources, marker, components);
        var second = SupplyChainEvidenceCli.sbom(
                "1.0.0", "c".repeat(64), sources, marker, components);
        assertArrayEquals(first, second);
        var text = new String(first, StandardCharsets.UTF_8);
        assertTrue(text.contains("\"specVersion\": \"1.7\""));
        assertTrue(text.contains("\"aggregate\": \"complete\""));
        assertTrue(text.contains("pkg:maven/com.android.tools.build/bundletool@1.18.1"));
        assertTrue(text.contains("\"id\": \"Apache-2.0\""));
        assertTrue(text.endsWith("}\n"));
    }
}
