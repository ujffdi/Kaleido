package com.tongsr.kaleido.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SeedDerivationTest {
    @Test
    fun canonicallyEquivalentUnicodeHasSameFingerprint() {
        assertEquals(
            SeedDerivation.fingerprint("Caf\u00e9"),
            SeedDerivation.fingerprint("Cafe\u0301"),
        )
    }

    @Test
    fun defaultSeedIncludesApplicationAndCompleteVariantIdentity() {
        val release = SeedDerivation.defaultFingerprint("example.app", "release|release|")
        val paid = SeedDerivation.defaultFingerprint(
            "example.app",
            "paidRelease|release|tier=paid",
        )
        val otherApplication = SeedDerivation.defaultFingerprint(
            "example.other",
            "release|release|",
        )

        assertNotEquals(release, paid)
        assertNotEquals(release, otherApplication)
    }

    @Test
    fun capabilityDomainsCannotReuseTheSameStream() {
        val root = SeedDerivation.fingerprint("root")
        assertNotEquals(
            SeedDerivation.derive(root, "generation-ordinary", "release"),
            SeedDerivation.derive(root, "r8-dictionary", "release"),
        )
    }
}
