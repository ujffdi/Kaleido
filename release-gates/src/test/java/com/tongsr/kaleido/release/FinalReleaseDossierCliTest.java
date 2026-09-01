package com.tongsr.kaleido.release;

import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class FinalReleaseDossierCliTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void closesPrePublicationApprovalWithPublicRuntimeEvidence() throws Exception {
        var root = temporary.getRoot().toPath();
        var pre = Files.writeString(root.resolve("pre.properties"),
                "schema=KaleidoPrePublicationDossier.v1\n"
                        + "candidate.sha256=candidate\nverdict=PASS\n");
        var post = Files.writeString(root.resolve("post.properties"),
                "schema=KaleidoPostPublication.v1\n"
                        + "candidate.sha256=candidate\n"
                        + "coordinates=io.github.ujffdi.kaleido:0.1.0\n"
                        + "publicPluginDigest=PASS\npublicMarkerDigest=PASS\n"
                        + "cleanMarkerResolution=PASS\nconsumerReleaseEvidence=PASS\n"
                        + "bundletoolAndDeviceSmoke=PASS\nverdict=PASS\n");
        var manifest = Files.writeString(root.resolve("manifest.properties"),
                "schema=KaleidoImmutableReleaseManifest.v1\nversion=0.1.0\n"
                        + "source.tag=v0.1.0\nsource.revision=revision\n"
                        + "asset.0.sha256=candidate\nverdict=PASS\n");
        var output = root.resolve("final.properties");

        FinalReleaseDossierCli.main(new String[] {
                "--output", output.toString(),
                "--pre-publication-dossier", pre.toString(),
                "--post-publication-record", post.toString(),
                "--manifest", manifest.toString(),
                "--version", "0.1.0", "--signed-tag", "v0.1.0"});

        var result = Files.readString(output);
        assertTrue(result.startsWith("schema=KaleidoReleaseDossier.v1\n"));
        assertTrue(result.contains("coordinates=io.github.ujffdi.kaleido:0.1.0\n"));
        assertTrue(result.endsWith("verdict=PASS\n"));
    }
}
