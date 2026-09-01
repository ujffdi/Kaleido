package com.tongsr.kaleido.gradle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.tongsr.kaleido.gradle.dsl.KaleidoProfile;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.gradle.api.GradleException;
import org.junit.Test;

public final class FullComponentGenerationEngineTest {
    @Test
    public void explicitFullProfileProducesDeterministicInertActivities() {
        var first = FullComponentGenerationEngine.plan(
                plan(KaleidoProfile.FULL, 2, "seed-a"), Set.of(), Set.of());
        var repeated = FullComponentGenerationEngine.plan(
                plan(KaleidoProfile.FULL, 2, "seed-a"), Set.of(), Set.of());
        var changed = FullComponentGenerationEngine.plan(
                plan(KaleidoProfile.FULL, 2, "seed-b"), Set.of(), Set.of());

        assertEquals(first, repeated);
        assertNotEquals(first.activities(), changed.activities());
        assertEquals(2, first.activities().size());
        assertEquals(2, first.javaFiles().size());
        assertEquals(2, first.manifest().split("<activity ", -1).length - 1);
        assertTrue(first.manifest().contains("android:exported=\"false\""));
        assertFalse(first.manifest().contains("<intent-filter"));
        assertFalse(first.manifest().contains("<uses-permission"));
        assertTrue(first.javaFiles().values().stream().allMatch(source ->
                source.contains("extends android.app.Activity")
                        && !source.contains("android.content.Intent")
                        && !source.contains("android.util.Log")
                        && !source.contains("java.net.")));
    }

    @Test
    public void zeroActivitiesRemainAbsentInSafeAndUnconfiguredFullProfiles() {
        var safe = FullComponentGenerationEngine.plan(
                plan(KaleidoProfile.SAFE, 0, "seed"), Set.of(), Set.of());
        var full = FullComponentGenerationEngine.plan(
                plan(KaleidoProfile.FULL, 0, "seed"), Set.of(), Set.of());

        assertTrue(safe.activities().isEmpty());
        assertTrue(full.activities().isEmpty());
        assertFalse(safe.manifest().contains("<activity"));
        assertFalse(full.manifest().contains("<activity"));
    }

    @Test
    public void collisionsAndContractDriftFailBeforeGeneration() {
        var plan = plan(KaleidoProfile.FULL, 1, "collision-seed");
        var initial = FullComponentGenerationEngine.plan(plan, Set.of(), Set.of());
        var identity = initial.activities().get(0);

        var collision = assertThrows(GradleException.class, () ->
                FullComponentGenerationEngine.plan(plan, Set.of(identity), Set.of()));
        assertTrue(collision.getMessage().contains("KLD-COMPONENT-001"));
        assertTrue(collision.getMessage().contains(identity));

        var exported = new FullComponentGenerationEngine.Result(
                initial.javaFiles(), initial.activities(),
                initial.manifest().replace("exported=\"false\"", "exported=\"true\""));
        var exportedFailure = assertThrows(GradleException.class, () ->
                FullComponentGenerationEngine.validateContract(plan, exported));
        assertTrue(exportedFailure.getMessage().contains("inert"));

        var dangling = new FullComponentGenerationEngine.Result(
                Map.of(), initial.activities(), initial.manifest());
        var danglingFailure = assertThrows(GradleException.class, () ->
                FullComponentGenerationEngine.validateContract(plan, dangling));
        assertTrue(danglingFailure.getMessage().contains("incomplete or duplicated"));
    }

    private static Map<String, String> plan(
            KaleidoProfile profile, int activities, String seed) {
        return AdoptionPlanFactory.create(new AdoptionPlanFactory.Input(
                ":app", "release", "release", List.of(), "example.app", profile,
                "example.app.kaleido.generated", 1, 1, 1, 1, 1, 1, activities,
                false, false, false, 4, 4, Set.of(), Set.of(), false, Set.of(),
                Set.of(), Set.of(), Set.of(), SeedDerivation.fingerprint(seed))).values();
    }
}
