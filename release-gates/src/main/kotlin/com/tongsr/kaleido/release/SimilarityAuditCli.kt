package com.tongsr.kaleido.release

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale
import java.util.regex.Pattern

/** Fails publication when independently authored content contains substantial upstream expression. */
object SimilarityAuditCli {
    private const val DIAGNOSTIC = "KLD-PROVENANCE-001 "
    private const val SHINGLE_TOKENS = 16

    // Long enough to exclude unavoidable Android/Gradle boilerplate while still catching copied
    // methods, templates, and generated fragments. Exact normalized files always block.
    private const val BLOCKING_RUN_TOKENS = 128
    private val EXTENSIONS: Set<String> = setOf(
        "java", "kt", "kts", "groovy", "xml", "json", "properties", "txt", "md",
    )
    private val TOKEN: Pattern = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*|[0-9]+|\\S")

    @JvmStatic
    fun main(arguments: Array<String>) {
        val options = parse(arguments)
        val output = Path.of(required(options, "output"))
        val candidates = files(paths(options, "candidate"))
        val upstreams = files(paths(options, "upstream"))
        if (candidates.isEmpty()) throw failure("candidate audit scope is empty")
        if (upstreams.isEmpty()) throw failure("upstream audit scope is empty")

        val findings = compare(candidates, upstreams)
        val text = StringBuilder("schema=KaleidoContentSimilarityAudit.v1\n")
            .append("algorithm=normalized-token-shingles-v1\n")
            .append("shingleTokens=").append(SHINGLE_TOKENS).append('\n')
            .append("blockingRunTokens=").append(BLOCKING_RUN_TOKENS).append('\n')
            .append("candidateFiles=").append(candidates.size).append('\n')
            .append("upstreamFiles=").append(upstreams.size).append('\n')
            .append("comparisons=").append(candidates.size.toLong() * upstreams.size).append('\n')
            .append("findings=").append(findings.size).append('\n')
        for (index in findings.indices) {
            val finding = findings[index]
            text.append("finding.").append(index).append(".candidate=")
                .append(finding.candidate).append('\n')
                .append("finding.").append(index).append(".upstream=")
                .append(finding.upstream).append('\n')
                .append("finding.").append(index).append(".runTokens=")
                .append(finding.runTokens).append('\n')
                .append("finding.").append(index).append(".exactNormalized=")
                .append(finding.exactNormalized).append('\n')
        }
        text.append("verdict=").append(if (findings.isEmpty()) "PASS" else "FAIL").append('\n')
        writeAtomically(output, text.toString())
        if (findings.isNotEmpty()) {
            throw failure("substantial upstream similarity found; see $output")
        }
    }

    @JvmStatic
    fun compare(candidates: List<Source>, upstreams: List<Source>): List<Finding> {
        val findings = ArrayList<Finding>()
        for (candidate in candidates) {
            if (candidate.tokens.size < SHINGLE_TOKENS) continue
            for (upstream in upstreams) {
                if (upstream.tokens.size < SHINGLE_TOKENS) continue
                val exact = candidate.normalizedDigest == upstream.normalizedDigest
                val run = longestCommonTokenRun(candidate.tokens, upstream.tokens)
                if (exact || run >= BLOCKING_RUN_TOKENS) {
                    findings.add(Finding(candidate.path, upstream.path, run, exact))
                }
            }
        }
        return findings.sortedWith(compareBy(Finding::candidate).thenBy(Finding::upstream))
    }

    private fun longestCommonTokenRun(left: List<String>, right: List<String>): Int {
        val positions = HashMap<String, MutableList<Int>>()
        var index = 0
        while (index + SHINGLE_TOKENS <= right.size) {
            positions.getOrPut(shingle(right, index)) { ArrayList() }.add(index)
            index++
        }
        var longest = 0
        var leftIndex = 0
        while (leftIndex + SHINGLE_TOKENS <= left.size) {
            val matches = positions[shingle(left, leftIndex)]
            if (matches == null) {
                leftIndex++
                continue
            }
            for (rightIndex in matches) {
                var run = SHINGLE_TOKENS
                while (
                    leftIndex + run < left.size &&
                    rightIndex + run < right.size &&
                    left[leftIndex + run] == right[rightIndex + run]
                ) {
                    run++
                }
                longest = maxOf(longest, run)
            }
            leftIndex++
        }
        return longest
    }

    private fun shingle(tokens: List<String>, start: Int): String =
        tokens.subList(start, start + SHINGLE_TOKENS).joinToString("\u0000")

    private fun files(roots: List<Path>): List<Source> {
        val files = ArrayList<Source>()
        for (root in roots) {
            if (!Files.exists(root)) throw failure("audit root is missing: $root")
            Files.walk(root).use { paths ->
                for (path in paths.filter { Files.isRegularFile(it) }.sorted().toList()) {
                    val portable = path.toString().replace('\\', '/')
                    if ((portable.contains("/release/fixtures/") || portable.contains("/samples/")) &&
                        portable.contains("/build/") &&
                        !portable.contains("/build/generated/kaleido/")
                    ) {
                        continue
                    }
                    if (extension(path) !in EXTENSIONS) continue
                    val text = Files.readString(path, StandardCharsets.UTF_8)
                    val tokens = TOKEN.matcher(text).results().map { it.group() }.toList()
                    files.add(
                        Source(
                            "${root.fileName}/${root.relativize(path)}",
                            tokens,
                            digest(tokens.joinToString("\u0000")),
                        ),
                    )
                }
            }
        }
        return files
    }

    private fun extension(path: Path): String {
        val name = path.fileName.toString()
        val dot = name.lastIndexOf('.')
        return if (dot < 0) "" else name.substring(dot + 1).lowercase(Locale.ROOT)
    }

    private fun paths(options: Map<String, List<String>>, name: String): List<Path> {
        val values = options[name]
        if (values.isNullOrEmpty()) throw failure("at least one --$name is required")
        return values.map { Path.of(it) }
    }

    private fun parse(arguments: Array<String>): Map<String, List<String>> {
        val values = LinkedHashMap<String, MutableList<String>>()
        var index = 0
        while (index < arguments.size) {
            if (!arguments[index].startsWith("--") || index + 1 >= arguments.size) {
                throw failure("arguments must be --name value pairs")
            }
            values.getOrPut(arguments[index].substring(2)) { ArrayList() }
                .add(arguments[index + 1])
            index += 2
        }
        return values
    }

    private fun required(options: Map<String, List<String>>, name: String): String {
        val values = options[name]
        if (values == null || values.size != 1 || values[0].isBlank()) {
            throw failure("exactly one --$name is required")
        }
        return values[0]
    }

    private fun digest(value: String): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
        )

    private fun writeAtomically(output: Path, text: String) {
        Files.createDirectories(output.toAbsolutePath().parent)
        val staged = Files.createTempFile(
            output.toAbsolutePath().parent,
            output.fileName.toString(),
            ".tmp",
        )
        try {
            Files.writeString(staged, text, StandardCharsets.UTF_8)
            try {
                Files.move(
                    staged,
                    output,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staged, output, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(staged)
        }
    }

    private fun failure(message: String): IllegalArgumentException =
        IllegalArgumentException(DIAGNOSTIC + message)

    data class Source(
        val path: String,
        val tokens: List<String>,
        val normalizedDigest: String,
    )

    data class Finding(
        val candidate: String,
        val upstream: String,
        val runTokens: Int,
        val exactNormalized: Boolean,
    )
}
