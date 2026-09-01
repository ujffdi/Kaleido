package com.tongsr.kaleido.gradle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public final class SeedDerivationTest {
    @Test
    public void canonicallyEquivalentUnicodeHasSameFingerprint() {
        assertEquals(
                SeedDerivation.fingerprint("Caf\u00e9"),
                SeedDerivation.fingerprint("Cafe\u0301"));
    }

    @Test
    public void defaultSeedIncludesApplicationAndCompleteVariantIdentity() {
        var release = SeedDerivation.defaultFingerprint(
                "example.app", "release|release|");
        var paid = SeedDerivation.defaultFingerprint(
                "example.app", "paidRelease|release|tier=paid");
        var otherApplication = SeedDerivation.defaultFingerprint(
                "example.other", "release|release|");

        assertNotEquals(release, paid);
        assertNotEquals(release, otherApplication);
    }

    @Test
    public void capabilityDomainsCannotReuseTheSameStream() {
        var root = SeedDerivation.fingerprint("root");
        assertNotEquals(
                SeedDerivation.derive(root, "generation-ordinary", "release"),
                SeedDerivation.derive(root, "r8-dictionary", "release"));
    }
}
