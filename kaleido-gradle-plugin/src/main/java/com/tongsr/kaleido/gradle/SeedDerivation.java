package com.tongsr.kaleido.gradle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;

final class SeedDerivation {
    static final String SEED_POLICY_VERSION = "kaleido-seed-v1";

    private SeedDerivation() {}

    static String fingerprint(String rawSeed) {
        return fingerprint(rawSeed, "<unknown>", "<unknown>");
    }

    static String fingerprint(String rawSeed, String projectPath, String variantName) {
        if (rawSeed == null || rawSeed.isBlank()) {
            throw new KaleidoDiagnostic(
                    "KLD-CONFIG-001",
                    projectPath,
                    variantName,
                    "adoption-plan",
                    "kaleido.seed",
                    "seed",
                    "Seed Provider resolved to a blank value",
                    "Provide a nonblank deterministic seed").failure();
        }
        return sha256(Normalizer.normalize(rawSeed, Normalizer.Form.NFC));
    }

    static String defaultFingerprint(String applicationId, String variantIdentity) {
        var material = String.join("\u0000",
                SEED_POLICY_VERSION,
                Normalizer.normalize(applicationId, Normalizer.Form.NFC),
                Normalizer.normalize(variantIdentity, Normalizer.Form.NFC));
        return sha256(material);
    }

    static String derive(String rootFingerprint, String domain, String variantIdentity) {
        var material = String.join("\u0000",
                "kaleido-domain-seed-v1",
                domain,
                rootFingerprint,
                variantIdentity);
        return sha256(material);
    }

    static org.gradle.api.GradleException missingProviderValue(
            String projectPath, String variantName) {
        return new KaleidoDiagnostic(
                "KLD-CONFIG-001",
                projectPath,
                variantName,
                "adoption-plan",
                "kaleido.seed",
                "seed",
                "Explicit seed Provider resolved no value",
                "Make the configured seed Provider present for every Release build").failure();
    }

    private static String sha256(String normalizedValue) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalizedValue.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

}
