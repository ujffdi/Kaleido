package com.tongsr.kaleido.gradle

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Comparator
import java.util.HexFormat
import java.util.Locale
import java.util.TreeMap
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Publication validates signing evidence and commits atomically")
abstract class PublishKaleidoReleaseTask : DefaultTask() {
    init {
        outputs.upToDateWhen { false }
    }

    @get:Input
    abstract val consumerProjectPath: Property<String>

    @get:Input
    abstract val variantName: Property<String>

    @get:Input
    abstract val pluginVersion: Property<String>

    @get:Internal
    abstract val consumerProjectDirectory: DirectoryProperty

    @get:Internal
    abstract val publishedEvidenceDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputBundle: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val stagedSignedBundle: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val signingReceipt: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val deterministicEvidence: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputBundle: RegularFileProperty

    @TaskAction
    @Throws(IOException::class)
    fun publish() {
        val project = consumerProjectPath.get()
        val variant = variantName.get()
        val root = consumerProjectDirectory.get().asFile.toPath()
            .toAbsolutePath().normalize()
        val deterministic = collectEvidence(root, deterministicEvidence)
        val inputBundlePath = inputBundle.get().asFile.toPath()
        val signedBundle = stagedSignedBundle.get().asFile.toPath()
        val signingReceiptPath = signingReceipt.get().asFile.toPath()
        ensureInput(deterministic, root, inputBundlePath)

        val publication = try {
            assemble(
                Context(project, variant, pluginVersion.get()),
                deterministic,
                Files.readAllBytes(signedBundle),
                Files.readAllBytes(signingReceiptPath),
            )
        } catch (invalid: IllegalArgumentException) {
            throw failure(
                project,
                variant,
                "staging",
                invalid.message,
                "Regenerate every stage artifact and publish only one complete verified set",
            )
        }

        val output = outputBundle.get().asFile.toPath()
        val evidence = publishedEvidenceDirectory.get().asFile.toPath()
        recoverInterruptedPublication(output, evidence)
        val outputStage = output.resolveSibling(output.fileName.toString() + ".publication-staging")
        val evidenceStage = evidence.resolveSibling(
            evidence.fileName.toString() + ".publication-staging",
        )
        deleteTree(outputStage)
        deleteTree(evidenceStage)
        Files.createDirectories(outputStage.parent)
        Files.write(outputStage, publication.signedBundle)
        Files.createDirectories(evidenceStage)
        for ((key, value) in publication.files) {
            val target = evidenceStage.resolve(key)
            Files.createDirectories(target.parent)
            Files.write(target, value)
        }
        validateStagedTree(evidenceStage, publication.files)
        commit(outputStage, output, evidenceStage, evidence)
    }

