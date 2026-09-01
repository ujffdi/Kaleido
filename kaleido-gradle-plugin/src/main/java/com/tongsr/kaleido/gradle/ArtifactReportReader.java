package com.tongsr.kaleido.gradle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

public final class ArtifactReportReader {
    public static final int CURRENT_MAJOR = 1;
    public static final int CURRENT_MINOR = 0;
    public static final String CURRENT_SCHEMA_URI = schemaUri(CURRENT_MAJOR, CURRENT_MINOR);

    private ArtifactReportReader() {}

    public static Report read(String source) {
        return read(source, CURRENT_MAJOR, CURRENT_MINOR);
    }

    public static Report read(String source, int readerMajor, int readerMinor) {
        if (source == null || source.contains("\r")) {
            throw schemaFailure("Artifact Report must be canonical UTF-8/LF text");
        }
        var fields = new TreeMap<String, String>();
        for (var line : source.split("\\n")) {
            if (line.isBlank()) continue;
            var separator = line.indexOf('=');
            if (separator <= 0 || fields.put(line.substring(0, separator),
                    line.substring(separator + 1)) != null) {
                throw schemaFailure("Artifact Report fields are malformed or duplicated");
            }
        }
        var dialect = SchemaDialect.parse(required(fields, "schemaVersion"));
        var expectedUri = schemaUri(dialect.major(), dialect.minor());
        if (!expectedUri.equals(required(fields, "schemaUri"))) {
            throw schemaFailure("Artifact Report URI and schemaVersion disagree");
        }
        if (dialect.major() > readerMajor || dialect.major() < readerMajor - 1) {
            throw schemaFailure("Artifact Report major " + dialect.major()
                    + " is outside reader support " + (readerMajor - 1) + ".." + readerMajor);
        }
        required(fields, "releaseEvidenceSetId");
        required(fields, "project");
        required(fields, "variant");
        return new Report(source.getBytes(StandardCharsets.UTF_8), dialect, Map.copyOf(fields),
                readerMajor, readerMinor);
    }

    public static DerivedView deriveCurrent(Report source, String converterVersion) {
        if (converterVersion == null || converterVersion.isBlank()) {
            throw new IllegalArgumentException(KaleidoVersionContract.MIGRATION_DIAGNOSTIC
                    + " converter version is required");
        }
        var sourceSha = sha256(source.sourceBytes());
        var text = new StringBuilder()
                .append("schemaUri=").append(CURRENT_SCHEMA_URI).append('\n')
                .append("schemaVersion=").append(CURRENT_MAJOR).append('.')
                .append(CURRENT_MINOR).append('\n')
                .append("derivedFromSchemaUri=")
                .append(source.fields().get("schemaUri")).append('\n')
                .append("derivedFromReleaseEvidenceSetId=")
                .append(source.fields().get("releaseEvidenceSetId")).append('\n')
                .append("derivedFromSha256=").append(sourceSha).append('\n')
                .append("converterVersion=").append(converterVersion).append('\n');
        source.fields().entrySet().stream()
                .filter(entry -> !entry.getKey().equals("schemaUri")
                        && !entry.getKey().equals("schemaVersion"))
                .forEach(entry -> text.append("source.").append(entry.getKey()).append('=')
                        .append(entry.getValue()).append('\n'));
        var identity = sha256(text.toString().getBytes(StandardCharsets.UTF_8));
        text.append("derivedViewId=").append(identity).append('\n');
        return new DerivedView(text.toString(), identity, sourceSha);
    }

    public static String schemaUri(int major, int minor) {
        if (major < 0 || minor < 0) throw schemaFailure("Schema versions cannot be negative");
        return "https://schemas.tongsr.com/kaleido/artifact-report/" + major + "." + minor;
    }

    private static String required(Map<String, String> fields, String key) {
        var value = fields.get(key);
        if (value == null || value.isBlank()) {
            throw schemaFailure("Artifact Report is missing " + key);
        }
        return value;
    }

    private static IllegalArgumentException schemaFailure(String reason) {
        return new IllegalArgumentException(KaleidoVersionContract.SCHEMA_DIAGNOSTIC
                + " " + reason);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record SchemaDialect(int major, int minor) {
        static SchemaDialect parse(String value) {
            if (!value.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")) {
                throw schemaFailure("schemaVersion must be canonical major.minor");
            }
            var parts = value.split("\\.");
            return new SchemaDialect(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        }
    }

    public record Report(
            byte[] sourceBytes,
            SchemaDialect dialect,
            Map<String, String> fields,
            int readerMajor,
            int readerMinor) {
        public Report {
            sourceBytes = sourceBytes.clone();
            fields = Map.copyOf(fields);
        }

        @Override
        public byte[] sourceBytes() {
            return sourceBytes.clone();
        }
    }

    public record DerivedView(String canonicalText, String identity, String sourceSha256) {}
}
