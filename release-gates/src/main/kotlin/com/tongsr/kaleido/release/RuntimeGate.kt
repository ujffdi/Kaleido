package com.tongsr.kaleido.release

import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/** Canonical optional bundletool and controlled-device evidence for one compatibility row. */
object RuntimeGate {
    const val SCHEMA: String = "KaleidoRuntimeGate.v1"
    const val BUNDLETOOL_VERSION: String = "1.18.1"
    private val SHA_256: Pattern = Pattern.compile("[0-9a-f]{64}")
    private val REQUIRED_FIXTURES: Set<String> = java.util.Set.copyOf(
        listOf(
            "full-compose",
            "java-safe",
            "kotlin-safe",
            "native-resource",
            "sample-comprehensive",
        ),
    )

    @JvmStatic
    fun requiredFixtures(): Set<String> = REQUIRED_FIXTURES

    @JvmStatic
    fun canonicalRecord(
        candidateDigest: String,
        matrixRecordDigest: String,
        testRevisionDigest: String,
        row: CompatibilityMatrix.Row,
        deviceSpecDigest: String,
        fixtures: List<FixtureResult>,
    ): ByteArray {
        requireDigest("candidate", candidateDigest)
        requireDigest("matrix record", matrixRecordDigest)
        requireDigest("test revision", testRevisionDigest)
        requireDigest("device spec", deviceSpecDigest)
        if (CompatibilityMatrix.requireRow(row.id) != row) {
            throw failure("runtime row differs from the mandatory Compatibility Matrix")
        }
        val byName = LinkedHashMap<String, FixtureResult>()
        for (fixture in fixtures) {
            if (byName.putIfAbsent(fixture.name, fixture) != null) {
                throw failure("duplicate runtime fixture: ${fixture.name}")
            }
        }
        if (byName.keys != REQUIRED_FIXTURES) {
            throw failure("runtime fixture closure differs from the required set")
        }

        val lines = ArrayList<String>()
        lines.add("schema=$SCHEMA")
        lines.add("candidate.sha256=$candidateDigest")
        lines.add("matrixRecord.sha256=$matrixRecordDigest")
        lines.add("testRevision.sha256=$testRevisionDigest")
        lines.add("row.id=${row.id}")
        lines.add("bundletool.version=$BUNDLETOOL_VERSION")
        lines.add("deviceSpec.sha256=$deviceSpecDigest")
        lines.add("fixtures=${REQUIRED_FIXTURES.size}")
        byName.values.sortedBy { it.name }.forEach { fixture -> appendFixture(lines, fixture) }
        lines.add("verdict=PASS")
        return (lines.joinToString("\n") + "\n").toByteArray(StandardCharsets.UTF_8)
    }

    private fun appendFixture(lines: MutableList<String>, fixture: FixtureResult) {
        requireDigest("${fixture.name} AAB", fixture.aabDigest)
        requireDigest("${fixture.name} APK set", fixture.apksDigest)
        requirePass(fixture.name, "bundletool", fixture.bundletool)
        requirePass(fixture.name, "install", fixture.install)
        requirePass(fixture.name, "launch", fixture.launch)
        requirePass(fixture.name, "package identity", fixture.packageIdentity)
        requirePass(fixture.name, "launch Activity", fixture.launchActivity)
        requirePass(fixture.name, "resource lookup", fixture.resourceLookup)
        requirePass(fixture.name, "generated startup isolation", fixture.noGeneratedStartup)
        if (fixture.name == "native-resource") {
            requirePass(fixture.name, "native load", fixture.nativeLoad)
        } else if (fixture.nativeLoad != Check.NOT_APPLICABLE) {
            throw failure("${fixture.name} native load must be NOT_APPLICABLE")
        }
        if (fixture.result != CompatibilityMatrix.Result.PASS) {
            throw failure("mandatory runtime fixture failed: ${fixture.name}")
        }
        val prefix = "fixture.${fixture.name}."
        lines.add(prefix + "aab.sha256=" + fixture.aabDigest)
        lines.add(prefix + "apks.sha256=" + fixture.apksDigest)
        lines.add(prefix + "bundletool=" + fixture.bundletool)
        lines.add(prefix + "install=" + fixture.install)
        lines.add(prefix + "launch=" + fixture.launch)
        lines.add(prefix + "packageIdentity=" + fixture.packageIdentity)
        lines.add(prefix + "launchActivity=" + fixture.launchActivity)
        lines.add(prefix + "resourceLookup=" + fixture.resourceLookup)
        lines.add(prefix + "nativeLoad=" + fixture.nativeLoad)
        lines.add(prefix + "noGeneratedStartup=" + fixture.noGeneratedStartup)
        lines.add(prefix + "result=" + fixture.result)
    }

    private fun requirePass(fixture: String, check: String, value: Check) {
        if (value != Check.PASS) {
            throw failure("$fixture did not pass $check")
        }
    }

    private fun requireDigest(name: String, value: String?) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw failure("invalid $name SHA-256 digest")
        }
    }

    private fun failure(message: String): IllegalArgumentException =
        IllegalArgumentException("KLD-RUNTIME-001 $message")

    enum class Check {
        PASS,
        FAIL,
        NOT_APPLICABLE,
    }

    data class FixtureResult(
        val name: String,
        val aabDigest: String,
        val apksDigest: String,
        val bundletool: Check,
        val install: Check,
        val launch: Check,
        val packageIdentity: Check,
        val launchActivity: Check,
        val resourceLookup: Check,
        val nativeLoad: Check,
        val noGeneratedStartup: Check,
        val result: CompatibilityMatrix.Result,
    )
}
