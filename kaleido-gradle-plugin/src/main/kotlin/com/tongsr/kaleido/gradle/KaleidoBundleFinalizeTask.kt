package com.tongsr.kaleido.gradle

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.TreeSet
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
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
abstract class KaleidoBundleFinalizeTask : DefaultTask() {
    @get:Input
    val kaleidoCacheSchema: String
        get() = "UnsignedBundleRewriteCache.v1"

    @get:Input
    abstract val consumerProjectPath: Property<String>

    @get:Input
    abstract val variantName: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputBundle: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val adoptionPlan: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val generationInventory: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val classRewriteEvidence: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val resourceProtectionEvidence: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val r8Evidence: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val applicationResourceDirectories: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val applicationNativeDirectories: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val applicationMetadataDirectories: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputBundle: RegularFileProperty

    @get:OutputFile
    abstract val bundleRewritePlan: RegularFileProperty

    @get:OutputFile
    abstract val resourceMapping: RegularFileProperty

    @get:OutputFile
    abstract val bundleTransformReceipt: RegularFileProperty

    @get:OutputFile
    abstract val unsignedCandidateEvidence: RegularFileProperty

    @get:OutputFile
    abstract val unsignedCandidateDigest: RegularFileProperty

    @TaskAction
    @Throws(IOException::class)
    fun finalizeBundle() {
        val input = inputBundle.get().asFile.toPath()
        val output = outputBundle.get().asFile.toPath()
        AabTopologyValidator.validate(
            input,
            AabTopologyValidator.Context(consumerProjectPath.get(), variantName.get()),
        )
        val project = consumerProjectPath.get()
        val variant = variantName.get()
        val adoption = readProperties(adoptionPlan.get().asFile.toPath())
        val roots = applicationResourceDirectories.files.map { it.toPath() }.sorted()
        val inventory = ApplicationResourceInventory.scan(roots, project, variant)
        val protectedNames = TreeSet(commaSeparated(adoption["protection.resourceNames"]))
        protectedNames.addAll(inventory.protectedNames)
        protectedNames.addAll(
            readResourceProtectionNames(
                resourceProtectionEvidence.get().asFile.toPath(),
                project,
                variant,
            ),
        )
        inventory.warnings.forEach { logger.warn(it) }
        val hatches = RewriteClassesAndManifestTask.parseEscapeHatches(
            adoption["protection.resourceEscapeHatches"],
            EscapeHatchDeclaration.Kind.RESOURCE,
            project,
            variant,
        )
        val context = BundleRewriteModule.Context(project, variant, logger::warn)
        val fullControls = BundleRewriteModule.FullControls(
            commaSeparated(adoption["resources.nativeLibrariesToDelete"]),
            commaSeparated(adoption["resources.metadataToDelete"]),
            commaSeparated(adoption["resources.confirmedUnusedStrings"]),
            commaSeparated(adoption["resources.retainedLanguages"]),
            inventoryNativeLibraries(applicationNativeDirectories),
            inventoryMetadata(applicationMetadataDirectories),
        )
        val plan = try {
            BundleRewriteModule.plan(
                input,
                context,
                inventory.resources,
                protectedNames,
                commaSeparated(adoption["protection.packagedPaths"]),
                hatches,
                required(adoption, "seed.domain.bundle-resource", project, variant),
                fullControls,
            )
        } catch (invalid: RuntimeException) {
            if (invalid is GradleException) throw invalid
            throw KaleidoDiagnostic(
                "KLD-BUNDLE-001",
                project,
                variant,
                "bundle-rewrite",
                BundleRewriteArtifacts.PLAN_SCHEMA,
                "planning",
                invalid.message ?: "",
                "Resolve the invalid resource identity or path and rebuild",
            ).failure()
        }
        val planBytes = BundleRewriteArtifacts.encodePlan(plan)
        write(bundleRewritePlan.get().asFile.toPath(), planBytes)
        val executablePlan = BundleRewriteArtifacts.decodePlan(planBytes, project, variant)
        val execution = BundleRewriteModule.execute(
            input,
            output,
            executablePlan,
            planBytes,
            context,
        )
        write(
            resourceMapping.get().asFile.toPath(),
            execution.resourceMapping.toByteArray(StandardCharsets.UTF_8),
        )
        write(bundleTransformReceipt.get().asFile.toPath(), execution.receiptBytes)
        write(unsignedCandidateEvidence.get().asFile.toPath(), Files.readAllBytes(output))
        write(
            unsignedCandidateDigest.get().asFile.toPath(),
            (RewriteClassesAndManifestTask.sha256(Files.readAllBytes(output)) + "\n")
                .toByteArray(StandardCharsets.UTF_8),
        )
    }