    companion object {
        const val MANIFEST_SCHEMA: String = "ReleaseEvidenceSetManifest.v1"

        @JvmField
        val REPORT_SCHEMA_URI: String = ArtifactReportReader.CURRENT_SCHEMA_URI

        @JvmStatic
        fun assemble(
            context: Context,
            deterministicInputs: Map<String, ByteArray>,
            signedBundle: ByteArray,
            signingBytes: ByteArray,
        ): Publication {
            val inputs = TreeMap<String, ByteArray>()
            deterministicInputs.forEach { (path, bytes) ->
                val normalized = path.replace('\\', '/')
                if (normalized.startsWith("/") || normalized.contains("../")) {
                    throw IllegalArgumentException(
                        "Evidence path is not project-relative: $path",
                    )
                }
                val previous = inputs.put(normalized, bytes.clone())
                if (previous != null && !previous.contentEquals(bytes)) {
                    throw IllegalArgumentException("Evidence path has conflicting bytes: $path")
                }
            }
            val adoptionBytes = unique(inputs, "adoption-plan.properties")
            val generationBytes = unique(inputs, "generated-inventory.properties")
            val rawKaleido = unique(inputs, "class-rewrite/raw-kaleido-mapping.txt")
            val rawR8 = unique(inputs, "r8/raw-r8-mapping.txt")
            val composed = unique(inputs, "r8/composed-mapping.txt")
            val resource = unique(inputs, "bundle-rewrite/resource-mapping.txt")
            val unsigned = unique(inputs, "bundle-rewrite/unsigned-candidate.aab")
            val unsignedDigestBytes = unique(inputs, "bundle-rewrite/unsigned-candidate.sha256")
            val composeReceipt = unique(inputs, "compose/final-dex-receipt.properties")

            val adoption = properties(adoptionBytes, "Adoption Plan")
            val generation = properties(generationBytes, "generation inventory")
            val signing = properties(signingBytes, "signing receipt")
            val compose = properties(composeReceipt, "Compose final DEX receipt")
            requireField(adoption, "schema", "AdoptionPlan.v1")
            requireField(generation, "schema", "GeneratedInventory.v1")
            requireField(signing, "schema", "SigningReceipt.v1")
            requireField(compose, "schema", "ComposeFinalDexReceipt.v1")
            requireField(signing, "signatureCoverageValidated", "true")
            requireField(signing, "certificateMatched", "true")
            requireField(signing, "bundletoolValidated", "true")
            requireField(compose, "mappingResolved", "true")
            requireField(compose, "incomingBytecodeEdges", "0")
            requireField(compose, "finalDexRetained", "true")
            if (context.project != adoption["project"] ||
                context.variant != adoption["variant.name"] ||
                context.project != signing["project"] ||
                context.variant != signing["variant"]
            ) {
                throw IllegalArgumentException(
                    "Variant identity differs across staged evidence",
                )
            }

            val unsignedSha = sha256(unsigned)
            val expectedUnsigned = String(unsignedDigestBytes, StandardCharsets.UTF_8).trim()
            val signedSha = sha256(signedBundle)
            if (unsignedSha != expectedUnsigned || unsignedSha != signing["unsignedAabSha256"]) {
                throw IllegalArgumentException("Unsigned AAB digest closure is incomplete")
            }
            if (signedSha != signing["signedAabSha256"]) {
                throw IllegalArgumentException(
                    "Signed AAB digest differs from signing evidence",
                )
            }
            val certificate = signing.getOrDefault("certificateSha256", "")
            if (!certificate.matches(Regex("[0-9a-f]{64}"))) {
                throw IllegalArgumentException("Signing certificate digest is not canonical")
            }

            val deterministicManifest = StringBuilder("schema=DeterministicEvidenceManifest.v1\n")
            inputs.forEach { (path, bytes) ->
                deterministicManifest.append("file=")
                    .append(path).append('|').append(sha256(bytes)).append('\n')
            }
            val deterministicBytes = deterministicManifest.toString()
                .toByteArray(StandardCharsets.UTF_8)
            val deterministicSha = sha256(deterministicBytes)
            val rawKaleidoSha = sha256(rawKaleido)
            val rawR8Sha = sha256(rawR8)
            val composedSha = sha256(composed)
            val resourceSha = sha256(resource)
            val identity = "schema=" + MANIFEST_SCHEMA + "\n" +
                "project=" + context.project + "\n" +
                "variant=" + context.variant + "\n" +
                "applicationId=" + adoption["applicationId"] + "\n" +
                "unsignedAabSha256=" + unsignedSha + "\n" +
                "signedAabSha256=" + signedSha + "\n" +
                "rawKaleidoMappingSha256=" + rawKaleidoSha + "\n" +
                "rawR8MappingSha256=" + rawR8Sha + "\n" +
                "composedMappingSha256=" + composedSha + "\n" +
                "resourceMappingSha256=" + resourceSha + "\n" +
                "deterministicEvidenceSha256=" + deterministicSha + "\n" +
                "certificateSha256=" + certificate + "\n"
            val setId = sha256(identity.toByteArray(StandardCharsets.UTF_8))
            val manifest = identity +
                "pluginVersion=" + context.pluginVersion + "\n" +
                "profile=" + adoption["profile"] + "\n" +
                "publicationResult=PUBLISHED\n" +
                "releaseEvidenceSetId=" + setId + "\n"
            val report = report(
                context, adoption, generation, signing, compose, setId,
                unsignedSha, signedSha, certificate, deterministicSha,
                rawKaleidoSha, rawR8Sha, composedSha, resourceSha,
            )
            ArtifactReportReader.read(report)

            val files = TreeMap<String, ByteArray>()
            inputs.forEach { (path, bytes) -> files["deterministic/$path"] = bytes.clone() }
            files["mappings/raw-kaleido-mapping.txt"] = rawKaleido.clone()
            files["mappings/raw-r8-mapping.txt"] = rawR8.clone()
            files["mappings/composed-mapping.txt"] = composed.clone()
            files["mappings/resource-mapping.txt"] = resource.clone()
            files["publication/signing-receipt.properties"] = signingBytes.clone()
            files["publication/compose-final-dex-receipt.properties"] = composeReceipt.clone()
            files["deterministic-evidence-manifest.properties"] = deterministicBytes
            files["release-evidence-set-manifest.properties"] =
                manifest.toByteArray(StandardCharsets.UTF_8)
            files["artifact-report.txt"] = report.toByteArray(StandardCharsets.UTF_8)
            return Publication(files.toMap(), signedBundle.clone(), setId)
        }

        private fun report(
            context: Context,
            adoption: Map<String, String>,
            generation: Map<String, String>,
            signing: Map<String, String>,
            compose: Map<String, String>,
            setId: String,
            unsignedSha: String,
            signedSha: String,
            certificate: String,
            deterministicSha: String,
            rawKaleidoSha: String,
            rawR8Sha: String,
            composedSha: String,
            resourceSha: String,
        ): String {
            val stages = listOf(
                "adoption-validation", "immutable-adoption-plan", "bounded-generation",
                "class-manifest-protection", "r8-configuration-mapping",
                "resource-plan-rewrite", "unsigned-canonicalization",
                "compose-final-dex-verification", "signing-bundle-verification",
                "atomic-publication",
            )
            val text = StringBuilder()
                .append("schemaUri=").append(REPORT_SCHEMA_URI).append('\n')
                .append("schemaVersion=1.0\n")
                .append("releaseEvidenceSetId=").append(setId).append('\n')
                .append("project=").append(context.project).append('\n')
                .append("variant=").append(context.variant).append('\n')
                .append("applicationId=").append(adoption["applicationId"]).append('\n')
                .append("profile=").append(adoption["profile"]).append('\n')
                .append("pluginVersion=").append(context.pluginVersion).append('\n')
            for (index in stages.indices) {
                text.append("stage.").append(String.format(Locale.ROOT, "%02d", index + 1))
                    .append('=').append(stages[index]).append("|PASS\n")
            }
            return text.append("generationClasses=").append(generation["classes"]).append('\n')
                .append("generationActivities=")
                .append(generation["components.activities"]).append('\n')
                .append("composeFacades=").append(compose["facades"]).append('\n')
                .append("unsignedAabSha256=").append(unsignedSha).append('\n')
                .append("signedAabSha256=").append(signedSha).append('\n')
                .append("certificateSha256=").append(certificate).append('\n')
                .append("signatureCoverageValidated=")
                .append(signing["signatureCoverageValidated"]).append('\n')
                .append("bundletoolValidated=").append(signing["bundletoolValidated"])
                .append('\n')
                .append("codeTransparencyEntries=")
                .append(signing["codeTransparencyEntries"]).append('\n')
                .append("rawKaleidoMappingSha256=").append(rawKaleidoSha).append('\n')
                .append("rawR8MappingSha256=").append(rawR8Sha).append('\n')
                .append("composedMappingSha256=").append(composedSha).append('\n')
                .append("resourceMappingSha256=").append(resourceSha).append('\n')
                .append("deterministicEvidenceSha256=").append(deterministicSha).append('\n')
                .append("diagnostics.count=0\n")
                .append("publicationResult=PUBLISHED\n")
                .append(
                    "proofLimitations=Static and controlled-fixture evidence does not prove " +
                        "all runtime paths, devices, store review, or absolute unreachability.\n",
                )
                .toString()
        }

        @Throws(IOException::class)
        private fun collectEvidence(
            root: Path,
            collection: ConfigurableFileCollection,
        ): TreeMap<String, ByteArray> {
            val values = TreeMap<String, ByteArray>()
            for (file in collection.files.sortedWith(compareBy(File::getPath))) {
                val path = file.toPath().toAbsolutePath().normalize()
                if (Files.isDirectory(path)) {
                    Files.walk(path).use { paths ->
                        for (child in paths.filter { Files.isRegularFile(it) }.sorted().toList()) {
                            putEvidence(values, root, child)
                        }
                    }
                } else if (Files.isRegularFile(path)) {
                    putEvidence(values, root, path)
                }
            }
            return values
        }

        @Throws(IOException::class)
        private fun ensureInput(values: MutableMap<String, ByteArray>, root: Path, input: Path) {
            putEvidence(values, root, input.toAbsolutePath().normalize())
        }

        @Throws(IOException::class)
        private fun putEvidence(values: MutableMap<String, ByteArray>, root: Path, path: Path) {
            if (!path.startsWith(root)) {
                throw IllegalArgumentException(
                    "Evidence input is outside the Consumer Project",
                )
            }
            val logical = root.relativize(path).toString().replace(File.separatorChar, '/')
            val bytes = Files.readAllBytes(path)
            val previous = values.putIfAbsent(logical, bytes)
            if (previous != null && !previous.contentEquals(bytes)) {
                throw IllegalArgumentException("Evidence path has conflicting bytes: $logical")
            }
        }

        private fun unique(values: Map<String, ByteArray>, suffix: String): ByteArray {
            val matches = values.entries.filter { it.key.endsWith(suffix) }
            if (matches.size != 1) {
                throw IllegalArgumentException("Expected exactly one staged $suffix")
            }
            return matches[0].value
        }

        private fun properties(bytes: ByteArray, label: String): Map<String, String> {
            val values = TreeMap<String, String>()
            for (line in String(bytes, StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank()) continue
                val separator = line.indexOf('=')
                if (separator <= 0) {
                    throw IllegalArgumentException(
                        "$label is not canonical key/value evidence",
                    )
                }
                val key = line.substring(0, separator)
                val previous = values.putIfAbsent(key, line.substring(separator + 1))
                if (previous != null &&
                    key !in listOf("file", "component", "composeFacade", "composeFunction")
                ) {
                    throw IllegalArgumentException(
                        "$label is not canonical key/value evidence",
                    )
                }
            }
            return values.toMap()
        }

        private fun requireField(values: Map<String, String>, key: String, expected: String) {
            if (expected != values[key]) {
                throw IllegalArgumentException("Evidence field $key must equal $expected")
            }
        }

        @Throws(IOException::class)
        private fun validateStagedTree(stage: Path, expected: Map<String, ByteArray>) {
            val actual = TreeMap<String, String>()
            Files.walk(stage).use { paths ->
                for (file in paths.filter { Files.isRegularFile(it) }.sorted().toList()) {
                    actual[
                        stage.relativize(file).toString().replace(File.separatorChar, '/'),
                    ] = sha256(Files.readAllBytes(file))
                }
            }
            val planned = TreeMap<String, String>()
            expected.forEach { (path, bytes) -> planned[path] = sha256(bytes) }
            if (actual != planned) {
                throw IOException(
                    "Staged Release Evidence Set differs from its validated plan",
                )
            }
        }

        @Throws(IOException::class)
        private fun recoverInterruptedPublication(output: Path, evidence: Path) {
            val outputBackup = backup(output)
            val evidenceBackup = backup(evidence)
            if (!Files.exists(output) && Files.exists(outputBackup)) move(outputBackup, output)
            if (!Files.exists(evidence) && Files.exists(evidenceBackup)) {
                move(evidenceBackup, evidence)
            }
            deleteTree(
                output.resolveSibling(output.fileName.toString() + ".publication-staging"),
            )
            deleteTree(
                evidence.resolveSibling(evidence.fileName.toString() + ".publication-staging"),
            )
        }

        @Throws(IOException::class)
        private fun commit(
            outputStage: Path,
            output: Path,
            evidenceStage: Path,
            evidence: Path,
        ) {
            val outputBackup = backup(output)
            val evidenceBackup = backup(evidence)
            deleteTree(outputBackup)
            deleteTree(evidenceBackup)
            if (Files.exists(output)) move(output, outputBackup)
            if (Files.exists(evidence)) move(evidence, evidenceBackup)
            try {
                move(evidenceStage, evidence)
                move(outputStage, output)
                deleteTree(outputBackup)
                deleteTree(evidenceBackup)
            } catch (failure: IOException) {
                deleteTree(output)
                deleteTree(evidence)
                if (Files.exists(outputBackup)) move(outputBackup, output)
                if (Files.exists(evidenceBackup)) move(evidenceBackup, evidence)
                throw failure
            } finally {
                deleteTree(outputStage)
                deleteTree(evidenceStage)
            }
        }

        private fun backup(path: Path): Path =
            path.resolveSibling(path.fileName.toString() + ".publication-previous")

        @Throws(IOException::class)
        private fun move(source: Path, target: Path) {
            Files.createDirectories(target.parent)
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (unsupported: AtomicMoveNotSupportedException) {
                Files.move(source, target)
            }
        }

        @Throws(IOException::class)
        private fun deleteTree(root: Path) {
            if (!Files.exists(root)) return
            if (Files.isDirectory(root)) {
                Files.walk(root).use { paths ->
                    for (path in paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path)
                    }
                }
            } else {
                Files.deleteIfExists(root)
            }
        }

