package com.tongsr.kaleido.release;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Evaluates the fixed release performance and size budgets from controlled raw measurements. */
public final class PerformanceGateCli {
    private static final String DIAGNOSTIC = "KLD-PERF-001 ";
    private static final long MIB = 1024L * 1024L;

    private PerformanceGateCli() {}

    public static void main(String[] arguments) throws Exception {
        var options = parse(arguments);
        var input = Path.of(required(options, "input"));
        var output = Path.of(required(options, "output"));
        if (!Files.isRegularFile(input)) throw failure("measurement input is missing");
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        var verdict = evaluate(properties);
        var bytes = verdict.record().getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(output.toAbsolutePath().getParent());
        var staged = Files.createTempFile(output.toAbsolutePath().getParent(), output.getFileName().toString(), ".tmp");
        try {
            Files.write(staged, bytes);
            try {
                Files.move(staged, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(staged, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
        if (!verdict.failures().isEmpty()) throw failure(String.join("; ", verdict.failures()));
    }

    static Verdict evaluate(Properties values) throws Exception {
        require(values, "candidate.sha256");
        exact(values, "environment.os", "Linux");
        exact(values, "environment.arch", "x86_64");
        exact(values, "complexity.verdict", "PASS");
        var failures = new ArrayList<String>();
        var record = new StringBuilder("schema=KaleidoPerformanceGate.v1\n")
                .append("candidate.sha256=").append(require(values, "candidate.sha256")).append('\n')
                .append("environment.os=Linux\nenvironment.arch=x86_64\n")
                .append("warmups=2\nsamples=5\nstatistic=median\n");

        duration(values, record, failures, "safe.clean", 0.20, 45.0);
        duration(values, record, failures, "full.clean", 0.30, 90.0);
        duration(values, record, failures, "warm.noClean", 0.10, 15.0);
        overhead(values, record, failures, "memory.peakMib", 0.25, 512.0);
        growth(values, record, failures, "sana.safe", 0.01, 1 * MIB);
        growth(values, record, failures, "sana.full", 0.02, 2 * MIB);
        growth(values, record, failures, "sana.compose512", 0.05, 5 * MIB);
        absoluteGrowth(values, record, failures, "sample.safe", 2 * MIB);
        absoluteGrowth(values, record, failures, "sample.full", 4 * MIB);
        maximum(values, record, failures, "plugin.jarBytes", 10 * MIB);
        maximum(values, record, failures, "dependencies.newBytes", 50 * MIB);
        record.append("rawInput.sha256=").append(digest(values)).append('\n')
                .append("complexity.verdict=PASS\n")
                .append("verdict=").append(failures.isEmpty() ? "PASS" : "FAIL").append('\n');
        return new Verdict(record.toString(), List.copyOf(failures));
    }

    private static void duration(Properties values, StringBuilder record, List<String> failures,
            String prefix, double ratio, double floor) {
        var baseline = median(series(values, prefix + ".baselineSeconds"));
        var candidate = median(series(values, prefix + ".candidateSeconds"));
        var limit = baseline + Math.max(baseline * ratio, floor);
        result(record, failures, prefix, baseline, candidate, limit);
    }

    private static void overhead(Properties values, StringBuilder record, List<String> failures,
            String prefix, double ratio, double floor) {
        var baseline = median(series(values, prefix + ".baseline"));
        var candidate = median(series(values, prefix + ".candidate"));
        var limit = baseline + Math.max(baseline * ratio, floor);
        result(record, failures, prefix, baseline, candidate, limit);
    }

    private static void growth(Properties values, StringBuilder record, List<String> failures,
            String prefix, double ratio, long floor) {
        var baseline = number(values, prefix + ".baselineBytes");
        var candidate = number(values, prefix + ".candidateBytes");
        var limit = baseline + Math.max(Math.round(baseline * ratio), floor);
        result(record, failures, prefix, baseline, candidate, limit);
    }

    private static void absoluteGrowth(Properties values, StringBuilder record, List<String> failures,
            String prefix, long growth) {
        var baseline = number(values, prefix + ".baselineBytes");
        var candidate = number(values, prefix + ".candidateBytes");
        result(record, failures, prefix, baseline, candidate, baseline + growth);
    }

    private static void maximum(Properties values, StringBuilder record, List<String> failures,
            String name, long limit) {
        result(record, failures, name, 0, number(values, name), limit);
    }

    private static void result(StringBuilder record, List<String> failures, String name,
            double baseline, double candidate, double limit) {
        record.append(name).append(".baseline=").append(decimal(baseline)).append('\n')
                .append(name).append(".candidate=").append(decimal(candidate)).append('\n')
                .append(name).append(".limit=").append(decimal(limit)).append('\n');
        if (candidate > limit) failures.add(name + " exceeded " + decimal(limit));
    }

    private static double[] series(Properties values, String name) {
        var parts = require(values, name).split(",", -1);
        if (parts.length != 7) throw failure(name + " must contain two warmups and five samples");
        var measured = new double[5];
        for (int index = 0; index < parts.length; index++) {
            var parsed = Double.parseDouble(parts[index]);
            if (!Double.isFinite(parsed) || parsed < 0) throw failure(name + " contains invalid value");
            if (index >= 2) measured[index - 2] = parsed;
        }
        return measured;
    }

    private static double median(double[] values) {
        Arrays.sort(values);
        return values[values.length / 2];
    }

    private static long number(Properties values, String name) {
        var value = Long.parseLong(require(values, name));
        if (value < 0) throw failure(name + " cannot be negative");
        return value;
    }

    private static void exact(Properties values, String name, String expected) {
        if (!expected.equals(require(values, name))) throw failure(name + " must be " + expected);
    }

    private static String require(Properties values, String name) {
        var value = values.getProperty(name);
        if (value == null || value.isBlank()) throw failure("missing measurement: " + name);
        return value.trim();
    }

    private static String digest(Properties values) throws Exception {
        var canonical = values.stringPropertyNames().stream().sorted()
                .map(name -> name + "=" + values.getProperty(name).trim() + "\n")
                .reduce("", String::concat);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private static String decimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
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

    private static String required(Map<String, String> values, String name) {
        var value = values.get(name);
        if (value == null || value.isBlank()) throw failure("--" + name + " is required");
        return value;
    }

    private static IllegalArgumentException failure(String message) {
        return new IllegalArgumentException(DIAGNOSTIC + message);
    }

    record Verdict(String record, List<String> failures) {}
}
