package com.tongsr.kaleido.gradle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.tongsr.kaleido.gradle.dsl.KaleidoProfile;
import java.util.List;
import java.util.Set;
import org.gradle.api.GradleException;
import org.junit.Test;

public final class SafeGenerationEngineTest {
    @Test
    public void safeDefaultsProduceExactOrdinaryInventoryWithoutComponents() {
        var plan = plan("seed-a");
        var content = SafeGenerationEngine.plan(plan.values(), Set.of());

        assertEquals(16, content.classCount());
        assertEquals(64, content.methodCount());
        assertEquals(8, content.layoutCount());
        assertEquals(16, content.drawableCount());
        assertEquals(32, content.stringCount());
        assertEquals(16, content.javaFiles().size());
        assertEquals(25, content.resourceFiles().size());
        assertTrue(content.javaFiles().keySet().stream().allMatch(path ->
                path.startsWith("example.app.kaleido.generated".replace('.', '/'))));
        assertTrue(content.resourceFiles().keySet().stream()
                .filter(path -> !path.equals("values/strings.xml"))
                .map(path -> path.substring(path.indexOf('/') + 1, path.length() - 4))
                .allMatch(name -> name.matches("kld_[0-9a-f]{8}_.+")));
        assertFalse(hasAndroidComponent(content.manifest()));
        assertTrue(content.keepRules().contains("example.app.kaleido.generated.**"));
    }

    @Test
    public void samePlanIsByteStableAndDifferentSeedChangesOnlyIdentities() {
        var first = SafeGenerationEngine.plan(plan("seed-a").values(), Set.of());
        var repeated = SafeGenerationEngine.plan(plan("seed-a").values(), Set.of());
        var changed = SafeGenerationEngine.plan(plan("seed-b").values(), Set.of());

        assertEquals(first, repeated);
        assertNotEquals(first.javaFiles().keySet(), changed.javaFiles().keySet());
        assertNotEquals(first.resourceFiles().keySet(), changed.resourceFiles().keySet());
        assertEquals(first.classCount(), changed.classCount());
        assertEquals(first.methodCount(), changed.methodCount());
        assertEquals(first.layoutCount(), changed.layoutCount());
        assertEquals(first.drawableCount(), changed.drawableCount());
        assertEquals(first.stringCount(), changed.stringCount());
    }

    @Test
    public void consumerResourceCollisionFailsClosedWithVariantContext() {
        var plan = plan("collision-seed");
        var initial = SafeGenerationEngine.plan(plan.values(), Set.of());
        var path = initial.resourceFiles().keySet().stream()
                .filter(candidate -> candidate.startsWith("layout/"))
                .sorted()
                .findFirst()
                .orElseThrow();
        var name = path.substring("layout/".length(), path.length() - ".xml".length());

        GradleException failure = assertThrows(GradleException.class, () ->
                SafeGenerationEngine.plan(plan.values(), Set.of(
                        new GenerateSafeContentTask.ResourceIdentity("layout", name))));

        assertTrue(failure.getMessage().contains("KLD-GENERATION-001"));
        assertTrue(failure.getMessage().contains("project=:app variant=release"));
        assertTrue(failure.getMessage().contains("target=layout/" + name));
    }

    private static boolean hasAndroidComponent(String manifest) {
        return List.of("<activity", "<service", "<receiver", "<provider", "<intent-filter")
                .stream().anyMatch(manifest::contains);
    }

    private static AdoptionPlan plan(String rawSeed) {
        return AdoptionPlanFactory.create(new AdoptionPlanFactory.Input(
                ":app",
                "release",
                "release",
                List.of(),
                "example.app",
                KaleidoProfile.SAFE,
                "example.app.kaleido.generated",
                4,
                4,
                4,
                8,
                16,
                32,
                0,
                false,
                false,
                false,
                4,
                4,
                Set.of(),
                Set.of(),
                false,
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                SeedDerivation.fingerprint(rawSeed)));
    }
}
