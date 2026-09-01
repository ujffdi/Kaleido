package com.tongsr.kaleido.gradle

import java.util.LinkedHashMap
import java.util.TreeSet

internal object R8ConfigurationEngine {
    const val SCHEMA: String = "KaleidoR8Configuration.v1"
    const val PRODUCER: String = "KaleidoR8Configuration/1"
    const val DICTIONARY_SIZE: Int = 4096

    @JvmStatic
    fun generate(
        adoption: Map<String, String>,
        classPlan: ClassRewriteArtifacts.Plan,
    ): Configuration {
        val stream = required(adoption, "seed.domain.r8-dictionary")
        val applicationId = required(adoption, "applicationId")
        val plannedOutputs = classPlan.expectedOutputs.orEmpty().toSet()
        val fixedIdentities = TreeSet<String>()
        for (site in classPlan.manifestSites.orEmpty()) {
            val identity = resolveIdentity(requireNotNull(site?.target), applicationId)
            if (identity !in plannedOutputs) {
                throw IllegalArgumentException(
                    "Semantic class identity is absent from ClassRewritePlan outputs: $identity",
                )
            }
            fixedIdentities.add(identity)
        }

        val dictionaries = LinkedHashMap<String, String>()
        dictionaries["member.txt"] = dictionary(stream, "member", "m")
        dictionaries["class.txt"] = dictionary(stream, "class", "C")
        dictionaries["package.txt"] = dictionary(stream, "package", "p")

        val rules = StringBuilder("# ").append(SCHEMA).append('\n')
            .append("-obfuscationdictionary ../dictionaries/member.txt\n")
            .append("-classobfuscationdictionary ../dictionaries/class.txt\n")
            .append("-packageobfuscationdictionary ../dictionaries/package.txt\n")
        for (identity in fixedIdentities) {
            rules.append("-keep,allowoptimization class ")
                .append(identity).append(" { *; }\n")
        }
        return Configuration(dictionaries.toMap(), rules.toString(), fixedIdentities.toList())
    }

    private fun dictionary(stream: String, domain: String, prefix: String): String {
        val values = TreeSet<String>()
        for (index in 0 until DICTIONARY_SIZE) {
            val digest = SeedDerivation.derive(stream, "r8-$domain", index.toString())
            var length = 10
            var value = prefix + digest.substring(0, length)
            while (!values.add(value)) {
                length += 2
                if (length > digest.length) {
                    throw IllegalStateException("Unable to allocate deterministic R8 token")
                }
                value = prefix + digest.substring(0, length)
            }
        }
        val text = StringBuilder("# ").append(SCHEMA)
            .append(" kind=").append(domain).append('\n')
        values.forEach { value -> text.append(value).append('\n') }
        return text.toString()
    }

    private fun resolveIdentity(lexical: String, applicationId: String): String {
        if (lexical.startsWith(".")) return applicationId + lexical
        if (!lexical.contains(".")) return "$applicationId.$lexical"
        return lexical
    }

    private fun required(values: Map<String, String>, key: String): String {
        val value = values[key]
        if (value.isNullOrBlank()) {
            throw IllegalArgumentException("Adoption Plan is missing $key")
        }
        return value
    }

    @JvmRecord
    data class Configuration(
        val dictionaries: Map<String, String>,
        val rules: String,
        val fixedIdentities: List<String>,
    )
}
