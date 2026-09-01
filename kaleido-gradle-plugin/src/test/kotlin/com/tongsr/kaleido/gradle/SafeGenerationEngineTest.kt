package com.tongsr.kaleido.gradle

import com.tongsr.kaleido.gradle.dsl.KaleidoProfile
import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeGenerationEngineTest {
    @Test
    fun safeDefaultsProduceExactOrdinaryInventoryWithoutComponents() {
        val plan = plan("seed-a")
        val content = SafeGenerationEngine.plan(plan.values, emptySet())

        assertEquals(16, content.classCount)
        assertEquals(64, content.methodCount)
        assertEquals(8, content.layoutCount)
        assertEquals(16, content.drawableCount)
        assertEquals(32, content.stringCount)
        assertEquals(16, content.kotlinFiles.size)
        assertEquals(25, content.resourceFiles.size)
        assertTrue(content.kotlinFiles.keys.all { path ->
            path.startsWith("example.app.kaleido.generated".replace('.', '/')) &&
                path.endsWith(".kt")
        })
        assertTrue(
            content.kotlinFiles.values.none { source ->
                source.contains("companion object") ||
                    source.contains("data class") ||
                    source.contains("internal object") ||
                    source.contains("internal fun ")
            },
        )
        assertTrue(content.kotlinFiles.values.all { "0x" in it && ".toInt()" in it })
        assertTrue(
            content.resourceFiles.keys
                .filter { it != "values/strings.xml" }
                .map { it.substring(it.indexOf('/') + 1, it.length - 4) }
                .all { it.matches(Regex("kld_[0-9a-f]{8}_.+")) },
        )
        assertFalse(hasAndroidComponent(content.manifest))
        assertTrue(content.keepRules.contains("example.app.kaleido.generated.**"))
    }

    @Test
    fun samePlanIsByteStableAndDifferentSeedChangesOnlyIdentities() {
        val first = SafeGenerationEngine.plan(plan("seed-a").values, emptySet())
        val repeated = SafeGenerationEngine.plan(plan("seed-a").values, emptySet())
        val changed = SafeGenerationEngine.plan(plan("seed-b").values, emptySet())

        assertEquals(first, repeated)
        assertNotEquals(first.kotlinFiles.keys, changed.kotlinFiles.keys)
        assertNotEquals(first.resourceFiles.keys, changed.resourceFiles.keys)
        assertEquals(first.classCount, changed.classCount)
        assertEquals(first.methodCount, changed.methodCount)
        assertEquals(first.layoutCount, changed.layoutCount)
        assertEquals(first.drawableCount, changed.drawableCount)
        assertEquals(first.stringCount, changed.stringCount)
    }

    @Test
    fun consumerResourceCollisionFailsClosedWithVariantContext() {
        val plan = plan("collision-seed")
        val initial = SafeGenerationEngine.plan(plan.values, emptySet())
        val path = initial.resourceFiles.keys
            .filter { it.startsWith("layout/") }
            .sorted()
            .first()
        val name = path.substring("layout/".length, path.length - ".xml".length)

        val failure = assertThrows(GradleException::class.java) {
            SafeGenerationEngine.plan(
                plan.values,
                setOf(GenerateSafeContentTask.ResourceIdentity("layout", name)),
            )
        }

        assertTrue(failure.message!!.contains("KLD-GENERATION-001"))
        assertTrue(failure.message!!.contains("project=:app variant=release"))
        assertTrue(failure.message!!.contains("target=layout/$name"))
    }

    private fun hasAndroidComponent(manifest: String): Boolean =
        listOf("<activity", "<service", "<receiver", "<provider", "<intent-filter")
            .any { it in manifest }

    private fun plan(rawSeed: String): AdoptionPlan =
        AdoptionPlanFactory.create(
            AdoptionPlanFactory.Input(
                ":app",
                "release",
                "release",
                emptyList(),
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
                emptySet(),
                emptySet(),
                false,
                emptySet(),
                emptySet(),
                emptySet(),
                emptySet(),
                SeedDerivation.fingerprint(rawSeed),
            ),
        )
}
