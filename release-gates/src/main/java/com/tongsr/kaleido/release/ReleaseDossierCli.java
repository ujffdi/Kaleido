package com.tongsr.kaleido.release;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Closes mandatory automated records and two independent approvals into one dossier. */
public final class ReleaseDossierCli {
    private static final String DIAGNOSTIC = "KLD-PUBLICATION-001 ";
    private static final Set<String> REQUIRED = Set.of(
            "cache", "matrix.A3", "matrix.A4", "runtime.A3", "runtime.A4",
            "performance", "provenance", "documentation", "portal-dry-run");

    private ReleaseDossierCli() {}

    public static void main(String[] arguments) throws Exception {
        var options = parse(arguments);
        var output = Path.of(single(options, "output"));
        var manifest = Path.of(single(options, "manifest"));
        var manifestSignature = Path.of(single(options, "manifest-signature"));
        if (!Files.isRegularFile(manifest)) throw failure("release manifest is missing");
        if (!Files.isRegularFile(manifestSignature)) {
            throw failure("release manifest signature is missing");
        }
        if (!"true".equals(single(options, "manifest-signature-verified"))) {
            throw failure("release manifest signature is not verified");
        }
        var manifestValues = load(manifest);
        exact(manifestValues, "schema", "KaleidoImmutableReleaseManifest.v1");
        exact(manifestValues, "verdict", "PASS");
        var candidate = require(manifestValues, "asset.0.sha256");
        var records = namedFiles(options.get("record"));
        if (!records.keySet().equals(REQUIRED)) {
            var missing = new java.util.TreeSet<>(REQUIRED);
            missing.removeAll(records.keySet());
            throw failure("mandatory records mismatch; missing=" + missing);
        }

        var text = new StringBuilder("schema=KaleidoPrePublicationDossier.v1\n")
                .append("candidate.sha256=").append(candidate).append('\n')
                .append("releaseManifest.sha256=").append(digest(manifest)).append('\n')
                .append("releaseManifest.signature.sha256=")
                .append(digest(manifestSignature)).append('\n')
                .append("releaseManifest.signatureVerified=true\n")
                .append("failurePolicy.product=new-versioned-candidate\n")
                .append("failurePolicy.infrastructure=same-bytes-classified-rerun\n")
                .append("publication.mutableReplacement=false\n")
                .append("publication.waiver=false\n");
        var ordered = records.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
        for (int index = 0; index < ordered.size(); index++) {
            var entry = ordered.get(index);
            var values = load(entry.getValue());
            exact(values, "verdict", "PASS");
            exact(values, "candidate.sha256", candidate);
            text.append("record.").append(index).append(".name=").append(entry.getKey()).append('\n')
                    .append("record.").append(index).append(".sha256=")
                    .append(digest(entry.getValue())).append('\n');
        }

        var approvals = files(options.get("approval"), "approval");
        var approvalSignatures = files(options.get("approval-signature"), "approval signature");
        if (approvals.size() != 2) throw failure("exactly two approvals are required");
        if (approvalSignatures.size() != approvals.size()) {
            throw failure("each approval requires one verified detached signature");
        }
        var reviewers = new LinkedHashSet<String>();
        var signerFingerprints = new LinkedHashSet<String>();
        var roles = new LinkedHashSet<String>();
        for (int index = 0; index < approvals.size(); index++) {
            var approval = load(approvals.get(index));
            exact(approval, "candidate.sha256", candidate);
            exact(approval, "decision", "APPROVE");
            exact(approval, "signatureVerified", "true");
            var reviewer = require(approval, "reviewer.id");
            var role = require(approval, "role");
            var fingerprint = require(approval, "signer.fingerprint").toUpperCase(java.util.Locale.ROOT);
            if (!fingerprint.matches("[0-9A-F]{40}|[0-9A-F]{64}")) {
                throw failure("approval signer fingerprint is invalid");
            }
            if (!reviewers.add(reviewer)) throw failure("approval reviewers must be independent");
            if (!signerFingerprints.add(fingerprint)) {
                throw failure("approval signing keys must be independent");
            }
            roles.add(role);
            text.append("approval.").append(index).append(".reviewer=").append(reviewer).append('\n')
                    .append("approval.").append(index).append(".role=").append(role).append('\n')
                    .append("approval.").append(index).append(".signerFingerprint=")
                    .append(fingerprint).append('\n')
                    .append("approval.").append(index).append(".sha256=")
                    .append(digest(approvals.get(index))).append('\n')
                    .append("approval.").append(index).append(".signature.sha256=")
                    .append(digest(approvalSignatures.get(index))).append('\n');
        }
        if (!roles.containsAll(Set.of("release-owner", "provenance-security-reviewer"))) {
            throw failure("release-owner and provenance-security-reviewer approvals are required");
        }
        text.append("verdict=PASS\n");
        write(output, text.toString());
    }

    private static Map<String, Path> namedFiles(List<String> encoded) {
        if (encoded == null) throw failure("mandatory records are missing");
        var values = new LinkedHashMap<String, Path>();
        for (var value : encoded) {
            var separator = value.indexOf('|');
            if (separator <= 0) throw failure("record must be name|path");
            var path = Path.of(value.substring(separator + 1));
            if (!Files.isRegularFile(path) || values.put(value.substring(0, separator), path) != null) {
                throw failure("record is missing or duplicated");
            }
        }
        return values;
    }

    private static List<Path> files(List<String> encoded, String name) {
        if (encoded == null) return List.of();
        return encoded.stream().map(Path::of).peek(path -> {
            if (!Files.isRegularFile(path)) throw failure(name + " file is missing");
        }).toList();
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
        var staged = Files.createTempFile(output.toAbsolutePath().getParent(), output.getFileName().toString(), ".tmp");
        try {
            Files.writeString(staged, text, StandardCharsets.UTF_8);
            try {
                Files.move(staged, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(staged, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static Map<String, List<String>> parse(String[] arguments) {
        var values = new LinkedHashMap<String, List<String>>();
        for (int index = 0; index < arguments.length; index += 2) {
            if (!arguments[index].startsWith("--") || index + 1 >= arguments.length) {
                throw failure("arguments must be --name value pairs");
            }
            values.computeIfAbsent(arguments[index].substring(2), ignored -> new ArrayList<>())
                    .add(arguments[index + 1]);
        }
        return values;
    }

    private static String single(Map<String, List<String>> options, String name) {
        var values = options.get(name);
        if (values == null || values.size() != 1 || values.get(0).isBlank()) {
            throw failure("exactly one --" + name + " is required");
        }
        return values.get(0);
    }

    private static IllegalArgumentException failure(String message) {
        return new IllegalArgumentException(DIAGNOSTIC + message);
    }
}
