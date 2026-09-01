package com.tongsr.kaleido.gradle

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class ComposeR8MappingsTask : DefaultTask() {
    @get:Input
    val kaleidoCacheSchema: String
        get() = "R8MappingCompositionCache.v1"

    @get:Input
    abstract val consumerProjectPath: Property<String>

    @get:Input
    abstract val variantName: Property<String>

    @get:Input
    abstract val retraceToolCoordinates: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val rawKaleidoMapping: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputRawR8Mapping: RegularFileProperty

    @get:OutputFile
    abstract val capturedRawR8Mapping: RegularFileProperty

    @get:OutputFile
    abstract val composedMapping: RegularFileProperty

    @get:OutputFile
    abstract val compositionMetadata: RegularFileProperty

    @TaskAction
    fun composeMappings() {
        val project = consumerProjectPath.get()
        val variant = variantName.get()
        val kaleidoBytes = Files.readAllBytes(rawKaleidoMapping.get().asFile.toPath())
        val r8Bytes = Files.readAllBytes(inputRawR8Mapping.get().asFile.toPath())
        val result = try {
            R8MappingComposer.compose(
                String(kaleidoBytes, StandardCharsets.UTF_8),
                String(r8Bytes, StandardCharsets.UTF_8),
            )
        } catch (invalid: IllegalArgumentException) {
            throw KaleidoDiagnostic(
                "KLD-R8-002",
                project,
                variant,
                "mapping-composition",
                R8MappingComposer.SCHEMA,
                "mapping",
                invalid.message ?: "",
                "Regenerate raw Kaleido and R8 mappings in the same Release build",
            ).failure()
        }
        val composedBytes = result.composedMapping.toByteArray(StandardCharsets.UTF_8)
        writeBytes(capturedRawR8Mapping.get().asFile.toPath(), r8Bytes)
        writeBytes(composedMapping.get().asFile.toPath(), composedBytes)

        val metadata = result.rawMetadata
        val evidence = StringBuilder()
            .append("schema=").append(R8MappingComposer.SCHEMA).append('\n')
            .append("composer=").append(R8MappingComposer.PRODUCER).append('\n')
            .append("project=").append(project).append('\n')
            .append("variant=").append(variant).append('\n')
            .append("rawKaleidoSha256=").append(GenerateR8ConfigurationTask.sha256(kaleidoBytes))
            .append('\n')
            .append("rawR8Sha256=").append(GenerateR8ConfigurationTask.sha256(r8Bytes))
            .append('\n')
            .append("composedSha256=").append(GenerateR8ConfigurationTask.sha256(composedBytes))
            .append('\n')
            .append("rawR8Compiler=").append(metadata.compiler).append('\n')
            .append("rawR8CompilerVersion=").append(metadata.compilerVersion).append('\n')
            .append("rawR8MappingVersion=").append(metadata.mappingVersion).append('\n')
            .append("rawR8PgMapId=").append(metadata.pgMapId).append('\n')
            .append("rawR8PgMapHash=").append(metadata.pgMapHash).append('\n')
            .append("retraceToolCoordinates=").append(retraceToolCoordinates.get())
            .append('\n')
            .append("retraceToolVersion=").append(metadata.compilerVersion).append('\n')
            .append("kaleidoMappingRows=").append(result.kaleidoMappingRows).append('\n')
            .append("r8ClassRows=").append(result.r8ClassRows).append('\n')
        writeBytes(
            compositionMetadata.get().asFile.toPath(),
            evidence.toString().toByteArray(StandardCharsets.UTF_8),
        )
    }

    companion object {
        private fun writeBytes(output: Path, bytes: ByteArray) {
            Files.createDirectories(output.parent)
            val temporary = output.resolveSibling("${output.fileName}.tmp")
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
