package com.tongsr.kaleido.release

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReleaseDossierCliTest {
    @Rule
    @JvmField
    val temporary = TemporaryFolder()

    @Test
    fun closesExactRecordsAndIndependentApprovals() {
        val root = temporary.root.toPath()
        val manifest = write(
            root,
            "manifest",
            "schema=KaleidoImmutableReleaseManifest.v1\nasset.0.sha256=candidate\nverdict=PASS\n",
        )
        val manifestSignature = write(root, "manifest-signature", "signature")
        val arguments = ArrayList<String>()
        arguments.addAll(
            listOf(
                "--output", root.resolve("dossier").toString(),
                "--manifest", manifest.toString(),
                "--manifest-signature", manifestSignature.toString(),
                "--manifest-signature-verified", "true",
            ),
        )
        for (name in listOf(
            "cache", "matrix.A3", "matrix.A4", "runtime.A3",
            "runtime.A4", "performance", "provenance", "documentation", "portal-dry-run",
        )) {
            val record = write(
                root,
                name.replace('.', '-'),
                "candidate.sha256=candidate\nverdict=PASS\n",
            )
            arguments.addAll(listOf("--record", "$name|$record"))
        }
        val owner = write(
            root,
            "owner",
            "candidate.sha256=candidate\nreviewer.id=one\n" +
                "role=release-owner\ndecision=APPROVE\nsignatureVerified=true\n" +
                "signer.fingerprint=${"A".repeat(40)}\n",
        )
        val security = write(
            root,
            "security",
            "candidate.sha256=candidate\nreviewer.id=two\n" +
                "role=provenance-security-reviewer\ndecision=APPROVE\nsignatureVerified=true\n" +
                "signer.fingerprint=${"B".repeat(40)}\n",
        )
        val ownerSignature = write(root, "owner-signature", "signature-one")
        val securitySignature = write(root, "security-signature", "signature-two")
        arguments.addAll(
            listOf(
                "--approval", owner.toString(),
                "--approval-signature", ownerSignature.toString(),
                "--approval", security.toString(),
                "--approval-signature", securitySignature.toString(),
            ),
        )
        ReleaseDossierCli.main(arguments.toTypedArray())
        val dossier = Files.readString(root.resolve("dossier"))
        assertTrue(dossier.startsWith("schema=KaleidoPrePublicationDossier.v1\n"))
        assertTrue(dossier.endsWith("verdict=PASS\n"))
    }

    @Test
    fun rejectsRecordsThatAreNotBoundToTheCandidate() {
        val root = temporary.newFolder("unbound-record").toPath()
        val manifest = write(
            root,
            "manifest",
            "schema=KaleidoImmutableReleaseManifest.v1\nasset.0.sha256=candidate\nverdict=PASS\n",
        )
        val manifestSignature = write(root, "manifest-signature", "signature")
        val arguments = ArrayList<String>()
        arguments.addAll(
            listOf(
                "--output", root.resolve("dossier").toString(),
                "--manifest", manifest.toString(),
                "--manifest-signature", manifestSignature.toString(),
                "--manifest-signature-verified", "true",
            ),
        )
        for (name in listOf(
            "cache", "matrix.A3", "matrix.A4", "runtime.A3",
            "runtime.A4", "performance", "provenance", "documentation", "portal-dry-run",
        )) {
            val contents = if (name == "documentation") {
                "verdict=PASS\n"
            } else {
                "candidate.sha256=candidate\nverdict=PASS\n"
            }
            val record = write(root, name.replace('.', '-'), contents)
            arguments.addAll(listOf("--record", "$name|$record"))
        }
        val owner = write(
            root,
            "owner",
            "candidate.sha256=candidate\nreviewer.id=one\n" +
                "role=release-owner\ndecision=APPROVE\nsignatureVerified=true\n" +
                "signer.fingerprint=${"A".repeat(40)}\n",
        )
        val security = write(
            root,
            "security",
            "candidate.sha256=candidate\nreviewer.id=two\n" +
                "role=provenance-security-reviewer\ndecision=APPROVE\nsignatureVerified=true\n" +
                "signer.fingerprint=${"B".repeat(40)}\n",
        )
        val ownerSignature = write(root, "owner-signature", "signature-one")
        val securitySignature = write(root, "security-signature", "signature-two")
        arguments.addAll(
            listOf(
                "--approval", owner.toString(),
                "--approval-signature", ownerSignature.toString(),
                "--approval", security.toString(),
                "--approval-signature", securitySignature.toString(),
            ),
        )

        val failure = assertThrows(IllegalArgumentException::class.java) {
            ReleaseDossierCli.main(arguments.toTypedArray())
        }
        assertTrue(failure.message!!.contains("missing property: candidate.sha256"))
    }

    companion object {
        private fun write(root: Path, name: String, contents: String): Path =
            Files.writeString(root.resolve("$name.properties"), contents)
    }
}
