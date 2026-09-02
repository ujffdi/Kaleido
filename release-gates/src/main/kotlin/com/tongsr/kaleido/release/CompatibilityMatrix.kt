package com.tongsr.kaleido.release

import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.TreeSet
import java.util.regex.Pattern

/** Exact mandatory compatibility rows and their canonical release-evidence record. */
object CompatibilityMatrix {
    const val SCHEMA: String = "KaleidoCompatibilityMatrix.v1"
    private val SHA_256: Pattern = Pattern.compile("[0-9a-f]{64}")
    private val REQUIRED_FIXTURES: Set<String> = java.util.Set.copyOf(
        listOf(
            "exhaustive-boundary",
            "full-compose",
            "java-safe",
            "kotlin-safe",
            "sample-comprehensive",
        ),
    )
    private val MANDATORY_ROWS: Map<String, Row> = mandatoryRows()

    @JvmStatic
    fun mandatoryRows(): Map<String, Row> {
        val rows = LinkedHashMap<String, Row>()
        rows["A3"] = Row(
            "A3", "9.2.0", "9.4.1", "macos", "arm64",
            17, "36.0.0", 36, "built-in",
        )
        return java.util.Map.copyOf(rows)
    }

    @JvmStatic
    fun requiredFixtures(): Set<String> = REQUIRED_FIXTURES

    @JvmStatic
    fun requireRow(id: String): Row =
        MANDATORY_ROWS[id]
            ?: throw IllegalArgumentException("KLD-COMPAT-001 unsupported mandatory matrix row: $id")

    @JvmStatic
    fun canonicalRecord(
        candidateDigest: String,
        actual: Row,
        fixtures: List<FixtureResult>,
    ): ByteArray {
        requireDigest("candidate", candidateDigest)
        val expected = requireRow(actual.id)
        require(expected == actual) {
            "KLD-COMPAT-001 mandatory row environment differs from ${expected.id}"
        }
        val byName = LinkedHashMap<String, FixtureResult>()
        for (fixture in fixtures) {
            require(byName.putIfAbsent(fixture.name, fixture) == null) {
                "KLD-COMPAT-001 duplicate fixture result: ${fixture.name}"
            }
        }
        if (byName.keys != REQUIRED_FIXTURES) {
            val missing = TreeSet(REQUIRED_FIXTURES)
            missing.removeAll(byName.keys)
            val unexpected = TreeSet(byName.keys)
            unexpected.removeAll(REQUIRED_FIXTURES)
            throw IllegalArgumentException(
                "KLD-COMPAT-001 fixture closure differs; missing=$missing unexpected=$unexpected",
            )
        }

        val lines = ArrayList<String>()
        lines.add("schema=$SCHEMA")
        lines.add("candidate.sha256=$candidateDigest")
        lines.add("row.id=${actual.id}")
        lines.add("row.agp=${actual.agp}")
        lines.add("row.gradle=${actual.gradle}")
        lines.add("row.os=${actual.os}")
        lines.add("row.arch=${actual.architecture}")
        lines.add("row.jdk=${actual.jdk}")
        lines.add("row.buildTools=${actual.buildTools}")
        lines.add("row.compileSdk=${actual.compileSdk}")
        lines.add("row.kotlinMode=${actual.kotlinMode}")
        lines.add("fixtures=${REQUIRED_FIXTURES.size}")
        byName.values.sortedBy { it.name }.forEach { fixture ->
            validateFixture(fixture)
            val prefix = "fixture.${fixture.name}."
            lines.add(prefix + "source.sha256=" + fixture.sourceDigest)
            lines.add(prefix + "aab.sha256=" + fixture.aabDigest)
            lines.add(prefix + "result=" + fixture.result.name)
        }
        lines.add("verdict=PASS")
        return (lines.joinToString("\n") + "\n").toByteArray(StandardCharsets.UTF_8)
    }

    private fun validateFixture(fixture: FixtureResult) {
        require(REQUIRED_FIXTURES.contains(fixture.name)) {
            "KLD-COMPAT-001 unexpected fixture result: ${fixture.name}"
        }
        requireDigest("${fixture.name} source", fixture.sourceDigest)
        if (fixture.aabDigest.isEmpty()) {
            require(fixture.name == "exhaustive-boundary") {
                "KLD-COMPAT-001 missing AAB digest: ${fixture.name}"
            }
        } else {
            requireDigest("${fixture.name} AAB", fixture.aabDigest)
        }
        require(fixture.result == Result.PASS) {
            "KLD-COMPAT-001 mandatory fixture failed: ${fixture.name}"
        }
    }

    private fun requireDigest(name: String, value: String?) {
        require(value != null && SHA_256.matcher(value).matches()) {
            "KLD-COMPAT-001 invalid $name SHA-256 digest"
        }
    }

    enum class Result {
        PASS,
        FAIL,
        ;

        companion object {
            @JvmStatic
            fun parse(value: String): Result = valueOf(value.uppercase(Locale.ROOT))
        }
    }

    data class Row(
        val id: String,
        val agp: String,
        val gradle: String,
        val os: String,
        val architecture: String,
        val jdk: Int,
        val buildTools: String,
        val compileSdk: Int,
        val kotlinMode: String,
    )

    data class FixtureResult(
        val name: String,
        val sourceDigest: String,
        val aabDigest: String,
        val result: Result,
    )
}
