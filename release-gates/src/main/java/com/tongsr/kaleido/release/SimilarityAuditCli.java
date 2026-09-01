package com.tongsr.kaleido.release;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Fails publication when independently authored content contains substantial upstream expression. */
public final class SimilarityAuditCli {
    private static final String DIAGNOSTIC = "KLD-PROVENANCE-001 ";
    private static final int SHINGLE_TOKENS = 16;
    // Long enough to exclude unavoidable Android/Gradle boilerplate while still catching copied
    // methods, templates, and generated fragments. Exact normalized files always block.
    private static final int BLOCKING_RUN_TOKENS = 128;
    private static final Set<String> EXTENSIONS = Set.of(
            "java", "kt", "kts", "groovy", "xml", "json", "properties", "txt", "md");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*|[0-9]+|\\S");

    private SimilarityAuditCli() {}

    public static void main(String[] arguments) throws Exception {
        var options = parse(arguments);
        var output = Path.of(required(options, "output"));
        var candidates = files(paths(options, "candidate"));
        var upstreams = files(paths(options, "upstream"));
        if (candidates.isEmpty()) throw failure("candidate audit scope is empty");
        if (upstreams.isEmpty()) throw failure("upstream audit scope is empty");

        var findings = compare(candidates, upstreams);
        var text = new StringBuilder("schema=KaleidoContentSimilarityAudit.v1\n")
                .append("algorithm=normalized-token-shingles-v1\n")
                .append("shingleTokens=").append(SHINGLE_TOKENS).append('\n')
                .append("blockingRunTokens=").append(BLOCKING_RUN_TOKENS).append('\n')
                .append("candidateFiles=").append(candidates.size()).append('\n')
                .append("upstreamFiles=").append(upstreams.size()).append('\n')
                .append("comparisons=").append((long) candidates.size() * upstreams.size()).append('\n')
                .append("findings=").append(findings.size()).append('\n');
        for (int index = 0; index < findings.size(); index++) {
            var finding = findings.get(index);
            text.append("finding.").append(index).append(".candidate=")
                    .append(finding.candidate()).append('\n')
                    .append("finding.").append(index).append(".upstream=")
                    .append(finding.upstream()).append('\n')
                    .append("finding.").append(index).append(".runTokens=")
                    .append(finding.runTokens()).append('\n')
                    .append("finding.").append(index).append(".exactNormalized=")
                    .append(finding.exactNormalized()).append('\n');
        }
        text.append("verdict=").append(findings.isEmpty() ? "PASS" : "FAIL").append('\n');
        writeAtomically(output, text.toString());
        if (!findings.isEmpty()) {
            throw failure("substantial upstream similarity found; see " + output);
        }
    }

    static List<Finding> compare(List<Source> candidates, List<Source> upstreams) {
        var findings = new ArrayList<Finding>();
        for (var candidate : candidates) {
            if (candidate.tokens().size() < SHINGLE_TOKENS) continue;
            for (var upstream : upstreams) {
                if (upstream.tokens().size() < SHINGLE_TOKENS) continue;
                var exact = candidate.normalizedDigest().equals(upstream.normalizedDigest());
                var run = longestCommonTokenRun(candidate.tokens(), upstream.tokens());
                if (exact || run >= BLOCKING_RUN_TOKENS) {
                    findings.add(new Finding(candidate.path(), upstream.path(), run, exact));
                }
            }
        }
        return findings.stream().sorted(Comparator.comparing(Finding::candidate)
                .thenComparing(Finding::upstream)).toList();
    }

    private static int longestCommonTokenRun(List<String> left, List<String> right) {
        var positions = new HashMap<String, List<Integer>>();
        for (int index = 0; index + SHINGLE_TOKENS <= right.size(); index++) {
            positions.computeIfAbsent(shingle(right, index), ignored -> new ArrayList<>()).add(index);
        }
        var longest = 0;
        for (int leftIndex = 0; leftIndex + SHINGLE_TOKENS <= left.size(); leftIndex++) {
            var matches = positions.get(shingle(left, leftIndex));
            if (matches == null) continue;
            for (var rightIndex : matches) {
                var run = SHINGLE_TOKENS;
                while (leftIndex + run < left.size() && rightIndex + run < right.size()
                        && left.get(leftIndex + run).equals(right.get(rightIndex + run))) {
                    run++;
                }
                longest = Math.max(longest, run);
            }
        }
        return longest;
    }

    private static String shingle(List<String> tokens, int start) {
        return String.join("\u0000", tokens.subList(start, start + SHINGLE_TOKENS));
    }

    private static List<Source> files(List<Path> roots) throws Exception {
        var files = new ArrayList<Source>();
        for (var root : roots) {
            if (!Files.exists(root)) throw failure("audit root is missing: " + root);
            try (var paths = Files.walk(root)) {
                for (var path : paths.filter(Files::isRegularFile).sorted().toList()) {
                    var portable = path.toString().replace('\\', '/');
                    if ((portable.contains("/release/fixtures/") || portable.contains("/samples/"))
                            && portable.contains("/build/")
                            && !portable.contains("/build/generated/kaleido/")) continue;
                    if (!EXTENSIONS.contains(extension(path))) continue;
                    var text = Files.readString(path, StandardCharsets.UTF_8);
                    var tokens = TOKEN.matcher(text).results().map(result -> result.group()).toList();
                    files.add(new Source(root.getFileName() + "/" + root.relativize(path), tokens,
                            digest(String.join("\u0000", tokens))));
                }
            }
        }
        return files;
    }

    private static String extension(Path path) {
        var name = path.getFileName().toString();
        var dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static List<Path> paths(Map<String, List<String>> options, String name) {
        var values = options.get(name);
        if (values == null || values.isEmpty()) throw failure("at least one --" + name + " is required");
        return values.stream().map(Path::of).toList();
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

    private static String required(Map<String, List<String>> options, String name) {
        var values = options.get(name);
        if (values == null || values.size() != 1 || values.get(0).isBlank()) {
            throw failure("exactly one --" + name + " is required");
        }
        return values.get(0);
    }

    private static String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static void writeAtomically(Path output, String text) throws Exception {
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

    private static IllegalArgumentException failure(String message) {
        return new IllegalArgumentException(DIAGNOSTIC + message);
    }

    record Source(String path, List<String> tokens, String normalizedDigest) {}
    record Finding(String candidate, String upstream, int runTokens, boolean exactNormalized) {}
}
