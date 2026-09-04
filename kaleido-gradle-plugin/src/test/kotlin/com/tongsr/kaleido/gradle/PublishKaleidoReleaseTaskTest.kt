package com.tongsr.kaleido.gradle

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.TreeMap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishKaleidoReleaseTaskTest {
    @Test
    fun embeddedPluginVersionMatchesPublishedArtifactVersion() {
        assertEquals(
            System.getProperty("kaleido.test.plugin.version", "0.1.1-dev"),
            KaleidoPluginVersion.current(),
        )
    }

    @Test
    fun missingBlankOrUnspecifiedPluginVersionFailsClosed() {
        val invalidResources = listOf<InputStream?>(
            null,
            ByteArrayInputStream("unrelated=value\n".toByteArray(StandardCharsets.UTF_8)),
            ByteArrayInputStream("pluginVersion=\n".toByteArray(StandardCharsets.UTF_8)),
            ByteArrayInputStream("pluginVersion=unspecified\n".toByteArray(StandardCharsets.UTF_8)),
        )

        invalidResources.forEach { resource ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                KaleidoPluginVersion.read(resource)
            }
            assertTrue(failure.message!!.startsWith("KLD-PUBLICATION-001"))
        }
    }

    @Test
    fun invalidPluginVersionCannotAssembleOrReplaceSuccessfulEvidence() {
        val signed = "signed-aab".toByteArray(StandardCharsets.UTF_8)
        val successful = PublishKaleidoReleaseTask.assemble(
            context(),
            evidence("build/"),
            signed,
            signing(signed),
        )
        val priorFiles = successful.files.mapValues { (_, bytes) -> bytes.clone() }

        listOf("", "unspecified").forEach { invalidVersion ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                PublishKaleidoReleaseTask.assemble(
                    context().copy(pluginVersion = invalidVersion),
                    evidence("build/"),
                    signed,
                    signing(signed),
                )
            }
            assertTrue(failure.message!!.startsWith("KLD-PUBLICATION-001"))
        }

        priorFiles.forEach { (name, bytes) ->
            assertArrayEquals(bytes, successful.files.getValue(name))
        }
    }

    @Test
    fun canonicalSetIsStableCompleteAndPathIndependent() {
        val signed = "signed-aab".toByteArray(StandardCharsets.UTF_8)
        val first = PublishKaleidoReleaseTask.assemble(
            context(),
            evidence("build/"),
            signed,
            signing(signed),
        )
        val relocated = PublishKaleidoReleaseTask.assemble(
            context(),
            evidence("build/"),
            signed,
            signing(signed),
        )

        assertEquals(first.setId, relocated.setId)
        assertArrayEquals(
            first.files["release-evidence-set-manifest.properties"],
            relocated.files["release-evidence-set-manifest.properties"],
        )
        assertArrayEquals(
            first.files["artifact-report.txt"],
            relocated.files["artifact-report.txt"],
        )
        assertTrue(
            first.files.keys.containsAll(
                listOf(
                    "mappings/raw-kaleido-mapping.txt",
                    "mappings/raw-r8-mapping.txt",
                    "mappings/composed-mapping.txt",
                    "mappings/resource-mapping.txt",
                    "publication/signing-receipt.properties",
                    "publication/compose-final-dex-receipt.properties",
                    "deterministic-evidence-manifest.properties",
                    "release-evidence-set-manifest.properties",
                    "artifact-report.txt",
                ),
            ),
        )
        val report = String(first.files["artifact-report.txt"]!!, StandardCharsets.UTF_8)
        assertEquals(10, report.lineSequence().count { line -> line.contains("|PASS") })
        assertTrue(report.contains("proofLimitations="))
    }

    @Test
    fun missingOrMutatedStagingEvidenceFailsClosed() {
        val signed = "signed-aab".toByteArray(StandardCharsets.UTF_8)
        val missing = TreeMap(evidence("build/"))
        missing.remove("build/intermediates/kaleido/release/r8/raw-r8-mapping.txt")
        val missingFailure = assertThrows(IllegalArgumentException::class.java) {
            PublishKaleidoReleaseTask.assemble(
                context(),
                missing,
                signed,
                signing(signed),
            )
        }
        assertTrue(missingFailure.message!!.contains("raw-r8-mapping.txt"))

        val mutation = assertThrows(IllegalArgumentException::class.java) {
            PublishKaleidoReleaseTask.assemble(
                context(),
                evidence("build/"),
                "mutated".toByteArray(StandardCharsets.UTF_8),
                signing(signed),
            )
        }
        assertTrue(mutation.message!!.contains("Signed AAB digest"))

        val absolute = TreeMap(evidence("build/"))
        absolute["/Users/person/secret.txt"] = "secret".toByteArray(StandardCharsets.UTF_8)
        val pathFailure = assertThrows(IllegalArgumentException::class.java) {
            PublishKaleidoReleaseTask.assemble(
                context(),
                absolute,
                signed,
                signing(signed),
            )
        }
        assertTrue(pathFailure.message!!.contains("not project-relative"))
    }

    private fun context(): PublishKaleidoReleaseTask.Context =
        PublishKaleidoReleaseTask.Context(":app", "release", "0.1.1-dev")

    private fun evidence(prefix: String): Map<String, ByteArray> {
        val unsigned = "unsigned-aab".toByteArray(StandardCharsets.UTF_8)
        val values = TreeMap<String, ByteArray>()
        values[prefix + "intermediates/kaleido/release/adoption-plan.properties"] = bytes(
            """
                schema=AdoptionPlan.v1
                project=:app
                variant.name=release
                applicationId=example.app
                profile=SAFE
            """.trimIndent(),
        )
        values[prefix + "intermediates/kaleido/release/generated-inventory.properties"] = bytes(
            """
                schema=GeneratedInventory.v1
                classes=1
                components.activities=0
                file=java/A.java|abc
            """.trimIndent(),
        )
        values[
            prefix + "intermediates/kaleido/release/class-rewrite/" +
                "raw-kaleido-mapping.txt",
        ] = bytes("example.app.A -> example.app.B\n")
        values[prefix + "intermediates/kaleido/release/r8/raw-r8-mapping.txt"] =
            bytes("# compiler: R8\nexample.app.B -> a:\n")
        values[prefix + "intermediates/kaleido/release/r8/composed-mapping.txt"] =
            bytes("example.app.A -> a:\n")
        values[
            prefix + "intermediates/kaleido/release/bundle-rewrite/" +
                "resource-mapping.txt",
        ] = bytes("schema=ResourceMapping.v1\n")
        values[
            prefix + "intermediates/kaleido/release/bundle-rewrite/" +
                "unsigned-candidate.aab",
        ] = unsigned
        values[
            prefix + "intermediates/kaleido/release/bundle-rewrite/" +
                "unsigned-candidate.sha256",
        ] = bytes(sha256(unsigned) + "\n")
        values[
            prefix + "intermediates/kaleido/release/compose/" +
                "final-dex-receipt.properties",
        ] = bytes(
            """
                schema=ComposeFinalDexReceipt.v1
                project=:app
                variant=release
                facades=0
                functions=0
                mappingResolved=true
                incomingBytecodeEdges=0
                finalDexRetained=true
            """.trimIndent(),
        )
        return values.toMap()
    }

    private fun signing(signed: ByteArray): ByteArray {
        val unsigned = "unsigned-aab".toByteArray(StandardCharsets.UTF_8)
        return bytes(
            """
                schema=SigningReceipt.v1
                project=:app
                variant=release
                source=ENVIRONMENT
                unsignedAabSha256=${sha256(unsigned)}
                signedAabSha256=${sha256(signed)}
                certificateSha256=${"a".repeat(64)}
                signatureCoverageValidated=true
                certificateMatched=true
                bundletoolValidated=true
                codeTransparencyEntries=0
            """.trimIndent(),
        )
    }

    private fun bytes(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8)

    private fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
