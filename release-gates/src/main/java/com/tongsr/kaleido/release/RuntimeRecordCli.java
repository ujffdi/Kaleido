package com.tongsr.kaleido.release;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes one complete controlled-device row record after external probes pass. */
public final class RuntimeRecordCli {
    private RuntimeRecordCli() {}

    public static void main(String[] arguments) throws Exception {
        var options = parse(arguments);
        var fixtures = new ArrayList<RuntimeGate.FixtureResult>();
        for (var encoded : options.getOrDefault("fixture", List.of())) {
            var parts = encoded.split(",", -1);
            if (parts.length != 12) {
                throw new IllegalArgumentException(
                        "fixture must have 12 comma-separated evidence fields");
            }
            fixtures.add(new RuntimeGate.FixtureResult(
                    parts[0], parts[1], parts[2],
                    check(parts[3]), check(parts[4]), check(parts[5]),
                    check(parts[6]), check(parts[7]), check(parts[8]),
                    check(parts[9]), check(parts[10]),
                    CompatibilityMatrix.Result.parse(parts[11])));
        }
        var bytes = RuntimeGate.canonicalRecord(
                required(options, "candidate-sha256"),
                required(options, "matrix-record-sha256"),
                required(options, "test-revision-sha256"),
                CompatibilityMatrix.requireRow(required(options, "row")),
                required(options, "device-spec-sha256"), fixtures);
        writeAtomically(Path.of(required(options, "output")), bytes);
    }

    private static RuntimeGate.Check check(String value) {
        return RuntimeGate.Check.valueOf(value);
    }

    private static Map<String, List<String>> parse(String[] arguments) {
        var values = new LinkedHashMap<String, List<String>>();
        for (int index = 0; index < arguments.length; index += 2) {
            if (!arguments[index].startsWith("--") || index + 1 >= arguments.length) {
                throw new IllegalArgumentException("arguments must be --name value pairs");
            }
            values.computeIfAbsent(arguments[index].substring(2), ignored -> new ArrayList<>())
                    .add(arguments[index + 1]);
        }
        return values;
    }

    private static String required(Map<String, List<String>> options, String name) {
        var values = options.get(name);
        if (values == null || values.size() != 1 || values.get(0).isBlank()) {
            throw new IllegalArgumentException("exactly one --" + name + " is required");
        }
        return values.get(0);
    }

    private static void writeAtomically(Path output, byte[] bytes) throws Exception {
        var parent = output.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        var staged = Files.createTempFile(parent, output.getFileName().toString(), ".tmp");
        try {
            Files.write(staged, bytes);
            try {
                Files.move(staged, output, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(staged, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
    }
}
