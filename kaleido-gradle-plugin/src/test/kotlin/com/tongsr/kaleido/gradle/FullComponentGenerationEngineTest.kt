package com.tongsr.kaleido.gradle

import com.tongsr.kaleido.gradle.dsl.KaleidoProfile
import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FullComponentGenerationEngineTest {
    @Test
    fun explicitFullProfileProducesDeterministicInertActivities() {
        val first = FullComponentGenerationEngine.plan(
            plan(KaleidoProfile.FULL, 2, "seed-a"),
            emptySet(),
            emptySet(),
        )
        val repeated = FullComponentGenerationEngine.plan(
            plan(KaleidoProfile.FULL, 2, "seed-a"),
            emptySet(),
            emptySet(),
        )
        val changed = FullComponentGenerationEngine.plan(
            plan(KaleidoProfile.FULL, 2, "seed-b"),
            emptySet(),
            emptySet(),
        )

        assertEquals(first, repeated)
        assertNotEquals(first.activities, changed.activities)
        assertEquals(2, first.activities.size)
        assertEquals(2, first.kotlinFiles.size)
        assertEquals(2, first.manifest.split("<activity ").size - 1)
        assertTrue(first.manifest.contains("android:exported=\"false\""))
        assertFalse(first.manifest.contains("<intent-filter"))
        assertFalse(first.manifest.contains("<uses-permission"))
        assertTrue(
            first.kotlinFiles.values.all { source ->
                source.contains(" : android.app.Activity()") &&
                    !source.contains("android.content.Intent") &&
                    !source.contains("android.util.Log") &&
                    !source.contains("java.net.")
            },
        )
        assertTrue(first.kotlinFiles.keys.all { it.endsWith(".kt") })
    }

    @Test
    fun zeroActivitiesRemainAbsentInSafeAndUnconfiguredFullProfiles() {
        val safe = FullComponentGenerationEngine.plan(
            plan(KaleidoProfile.SAFE, 0, "seed"),
            emptySet(),
            emptySet(),
        )
        val full = FullComponentGenerationEngine.plan(
            plan(KaleidoProfile.FULL, 0, "seed"),
            emptySet(),
            emptySet(),
        )

        assertTrue(safe.activities.isEmpty())
        assertTrue(full.activities.isEmpty())
        assertFalse(safe.manifest.contains("<activity"))
        assertFalse(full.manifest.contains("<activity"))
    }

    @Test
    fun collisionsAndContractDriftFailBeforeGeneration() {
        val plan = plan(KaleidoProfile.FULL, 1, "collision-seed")
        val initial = FullComponentGenerationEngine.plan(plan, emptySet(), emptySet())
        val identity = initial.activities[0]

        val collision = assertThrows(GradleException::class.java) {
            FullComponentGenerationEngine.plan(plan, setOf(identity), emptySet())
        }
        assertTrue(collision.message!!.contains("KLD-COMPONENT-001"))
        assertTrue(collision.message!!.contains(identity))

        val exported = FullComponentGenerationEngine.Result(
            initial.kotlinFiles,
            initial.activities,
            initial.manifest.replace("exported=\"false\"", "exported=\"true\""),
        )
        val exportedFailure = assertThrows(GradleException::class.java) {
            FullComponentGenerationEngine.validateContract(plan, exported)
        }
        assertTrue(exportedFailure.message!!.contains("inert"))

        val dangling = FullComponentGenerationEngine.Result(
            emptyMap(),
            initial.activities,
            initial.manifest,
        )
        val danglingFailure = assertThrows(GradleException::class.java) {
            FullComponentGenerationEngine.validateContract(plan, dangling)
        }
        assertTrue(danglingFailure.message!!.contains("incomplete or duplicated"))
    }

    private fun plan(profile: KaleidoProfile, activities: Int, seed: String): Map<String, String> =
        AdoptionPlanFactory.create(
            AdoptionPlanFactory.Input(
                ":app",
                "release",
                "release",
                emptyList(),
                "example.app",
                profile,
                "example.app.kaleido.generated",
                1,
                1,
                1,
                1,
                1,
                1,
                activities,
                false,
                false,
                false,
                4,
                4,
                emptySet(),
                emptySet(),
                false,
                emptySet(),
                emptySet(),
                emptySet(),
                emptySet(),
                SeedDerivation.fingerprint(seed),
            ),
        ).values
}
