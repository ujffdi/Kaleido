package com.tongsr.kaleido.gradle

import java.util.LinkedHashMap
import java.util.TreeMap
import java.util.regex.Matcher
import java.util.regex.Pattern

internal object R8MappingComposer {
    const val SCHEMA: String = "KaleidoComposedMapping.v1"
    const val PRODUCER: String = "KaleidoMappingComposer/1"
    private val TYPE: Pattern = Pattern.compile(
        "(?<![A-Za-z0-9_$])(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)+" +
            "[A-Za-z_$][A-Za-z0-9_$]*(?![A-Za-z0-9_$])",
    )
    private val MAPPING_VERSION: Pattern = Pattern.compile(
        "\\{\"id\":\"com\\.android\\.tools\\.r8\\.mapping\"," +
            "\"version\":\"([^\"]+)\"}",
    )

    @JvmStatic
    fun compose(rawKaleido: String, rawR8: String): Result {
        val originalToKaleido = parseKaleido(rawKaleido)
        val kaleidoToOriginal = TreeMap<String, String>()
        originalToKaleido.forEach { original, transformed ->
            if (kaleidoToOriginal.putIfAbsent(transformed, original) != null) {
                throw IllegalArgumentException(
                    "Raw Kaleido mapping is not bijective at $transformed",
                )
            }
        }
        val rawMetadata = parseMetadata(rawR8)
        if (rawMetadata.mappingVersion.isBlank()) {
            throw IllegalArgumentException("Raw R8 mapping has no mapping version metadata")
        }

        val output = StringBuilder()
            .append("# compiler: Kaleido\n")
            .append("# compiler_version: 1\n")
            .append("# kaleido_composer: ").append(PRODUCER).append('\n')
            .append("# kaleido_raw_r8_compiler: ").append(rawMetadata.compiler).append('\n')
            .append("# kaleido_raw_r8_compiler_version: ")
            .append(rawMetadata.compilerVersion).append('\n')
            .append("# kaleido_raw_r8_pg_map_id: ").append(rawMetadata.pgMapId).append('\n')
            .append("# kaleido_raw_r8_pg_map_hash: ").append(rawMetadata.pgMapHash).append('\n')
            .append("# {\"id\":\"com.android.tools.r8.mapping\",\"version\":\"")
            .append(rawMetadata.mappingVersion).append("\"}\n")

        var classRows = 0
        val lines = rawR8.split("\n")
        var inBody = false
        for (index in lines.indices) {
            val line = lines[index]
            if (!inBody && classHeader(line) == null) {
                if (line.startsWith("# min_api:") || line == "# common_typos_disable") {
                    output.append(line).append('\n')
                }
                continue
            }
            val header = classHeader(line)
            if (header != null) {
                inBody = true
                classRows++
                output.append(kaleidoToOriginal.getOrDefault(header.original, header.original))
                    .append(" -> ").append(header.obfuscated).append(":\n")
            } else if (line.startsWith(" ") && line.contains(" -> ")) {
                val separator = line.indexOf(" -> ")
                output.append(rewriteTypes(line.substring(0, separator), kaleidoToOriginal))
                    .append(line.substring(separator)).append('\n')
            } else if (inBody && !(line.isEmpty() && index == lines.lastIndex)) {
                // R8 mapping-information comments, including residual signatures, are retained.
                output.append(line).append('\n')
            }
        }
        if (classRows == 0) {
            throw IllegalArgumentException("Raw R8 mapping contains no class rows")
        }
        return Result(output.toString(), rawMetadata, originalToKaleido.size, classRows)
    }

    private fun parseKaleido(text: String): Map<String, String> {
        val values = TreeMap<String, String>()
        val lines = text.split("\n")
        if (lines.isEmpty() || lines[0] != "schema=KaleidoRawClassMapping.v1") {
            throw IllegalArgumentException("Unknown raw Kaleido mapping schema")
        }
        for (index in 1 until lines.size) {
            val line = lines[index]
            if (line.isBlank()) continue
            val separator = line.indexOf(" -> ")
            if (separator <= 0 || line.indexOf(" -> ", separator + 4) >= 0) {
                throw IllegalArgumentException("Malformed raw Kaleido mapping row: $line")
            }
            val original = line.substring(0, separator)
            val transformed = line.substring(separator + 4)
            if (transformed.isBlank() || values.putIfAbsent(original, transformed) != null) {
                throw IllegalArgumentException("Duplicate raw Kaleido mapping row: $original")
            }
        }
        return values.toMap()
    }

    private fun parseMetadata(rawR8: String): Metadata {
        val values = LinkedHashMap<String, String>()
        var mappingVersion = ""
        for (line in rawR8.split("\n")) {
            if (!line.startsWith("#")) break
            val matcher = MAPPING_VERSION.matcher(line)
            if (matcher.find()) mappingVersion = matcher.group(1)
            val separator = line.indexOf(':')
            if (separator > 2) {
                values[line.substring(2, separator).trim()] =
                    line.substring(separator + 1).trim()
            }
        }
        return Metadata(
            values.getOrDefault("compiler", ""),
            values.getOrDefault("compiler_version", ""),
            mappingVersion,
            values.getOrDefault("pg_map_id", ""),
            values.getOrDefault("pg_map_hash", ""),
        )
    }

    private fun classHeader(line: String): Header? {
        if (line.isEmpty() || line[0].isWhitespace() || line[0] == '#') {
            return null
        }
        val separator = line.indexOf(" -> ")
        if (separator <= 0 || !line.endsWith(":")) return null
        return Header(line.substring(0, separator), line.substring(separator + 4, line.length - 1))
    }

    private fun rewriteTypes(value: String, inverse: Map<String, String>): String {
        val matcher = TYPE.matcher(value)
        val output = StringBuffer()
        while (matcher.find()) {
            val type = matcher.group()
            matcher.appendReplacement(output, Matcher.quoteReplacement(inverse[type] ?: type))
        }
        matcher.appendTail(output)
        return output.toString()
    }

    @JvmRecord
    data class Result(
        val composedMapping: String,
        val rawMetadata: Metadata,
        val kaleidoMappingRows: Int,
        val r8ClassRows: Int,
    )

    @JvmRecord
    data class Metadata(
        val compiler: String,
        val compilerVersion: String,
        val mappingVersion: String,
        val pgMapId: String,
        val pgMapHash: String,
    )

    @JvmRecord
    private data class Header(val original: String, val obfuscated: String)
}
