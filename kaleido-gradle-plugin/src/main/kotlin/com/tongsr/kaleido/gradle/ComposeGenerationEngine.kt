package com.tongsr.kaleido.gradle

internal object ComposeGenerationEngine {
    const val SCHEMA: String = "ComposeGeneration.v1"

    @JvmStatic
    fun plan(adoption: Map<String, String>): GeneratedCompose {
        if (!required(adoption, "generation.compose.enabled").toBoolean()) {
            return GeneratedCompose(emptyMap(), emptyList(), emptyList())
        }
        val packageName = required(adoption, "generation.packageBase") + ".compose"
        val fileCount = integer(adoption, "generation.compose.fileCount")
        val functionsPerFile = integer(adoption, "generation.compose.functionsPerFile")
        val stream = required(adoption, "seed.domain.generation-compose")
        val total = Math.multiplyExact(fileCount, functionsPerFile)
        val functions = ArrayList<FunctionIdentity>(total)
        val facades = ArrayList<String>(fileCount)
        for (fileIndex in 0 until fileCount) {
            val facade = "KldCompose_" + token(stream, "facade", fileIndex, 12)
            facades.add("$packageName.$facade")
            for (functionIndex in 0 until functionsPerFile) {
                val globalIndex = fileIndex * functionsPerFile + functionIndex
                functions.add(
                    FunctionIdentity(
                        "$packageName.$facade",
                        "kld_" + token(stream, "function", globalIndex, 12),
                        globalIndex,
                    ),
                )
            }
        }

        val files = LinkedHashMap<String, String>()
        for (fileIndex in 0 until fileCount) {
            val facadeSimple = facades[fileIndex].substring(packageName.length + 1)
            val source = StringBuilder()
                .append("@file:JvmName(\"").append(facadeSimple).append("\")\n\n")
                .append("package ").append(packageName).append("\n\n")
                .append("import androidx.compose.runtime.Composable\n\n")
            for (functionIndex in 0 until functionsPerFile) {
                val globalIndex = fileIndex * functionsPerFile + functionIndex
                val function = functions[globalIndex]
                val constant = token(stream, "constant", globalIndex, 8)
                source.append("@Composable\n")
                    .append("internal fun ").append(function.name)
                    .append("(value: Int): Int {\n")
                    .append("    var mixed = value xor 0x").append(constant).append(".toInt()\n")
                if (globalIndex + 1 < total) {
                    source.append("    if ((mixed and 1) == 0) mixed = ")
                        .append(functions[globalIndex + 1].name)
                        .append("(mixed)\n")
                }
                source.append("    return mixed\n")
                    .append("}\n\n")
            }
            files["${packageName.replace('.', '/')}/$facadeSimple.kt"] = source.toString()
        }
        return GeneratedCompose(files.toMap(), facades.toList(), functions.toList())
    }

    private fun integer(values: Map<String, String>, key: String): Int =
        required(values, key).toInt()

    private fun required(values: Map<String, String>, key: String): String =
        values[key] ?: throw IllegalArgumentException("Missing $key")

    private fun token(stream: String, domain: String, identity: Any, length: Int): String =
        SeedDerivation.derive(stream, domain, identity.toString()).substring(0, length)

    @JvmRecord
    data class FunctionIdentity(
        val facade: String,
        val name: String,
        val graphIndex: Int,
    )

    @JvmRecord
    data class GeneratedCompose(
        val kotlinFiles: Map<String, String>,
        val facades: List<String>,
        val functions: List<FunctionIdentity>,
    )
}
