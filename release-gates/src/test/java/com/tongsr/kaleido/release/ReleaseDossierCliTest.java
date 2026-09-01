package com.tongsr.kaleido.release;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ReleaseDossierCliTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void closesExactRecordsAndIndependentApprovals() throws Exception {
        var root = temporary.getRoot().toPath();
        var manifest = write(root, "manifest", "schema=KaleidoImmutableReleaseManifest.v1\n"
                + "asset.0.sha256=candidate\nverdict=PASS\n");
        var manifestSignature = write(root, "manifest-signature", "signature");
        var arguments = new ArrayList<String>();
        arguments.addAll(java.util.List.of("--output", root.resolve("dossier").toString(),
                "--manifest", manifest.toString(),
                "--manifest-signature", manifestSignature.toString(),
                "--manifest-signature-verified", "true"));
        for (var name : java.util.List.of("cache", "matrix.A3", "matrix.A4", "runtime.A3",
                "runtime.A4", "performance", "provenance", "documentation", "portal-dry-run")) {
            var record = write(root, name.replace('.', '-'),
                    "candidate.sha256=candidate\nverdict=PASS\n");
            arguments.addAll(java.util.List.of("--record", name + "|" + record));
        }
        var owner = write(root, "owner", "candidate.sha256=candidate\nreviewer.id=one\n"
                + "role=release-owner\ndecision=APPROVE\nsignatureVerified=true\n"
                + "signer.fingerprint=" + "A".repeat(40) + "\n");
        var security = write(root, "security", "candidate.sha256=candidate\nreviewer.id=two\n"
                + "role=provenance-security-reviewer\ndecision=APPROVE\nsignatureVerified=true\n"
                + "signer.fingerprint=" + "B".repeat(40) + "\n");
        var ownerSignature = write(root, "owner-signature", "signature-one");
        var securitySignature = write(root, "security-signature", "signature-two");
        arguments.addAll(java.util.List.of("--approval", owner.toString(),
                "--approval-signature", ownerSignature.toString(),
                "--approval", security.toString(),
                "--approval-signature", securitySignature.toString()));
        ReleaseDossierCli.main(arguments.toArray(String[]::new));
        var dossier = Files.readString(root.resolve("dossier"));
        assertTrue(dossier.startsWith("schema=KaleidoPrePublicationDossier.v1\n"));
        assertTrue(dossier.endsWith("verdict=PASS\n"));
    }

    @Test
    public void rejectsRecordsThatAreNotBoundToTheCandidate() throws Exception {
        var root = temporary.newFolder("unbound-record").toPath();
        var manifest = write(root, "manifest", "schema=KaleidoImmutableReleaseManifest.v1\n"
                + "asset.0.sha256=candidate\nverdict=PASS\n");
        var manifestSignature = write(root, "manifest-signature", "signature");
        var arguments = new ArrayList<String>();
        arguments.addAll(java.util.List.of("--output", root.resolve("dossier").toString(),
                "--manifest", manifest.toString(),
                "--manifest-signature", manifestSignature.toString(),
                "--manifest-signature-verified", "true"));
        for (var name : java.util.List.of("cache", "matrix.A3", "matrix.A4", "runtime.A3",
                "runtime.A4", "performance", "provenance", "documentation", "portal-dry-run")) {
            var contents = name.equals("documentation")
                    ? "verdict=PASS\n"
                    : "candidate.sha256=candidate\nverdict=PASS\n";
            var record = write(root, name.replace('.', '-'), contents);
            arguments.addAll(java.util.List.of("--record", name + "|" + record));
        }
        var owner = write(root, "owner", "candidate.sha256=candidate\nreviewer.id=one\n"
                + "role=release-owner\ndecision=APPROVE\nsignatureVerified=true\n"
                + "signer.fingerprint=" + "A".repeat(40) + "\n");
        var security = write(root, "security", "candidate.sha256=candidate\nreviewer.id=two\n"
                + "role=provenance-security-reviewer\ndecision=APPROVE\nsignatureVerified=true\n"
                + "signer.fingerprint=" + "B".repeat(40) + "\n");
        var ownerSignature = write(root, "owner-signature", "signature-one");
        var securitySignature = write(root, "security-signature", "signature-two");
        arguments.addAll(java.util.List.of("--approval", owner.toString(),
                "--approval-signature", ownerSignature.toString(),
                "--approval", security.toString(),
                "--approval-signature", securitySignature.toString()));

        var failure = assertThrows(IllegalArgumentException.class,
                () -> ReleaseDossierCli.main(arguments.toArray(String[]::new)));
        assertTrue(failure.getMessage().contains("missing property: candidate.sha256"));
    }

    private static Path write(Path root, String name, String contents) throws Exception {
        return Files.writeString(root.resolve(name + ".properties"), contents);
    }
}
