package com.tongsr.kaleido.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
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
public abstract class KaleidoBundleFinalizeTask extends DefaultTask {
    @Input public String getKaleidoCacheSchema() { return "UnsignedBundleRewriteCache.v1"; }
    @Input
    public abstract Property<String> getConsumerProjectPath();

    @Input
    public abstract Property<String> getVariantName();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInputBundle();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getAdoptionPlan();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getGenerationInventory();

    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getClassRewriteEvidence();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getResourceProtectionEvidence();

    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getR8Evidence();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getApplicationResourceDirectories();

    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getApplicationNativeDirectories();

    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getApplicationMetadataDirectories();

    @OutputFile
    public abstract RegularFileProperty getOutputBundle();

    @OutputFile public abstract RegularFileProperty getBundleRewritePlan();
    @OutputFile public abstract RegularFileProperty getResourceMapping();
    @OutputFile public abstract RegularFileProperty getBundleTransformReceipt();
    @OutputFile public abstract RegularFileProperty getUnsignedCandidateEvidence();
    @OutputFile public abstract RegularFileProperty getUnsignedCandidateDigest();

    @TaskAction
    public void finalizeBundle() throws IOException {
        var input = getInputBundle().get().getAsFile().toPath();
        var output = getOutputBundle().get().getAsFile().toPath();
        AabTopologyValidator.validate(
                input,
                new AabTopologyValidator.Context(
                        getConsumerProjectPath().get(), getVariantName().get()));
        var project = getConsumerProjectPath().get();
        var variant = getVariantName().get();
        var adoption = readProperties(getAdoptionPlan().get().getAsFile().toPath());
        var roots = getApplicationResourceDirectories().getFiles().stream()
                .map(java.io.File::toPath).sorted().toList();
        var inventory = ApplicationResourceInventory.scan(roots, project, variant);
        var protectedNames = new TreeSet<>(commaSeparated(
                adoption.get("protection.resourceNames")));
        protectedNames.addAll(inventory.protectedNames());
        protectedNames.addAll(readResourceProtectionNames(
                getResourceProtectionEvidence().get().getAsFile().toPath(), project, variant));
        inventory.warnings().forEach(getLogger()::warn);
        var hatches = RewriteClassesAndManifestTask.parseEscapeHatches(
                adoption.get("protection.resourceEscapeHatches"),
                EscapeHatchDeclaration.Kind.RESOURCE, project, variant);
        var context = new BundleRewriteModule.Context(project, variant, getLogger()::warn);
        var fullControls = new BundleRewriteModule.FullControls(
                commaSeparated(adoption.get("resources.nativeLibrariesToDelete")),
                commaSeparated(adoption.get("resources.metadataToDelete")),
                commaSeparated(adoption.get("resources.confirmedUnusedStrings")),
                commaSeparated(adoption.get("resources.retainedLanguages")),
                inventoryNativeLibraries(getApplicationNativeDirectories()),
                inventoryMetadata(getApplicationMetadataDirectories()));
        final BundleRewriteArtifacts.Plan plan;
        try {
            plan = BundleRewriteModule.plan(
                    input, context, inventory.resources(), protectedNames,
                    commaSeparated(adoption.get("protection.packagedPaths")), hatches,
                    required(adoption, "seed.domain.bundle-resource", project, variant),
                    fullControls);
        } catch (RuntimeException invalid) {
            if (invalid instanceof org.gradle.api.GradleException gradleFailure) {
                throw gradleFailure;
            }
            throw new KaleidoDiagnostic("KLD-BUNDLE-001", project, variant,
                    "bundle-rewrite", BundleRewriteArtifacts.PLAN_SCHEMA,
                    "planning", invalid.getMessage(),
                    "Resolve the invalid resource identity or path and rebuild").failure();
        }
        var planBytes = BundleRewriteArtifacts.encodePlan(plan);
        write(getBundleRewritePlan().get().getAsFile().toPath(), planBytes);
        var executablePlan = BundleRewriteArtifacts.decodePlan(planBytes, project, variant);
        var execution = BundleRewriteModule.execute(input, output, executablePlan,
                planBytes, context);
        write(getResourceMapping().get().getAsFile().toPath(),
                execution.resourceMapping().getBytes(StandardCharsets.UTF_8));
        write(getBundleTransformReceipt().get().getAsFile().toPath(), execution.receiptBytes());
        write(getUnsignedCandidateEvidence().get().getAsFile().toPath(), Files.readAllBytes(output));
        write(getUnsignedCandidateDigest().get().getAsFile().toPath(),
                (RewriteClassesAndManifestTask.sha256(Files.readAllBytes(output)) + "\n")
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> readProperties(java.nio.file.Path path) throws IOException {
        var values = new HashMap<String, String>();
        for (var line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            var separator = line.indexOf('=');
            if (separator > 0) values.put(line.substring(0, separator),
                    line.substring(separator + 1));
        }
        return Map.copyOf(values);
    }

    private static java.util.Set<String> commaSeparated(String value) {
        if (value == null || value.isBlank()) return java.util.Set.of();
        return java.util.Arrays.stream(value.split(","))
                .filter(item -> !item.isBlank()).collect(
                        java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static java.util.Set<String> readResourceProtectionNames(
            java.nio.file.Path path, String project, String variant) throws IOException {
        var lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).equals("schema=ResourceProtectionEvidence.v1")) {
            throw new KaleidoDiagnostic("KLD-BUNDLE-001", project, variant,
                    "bundle-rewrite", BundleRewriteArtifacts.PLAN_SCHEMA, path.toString(),
                    "Resource protection evidence schema is missing or incompatible",
                    "Regenerate class rewrite evidence with this Kaleido version").failure();
        }
        var names = new TreeSet<String>();
        for (var line : lines.subList(1, lines.size())) {
            if (line.isBlank()) continue;
            if (!line.startsWith("resource=") || !line.contains("|exact-getIdentifier|")) {
                throw new KaleidoDiagnostic("KLD-BUNDLE-001", project, variant,
                        "bundle-rewrite", BundleRewriteArtifacts.PLAN_SCHEMA, line,
                        "Resource protection evidence entry is malformed",
                        "Regenerate class rewrite evidence with this Kaleido version").failure();
            }
            var identity = line.substring("resource=".length(), line.indexOf('|'));
            var slash = identity.indexOf('/');
            if (slash <= 0 || slash == identity.length() - 1) {
                throw new KaleidoDiagnostic("KLD-BUNDLE-001", project, variant,
                        "bundle-rewrite", BundleRewriteArtifacts.PLAN_SCHEMA, identity,
                        "Resource protection identity is malformed",
                        "Regenerate class rewrite evidence with this Kaleido version").failure();
            }
            names.add(identity.substring(slash + 1));
        }
        return Set.copyOf(names);
    }

    private static Set<String> inventoryNativeLibraries(
            ConfigurableFileCollection directories) throws IOException {
        var names = new TreeSet<String>();
        for (var rootFile : directories.getFiles()) {
            var root = rootFile.toPath();
            if (!Files.isDirectory(root)) continue;
            try (var paths = Files.walk(root)) {
                paths.filter(Files::isRegularFile).map(path -> path.getFileName().toString())
                        .filter(name -> name.matches("lib[A-Za-z0-9_.-]+\\.so"))
                        .forEach(names::add);
            }
        }
        return Set.copyOf(names);
    }

    private static Set<String> inventoryMetadata(
            ConfigurableFileCollection directories) throws IOException {
        var names = new TreeSet<String>();
        for (var rootFile : directories.getFiles()) {
            var root = rootFile.toPath();
            if (!Files.isDirectory(root)) continue;
            try (var paths = Files.walk(root)) {
                for (var path : paths.filter(Files::isRegularFile).toList()) {
                    var relative = root.relativize(path).toString()
                            .replace(java.io.File.separatorChar, '/');
                    if (relative.startsWith("META-INF/")) names.add(relative);
                }
            }
        }
        return Set.copyOf(names);
    }

    private static String required(
            Map<String, String> values, String key, String project, String variant) {
        var value = values.get(key);
        if (value == null || value.isBlank()) throw new KaleidoDiagnostic(
                "KLD-BUNDLE-001", project, variant, "bundle-rewrite",
                BundleRewriteArtifacts.PLAN_SCHEMA, key,
                "Adoption Plan is missing a required Bundle rewrite input",
                "Regenerate a complete AdoptionPlan.v1").failure();
        return value;
    }

    private static void write(java.nio.file.Path output, byte[] bytes) throws IOException {
        Files.createDirectories(output.getParent());
        var temporary = output.resolveSibling(output.getFileName() + ".tmp");
        Files.write(temporary, bytes);
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }
}
