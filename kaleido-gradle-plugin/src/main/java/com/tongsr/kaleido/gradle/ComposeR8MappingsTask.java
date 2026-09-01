package com.tongsr.kaleido.gradle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.CacheableTask;

@CacheableTask
public abstract class ComposeR8MappingsTask extends DefaultTask {
    @Input public String getKaleidoCacheSchema() { return "R8MappingCompositionCache.v1"; }
    @Input public abstract Property<String> getConsumerProjectPath();
    @Input public abstract Property<String> getVariantName();
    @Input public abstract Property<String> getRetraceToolCoordinates();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getRawKaleidoMapping();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInputRawR8Mapping();

    @OutputFile public abstract RegularFileProperty getCapturedRawR8Mapping();
    @OutputFile public abstract RegularFileProperty getComposedMapping();
    @OutputFile public abstract RegularFileProperty getCompositionMetadata();

    @TaskAction
    public void composeMappings() throws IOException {
        var project = getConsumerProjectPath().get();
        var variant = getVariantName().get();
        var kaleidoBytes = Files.readAllBytes(getRawKaleidoMapping().get().getAsFile().toPath());
        var r8Bytes = Files.readAllBytes(getInputRawR8Mapping().get().getAsFile().toPath());
        final R8MappingComposer.Result result;
        try {
            result = R8MappingComposer.compose(
                    new String(kaleidoBytes, StandardCharsets.UTF_8),
                    new String(r8Bytes, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException invalid) {
            throw new KaleidoDiagnostic("KLD-R8-002", project, variant,
                    "mapping-composition", R8MappingComposer.SCHEMA, "mapping",
                    invalid.getMessage(),
                    "Regenerate raw Kaleido and R8 mappings in the same Release build").failure();
        }
        var composedBytes = result.composedMapping().getBytes(StandardCharsets.UTF_8);
        writeBytes(getCapturedRawR8Mapping().get().getAsFile().toPath(), r8Bytes);
        writeBytes(getComposedMapping().get().getAsFile().toPath(), composedBytes);

        var metadata = result.rawMetadata();
        var evidence = new StringBuilder()
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
                .append("rawR8Compiler=").append(metadata.compiler()).append('\n')
                .append("rawR8CompilerVersion=").append(metadata.compilerVersion()).append('\n')
                .append("rawR8MappingVersion=").append(metadata.mappingVersion()).append('\n')
                .append("rawR8PgMapId=").append(metadata.pgMapId()).append('\n')
                .append("rawR8PgMapHash=").append(metadata.pgMapHash()).append('\n')
                .append("retraceToolCoordinates=").append(getRetraceToolCoordinates().get())
                .append('\n')
                .append("retraceToolVersion=").append(metadata.compilerVersion()).append('\n')
                .append("kaleidoMappingRows=").append(result.kaleidoMappingRows()).append('\n')
                .append("r8ClassRows=").append(result.r8ClassRows()).append('\n');
        writeBytes(getCompositionMetadata().get().getAsFile().toPath(),
                evidence.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(Path output, byte[] bytes) throws IOException {
        Files.createDirectories(output.getParent());
        var temporary = output.resolveSibling(output.getFileName() + ".tmp");
        Files.write(temporary, bytes);
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }
}
