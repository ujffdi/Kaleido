package com.tongsr.kaleido.release

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Writes one validated mandatory-row record after its external builds have completed. */
object CompatibilityMatrixCli {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val options = parse(arguments)
        val row = CompatibilityMatrix.Row(
            required(options, "row"),
            required(options, "agp"),
            required(options, "gradle"),
            required(options, "os"),
            required(options, "arch"),
            required(options, "jdk").toInt(),
            required(options, "build-tools"),
            required(options, "compile-sdk").toInt(),
            required(options, "kotlin-mode"),
        )
        val results = ArrayList<CompatibilityMatrix.FixtureResult>()
        for (encoded in options.getOrDefault("fixture", emptyList())) {
            val parts = encoded.split(",", limit = 0)
            require(parts.size == 4) { "fixture must be name,sourceSha256,aabSha256,result" }
            results.add(
                CompatibilityMatrix.FixtureResult(
                    parts[0],
                    parts[1],
                    parts[2],
                    CompatibilityMatrix.Result.parse(parts[3]),
                ),
            )
        }
        val bytes = CompatibilityMatrix.canonicalRecord(
            required(options, "candidate-sha256"),
            row,
            results,
        )
        val output = Path.of(required(options, "output"))
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
}
