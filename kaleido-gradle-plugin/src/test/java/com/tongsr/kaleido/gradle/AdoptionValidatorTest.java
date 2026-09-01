package com.tongsr.kaleido.gradle;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;
import org.gradle.api.GradleException;
import org.junit.Test;

public final class AdoptionValidatorTest {
    @Test
    public void rejectsDynamicFeaturesDeterministically() {
        var failure = assertThrows(GradleException.class, () -> AdoptionValidator.validateDsl(
                new AdoptionValidator.DslSnapshot(
                        ":app", Set.of(":z", ":a"), Set.of(), Set.of("release"))));

        assertTrue(failure.getMessage().contains("KLD-TOPOLOGY-002"));
        assertTrue(failure.getMessage().contains("target=[:a, :z]"));
    }

    @Test
    public void rejectsAssetPacks() {
        var failure = assertThrows(GradleException.class, () -> AdoptionValidator.validateDsl(
                new AdoptionValidator.DslSnapshot(
                        ":app", Set.of(), Set.of(":assets"), Set.of("release"))));

        assertTrue(failure.getMessage().contains("KLD-TOPOLOGY-003"));
    }

    @Test
    public void rejectsNonMinifiedReleaseVariant() {
        var failure = assertThrows(GradleException.class, () -> AdoptionValidator.validateVariant(
                new AdoptionValidator.VariantSnapshot(
                        ":app", "paidRelease", "release", List.of("tier=paid"), false)));

        assertTrue(failure.getMessage().contains("KLD-TOPOLOGY-006"));
        assertTrue(failure.getMessage().contains("variant=paidRelease"));
    }
}
