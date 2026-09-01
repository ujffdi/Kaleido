package com.tongsr.kaleido.release;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class RuntimeGateTest {
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);

    @Test
    public void recordIsCanonicalCompleteAndPinnedToBundletool() {
        var first = fixtures();
        var second = new ArrayList<>(first);
        java.util.Collections.reverse(second);
        var row = CompatibilityMatrix.requireRow("A3");
        var firstBytes = RuntimeGate.canonicalRecord(A, B, A, row, B, first);
        assertArrayEquals(firstBytes, RuntimeGate.canonicalRecord(A, B, A, row, B, second));
        var text = new String(firstBytes, StandardCharsets.UTF_8);
        assertTrue(text.contains("bundletool.version=1.18.1\n"));
        assertTrue(text.contains("fixture.native-resource.nativeLoad=PASS\n"));
        assertTrue(text.endsWith("verdict=PASS\n"));
    }

    @Test
    public void runtimeUsesTheComprehensiveSampleAndTheDedicatedFullComposeFixture() {
        assertTrue(RuntimeGate.requiredFixtures().contains("sample-comprehensive"));
        assertTrue(RuntimeGate.requiredFixtures().contains("full-compose"));
        assertFalse(RuntimeGate.requiredFixtures().contains("sample-app"));
        assertFalse(RuntimeGate.requiredFixtures().contains("sample-safe"));
        assertFalse(RuntimeGate.requiredFixtures().contains("sample-full-compose"));
    }

    @Test
    public void failureMissingFixtureAndFalseNativeEvidenceBlockTheRow() {
        var row = CompatibilityMatrix.requireRow("A4");
        var incomplete = new ArrayList<>(fixtures());
        incomplete.remove(0);
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeGate.canonicalRecord(A, B, A, row, B, incomplete));

        var wrongNative = new ArrayList<>(fixtures());
        var index = java.util.stream.IntStream.range(0, wrongNative.size())
                .filter(i -> wrongNative.get(i).name().equals("native-resource"))
                .findFirst().orElseThrow();
        wrongNative.set(index, passing("native-resource", RuntimeGate.Check.FAIL));
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeGate.canonicalRecord(A, B, A, row, B, wrongNative));
    }

    private static List<RuntimeGate.FixtureResult> fixtures() {
        return RuntimeGate.requiredFixtures().stream().sorted()
                .map(name -> passing(name, name.equals("native-resource")
                        ? RuntimeGate.Check.PASS : RuntimeGate.Check.NOT_APPLICABLE))
                .toList();
    }

    private static RuntimeGate.FixtureResult passing(
            String name, RuntimeGate.Check nativeLoad) {
        return new RuntimeGate.FixtureResult(
                name, A, B,
                RuntimeGate.Check.PASS, RuntimeGate.Check.PASS, RuntimeGate.Check.PASS,
                RuntimeGate.Check.PASS, RuntimeGate.Check.PASS, RuntimeGate.Check.PASS,
                nativeLoad, RuntimeGate.Check.PASS, CompatibilityMatrix.Result.PASS);
    }
}
