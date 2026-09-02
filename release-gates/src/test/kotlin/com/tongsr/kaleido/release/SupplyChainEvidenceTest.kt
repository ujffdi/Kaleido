package com.tongsr.kaleido.release

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplyChainEvidenceTest {
    @Test
    fun cycloneDx17IsDeterministicCompleteAndDigestBound() {
        val components = listOf(
            SupplyChainEvidenceCli.Component(
                "org.ow2.asm",
                "asm",
                "9.10.1",
                "a".repeat(64),
                "BSD-3-Clause",
            ),
            SupplyChainEvidenceCli.Component(
                "com.android.tools.build",
                "bundletool",
                "1.18.1",
                "b".repeat(64),
                "Apache-2.0",
            ),
        )
        val sources = Path.of(System.getProperty("kaleido.repository.root"), "LICENSE")
        val marker = Path.of(System.getProperty("kaleido.repository.root"), "NOTICE")
        val first = SupplyChainEvidenceCli.sbom(
            "1.0.0",
            "c".repeat(64),
            sources,
            marker,
            components,
        )
        val second = SupplyChainEvidenceCli.sbom(
            "1.0.0",
            "c".repeat(64),
            sources,
            marker,
            components,
        )
        assertArrayEquals(first, second)
        val text = String(first, StandardCharsets.UTF_8)
        assertTrue(text.contains("\"specVersion\": \"1.7\""))
        assertTrue(text.contains("\"aggregate\": \"complete\""))
        assertTrue(text.contains("pkg:maven/io.github.ujffdi/kaleido-gradle-plugin@1.0.0"))
        assertTrue(text.contains("pkg:maven/com.android.tools.build/bundletool@1.18.1"))
        assertTrue(text.contains("\"id\": \"Apache-2.0\""))
        assertTrue(text.endsWith("}\n"))
    }
}
