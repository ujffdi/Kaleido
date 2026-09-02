package com.tongsr.kaleido.release

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityMatrixTest {
    @Test
    fun mandatoryRowsAreExactAndImmutable() {
        assertEquals(
            CompatibilityMatrix.Row(
                "A3", "9.2.0", "9.4.1", "linux", "x86_64",
                17, "36.0.0", 36, "built-in",
            ),
            CompatibilityMatrix.requireRow("A3"),
        )
        assertEquals(
            CompatibilityMatrix.Row(
                "A4", "9.3.2", "9.5.0", "linux", "x86_64",
                17, "36.0.0", 36, "built-in",
            ),
            CompatibilityMatrix.requireRow("A4"),
        )
        assertThrows(UnsupportedOperationException::class.java) {
            (CompatibilityMatrix.mandatoryRows() as MutableMap<String, CompatibilityMatrix.Row>).clear()
        }
    }

    @Test
    fun mandatoryFixtureClosureIncludesTheComprehensiveSample() {
        assertEquals(
            setOf(
                "exhaustive-boundary",
                "full-compose",
                "java-safe",
                "kotlin-safe",
                "sample-comprehensive",
                "sana-reference",
            ),
            CompatibilityMatrix.requiredFixtures(),
        )
    }

    @Test
    fun canonicalRecordIsOrderIndependentAndBindsEveryFixture() {
        val fixtures = fixtures()
        val reversed = ArrayList(fixtures)
        reversed.reverse()
        val row = CompatibilityMatrix.requireRow("A3")
        val first = CompatibilityMatrix.canonicalRecord(DIGEST_A, row, fixtures)
        val second = CompatibilityMatrix.canonicalRecord(DIGEST_A, row, reversed)
        assertArrayEquals(first, second)
        val text = String(first, StandardCharsets.UTF_8)
        assertTrue(text.startsWith("schema=KaleidoCompatibilityMatrix.v1\n"))
        assertTrue(text.contains("row.agp=9.2.0\n"))
        assertTrue(text.contains("row.os=linux\nrow.arch=x86_64\n"))
        assertTrue(text.endsWith("verdict=PASS\n"))
    }

    @Test
    fun mismatchedEnvironmentIncompleteClosureAndFailureAreRejected() {
        val expected = CompatibilityMatrix.requireRow("A4")
        val wrongHost = CompatibilityMatrix.Row(
            expected.id,
            expected.agp,
            expected.gradle,
            "macos",
            "arm64",
            expected.jdk,
            expected.buildTools,
            expected.compileSdk,
            expected.kotlinMode,
        )
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) {
                CompatibilityMatrix.canonicalRecord(DIGEST_A, wrongHost, fixtures())
            }.message!!.contains("KLD-COMPAT-001"),
        )

        val incomplete = ArrayList(fixtures())
        incomplete.removeAt(0)
        assertThrows(IllegalArgumentException::class.java) {
            CompatibilityMatrix.canonicalRecord(DIGEST_A, expected, incomplete)
        }

        val failed = ArrayList(fixtures())
        failed[0] = CompatibilityMatrix.FixtureResult(
            failed[0].name,
            DIGEST_A,
            failed[0].aabDigest,
            CompatibilityMatrix.Result.FAIL,
        )
        assertThrows(IllegalArgumentException::class.java) {
            CompatibilityMatrix.canonicalRecord(DIGEST_A, expected, failed)
        }
    }

    companion object {
        private val DIGEST_A = "a".repeat(64)
        private val DIGEST_B = "b".repeat(64)

        private fun fixtures(): List<CompatibilityMatrix.FixtureResult> =
            CompatibilityMatrix.requiredFixtures().sorted().map { name ->
                CompatibilityMatrix.FixtureResult(
                    name,
                    DIGEST_A,
                    if (name == "exhaustive-boundary") "" else DIGEST_B,
                    CompatibilityMatrix.Result.PASS,
                )
            }
    }
}
