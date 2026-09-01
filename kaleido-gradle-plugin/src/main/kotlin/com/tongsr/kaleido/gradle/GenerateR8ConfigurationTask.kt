package com.tongsr.kaleido.gradle

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Comparator
import java.util.HexFormat
import java.util.TreeMap
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class GenerateR8ConfigurationTask : DefaultTask() {
    @get:Input
    val kaleidoCacheSchema: String
        get() = "R8ConfigurationCache.v1"

    @get:Input
    abstract val consumerProjectPath: Property<String>

    @get:Input
    abstract val variantName: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val adoptionPlan: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val classRewritePlan: RegularFileProperty

    @get:OutputDirectory
    abstract val rulesOutputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val dictionariesOutputDirectory: DirectoryProperty

    @get:OutputFile
    abstract val configurationEvidence: RegularFileProperty

    @TaskAction
    fun generateConfiguration() {
        val project = consumerProjectPath.get()
        val variant = variantName.get()
        val adoptionBytes = Files.readAllBytes(adoptionPlan.get().asFile.toPath())
        val planBytes = Files.readAllBytes(classRewritePlan.get().asFile.toPath())
        val adoption = properties(adoptionBytes)
        val classPlan = ClassRewriteArtifacts.decodePlan(planBytes, project, variant)
        if (project != classPlan.project || variant != classPlan.variant) {
            throw failure(
                project,
                variant,
                "class-rewrite-plan.pb",
                "Class Rewrite Plan belongs to a different project or variant",
                "Regenerate the Release variant from stable inputs",
            )
        }
        if (sha256(adoptionBytes) != classPlan.adoptionPlanSha256) {
            throw failure(
                project,
                variant,
                "adoption-plan.properties",
                "Adoption Plan digest does not match the Class Rewrite Plan",
                "Regenerate both plans in the same Release build",
            )
        }

        val configuration = try {
            R8ConfigurationEngine.generate(adoption, classPlan)
        } catch (invalid: IllegalArgumentException) {
            throw failure(
                project,
                variant,
                "r8-configuration",
                invalid.message,
                "Regenerate a complete and consistent class plan",
            )
        }
        val rulesRoot = rulesOutputDirectory.get().asFile.toPath()
        val dictionariesRoot = dictionariesOutputDirectory.get().asFile.toPath()
        recreate(rulesRoot)
        recreate(dictionariesRoot)
        write(rulesRoot.resolve("kaleido-r8.keep"), configuration.rules)
        for ((name, content) in configuration.dictionaries) {
            write(dictionariesRoot.resolve(name), content)
        }

        val evidence = StringBuilder()
            .append("schema=").append(R8ConfigurationEngine.SCHEMA).append('\n')
            .append("producer=").append(R8ConfigurationEngine.PRODUCER).append('\n')
            .append("project=").append(project).append('\n')
            .append("variant=").append(variant).append('\n')
            .append("adoptionPlanSha256=").append(sha256(adoptionBytes)).append('\n')
            .append("classRewritePlanSha256=").append(sha256(planBytes)).append('\n')
            .append("dictionarySize=").append(R8ConfigurationEngine.DICTIONARY_SIZE).append('\n')
            .append("rulesSha256=")
            .append(sha256(configuration.rules.toByteArray(StandardCharsets.UTF_8))).append('\n')
        configuration.dictionaries.entries.sortedBy { it.key }.forEach { entry ->
            evidence.append("dictionary=").append(entry.key)
                .append('|')
                .append(sha256(entry.value.toByteArray(StandardCharsets.UTF_8)))
                .append('\n')
        }
        configuration.fixedIdentities.forEach { identity ->
            evidence.append("fixedIdentity=").append(identity).append('\n')
        }
        write(configurationEvidence.get().asFile.toPath(), evidence.toString())
    }

    companion object {
        private fun properties(bytes: ByteArray): Map<String, String> {
            val values = TreeMap<String, String>()
            for (line in String(bytes, StandardCharsets.UTF_8).split("\n")) {
                val separator = line.indexOf('=')
                if (separator > 0) {
                    values[line.substring(0, separator)] = line.substring(separator + 1)
                }
            }
            return values.toMap()
        }

        private fun recreate(root: Path) {
            if (Files.exists(root)) {
                Files.walk(root).use { paths ->
                    for (path in paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(path)
                    }
                }
            }
            Files.createDirectories(root)
        }

        private fun write(output: Path, text: String) {
            Files.createDirectories(output.parent)
            val temporary = output.resolveSibling("${output.fileName}.tmp")
            Files.writeString(temporary, text, StandardCharsets.UTF_8)
            Files.move(
                temporary,
                output,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }

        internal fun sha256(bytes: ByteArray): String {
            return try {
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
            } catch (impossible: NoSuchAlgorithmException) {
                throw IllegalStateException("SHA-256 is unavailable", impossible)
            }
        }

        private fun failure(
            project: String,
            variant: String,
            target: String,
            reason: String?,
            repair: String,
        ): GradleException =
            KaleidoDiagnostic(
                "KLD-R8-001",
                project,
                variant,
                "r8-configuration",
                R8ConfigurationEngine.SCHEMA,
                target,
                reason ?: "",
                repair,
            ).failure()
    }
}
