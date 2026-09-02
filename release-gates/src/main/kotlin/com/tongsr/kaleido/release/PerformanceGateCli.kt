package com.tongsr.kaleido.release

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale
import java.util.Properties

/** Evaluates the fixed release performance and size budgets from controlled raw measurements. */
object PerformanceGateCli {
    private const val DIAGNOSTIC = "KLD-PERF-001 "
    private const val MIB = 1024L * 1024L

    @JvmStatic
    fun main(arguments: Array<String>) {
        val options = parse(arguments)
        val input = Path.of(required(options, "input"))
        val output = Path.of(required(options, "output"))
        if (!Files.isRegularFile(input)) throw failure("measurement input is missing")
        val properties = Properties()
        Files.newBufferedReader(input, StandardCharsets.UTF_8).use { reader ->
            properties.load(reader)
        }
        val verdict = evaluate(properties)
        val bytes = verdict.record.toByteArray(StandardCharsets.UTF_8)
        Files.createDirectories(output.toAbsolutePath().parent)
        val staged = Files.createTempFile(
            output.toAbsolutePath().parent,
            output.fileName.toString(),
            ".tmp",
        )
        try {
            Files.write(staged, bytes)
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
        if (verdict.failures.isNotEmpty()) {
            throw failure(verdict.failures.joinToString("; "))
        }
    }

    @JvmStatic
    fun evaluate(values: Properties): Verdict {
        requireProperty(values, "candidate.sha256")
        exact(values, "environment.os", "Linux")
        exact(values, "environment.arch", "x86_64")
        exact(values, "complexity.verdict", "PASS")
        val failures = ArrayList<String>()
        val record = StringBuilder("schema=KaleidoPerformanceGate.v1\n")
            .append("candidate.sha256=").append(requireProperty(values, "candidate.sha256")).append('\n')
            .append("environment.os=Linux\nenvironment.arch=x86_64\n")
            .append("warmups=2\nsamples=5\nstatistic=median\n")

        duration(values, record, failures, "safe.clean", 0.20, 45.0)
        duration(values, record, failures, "full.clean", 0.30, 90.0)
        duration(values, record, failures, "warm.noClean", 0.10, 15.0)
        overhead(values, record, failures, "memory.peakMib", 0.25, 512.0)
        growth(values, record, failures, "sana.safe", 0.01, 1 * MIB)
        growth(values, record, failures, "sana.full", 0.02, 2 * MIB)
        growth(values, record, failures, "sana.compose512", 0.05, 5 * MIB)
        absoluteGrowth(values, record, failures, "sample.safe", 2 * MIB)
        absoluteGrowth(values, record, failures, "sample.full", 4 * MIB)
        maximum(values, record, failures, "plugin.jarBytes", 10 * MIB)
        maximum(values, record, failures, "dependencies.newBytes", 50 * MIB)
        record.append("rawInput.sha256=").append(digest(values)).append('\n')
            .append("complexity.verdict=PASS\n")
            .append("verdict=").append(if (failures.isEmpty()) "PASS" else "FAIL").append('\n')
        return Verdict(record.toString(), failures.toList())
    }

    private fun duration(
        values: Properties,
        record: StringBuilder,
        failures: MutableList<String>,
        prefix: String,
        ratio: Double,
        floor: Double,
    ) {
        val baseline = median(series(values, "$prefix.baselineSeconds"))
        val candidate = median(series(values, "$prefix.candidateSeconds"))
        val limit = baseline + maxOf(baseline * ratio, floor)
        result(record, failures, prefix, baseline, candidate, limit)
    }

    private fun overhead(
        values: Properties,
        record: StringBuilder,
        failures: MutableList<String>,
        prefix: String,
        ratio: Double,
        floor: Double,
    ) {
        val baseline = median(series(values, "$prefix.baseline"))
        val candidate = median(series(values, "$prefix.candidate"))
        val limit = baseline + maxOf(baseline * ratio, floor)
        result(record, failures, prefix, baseline, candidate, limit)
    }

    private fun growth(
        values: Properties,
        record: StringBuilder,
        failures: MutableList<String>,
        prefix: String,
        ratio: Double,
        floor: Long,
    ) {
        val baseline = number(values, "$prefix.baselineBytes")
        val candidate = number(values, "$prefix.candidateBytes")
        val limit = baseline + maxOf(Math.round(baseline * ratio), floor)
        result(record, failures, prefix, baseline.toDouble(), candidate.toDouble(), limit.toDouble())
    }

    private fun absoluteGrowth(
        values: Properties,
        record: StringBuilder,
        failures: MutableList<String>,
        prefix: String,
        growth: Long,
    ) {
        val baseline = number(values, "$prefix.baselineBytes")
        val candidate = number(values, "$prefix.candidateBytes")
        result(
            record,
            failures,
            prefix,
            baseline.toDouble(),
            candidate.toDouble(),
            (baseline + growth).toDouble(),
        )
    }

    private fun maximum(
        values: Properties,
        record: StringBuilder,
        failures: MutableList<String>,
        name: String,
        limit: Long,
    ) {
        result(record, failures, name, 0.0, number(values, name).toDouble(), limit.toDouble())
    }

    private fun result(
        record: StringBuilder,
        failures: MutableList<String>,
        name: String,
        baseline: Double,
        candidate: Double,
        limit: Double,
    ) {
        record.append(name).append(".baseline=").append(decimal(baseline)).append('\n')
            .append(name).append(".candidate=").append(decimal(candidate)).append('\n')
            .append(name).append(".limit=").append(decimal(limit)).append('\n')
        if (candidate > limit) failures.add("$name exceeded ${decimal(limit)}")
    }

    private fun series(values: Properties, name: String): DoubleArray {
        val parts = requireProperty(values, name).split(",", limit = 0)
        if (parts.size != 7) throw failure("$name must contain two warmups and five samples")
        val measured = DoubleArray(5)
        for (index in parts.indices) {
            val parsed = parts[index].toDouble()
            if (!parsed.isFinite() || parsed < 0) throw failure("$name contains invalid value")
            if (index >= 2) measured[index - 2] = parsed
        }
        return measured
    }

    private fun median(values: DoubleArray): Double {
        values.sort()
        return values[values.size / 2]
    }

    private fun number(values: Properties, name: String): Long {
        val value = requireProperty(values, name).toLong()
        if (value < 0) throw failure("$name cannot be negative")
        return value
    }

    private fun exact(values: Properties, name: String, expected: String) {
        if (expected != requireProperty(values, name)) throw failure("$name must be $expected")
    }

    private fun requireProperty(values: Properties, name: String): String {
        val value = values.getProperty(name)
        if (value.isNullOrBlank()) throw failure("missing measurement: $name")
        return value.trim()
    }

    private fun digest(values: Properties): String {
        val canonical = values.stringPropertyNames().sorted()
            .joinToString("") { name -> "$name=${values.getProperty(name).trim()}\n" }
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8)),
        )
    }

    private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

    private fun parse(arguments: Array<String>): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        var index = 0
        while (index < arguments.size) {
            if (!arguments[index].startsWith("--") || index + 1 >= arguments.size ||
                values.put(arguments[index].substring(2), arguments[index + 1]) != null
            ) {
                throw failure("arguments must be unique --name value pairs")
            }
            index += 2
        }
        return values
    }

    private fun required(values: Map<String, String>, name: String): String {
        val value = values[name]
        if (value.isNullOrBlank()) throw failure("--$name is required")
        return value
    }

    private fun failure(message: String): IllegalArgumentException =
        IllegalArgumentException(DIAGNOSTIC + message)

    data class Verdict(val record: String, val failures: List<String>)
}
