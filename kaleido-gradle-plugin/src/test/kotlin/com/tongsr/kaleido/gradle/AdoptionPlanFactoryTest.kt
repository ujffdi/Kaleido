package com.tongsr.kaleido.gradle

import com.tongsr.kaleido.gradle.dsl.KaleidoProfile
import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdoptionPlanFactoryTest {
    @Test
    fun resolvesExactSafeDefaultsAndDomainSeeds() {
        val plan = AdoptionPlanFactory.create(input(KaleidoProfile.SAFE, 4, 0, emptySet()))

        assertEquals("SafeDefaults.v1", plan.values["defaultsVersion"])
        assertEquals("SAFE", plan.values["profile"])
        assertEquals("4", plan.values["generation.packageCount"])
        assertEquals("4", plan.values["generation.classesPerPackage"])
        assertEquals("4", plan.values["generation.methodsPerClass"])
        assertEquals("8", plan.values["generation.layoutCount"])
        assertEquals("16", plan.values["generation.drawableCount"])
        assertEquals("32", plan.values["generation.stringCount"])
        assertEquals("0", plan.values["generation.activityCount"])
        assertEquals("false", plan.values["generation.compose.enabled"])
        assertTrue(plan.values["resources.prefix"]!!.matches(Regex("kld_[0-9a-f]{8}_")))
        assertTrue(plan.values["seed.domain.class-rewrite"]!!.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun fullProfileUnlocksButDoesNotWeakenValidation() {
        AdoptionPlanFactory.create(input(KaleidoProfile.FULL, 4, 1, setOf("libobsolete.so")))

        assertThrows(GradleException::class.java) {
            AdoptionPlanFactory.create(input(KaleidoProfile.FULL, 0, 1, setOf("libobsolete.so")))
        }
    }

    @Test
    fun safeProfileRejectsEverySelectedFullOnlyControl() {
        val failure = assertThrows(GradleException::class.java) {
            AdoptionPlanFactory.create(input(KaleidoProfile.SAFE, 4, 1, setOf("libobsolete.so")))
        }

        assertTrue(failure.message!!.contains("KLD-CONFIG-001"))
        assertTrue(failure.message!!.contains("target=profile"))
    }

    @Test
    fun activityGenerationRejectsUnsupportedBounds() {
        val failure = assertThrows(GradleException::class.java) {
            AdoptionPlanFactory.create(input(KaleidoProfile.FULL, 4, 65, emptySet()))
        }

        assertTrue(failure.message!!.contains("target=generation.activityCount"))
        assertTrue(failure.message!!.contains("outside 0..64"))
    }

    private fun input(
        profile: KaleidoProfile,
        packageCount: Int,
        activityCount: Int,
        nativeLibrariesToDelete: Set<String>,
    ): AdoptionPlanFactory.Input = AdoptionPlanFactory.Input(
        ":app",
        "release",
        "release",
        emptyList(),
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
        emptySet(),
        false,
        emptySet(),
        emptySet(),
        emptySet(),
        emptySet(),
        SeedDerivation.fingerprint("seed"),
    )
}
