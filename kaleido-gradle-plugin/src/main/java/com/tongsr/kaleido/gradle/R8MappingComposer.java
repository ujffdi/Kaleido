package com.tongsr.kaleido.gradle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class R8MappingComposer {
    static final String SCHEMA = "KaleidoComposedMapping.v1";
    static final String PRODUCER = "KaleidoMappingComposer/1";
    private static final Pattern TYPE = Pattern.compile(
            "(?<![A-Za-z0-9_$])(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)+"
                    + "[A-Za-z_$][A-Za-z0-9_$]*(?![A-Za-z0-9_$])");
    private static final Pattern MAPPING_VERSION = Pattern.compile(
            "\\{\\\"id\\\":\\\"com\\.android\\.tools\\.r8\\.mapping\\\","
                    + "\\\"version\\\":\\\"([^\\\"]+)\\\"}");

    private R8MappingComposer() {}

    static Result compose(String rawKaleido, String rawR8) {
        var originalToKaleido = parseKaleido(rawKaleido);
        var kaleidoToOriginal = new TreeMap<String, String>();
        originalToKaleido.forEach((original, transformed) -> {
            if (kaleidoToOriginal.putIfAbsent(transformed, original) != null) {
                throw new IllegalArgumentException(
                        "Raw Kaleido mapping is not bijective at " + transformed);
            }
        });
        var rawMetadata = parseMetadata(rawR8);
        if (rawMetadata.mappingVersion().isBlank()) {
            throw new IllegalArgumentException("Raw R8 mapping has no mapping version metadata");
        }

        var output = new StringBuilder()
                .append("# compiler: Kaleido\n")
                .append("# compiler_version: 1\n")
                .append("# kaleido_composer: ").append(PRODUCER).append('\n')
                .append("# kaleido_raw_r8_compiler: ").append(rawMetadata.compiler()).append('\n')
                .append("# kaleido_raw_r8_compiler_version: ")
                .append(rawMetadata.compilerVersion()).append('\n')
                .append("# kaleido_raw_r8_pg_map_id: ").append(rawMetadata.pgMapId()).append('\n')
                .append("# kaleido_raw_r8_pg_map_hash: ").append(rawMetadata.pgMapHash()).append('\n')
                .append("# {\"id\":\"com.android.tools.r8.mapping\",\"version\":\"")
                .append(rawMetadata.mappingVersion()).append("\"}\n");

        var classRows = 0;
        var lines = rawR8.split("\\n", -1);
        var inBody = false;
        for (var line : lines) {
            if (!inBody && classHeader(line) == null) {
                if (line.startsWith("# min_api:") || line.equals("# common_typos_disable")) {
                    output.append(line).append('\n');
                }
                continue;
            }
            var header = classHeader(line);
            if (header != null) {
                inBody = true;
                classRows++;
                output.append(kaleidoToOriginal.getOrDefault(header.original(), header.original()))
                        .append(" -> ").append(header.obfuscated()).append(":\n");
            } else if (line.startsWith(" ") && line.contains(" -> ")) {
                var separator = line.indexOf(" -> ");
                output.append(rewriteTypes(line.substring(0, separator), kaleidoToOriginal))
                        .append(line.substring(separator)).append('\n');
            } else if (inBody && !(line.isEmpty() && line == lines[lines.length - 1])) {
                // R8 mapping-information comments, including residual signatures, are retained.
                output.append(line).append('\n');
            }
        }
        if (classRows == 0) {
            throw new IllegalArgumentException("Raw R8 mapping contains no class rows");
        }
        return new Result(output.toString(), rawMetadata, originalToKaleido.size(), classRows);
    }

    private static Map<String, String> parseKaleido(String text) {
        var values = new TreeMap<String, String>();
        var lines = text.split("\\n");
        if (lines.length == 0 || !"schema=KaleidoRawClassMapping.v1".equals(lines[0])) {
            throw new IllegalArgumentException("Unknown raw Kaleido mapping schema");
        }
        for (var index = 1; index < lines.length; index++) {
            var line = lines[index];
            if (line.isBlank()) continue;
            var separator = line.indexOf(" -> ");
            if (separator <= 0 || line.indexOf(" -> ", separator + 4) >= 0) {
                throw new IllegalArgumentException("Malformed raw Kaleido mapping row: " + line);
            }
            var original = line.substring(0, separator);
            var transformed = line.substring(separator + 4);
            if (transformed.isBlank() || values.putIfAbsent(original, transformed) != null) {
                throw new IllegalArgumentException("Duplicate raw Kaleido mapping row: " + original);
            }
        }
        return Map.copyOf(values);
    }

    private static Metadata parseMetadata(String rawR8) {
        var values = new LinkedHashMap<String, String>();
        var mappingVersion = "";
        for (var line : rawR8.split("\\n")) {
            if (!line.startsWith("#")) break;
            var matcher = MAPPING_VERSION.matcher(line);
            if (matcher.find()) mappingVersion = matcher.group(1);
            var separator = line.indexOf(':');
            if (separator > 2) {
                values.put(line.substring(2, separator).trim(),
                        line.substring(separator + 1).trim());
            }
        }
        return new Metadata(
                values.getOrDefault("compiler", ""),
                values.getOrDefault("compiler_version", ""),
                mappingVersion,
                values.getOrDefault("pg_map_id", ""),
                values.getOrDefault("pg_map_hash", ""));
    }

    private static Header classHeader(String line) {
        if (line.isEmpty() || Character.isWhitespace(line.charAt(0)) || line.charAt(0) == '#') {
            return null;
        }
        var separator = line.indexOf(" -> ");
        if (separator <= 0 || !line.endsWith(":")) return null;
        return new Header(line.substring(0, separator),
                line.substring(separator + 4, line.length() - 1));
    }

    private static String rewriteTypes(String value, Map<String, String> inverse) {
        var matcher = TYPE.matcher(value);
        var output = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(output,
                    Matcher.quoteReplacement(inverse.getOrDefault(matcher.group(), matcher.group())));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    record Result(String composedMapping, Metadata rawMetadata,
                  int kaleidoMappingRows, int r8ClassRows) {}
    record Metadata(String compiler, String compilerVersion, String mappingVersion,
                    String pgMapId, String pgMapHash) {}
    private record Header(String original, String obfuscated) {}
}
