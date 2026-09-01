package com.tongsr.kaleido.gradle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.CacheableTask;

@CacheableTask
public abstract class GenerateR8ConfigurationTask extends DefaultTask {
    @Input public String getKaleidoCacheSchema() { return "R8ConfigurationCache.v1"; }
    @Input public abstract Property<String> getConsumerProjectPath();
    @Input public abstract Property<String> getVariantName();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getAdoptionPlan();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getClassRewritePlan();

    @OutputDirectory public abstract DirectoryProperty getRulesOutputDirectory();
    @OutputDirectory public abstract DirectoryProperty getDictionariesOutputDirectory();
    @OutputFile public abstract RegularFileProperty getConfigurationEvidence();

    @TaskAction
    public void generateConfiguration() throws IOException {
        var project = getConsumerProjectPath().get();
        var variant = getVariantName().get();
        var adoptionBytes = Files.readAllBytes(getAdoptionPlan().get().getAsFile().toPath());
        var planBytes = Files.readAllBytes(getClassRewritePlan().get().getAsFile().toPath());
        var adoption = properties(adoptionBytes);
        var classPlan = ClassRewriteArtifacts.decodePlan(planBytes, project, variant);
        if (!project.equals(classPlan.project()) || !variant.equals(classPlan.variant())) {
            throw failure(project, variant, "class-rewrite-plan.pb",
                    "Class Rewrite Plan belongs to a different project or variant",
                    "Regenerate the Release variant from stable inputs");
        }
        if (!sha256(adoptionBytes).equals(classPlan.adoptionPlanSha256())) {
            throw failure(project, variant, "adoption-plan.properties",
                    "Adoption Plan digest does not match the Class Rewrite Plan",
                    "Regenerate both plans in the same Release build");
        }

        final R8ConfigurationEngine.Configuration configuration;
        try {
            configuration = R8ConfigurationEngine.generate(adoption, classPlan);
        } catch (IllegalArgumentException invalid) {
            throw failure(project, variant, "r8-configuration",
                    invalid.getMessage(), "Regenerate a complete and consistent class plan");
        }
        var rulesRoot = getRulesOutputDirectory().get().getAsFile().toPath();
        var dictionariesRoot = getDictionariesOutputDirectory().get().getAsFile().toPath();
        recreate(rulesRoot);
        recreate(dictionariesRoot);
        write(rulesRoot.resolve("kaleido-r8.keep"), configuration.rules());
        for (var entry : configuration.dictionaries().entrySet()) {
            write(dictionariesRoot.resolve(entry.getKey()), entry.getValue());
        }

        var evidence = new StringBuilder()
                .append("schema=").append(R8ConfigurationEngine.SCHEMA).append('\n')
                .append("producer=").append(R8ConfigurationEngine.PRODUCER).append('\n')
                .append("project=").append(project).append('\n')
                .append("variant=").append(variant).append('\n')
                .append("adoptionPlanSha256=").append(sha256(adoptionBytes)).append('\n')
                .append("classRewritePlanSha256=").append(sha256(planBytes)).append('\n')
                .append("dictionarySize=").append(R8ConfigurationEngine.DICTIONARY_SIZE).append('\n')
                .append("rulesSha256=")
                .append(sha256(configuration.rules().getBytes(StandardCharsets.UTF_8))).append('\n');
        configuration.dictionaries().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> evidence.append("dictionary=").append(entry.getKey())
                        .append('|').append(sha256(entry.getValue().getBytes(StandardCharsets.UTF_8)))
                        .append('\n'));
        configuration.fixedIdentities().forEach(identity ->
                evidence.append("fixedIdentity=").append(identity).append('\n'));
        write(getConfigurationEvidence().get().getAsFile().toPath(), evidence.toString());
    }

    private static Map<String, String> properties(byte[] bytes) {
        var values = new TreeMap<String, String>();
        for (var line : new String(bytes, StandardCharsets.UTF_8).split("\\n")) {
            var separator = line.indexOf('=');
            if (separator > 0) values.put(line.substring(0, separator), line.substring(separator + 1));
        }
        return Map.copyOf(values);
    }

    private static void recreate(Path root) throws IOException {
        if (Files.exists(root)) {
            try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        Files.createDirectories(root);
    }

    private static void write(Path output, String text) throws IOException {
        Files.createDirectories(output.getParent());
        var temporary = output.resolveSibling(output.getFileName() + ".tmp");
        Files.writeString(temporary, text, StandardCharsets.UTF_8);
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static org.gradle.api.GradleException failure(
            String project, String variant, String target, String reason, String repair) {
        return new KaleidoDiagnostic("KLD-R8-001", project, variant, "r8-configuration",
                R8ConfigurationEngine.SCHEMA, target, reason, repair).failure();
    }
}
