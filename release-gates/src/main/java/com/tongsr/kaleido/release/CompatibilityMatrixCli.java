package com.tongsr.kaleido.release;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes one validated mandatory-row record after its external builds have completed. */
public final class CompatibilityMatrixCli {
    private CompatibilityMatrixCli() {}

    public static void main(String[] arguments) throws Exception {
        var options = parse(arguments);
        var row = new CompatibilityMatrix.Row(
                required(options, "row"),
                required(options, "agp"),
                required(options, "gradle"),
                required(options, "os"),
                required(options, "arch"),
                Integer.parseInt(required(options, "jdk")),
                required(options, "build-tools"),
                Integer.parseInt(required(options, "compile-sdk")),
                required(options, "kotlin-mode"));
        var results = new ArrayList<CompatibilityMatrix.FixtureResult>();
        for (var encoded : options.getOrDefault("fixture", List.of())) {
            var parts = encoded.split(",", -1);
            if (parts.length != 4) {
                throw new IllegalArgumentException(
                        "fixture must be name,sourceSha256,aabSha256,result");
            }
            results.add(new CompatibilityMatrix.FixtureResult(
                    parts[0], parts[1], parts[2], CompatibilityMatrix.Result.parse(parts[3])));
        }
        var bytes = CompatibilityMatrix.canonicalRecord(
                required(options, "candidate-sha256"), row, results);
        var output = Path.of(required(options, "output"));
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
}
