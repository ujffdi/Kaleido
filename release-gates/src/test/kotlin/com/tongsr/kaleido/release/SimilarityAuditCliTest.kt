package com.tongsr.kaleido.release

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class SimilarityAuditCliTest {
    @Test
    fun detectsSubstantialCopiedExpression() {
        val tokens = (0 until 80).map { index -> "token$index" }
        val digest = MessageDigest.getInstance("SHA-256")
        val hex = HexFormat.of().formatHex(
            digest.digest(tokens.joinToString("\u0000").toByteArray(StandardCharsets.UTF_8)),
        )
        val findings = SimilarityAuditCli.compare(
            listOf(SimilarityAuditCli.Source("candidate", tokens, hex)),
            listOf(SimilarityAuditCli.Source("upstream", tokens, hex)),
        )
        assertEquals(1, findings.size)
    }

    @Test
    fun allowsUnrelatedImplementations() {
        val left = (0 until 80).map { index -> "left$index" }
        val right = (0 until 80).map { index -> "right$index" }
        assertEquals(
            emptyList<SimilarityAuditCli.Finding>(),
            SimilarityAuditCli.compare(
                listOf(SimilarityAuditCli.Source("candidate", left, "left")),
                listOf(SimilarityAuditCli.Source("upstream", right, "right")),
            ),
        )
    }
}
