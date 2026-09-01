package com.tongsr.kaleido.release;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Canonical bundletool and controlled-device evidence for one mandatory row. */
public final class RuntimeGate {
    public static final String SCHEMA = "KaleidoRuntimeGate.v1";
    public static final String BUNDLETOOL_VERSION = "1.18.1";
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> REQUIRED_FIXTURES = Set.of(
            "full-compose",
            "java-safe",
            "kotlin-safe",
            "native-resource",
            "sample-app",
            "sana-reference");

    private RuntimeGate() {}

    public static Set<String> requiredFixtures() {
        return REQUIRED_FIXTURES;
    }

    public static byte[] canonicalRecord(
            String candidateDigest,
            String matrixRecordDigest,
            String testRevisionDigest,
            CompatibilityMatrix.Row row,
            String deviceSpecDigest,
            List<FixtureResult> fixtures) {
        requireDigest("candidate", candidateDigest);
        requireDigest("matrix record", matrixRecordDigest);
        requireDigest("test revision", testRevisionDigest);
        requireDigest("device spec", deviceSpecDigest);
        if (!CompatibilityMatrix.requireRow(row.id()).equals(row)) {
            throw failure("runtime row differs from the mandatory Compatibility Matrix");
        }
        var byName = new LinkedHashMap<String, FixtureResult>();
        for (var fixture : fixtures) {
            if (byName.putIfAbsent(fixture.name(), fixture) != null) {
                throw failure("duplicate runtime fixture: " + fixture.name());
            }
        }
        if (!byName.keySet().equals(REQUIRED_FIXTURES)) {
            throw failure("runtime fixture closure differs from the required set");
        }

        var lines = new ArrayList<String>();
        lines.add("schema=" + SCHEMA);
        lines.add("candidate.sha256=" + candidateDigest);
        lines.add("matrixRecord.sha256=" + matrixRecordDigest);
        lines.add("testRevision.sha256=" + testRevisionDigest);
        lines.add("row.id=" + row.id());
        lines.add("bundletool.version=" + BUNDLETOOL_VERSION);
        lines.add("deviceSpec.sha256=" + deviceSpecDigest);
        lines.add("fixtures=" + REQUIRED_FIXTURES.size());
        byName.values().stream().sorted(Comparator.comparing(FixtureResult::name))
                .forEach(fixture -> appendFixture(lines, fixture));
        lines.add("verdict=PASS");
        return (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static void appendFixture(List<String> lines, FixtureResult fixture) {
        requireDigest(fixture.name() + " AAB", fixture.aabDigest());
        requireDigest(fixture.name() + " APK set", fixture.apksDigest());
        requirePass(fixture.name(), "bundletool", fixture.bundletool());
        requirePass(fixture.name(), "install", fixture.install());
        requirePass(fixture.name(), "launch", fixture.launch());
        requirePass(fixture.name(), "package identity", fixture.packageIdentity());
        requirePass(fixture.name(), "launch Activity", fixture.launchActivity());
        requirePass(fixture.name(), "resource lookup", fixture.resourceLookup());
        requirePass(fixture.name(), "generated startup isolation", fixture.noGeneratedStartup());
        if (fixture.name().equals("native-resource")) {
            requirePass(fixture.name(), "native load", fixture.nativeLoad());
        } else if (fixture.nativeLoad() != Check.NOT_APPLICABLE) {
            throw failure(fixture.name() + " native load must be NOT_APPLICABLE");
        }
        if (fixture.result() != CompatibilityMatrix.Result.PASS) {
            throw failure("mandatory runtime fixture failed: " + fixture.name());
        }
        var prefix = "fixture." + fixture.name() + ".";
        lines.add(prefix + "aab.sha256=" + fixture.aabDigest());
        lines.add(prefix + "apks.sha256=" + fixture.apksDigest());
        lines.add(prefix + "bundletool=" + fixture.bundletool());
        lines.add(prefix + "install=" + fixture.install());
        lines.add(prefix + "launch=" + fixture.launch());
        lines.add(prefix + "packageIdentity=" + fixture.packageIdentity());
        lines.add(prefix + "launchActivity=" + fixture.launchActivity());
        lines.add(prefix + "resourceLookup=" + fixture.resourceLookup());
        lines.add(prefix + "nativeLoad=" + fixture.nativeLoad());
        lines.add(prefix + "noGeneratedStartup=" + fixture.noGeneratedStartup());
        lines.add(prefix + "result=" + fixture.result());
    }

    private static void requirePass(String fixture, String check, Check value) {
        if (value != Check.PASS) {
            throw failure(fixture + " did not pass " + check);
        }
    }

    private static void requireDigest(String name, String value) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw failure("invalid " + name + " SHA-256 digest");
        }
    }

    private static IllegalArgumentException failure(String message) {
        return new IllegalArgumentException("KLD-RUNTIME-001 " + message);
    }

    public enum Check {
        PASS,
        FAIL,
        NOT_APPLICABLE
    }

    public record FixtureResult(
            String name,
            String aabDigest,
            String apksDigest,
            Check bundletool,
            Check install,
            Check launch,
            Check packageIdentity,
            Check launchActivity,
            Check resourceLookup,
            Check nativeLoad,
            Check noGeneratedStartup,
            CompatibilityMatrix.Result result) {}
}