    companion object {
        @Throws(IOException::class)
        private fun readProperties(path: Path): Map<String, String> {
            val values = HashMap<String, String>()
            for (line in Files.readAllLines(path, StandardCharsets.UTF_8)) {
                val separator = line.indexOf('=')
                if (separator > 0) {
                    values[line.substring(0, separator)] = line.substring(separator + 1)
                }
            }
            return values.toMap()
        }

        private fun commaSeparated(value: String?): Set<String> {
            if (value.isNullOrBlank()) return emptySet()
            return value.split(",")
                .filter { it.isNotBlank() }
                .toSet()
        }

        @Throws(IOException::class)
        private fun readResourceProtectionNames(
            path: Path,
            project: String,
            variant: String,
        ): Set<String> {
            val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
            if (lines.isEmpty() || lines[0] != "schema=ResourceProtectionEvidence.v1") {
                throw KaleidoDiagnostic(
                    "KLD-BUNDLE-001",
                    project,
                    variant,
                    "bundle-rewrite",
                    BundleRewriteArtifacts.PLAN_SCHEMA,
                    path.toString(),
                    "Resource protection evidence schema is missing or incompatible",
                    "Regenerate class rewrite evidence with this Kaleido version",
                ).failure()
            }
            val names = TreeSet<String>()
            for (line in lines.subList(1, lines.size)) {
                if (line.isBlank()) continue
                if (!line.startsWith("resource=") || !line.contains("|exact-getIdentifier|")) {
                    throw KaleidoDiagnostic(
                        "KLD-BUNDLE-001",
                        project,
                        variant,
                        "bundle-rewrite",
                        BundleRewriteArtifacts.PLAN_SCHEMA,
                        line,
                        "Resource protection evidence entry is malformed",
                        "Regenerate class rewrite evidence with this Kaleido version",
                    ).failure()
                }
                val identity = line.substring("resource=".length, line.indexOf('|'))
                val slash = identity.indexOf('/')
                if (slash <= 0 || slash == identity.length - 1) {
                    throw KaleidoDiagnostic(
                        "KLD-BUNDLE-001",
                        project,
                        variant,
                        "bundle-rewrite",
                        BundleRewriteArtifacts.PLAN_SCHEMA,
                        identity,
                        "Resource protection identity is malformed",
                        "Regenerate class rewrite evidence with this Kaleido version",
                    ).failure()
                }
                names.add(identity.substring(slash + 1))
            }
            return names.toSet()
        }

        @Throws(IOException::class)
        private fun inventoryNativeLibraries(directories: ConfigurableFileCollection): Set<String> {
            val names = TreeSet<String>()
            for (rootFile in directories.files) {
                val root = rootFile.toPath()
                if (!Files.isDirectory(root)) continue
                Files.walk(root).use { paths ->
                    paths.filter { Files.isRegularFile(it) }
                        .map { it.fileName.toString() }
                        .filter { name -> name.matches(Regex("lib[A-Za-z0-9_.-]+\\.so")) }
                        .forEach { names.add(it) }
                }
            }
            return names.toSet()
        }

        @Throws(IOException::class)
        private fun inventoryMetadata(directories: ConfigurableFileCollection): Set<String> {
            val names = TreeSet<String>()
            for (rootFile in directories.files) {
                val root = rootFile.toPath()
                if (!Files.isDirectory(root)) continue
                Files.walk(root).use { paths ->
                    for (path in paths.filter { Files.isRegularFile(it) }.toList()) {
                        val relative = root.relativize(path).toString()
                            .replace(File.separatorChar, '/')
                        if (relative.startsWith("META-INF/")) names.add(relative)
                    }
                }
            }
            return names.toSet()
        }

        private fun required(
            values: Map<String, String>,
            key: String,
            project: String,
            variant: String,
        ): String {
            val value = values[key]
            if (value.isNullOrBlank()) {
                throw KaleidoDiagnostic(
                    "KLD-BUNDLE-001",
                    project,
                    variant,
                    "bundle-rewrite",
                    BundleRewriteArtifacts.PLAN_SCHEMA,
                    key,
                    "Adoption Plan is missing a required Bundle rewrite input",
                    "Regenerate a complete AdoptionPlan.v1",
                ).failure()
            }
            return value
        }

        @Throws(IOException::class)
        private fun write(output: Path, bytes: ByteArray) {
            Files.createDirectories(output.parent)
            val temporary = output.resolveSibling(output.fileName.toString() + ".tmp")
            Files.write(temporary, bytes)
            Files.move(
                temporary,
                output,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }
    }
}
