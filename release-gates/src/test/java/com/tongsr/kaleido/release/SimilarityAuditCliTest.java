package com.tongsr.kaleido.release;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

public final class SimilarityAuditCliTest {
    @Test
    public void detectsSubstantialCopiedExpression() throws Exception {
        var tokens = java.util.stream.IntStream.range(0, 80).mapToObj(index -> "token" + index).toList();
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        var hex = java.util.HexFormat.of().formatHex(digest.digest(
                String.join("\u0000", tokens).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var findings = SimilarityAuditCli.compare(
                List.of(new SimilarityAuditCli.Source("candidate", tokens, hex)),
                List.of(new SimilarityAuditCli.Source("upstream", tokens, hex)));
        assertEquals(1, findings.size());
    }

    @Test
    public void allowsUnrelatedImplementations() throws Exception {
        var left = java.util.stream.IntStream.range(0, 80).mapToObj(index -> "left" + index).toList();
        var right = java.util.stream.IntStream.range(0, 80).mapToObj(index -> "right" + index).toList();
        assertEquals(List.of(), SimilarityAuditCli.compare(
                List.of(new SimilarityAuditCli.Source("candidate", left, "left")),
                List.of(new SimilarityAuditCli.Source("upstream", right, "right"))));
    }
}
