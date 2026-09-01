package com.tongsr.kaleido.gradle;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.Test;

public final class VersionSchemaMigrationContractTest {
    @Test
    public void semverClassifiesFixAdditiveAndBreakingPublicChanges() {
        assertEquals(java.util.Set.of(
                        "plugin-behavior", "adoption-dsl", "safe-defaults",
                        "compatibility-matrix", "diagnostics", "artifact-report",
                        "release-evidence-set", "mapping-formats"),
                KaleidoVersionContract.SEMVER_PUBLIC_SURFACES);
        KaleidoVersionContract.validateTransition(
                "1.2.3", "1.2.4", KaleidoVersionContract.ChangeKind.FIX);
        KaleidoVersionContract.validateTransition(
                "1.2.3", "1.3.0", KaleidoVersionContract.ChangeKind.ADDITIVE);
        KaleidoVersionContract.validateTransition(
                "1.2.3", "2.0.0", KaleidoVersionContract.ChangeKind.BREAKING);

        var additivePatch = assertThrows(IllegalArgumentException.class, () ->
                KaleidoVersionContract.validateTransition(
                        "1.2.3", "1.2.4", KaleidoVersionContract.ChangeKind.ADDITIVE));
        assertTrue(additivePatch.getMessage().startsWith("KLD-COMPAT-001"));
        var breakingMinor = assertThrows(IllegalArgumentException.class, () ->
                KaleidoVersionContract.validateTransition(
                        "1.2.3", "1.3.0", KaleidoVersionContract.ChangeKind.BREAKING));
        assertTrue(breakingMinor.getMessage().startsWith("KLD-COMPAT-001"));
    }

    @Test
    public void bridgeReleaseRetainsBehaviorForNextMinorAndNinetyDays() {
        var valid = new KaleidoVersionContract.DeprecationWindow(
                "1.2.0", LocalDate.of(2026, 1, 1), "1.3.0", "2.0.0",
                LocalDate.of(2026, 4, 1));
        var warning = KaleidoVersionContract.bridgeDiagnostic(
                "kaleido.oldOption", "kaleido.newOption", valid,
                "replace oldOption.set(x) with newOption.set(x)");
        assertTrue(warning.startsWith("KLD-DEPRECATION-001"));
        assertTrue(warning.contains("replacement=kaleido.newOption"));
        assertTrue(warning.contains("deadline=2026-04-01"));
        assertTrue(warning.contains("migration=replace oldOption.set(x)"));

        var shortWindow = new KaleidoVersionContract.DeprecationWindow(
                "1.2.0", LocalDate.of(2026, 1, 1), "1.3.0", "2.0.0",
                LocalDate.of(2026, 3, 31));
        var failure = assertThrows(IllegalArgumentException.class, () ->
                KaleidoVersionContract.validateDeprecationWindow(shortWindow));
        assertTrue(failure.getMessage().startsWith("KLD-DEPRECATION-001"));
        assertTrue(KaleidoVersionContract.migrationDiagnostic(
                "1.2.0", "1.3.0", "2.0.0", "clear deprecations then upgrade")
                .startsWith("KLD-MIGRATION-001"));
    }

    @Test
    public void currentReaderAcceptsOwnAndPreviousMajorGoldensWithUnknownMinorFields()
            throws Exception {
        var current = golden("artifact-report-1.7.txt");
        var previous = golden("artifact-report-0.9.txt");

        var currentReport = ArtifactReportReader.read(current);
        var previousReport = ArtifactReportReader.read(previous);

        assertEquals(new ArtifactReportReader.SchemaDialect(1, 7), currentReport.dialect());
        assertEquals("preserved", currentReport.fields().get("additiveFutureField"));
        assertEquals(new ArtifactReportReader.SchemaDialect(0, 9), previousReport.dialect());
        assertEquals("retained", previousReport.fields().get("legacyField"));
    }

    @Test
    public void unsupportedMajorsAndMismatchedUrisHaveStableSchemaDiagnostics() {
        var newer = goldenUnchecked("artifact-report-1.7.txt")
                .replace("artifact-report/1.7", "artifact-report/2.0")
                .replace("schemaVersion=1.7", "schemaVersion=2.0");
        var newerFailure = assertThrows(IllegalArgumentException.class, () ->
                ArtifactReportReader.read(newer));
        assertTrue(newerFailure.getMessage().startsWith("KLD-SCHEMA-001"));

        var older = goldenUnchecked("artifact-report-0.9.txt")
                .replace("artifact-report/0.9", "artifact-report/0.8")
                .replace("schemaVersion=0.9", "schemaVersion=0.8");
        var olderFailure = assertThrows(IllegalArgumentException.class, () ->
                ArtifactReportReader.read(older, 2, 0));
        assertTrue(olderFailure.getMessage().contains("outside reader support"));

        var mismatch = goldenUnchecked("artifact-report-1.7.txt")
                .replace("artifact-report/1.7", "artifact-report/1.6");
        var mismatchFailure = assertThrows(IllegalArgumentException.class, () ->
                ArtifactReportReader.read(mismatch));
        assertTrue(mismatchFailure.getMessage().contains("URI and schemaVersion disagree"));
    }

    @Test
    public void derivedCurrentViewCannotOverwriteOrImpersonateHistoricalEvidence()
            throws Exception {
        var sourceText = golden("artifact-report-0.9.txt");
        var source = ArtifactReportReader.read(sourceText);
        var before = source.sourceBytes().clone();

        var derived = ArtifactReportReader.deriveCurrent(source, "KaleidoReportConverter/1");

        assertArrayEquals(before, source.sourceBytes());
        source.sourceBytes()[0] = 'x';
        assertArrayEquals(before, source.sourceBytes());
        assertTrue(derived.canonicalText().startsWith(
                "schemaUri=https://schemas.tongsr.com/kaleido/artifact-report/1.0\n"));
        assertTrue(derived.canonicalText().contains(
                "derivedFromReleaseEvidenceSetId="
                        + source.fields().get("releaseEvidenceSetId")));
        assertTrue(derived.canonicalText().contains("derivedFromSha256="));
        assertTrue(derived.canonicalText().contains("source.legacyField=retained\n"));
        assertTrue(derived.canonicalText().endsWith(
                "derivedViewId=" + derived.identity() + "\n"));
        assertTrue(!derived.identity().equals(source.fields().get("releaseEvidenceSetId")));
    }

    private static String golden(String name) throws Exception {
        return new String(VersionSchemaMigrationContractTest.class.getResourceAsStream(
                "/goldens/" + name).readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String goldenUnchecked(String name) {
        try {
            return golden(name);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
