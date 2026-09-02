package com.tongsr.kaleido.release

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeGateTest {
    @Test
    fun recordIsCanonicalCompleteAndPinnedToBundletool() {
        val first = fixtures()
        val second = ArrayList(first)
        second.reverse()
        val row = CompatibilityMatrix.requireRow("A3")
        val firstBytes = RuntimeGate.canonicalRecord(A, B, A, row, B, first)
        assertArrayEquals(firstBytes, RuntimeGate.canonicalRecord(A, B, A, row, B, second))
        val text = String(firstBytes, StandardCharsets.UTF_8)
        assertTrue(text.contains("bundletool.version=1.18.1\n"))
        assertTrue(text.contains("fixture.native-resource.nativeLoad=PASS\n"))
        assertTrue(text.endsWith("verdict=PASS\n"))
    }

    @Test
    fun runtimeUsesTheComprehensiveSampleAndTheDedicatedFullComposeFixture() {
        assertTrue(RuntimeGate.requiredFixtures().contains("sample-comprehensive"))
        assertTrue(RuntimeGate.requiredFixtures().contains("full-compose"))
        assertFalse(RuntimeGate.requiredFixtures().contains("sample-app"))
        assertFalse(RuntimeGate.requiredFixtures().contains("sample-safe"))
        assertFalse(RuntimeGate.requiredFixtures().contains("sample-full-compose"))
    }

    @Test
    fun failureMissingFixtureAndFalseNativeEvidenceBlockTheRow() {
        val row = CompatibilityMatrix.requireRow("A4")
        val incomplete = ArrayList(fixtures())
        incomplete.removeAt(0)
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeGate.canonicalRecord(A, B, A, row, B, incomplete)
        }

        val wrongNative = ArrayList(fixtures())
        val index = wrongNative.indices.first { wrongNative[it].name == "native-resource" }
        wrongNative[index] = passing("native-resource", RuntimeGate.Check.FAIL)
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeGate.canonicalRecord(A, B, A, row, B, wrongNative)
        }
    }

    companion object {
        private val A = "a".repeat(64)
        private val B = "b".repeat(64)

        private fun fixtures(): List<RuntimeGate.FixtureResult> =
            RuntimeGate.requiredFixtures().sorted().map { name ->
                passing(
                    name,
                    if (name == "native-resource") RuntimeGate.Check.PASS else RuntimeGate.Check.NOT_APPLICABLE,
                )
            }

        private fun passing(name: String, nativeLoad: RuntimeGate.Check): RuntimeGate.FixtureResult =
            RuntimeGate.FixtureResult(
                name,
                A,
                B,
                RuntimeGate.Check.PASS,
                RuntimeGate.Check.PASS,
                RuntimeGate.Check.PASS,
                RuntimeGate.Check.PASS,
                RuntimeGate.Check.PASS,
                RuntimeGate.Check.PASS,
                nativeLoad,
                RuntimeGate.Check.PASS,
                CompatibilityMatrix.Result.PASS,
            )
    }
}
