package com.tongsr.kaleido.release;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** Closes the approved candidate with its public-Portal resolution and device smoke. */
public final class FinalReleaseDossierCli {
    private static final String DIAGNOSTIC = "KLD-PUBLICATION-001 ";

    private FinalReleaseDossierCli() {}

    public static void main(String[] arguments) throws Exception {
        var options = parse(arguments);
        var output = Path.of(required(options, "output"));
        var prePublicationPath = existing(options, "pre-publication-dossier");
        var postPublicationPath = existing(options, "post-publication-record");
        var manifestPath = existing(options, "manifest");
        var version = required(options, "version");
        var signedTag = required(options, "signed-tag");

        var prePublication = load(prePublicationPath);
        var postPublication = load(postPublicationPath);
        var manifest = load(manifestPath);
        exact(prePublication, "schema", "KaleidoPrePublicationDossier.v1");
        exact(prePublication, "verdict", "PASS");
        exact(postPublication, "schema", "KaleidoPostPublication.v1");
        exact(postPublication, "verdict", "PASS");
        exact(manifest, "schema", "KaleidoImmutableReleaseManifest.v1");
        exact(manifest, "verdict", "PASS");
        exact(manifest, "version", version);
        exact(manifest, "source.tag", signedTag);

        var candidate = require(manifest, "asset.0.sha256");
        exact(prePublication, "candidate.sha256", candidate);
        exact(postPublication, "candidate.sha256", candidate);
        exact(postPublication, "coordinates", "io.github.ujffdi.kaleido:" + version);
        for (var property : new String[] {
                "publicPluginDigest", "publicMarkerDigest", "cleanMarkerResolution",
                "consumerReleaseEvidence", "bundletoolAndDeviceSmoke"}) {
            exact(postPublication, property, "PASS");
        }

        var text = "schema=KaleidoReleaseDossier.v1\n"
                + "version=" + version + "\n"
                + "coordinates=io.github.ujffdi.kaleido:" + version + "\n"
                + "source.tag=" + signedTag + "\n"
                + "source.revision=" + require(manifest, "source.revision") + "\n"
                + "candidate.sha256=" + candidate + "\n"
                + "releaseManifest.sha256=" + digest(manifestPath) + "\n"
                + "prePublicationDossier.sha256=" + digest(prePublicationPath) + "\n"
                + "postPublicationRecord.sha256=" + digest(postPublicationPath) + "\n"
                + "publicPluginDigest=PASS\n"
                + "publicMarkerDigest=PASS\n"
                + "cleanMarkerResolution=PASS\n"
                + "consumerReleaseEvidence=PASS\n"
                + "bundletoolAndDeviceSmoke=PASS\n"
                + "publication.mutableReplacement=false\n"
                + "publication.waiver=false\n"
                + "verdict=PASS\n";
        write(output, text);
    }

    private static Map<String, String> parse(String[] arguments) {
        var values = new LinkedHashMap<String, String>();
        for (int index = 0; index < arguments.length; index += 2) {
            if (!arguments[index].startsWith("--") || index + 1 >= arguments.length
                    || values.put(arguments[index].substring(2), arguments[index + 1]) != null) {
                throw failure("arguments must be unique --name value pairs");
            }
        }
        return values;
    }

    private static Path existing(Map<String, String> options, String name) {
        var path = Path.of(required(options, name));
        if (!Files.isRegularFile(path)) throw failure(name + " is missing");
        return path;
    }

    private static String required(Map<String, String> options, String name) {
        var value = options.get(name);
        if (value == null || value.isBlank()) throw failure("--" + name + " is required");
        return value;
    }

    private static Properties load(Path path) throws Exception {
        var values = new Properties();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            values.load(reader);
        }
        return values;
    }

    private static void exact(Properties values, String name, String expected) {
        if (!expected.equals(require(values, name))) throw failure(name + " mismatch");
    }

    private static String require(Properties values, String name) {
        var value = values.getProperty(name);
        if (value == null || value.isBlank()) throw failure("missing property: " + name);
        return value.trim();
    }

    private static String digest(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)));
    }

    private static void write(Path output, String text) throws Exception {
        Files.createDirectories(output.toAbsolutePath().getParent());
        var staged = Files.createTempFile(
                output.toAbsolutePath().getParent(), output.getFileName().toString(), ".tmp");
        try {
            Files.writeString(staged, text, StandardCharsets.UTF_8);
            try {
                Files.move(staged, output,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(staged, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static IllegalArgumentException failure(String message) {
        return new IllegalArgumentException(DIAGNOSTIC + message);
    }
}
