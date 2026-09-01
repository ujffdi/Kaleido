package com.tongsr.kaleido.gradle

import java.nio.charset.StandardCharsets
import java.time.LocalDate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionSchemaMigrationContractTest {
    @Test
    fun semverClassifiesFixAdditiveAndBreakingPublicChanges() {
        assertEquals(
            setOf(
                "plugin-behavior",
                "adoption-dsl",
                "safe-defaults",
                "compatibility-matrix",
                "diagnostics",
                "artifact-report",
                "release-evidence-set",
                "mapping-formats",
            ),
            KaleidoVersionContract.SEMVER_PUBLIC_SURFACES,
        )
        KaleidoVersionContract.validateTransition(
            "1.2.3",
            "1.2.4",
            KaleidoVersionContract.ChangeKind.FIX,
        )
        KaleidoVersionContract.validateTransition(
            "1.2.3",
            "1.3.0",
            KaleidoVersionContract.ChangeKind.ADDITIVE,
        )
        KaleidoVersionContract.validateTransition(
            "1.2.3",
            "2.0.0",
            KaleidoVersionContract.ChangeKind.BREAKING,
        )

        val additivePatch = assertThrows(IllegalArgumentException::class.java) {
            KaleidoVersionContract.validateTransition(
                "1.2.3",
                "1.2.4",
                KaleidoVersionContract.ChangeKind.ADDITIVE,
            )
        }
        assertTrue(additivePatch.message!!.startsWith("KLD-COMPAT-001"))
        val breakingMinor = assertThrows(IllegalArgumentException::class.java) {
            KaleidoVersionContract.validateTransition(
                "1.2.3",
                "1.3.0",
                KaleidoVersionContract.ChangeKind.BREAKING,
            )
        }
        assertTrue(breakingMinor.message!!.startsWith("KLD-COMPAT-001"))
    }

    @Test
    fun bridgeReleaseRetainsBehaviorForNextMinorAndNinetyDays() {
        val valid = KaleidoVersionContract.DeprecationWindow(
            "1.2.0",
            LocalDate.of(2026, 1, 1),
            "1.3.0",
            "2.0.0",
            LocalDate.of(2026, 4, 1),
        )
        val warning = KaleidoVersionContract.bridgeDiagnostic(
            "kaleido.oldOption",
            "kaleido.newOption",
            valid,
            "replace oldOption.set(x) with newOption.set(x)",
        )
        assertTrue(warning.startsWith("KLD-DEPRECATION-001"))
        assertTrue(warning.contains("replacement=kaleido.newOption"))
        assertTrue(warning.contains("deadline=2026-04-01"))
        assertTrue(warning.contains("migration=replace oldOption.set(x)"))

        val shortWindow = KaleidoVersionContract.DeprecationWindow(
            "1.2.0",
            LocalDate.of(2026, 1, 1),
            "1.3.0",
            "2.0.0",
            LocalDate.of(2026, 3, 31),
        )
        val failure = assertThrows(IllegalArgumentException::class.java) {
            KaleidoVersionContract.validateDeprecationWindow(shortWindow)
        }
        assertTrue(failure.message!!.startsWith("KLD-DEPRECATION-001"))
        assertTrue(
            KaleidoVersionContract.migrationDiagnostic(
                "1.2.0",
                "1.3.0",
                "2.0.0",
                "clear deprecations then upgrade",
            ).startsWith("KLD-MIGRATION-001"),
        )
    }

    @Test
    fun currentReaderAcceptsOwnAndPreviousMajorGoldensWithUnknownMinorFields() {
        val current = golden("artifact-report-1.7.txt")
        val previous = golden("artifact-report-0.9.txt")

        val currentReport = ArtifactReportReader.read(current)
        val previousReport = ArtifactReportReader.read(previous)

        assertEquals(ArtifactReportReader.SchemaDialect(1, 7), currentReport.dialect)
        assertEquals("preserved", currentReport.fields["additiveFutureField"])
        assertEquals(ArtifactReportReader.SchemaDialect(0, 9), previousReport.dialect)
        assertEquals("retained", previousReport.fields["legacyField"])
    }

    @Test
    fun unsupportedMajorsAndMismatchedUrisHaveStableSchemaDiagnostics() {
        val newer = goldenUnchecked("artifact-report-1.7.txt")
            .replace("artifact-report/1.7", "artifact-report/2.0")
            .replace("schemaVersion=1.7", "schemaVersion=2.0")
        val newerFailure = assertThrows(IllegalArgumentException::class.java) {
            ArtifactReportReader.read(newer)
        }
        assertTrue(newerFailure.message!!.startsWith("KLD-SCHEMA-001"))

        val older = goldenUnchecked("artifact-report-0.9.txt")
            .replace("artifact-report/0.9", "artifact-report/0.8")
            .replace("schemaVersion=0.9", "schemaVersion=0.8")
        val olderFailure = assertThrows(IllegalArgumentException::class.java) {
            ArtifactReportReader.read(older, 2, 0)
        }
        assertTrue(olderFailure.message!!.contains("outside reader support"))

        val mismatch = goldenUnchecked("artifact-report-1.7.txt")
            .replace("artifact-report/1.7", "artifact-report/1.6")
        val mismatchFailure = assertThrows(IllegalArgumentException::class.java) {
            ArtifactReportReader.read(mismatch)
        }
        assertTrue(mismatchFailure.message!!.contains("URI and schemaVersion disagree"))
    }

    @Test
    fun derivedCurrentViewCannotOverwriteOrImpersonateHistoricalEvidence() {
        val sourceText = golden("artifact-report-0.9.txt")
        val source = ArtifactReportReader.read(sourceText)
        val before = source.sourceBytes().clone()

        val derived = ArtifactReportReader.deriveCurrent(source, "KaleidoReportConverter/1")

        assertArrayEquals(before, source.sourceBytes())
        source.sourceBytes()[0] = 'x'.code.toByte()
        assertArrayEquals(before, source.sourceBytes())
        assertTrue(
            derived.canonicalText.startsWith(
                "schemaUri=https://schemas.tongsr.com/kaleido/artifact-report/1.0\n",
            ),
        )
        assertTrue(
            derived.canonicalText.contains(
                "derivedFromReleaseEvidenceSetId=" +
                    source.fields["releaseEvidenceSetId"],
            ),
        )
        assertTrue(derived.canonicalText.contains("derivedFromSha256="))
        assertTrue(derived.canonicalText.contains("source.legacyField=retained\n"))
        assertTrue(
            derived.canonicalText.endsWith(
                "derivedViewId=" + derived.identity + "\n",
            ),
        )
        assertTrue(derived.identity != source.fields["releaseEvidenceSetId"])
    }

    private fun golden(name: String): String =
        String(
            VersionSchemaMigrationContractTest::class.java
                .getResourceAsStream("/goldens/$name")!!
                .readAllBytes(),
            StandardCharsets.UTF_8,
        )

    private fun goldenUnchecked(name: String): String = try {
        golden(name)
    } catch (failure: Exception) {
        throw AssertionError(failure)
    }
}
