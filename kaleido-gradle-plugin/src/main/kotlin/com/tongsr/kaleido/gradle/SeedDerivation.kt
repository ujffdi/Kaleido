package com.tongsr.kaleido.gradle

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.text.Normalizer
import java.util.HexFormat
import org.gradle.api.GradleException

internal object SeedDerivation {
    const val SEED_POLICY_VERSION: String = "kaleido-seed-v1"

    @JvmStatic
    fun fingerprint(rawSeed: String): String =
        fingerprint(rawSeed, "<unknown>", "<unknown>")

    @JvmStatic
    fun fingerprint(rawSeed: String?, projectPath: String, variantName: String): String {
        if (rawSeed.isNullOrBlank()) {
            throw KaleidoDiagnostic(
                "KLD-CONFIG-001",
                projectPath,
                variantName,
                "adoption-plan",
                "kaleido.seed",
                "seed",
                "Seed Provider resolved to a blank value",
                "Provide a nonblank deterministic seed",
            ).failure()
        }
        return sha256(Normalizer.normalize(rawSeed, Normalizer.Form.NFC))
    }

    @JvmStatic
    fun defaultFingerprint(applicationId: String, variantIdentity: String): String {
        val material = listOf(
            SEED_POLICY_VERSION,
            Normalizer.normalize(applicationId, Normalizer.Form.NFC),
            Normalizer.normalize(variantIdentity, Normalizer.Form.NFC),
        ).joinToString("\u0000")
        return sha256(material)
    }

    @JvmStatic
    fun derive(rootFingerprint: String, domain: String, variantIdentity: String): String {
        val material = listOf(
            "kaleido-domain-seed-v1",
            domain,
            rootFingerprint,
            variantIdentity,
        ).joinToString("\u0000")
        return sha256(material)
    }

    fun missingProviderValue(projectPath: String, variantName: String): GradleException =
        KaleidoDiagnostic(
            "KLD-CONFIG-001",
            projectPath,
            variantName,
            "adoption-plan",
            "kaleido.seed",
            "seed",
            "Explicit seed Provider resolved no value",
            "Make the configured seed Provider present for every Release build",
        ).failure()

    private fun sha256(normalizedValue: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(normalizedValue.toByteArray(StandardCharsets.UTF_8))
            HexFormat.of().formatHex(digest)
        } catch (impossible: NoSuchAlgorithmException) {
            throw IllegalStateException("SHA-256 is unavailable", impossible)
        }
    }
}
