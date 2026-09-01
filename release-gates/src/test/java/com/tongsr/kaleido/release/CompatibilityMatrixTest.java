package com.tongsr.kaleido.release;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class CompatibilityMatrixTest {
    private static final String DIGEST_A = "a".repeat(64);
    private static final String DIGEST_B = "b".repeat(64);

    @Test
    public void mandatoryRowsAreExactAndImmutable() {
        assertEquals(new CompatibilityMatrix.Row(
                "A3", "9.2.1", "9.4.1", "linux", "x86_64",
                17, "36.0.0", 36, "built-in"),
                CompatibilityMatrix.requireRow("A3"));
        assertEquals(new CompatibilityMatrix.Row(
                "A4", "9.3.2", "9.5.0", "linux", "x86_64",
                17, "36.0.0", 36, "built-in"),
                CompatibilityMatrix.requireRow("A4"));
        assertThrows(UnsupportedOperationException.class,
                () -> CompatibilityMatrix.mandatoryRows().clear());
    }

    @Test
    public void canonicalRecordIsOrderIndependentAndBindsEveryFixture() {
        var fixtures = fixtures();
        var reversed = new ArrayList<>(fixtures);
        java.util.Collections.reverse(reversed);
        var row = CompatibilityMatrix.requireRow("A3");
        var first = CompatibilityMatrix.canonicalRecord(DIGEST_A, row, fixtures);
        var second = CompatibilityMatrix.canonicalRecord(DIGEST_A, row, reversed);
        assertArrayEquals(first, second);
        var text = new String(first, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("schema=KaleidoCompatibilityMatrix.v1\n"));
        assertTrue(text.contains("row.agp=9.2.1\n"));
        assertTrue(text.contains("row.os=linux\nrow.arch=x86_64\n"));
        assertTrue(text.endsWith("verdict=PASS\n"));
    }

    @Test
    public void mismatchedEnvironmentIncompleteClosureAndFailureAreRejected() {
        var expected = CompatibilityMatrix.requireRow("A4");
        var wrongHost = new CompatibilityMatrix.Row(
                expected.id(), expected.agp(), expected.gradle(), "macos", "arm64",
                expected.jdk(), expected.buildTools(), expected.compileSdk(),
                expected.kotlinMode());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> CompatibilityMatrix.canonicalRecord(DIGEST_A, wrongHost, fixtures()))
                .getMessage().contains("KLD-COMPAT-001"));

        var incomplete = new ArrayList<>(fixtures());
        incomplete.remove(0);
        assertThrows(IllegalArgumentException.class,
                () -> CompatibilityMatrix.canonicalRecord(DIGEST_A, expected, incomplete));

        var failed = new ArrayList<>(fixtures());
        failed.set(0, new CompatibilityMatrix.FixtureResult(
                failed.get(0).name(), DIGEST_A, failed.get(0).aabDigest(),
                CompatibilityMatrix.Result.FAIL));
        assertThrows(IllegalArgumentException.class,
                () -> CompatibilityMatrix.canonicalRecord(DIGEST_A, expected, failed));
    }

    private static List<CompatibilityMatrix.FixtureResult> fixtures() {
        return CompatibilityMatrix.requiredFixtures().stream().sorted()
                .map(name -> new CompatibilityMatrix.FixtureResult(
                        name, DIGEST_A,
                        name.equals("exhaustive-boundary") ? "" : DIGEST_B,
                        CompatibilityMatrix.Result.PASS))
                .toList();
    }
}
