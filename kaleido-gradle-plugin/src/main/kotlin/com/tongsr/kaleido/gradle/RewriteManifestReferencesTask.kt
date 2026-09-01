package com.tongsr.kaleido.gradle

import com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeSet
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class RewriteManifestReferencesTask : DefaultTask() {
    @get:Input
    val kaleidoCacheSchema: String
        get() = "ManifestRewriteCache.v2"

    @get:Input
    abstract val consumerProjectPath: Property<String>

    @get:Input
    abstract val variantName: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val adoptionPlan: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputManifest: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val consumerSourceDirectories: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputManifest: RegularFileProperty

    @get:OutputFile
    abstract val rewriteIntent: RegularFileProperty

    @TaskAction
    fun rewrite() {
        val project = consumerProjectPath.get()
        val variant = variantName.get()
        val adoption = readProperties(Files.readAllBytes(adoptionPlan.get().asFile.toPath()))
        val applicationId = adoption["applicationId"]
        val stream = adoption["seed.domain.class-rewrite"]
        if (adoption["schema"] != "AdoptionPlan.v1" || applicationId == null || stream == null) {
            throw RewriteClassesAndManifestTask.failure(
                project,
                variant,
                "AdoptionPlan.v1",
                "Manifest rewrite inputs are incomplete",
                "Regenerate the complete Adoption Plan",
            )
        }
        val protectedNames = TreeSet(commaSeparated(adoption["protection.originalClassNames"]))
        val classHatches = RewriteClassesAndManifestTask.parseEscapeHatches(
            adoption["protection.classEscapeHatches"],
            EscapeHatchDeclaration.Kind.CLASS,
            project,
            variant,
        )
        val inputBytes = Files.readAllBytes(inputManifest.get().asFile.toPath())
        val document = RewriteClassesAndManifestTask.parseManifest(inputBytes, project, variant)
        val references = RewriteClassesAndManifestTask.manifestReferences(document, applicationId)
        val roots = TreeSet<String>()
        references.map { it.resolvedIdentity }
            .filter { identity -> identity.startsWith("$applicationId.") }
            .forEach { roots.add(it) }
        protectedNames.addAll(
            SourceProtectionScanner.inferProtectedIdentities(
                consumerSourceDirectories.files,
                roots,
            ),
        )
        classHatches
            .filter { hatch -> hatch.protects(KaleidoProtectionDimension.ORIGINAL_IDENTITY) }
            .forEach { hatch ->
                roots.filter { hatch.matches(it) }.forEach { protectedNames.add(it) }
            }
        val mapping = RewriteClassesAndManifestTask.allocateMapping(
            roots,
            protectedNames,
            roots,
            stream,
        )
        val sites = references
            .filter { reference -> mapping.containsKey(reference.resolvedIdentity) }
            .map { reference ->
                ClassRewriteArtifacts.ManifestSite(
                    reference.location,
                    reference.lexicalValue,
                    RewriteClassesAndManifestTask.renderManifestIdentity(
                        reference.lexicalValue,
                        applicationId,
                        mapping.getValue(reference.resolvedIdentity),
                    ),
                )
            }
            .sortedBy { it.location }
        val outputBytes = RewriteClassesAndManifestTask.rewriteManifest(document, sites, project, variant)
        write(outputManifest.get().asFile.toPath(), outputBytes)

        val intent = StringBuilder()
            .append("schema=ManifestRewriteIntent.v1\n")
            .append("originalManifestSha256=")
            .append(RewriteClassesAndManifestTask.sha256(inputBytes)).append('\n')
        mapping.entries.sortedBy { it.key }.forEach { entry ->
            intent.append("mapping=").append(entry.key).append('|')
                .append(entry.value).append('\n')
        }
        sites.forEach { site ->
            intent.append("site=").append(site.location).append('|')
                .append(site.original).append('|').append(site.target).append('\n')
        }
        write(rewriteIntent.get().asFile.toPath(), intent.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private fun readProperties(bytes: ByteArray): Map<String, String> {
        val values = HashMap<String, String>()
        for (line in String(bytes, StandardCharsets.UTF_8).split('\n')) {
            val separator = line.indexOf('=')
            if (separator > 0) {
                values[line.substring(0, separator)] = line.substring(separator + 1)
            }
        }
        return values.toMap()
    }

    private fun commaSeparated(value: String?): Set<String> =
        if (value.isNullOrBlank()) emptySet() else value.split(',').toSet()

    private fun write(path: Path, bytes: ByteArray) {
        Files.createDirectories(path.parent)
        Files.write(path, bytes)
    }
}