        private fun sha256(bytes: ByteArray): String = try {
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        } catch (impossible: NoSuchAlgorithmException) {
            throw IllegalStateException("SHA-256 is unavailable", impossible)
        }

        private fun failure(
            project: String,
            variant: String,
            target: String,
            reason: String?,
            repair: String,
        ): GradleException = KaleidoDiagnostic(
            "KLD-PUBLICATION-001",
            project,
            variant,
            "atomic-publication",
            MANIFEST_SCHEMA,
            target,
            reason ?: "",
            repair,
        ).failure()
    }

    @JvmRecord
    data class Context(val project: String, val variant: String, val pluginVersion: String)

    @JvmRecord
    data class Publication(
        val files: Map<String, ByteArray>,
        val signedBundle: ByteArray,
        val setId: String,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Publication) return false
            if (setId != other.setId) return false
            if (!signedBundle.contentEquals(other.signedBundle)) return false
            if (files.size != other.files.size) return false
            for ((key, value) in files) {
                val otherValue = other.files[key] ?: return false
                if (!value.contentEquals(otherValue)) return false
            }
            return true
        }

        override fun hashCode(): Int {
            var result = setId.hashCode()
            result = 31 * result + signedBundle.contentHashCode()
            result = 31 * result + files.entries.fold(0) { acc, (key, value) ->
                acc + 31 * key.hashCode() + value.contentHashCode()
            }
            return result
        }
    }
}
