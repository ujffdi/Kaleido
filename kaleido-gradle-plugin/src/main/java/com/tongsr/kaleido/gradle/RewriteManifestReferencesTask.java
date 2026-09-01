package com.tongsr.kaleido.gradle;

import com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.CacheableTask;

@CacheableTask
public abstract class RewriteManifestReferencesTask extends DefaultTask {
    @Input public String getKaleidoCacheSchema() { return "ManifestRewriteCache.v2"; }
    @Input public abstract Property<String> getConsumerProjectPath();
    @Input public abstract Property<String> getVariantName();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getAdoptionPlan();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInputManifest();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getConsumerSourceDirectories();

    @OutputFile public abstract RegularFileProperty getOutputManifest();
    @OutputFile public abstract RegularFileProperty getRewriteIntent();

    @TaskAction
    public void rewrite() throws IOException {
        var project = getConsumerProjectPath().get();
        var variant = getVariantName().get();
        var adoption = readProperties(Files.readAllBytes(
                getAdoptionPlan().get().getAsFile().toPath()));
        var applicationId = adoption.get("applicationId");
        var stream = adoption.get("seed.domain.class-rewrite");
        if (!"AdoptionPlan.v1".equals(adoption.get("schema"))
                || applicationId == null || stream == null) {
            throw RewriteClassesAndManifestTask.failure(project, variant, "AdoptionPlan.v1",
                    "Manifest rewrite inputs are incomplete",
                    "Regenerate the complete Adoption Plan");
        }
        var protectedNames = new TreeSet<>(
                commaSeparated(adoption.get("protection.originalClassNames")));
        var classHatches = RewriteClassesAndManifestTask.parseEscapeHatches(
                adoption.get("protection.classEscapeHatches"),
                EscapeHatchDeclaration.Kind.CLASS, project, variant);
        var inputBytes = Files.readAllBytes(getInputManifest().get().getAsFile().toPath());
        var document = RewriteClassesAndManifestTask.parseManifest(inputBytes, project, variant);
        var references = RewriteClassesAndManifestTask.manifestReferences(document, applicationId);
        var roots = new TreeSet<String>();
        references.stream()
                .map(RewriteClassesAndManifestTask.ManifestReference::resolvedIdentity)
                .filter(identity -> identity.startsWith(applicationId + "."))
                .forEach(roots::add);
        protectedNames.addAll(SourceProtectionScanner.inferProtectedIdentities(
                getConsumerSourceDirectories().getFiles(), roots));
        classHatches.stream()
                .filter(hatch -> hatch.protects(KaleidoProtectionDimension.ORIGINAL_IDENTITY))
                .forEach(hatch -> roots.stream().filter(hatch::matches)
                        .forEach(protectedNames::add));
        var mapping = RewriteClassesAndManifestTask.allocateMapping(
                roots, protectedNames, roots, stream);
        var sites = references.stream()
                .filter(reference -> mapping.containsKey(reference.resolvedIdentity()))
                .map(reference -> new ClassRewriteArtifacts.ManifestSite(
                        reference.location(), reference.lexicalValue(),
                        RewriteClassesAndManifestTask.renderManifestIdentity(
                                reference.lexicalValue(), applicationId,
                                mapping.get(reference.resolvedIdentity()))))
                .sorted(java.util.Comparator.comparing(
                        ClassRewriteArtifacts.ManifestSite::location))
                .toList();
        var outputBytes = RewriteClassesAndManifestTask.rewriteManifest(
                document, sites, project, variant);
        write(getOutputManifest().get().getAsFile().toPath(), outputBytes);

        var intent = new StringBuilder()
                .append("schema=ManifestRewriteIntent.v1\n")
                .append("originalManifestSha256=")
                .append(RewriteClassesAndManifestTask.sha256(inputBytes)).append('\n');
        mapping.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                intent.append("mapping=").append(entry.getKey()).append('|')
                        .append(entry.getValue()).append('\n'));
        sites.forEach(site -> intent.append("site=").append(site.location()).append('|')
                .append(site.original()).append('|').append(site.target()).append('\n'));
        write(getRewriteIntent().get().getAsFile().toPath(),
                intent.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> readProperties(byte[] bytes) {
        var values = new HashMap<String, String>();
        for (var line : new String(bytes, StandardCharsets.UTF_8).split("\\n")) {
            var separator = line.indexOf('=');
            if (separator > 0) {
                values.put(line.substring(0, separator), line.substring(separator + 1));
            }
        }
        return Map.copyOf(values);
    }

    private static Set<String> commaSeparated(String value) {
        return value == null || value.isBlank() ? Set.of() : Set.of(value.split(","));
    }

    private static void write(java.nio.file.Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
    }
}
