package com.tongsr.kaleido.release;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Exact mandatory compatibility rows and their canonical release-evidence record. */
public final class CompatibilityMatrix {
    public static final String SCHEMA = "KaleidoCompatibilityMatrix.v1";
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> REQUIRED_FIXTURES = Set.of(
            "exhaustive-boundary",
            "full-compose",
            "java-safe",
            "kotlin-safe",
            "sample-comprehensive",
            "sana-reference");
    private static final Map<String, Row> MANDATORY_ROWS = mandatoryRows();

    private CompatibilityMatrix() {}

    public static Map<String, Row> mandatoryRows() {
        var rows = new LinkedHashMap<String, Row>();
        rows.put("A3", new Row("A3", "9.2.0", "9.4.1", "linux", "x86_64",
                17, "36.0.0", 36, "built-in"));
        rows.put("A4", new Row("A4", "9.3.2", "9.5.0", "linux", "x86_64",
                17, "36.0.0", 36, "built-in"));
        return Map.copyOf(rows);
    }

    public static Set<String> requiredFixtures() {
        return REQUIRED_FIXTURES;
    }

    public static Row requireRow(String id) {
        var row = MANDATORY_ROWS.get(id);
        if (row == null) {
            throw new IllegalArgumentException(
                    "KLD-COMPAT-001 unsupported mandatory matrix row: " + id);
        }
        return row;
    }

    public static byte[] canonicalRecord(
            String candidateDigest, Row actual, List<FixtureResult> fixtures) {
        requireDigest("candidate", candidateDigest);
        var expected = requireRow(actual.id());
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                    "KLD-COMPAT-001 mandatory row environment differs from " + expected.id());
        }
        var byName = new LinkedHashMap<String, FixtureResult>();
        for (var fixture : fixtures) {
            if (byName.putIfAbsent(fixture.name(), fixture) != null) {
                throw new IllegalArgumentException(
                        "KLD-COMPAT-001 duplicate fixture result: " + fixture.name());
            }
        }
        if (!byName.keySet().equals(REQUIRED_FIXTURES)) {
            var missing = new java.util.TreeSet<>(REQUIRED_FIXTURES);
            missing.removeAll(byName.keySet());
            var unexpected = new java.util.TreeSet<>(byName.keySet());
            unexpected.removeAll(REQUIRED_FIXTURES);
            throw new IllegalArgumentException(
                    "KLD-COMPAT-001 fixture closure differs; missing=" + missing
                            + " unexpected=" + unexpected);
        }

        var lines = new ArrayList<String>();
        lines.add("schema=" + SCHEMA);
        lines.add("candidate.sha256=" + candidateDigest);
        lines.add("row.id=" + actual.id());
        lines.add("row.agp=" + actual.agp());
        lines.add("row.gradle=" + actual.gradle());
        lines.add("row.os=" + actual.os());
        lines.add("row.arch=" + actual.architecture());
        lines.add("row.jdk=" + actual.jdk());
        lines.add("row.buildTools=" + actual.buildTools());
        lines.add("row.compileSdk=" + actual.compileSdk());
        lines.add("row.kotlinMode=" + actual.kotlinMode());
        lines.add("fixtures=" + REQUIRED_FIXTURES.size());
        byName.values().stream()
                .sorted(Comparator.comparing(FixtureResult::name))
                .forEach(fixture -> {
                    validateFixture(fixture);
                    var prefix = "fixture." + fixture.name() + ".";
                    lines.add(prefix + "source.sha256=" + fixture.sourceDigest());
                    lines.add(prefix + "aab.sha256=" + fixture.aabDigest());
                    lines.add(prefix + "result=" + fixture.result().name());
                });
        lines.add("verdict=PASS");
        return (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static void validateFixture(FixtureResult fixture) {
        if (!REQUIRED_FIXTURES.contains(fixture.name())) {
            throw new IllegalArgumentException(
                    "KLD-COMPAT-001 unexpected fixture result: " + fixture.name());
        }
        requireDigest(fixture.name() + " source", fixture.sourceDigest());
        if (fixture.aabDigest().isEmpty()) {
            if (!fixture.name().equals("exhaustive-boundary")) {
                throw new IllegalArgumentException(
                        "KLD-COMPAT-001 missing AAB digest: " + fixture.name());
            }
        } else {
            requireDigest(fixture.name() + " AAB", fixture.aabDigest());
        }
        if (fixture.result() != Result.PASS) {
            throw new IllegalArgumentException(
                    "KLD-COMPAT-001 mandatory fixture failed: " + fixture.name());
        }
    }

    private static void requireDigest(String name, String value) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "KLD-COMPAT-001 invalid " + name + " SHA-256 digest");
        }
    }

    public enum Result {
        PASS,
        FAIL;

        public static Result parse(String value) {
            return valueOf(value.toUpperCase(Locale.ROOT));
        }
    }

    public record Row(
            String id,
            String agp,
            String gradle,
            String os,
            String architecture,
            int jdk,
            String buildTools,
            int compileSdk,
            String kotlinMode) {}

    public record FixtureResult(
            String name, String sourceDigest, String aabDigest, Result result) {}
}
