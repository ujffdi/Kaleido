package com.tongsr.kaleido.gradle

import org.gradle.api.GradleException
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdoptionValidatorTest {
    @Test
    fun rejectsDynamicFeaturesDeterministically() {
        val failure = assertThrows(GradleException::class.java) {
            AdoptionValidator.validateDsl(
                AdoptionValidator.DslSnapshot(
                    ":app",
                    setOf(":z", ":a"),
                    emptySet(),
                    setOf("release"),
                ),
            )
        }

        assertTrue(failure.message!!.contains("KLD-TOPOLOGY-002"))
        assertTrue(failure.message!!.contains("target=[:a, :z]"))
    }

    @Test
    fun rejectsAssetPacks() {
        val failure = assertThrows(GradleException::class.java) {
            AdoptionValidator.validateDsl(
                AdoptionValidator.DslSnapshot(
                    ":app",
                    emptySet(),
                    setOf(":assets"),
                    setOf("release"),
                ),
            )
        }

        assertTrue(failure.message!!.contains("KLD-TOPOLOGY-003"))
    }

    @Test
    fun rejectsNonMinifiedReleaseVariant() {
        val failure = assertThrows(GradleException::class.java) {
            AdoptionValidator.validateVariant(
                AdoptionValidator.VariantSnapshot(
                    ":app",
                    "paidRelease",
                    "release",
                    listOf("tier=paid"),
                    false,
                ),
            )
        }

        assertTrue(failure.message!!.contains("KLD-TOPOLOGY-006"))
        assertTrue(failure.message!!.contains("variant=paidRelease"))
    }
}
