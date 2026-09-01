package com.tongsr.kaleido.gradle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.tongsr.kaleido.gradle.dsl.KaleidoProfile;
import java.util.List;
import java.util.Set;
import org.gradle.api.GradleException;
import org.junit.Test;

public final class AdoptionPlanFactoryTest {
    @Test
    public void resolvesExactSafeDefaultsAndDomainSeeds() {
        var plan = AdoptionPlanFactory.create(input(KaleidoProfile.SAFE, 4, 0, Set.of()));

        assertEquals("SafeDefaults.v1", plan.values().get("defaultsVersion"));
        assertEquals("SAFE", plan.values().get("profile"));
        assertEquals("4", plan.values().get("generation.packageCount"));
        assertEquals("4", plan.values().get("generation.classesPerPackage"));
        assertEquals("4", plan.values().get("generation.methodsPerClass"));
        assertEquals("8", plan.values().get("generation.layoutCount"));
        assertEquals("16", plan.values().get("generation.drawableCount"));
        assertEquals("32", plan.values().get("generation.stringCount"));
        assertEquals("0", plan.values().get("generation.activityCount"));
        assertEquals("false", plan.values().get("generation.compose.enabled"));
        assertTrue(plan.values().get("resources.prefix").matches("kld_[0-9a-f]{8}_"));
        assertTrue(plan.values().get("seed.domain.class-rewrite").matches("[0-9a-f]{64}"));
    }

    @Test
    public void fullProfileUnlocksButDoesNotWeakenValidation() {
        AdoptionPlanFactory.create(input(
                KaleidoProfile.FULL, 4, 1, Set.of("libobsolete.so")));

        assertThrows(GradleException.class, () -> AdoptionPlanFactory.create(input(
                KaleidoProfile.FULL, 0, 1, Set.of("libobsolete.so"))));
    }

    @Test
    public void safeProfileRejectsEverySelectedFullOnlyControl() {
        var failure = assertThrows(GradleException.class, () ->
                AdoptionPlanFactory.create(input(
                        KaleidoProfile.SAFE, 4, 1, Set.of("libobsolete.so"))));

        assertTrue(failure.getMessage().contains("KLD-CONFIG-001"));
        assertTrue(failure.getMessage().contains("target=profile"));
    }

    @Test
    public void activityGenerationRejectsUnsupportedBounds() {
        var failure = assertThrows(GradleException.class, () ->
                AdoptionPlanFactory.create(input(KaleidoProfile.FULL, 4, 65, Set.of())));

        assertTrue(failure.getMessage().contains("target=generation.activityCount"));
        assertTrue(failure.getMessage().contains("outside 0..64"));
    }

    private static AdoptionPlanFactory.Input input(
            KaleidoProfile profile,
            int packageCount,
            int activityCount,
            Set<String> nativeLibrariesToDelete) {
        return new AdoptionPlanFactory.Input(
                ":app",
                "release",
                "release",
                List.of(),
                "example.app",
                profile,
                "example.app.kaleido.generated",
                packageCount,
                4,
                4,
                8,
                16,
                32,
                activityCount,
                false,
                false,
                false,
                4,
                4,
                nativeLibrariesToDelete,
                Set.of(),
                false,
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                SeedDerivation.fingerprint("seed"));
    }
}
