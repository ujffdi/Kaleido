package com.tongsr.kaleido.release

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Writes one complete controlled-device row record after external probes pass. */
object RuntimeRecordCli {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val options = parse(arguments)
        val fixtures = ArrayList<RuntimeGate.FixtureResult>()
        for (encoded in options.getOrDefault("fixture", emptyList())) {
            val parts = encoded.split(",", limit = 0)
            require(parts.size == 12) { "fixture must have 12 comma-separated evidence fields" }
            fixtures.add(
                RuntimeGate.FixtureResult(
                    parts[0],
                    parts[1],
                    parts[2],
                    check(parts[3]),
                    check(parts[4]),
                    check(parts[5]),
                    check(parts[6]),
                    check(parts[7]),
                    check(parts[8]),
                    check(parts[9]),
                    check(parts[10]),
                    CompatibilityMatrix.Result.parse(parts[11]),
                ),
            )
        }
        val bytes = RuntimeGate.canonicalRecord(
            required(options, "candidate-sha256"),
            required(options, "matrix-record-sha256"),
            required(options, "test-revision-sha256"),
            CompatibilityMatrix.requireRow(required(options, "row")),
            required(options, "device-spec-sha256"),
            fixtures,
        )
        writeAtomically(Path.of(required(options, "output")), bytes)
    }

    private fun check(value: String): RuntimeGate.Check = RuntimeGate.Check.valueOf(value)

    private fun parse(arguments: Array<String>): Map<String, List<String>> {
        val values = LinkedHashMap<String, MutableList<String>>()
        var index = 0
        while (index < arguments.size) {
            require(arguments[index].startsWith("--") && index + 1 < arguments.size) {
                "arguments must be --name value pairs"
            }
            values.getOrPut(arguments[index].substring(2)) { ArrayList() }
                .add(arguments[index + 1])
            index += 2
        }
        return values
    }

    private fun required(options: Map<String, List<String>>, name: String): String {
        val values = options[name]
        require(values != null && values.size == 1 && values[0].isNotBlank()) {
            "exactly one --$name is required"
        }
        return values[0]
    }

    private fun writeAtomically(output: Path, bytes: ByteArray) {
        val parent = output.toAbsolutePath().parent
        Files.createDirectories(parent)
        val staged = Files.createTempFile(parent, output.fileName.toString(), ".tmp")
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
    }
}
