package com.tongsr.kaleido.gradle;

import com.tongsr.kaleido.gradle.dsl.KaleidoProfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.TreeMap;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.CacheableTask;

@CacheableTask
public abstract class ResolveKaleidoAdoptionPlanTask extends DefaultTask {
    @Input public String getKaleidoCacheSchema() { return "ResolveAdoptionPlanCache.v1"; }
    @Input public abstract Property<String> getConsumerProjectPath();
    @Input public abstract Property<String> getVariantName();
    @Input public abstract Property<String> getBuildType();
    @Input public abstract ListProperty<String> getFlavorIdentities();
    @Input public abstract Property<String> getApplicationId();
    @Input public abstract Property<KaleidoProfile> getProfile();
    @Input public abstract Property<String> getPackageBase();
    @Input public abstract Property<Integer> getPackageCount();
    @Input public abstract Property<Integer> getClassesPerPackage();
    @Input public abstract Property<Integer> getMethodsPerClass();
    @Input public abstract Property<Integer> getLayoutCount();
    @Input public abstract Property<Integer> getDrawableCount();
    @Input public abstract Property<Integer> getStringCount();
    @Input public abstract Property<Integer> getActivityCount();
    @Input public abstract Property<Boolean> getComposeEnabled();
    @Input public abstract Property<Boolean> getComposeBuildFeatureEnabled();
    @Input public abstract Property<Boolean> getComposeCompilerPluginApplied();
    @Input public abstract Property<Integer> getComposeFileCount();
    @Input public abstract Property<Integer> getComposeFunctionsPerFile();
    @Input public abstract SetProperty<String> getNativeLibrariesToDelete();
    @Input public abstract SetProperty<String> getMetadataToDelete();
    @Input public abstract Property<Boolean> getReplaceUnusedStrings();
    @InputFile @Optional @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getConfirmedUnusedStringsFile();
    @Input public abstract SetProperty<String> getRetainedLanguages();
    @Input public abstract SetProperty<String> getOriginalClassNames();
    @Input public abstract SetProperty<String> getResourceNames();
    @Input public abstract SetProperty<String> getPackagedPaths();
    @Input public abstract ListProperty<String> getClassEscapeHatches();
    @Input public abstract ListProperty<String> getResourceEscapeHatches();
    @Input public abstract Property<String> getSeedFingerprint();
    @OutputFile public abstract RegularFileProperty getPlanFile();

    @TaskAction
    public void resolvePlan() throws IOException {
        var confirmedUnusedFilePresent = getConfirmedUnusedStringsFile().isPresent();
        var replaceUnusedStrings = getReplaceUnusedStrings().get()
                || confirmedUnusedFilePresent;
        var plan = AdoptionPlanFactory.create(new AdoptionPlanFactory.Input(
                getConsumerProjectPath().get(),
                getVariantName().get(),
                getBuildType().get(),
                getFlavorIdentities().get(),
                getApplicationId().get(),
                getProfile().get(),
                getPackageBase().get(),
                getPackageCount().get(),
                getClassesPerPackage().get(),
                getMethodsPerClass().get(),
                getLayoutCount().get(),
                getDrawableCount().get(),
                getStringCount().get(),
                getActivityCount().get(),
                getComposeEnabled().get(),
                getComposeBuildFeatureEnabled().get(),
                getComposeCompilerPluginApplied().get(),
                getComposeFileCount().get(),
                getComposeFunctionsPerFile().get(),
                getNativeLibrariesToDelete().get(),
                getMetadataToDelete().get(),
                replaceUnusedStrings,
                getRetainedLanguages().get(),
                getOriginalClassNames().get(),
                getResourceNames().get(),
                getPackagedPaths().get(),
                getSeedFingerprint().get()));
        var classHatches = getClassEscapeHatches().get().stream()
                .map(value -> EscapeHatchDeclaration.parse(value,
                        EscapeHatchDeclaration.Kind.CLASS,
                        getConsumerProjectPath().get(), getVariantName().get()))
                .toList();
        var resourceHatches = getResourceEscapeHatches().get().stream()
                .map(value -> EscapeHatchDeclaration.parse(value,
                        EscapeHatchDeclaration.Kind.RESOURCE,
                        getConsumerProjectPath().get(), getVariantName().get()))
                .toList();
        var ids = new HashSet<String>();
        for (var declaration : java.util.stream.Stream.concat(
                classHatches.stream(), resourceHatches.stream()).toList()) {
            if (!ids.add(declaration.id())) {
                throw new KaleidoDiagnostic("KLD-PROTECTION-001",
                        getConsumerProjectPath().get(), getVariantName().get(),
                        "protection", "kaleido.protection", declaration.id(),
                        "Escape Hatch ID is duplicated across typed blocks",
                        "Use one globally unique stable ID per variant").failure();
            }
        }
        var values = new TreeMap<>(plan.values());
        if (replaceUnusedStrings) {
            if (!confirmedUnusedFilePresent) {
                throw new KaleidoDiagnostic("KLD-CONFIG-001",
                        getConsumerProjectPath().get(), getVariantName().get(),
                        "adoption-plan", "kaleido.resources", "confirmedUnusedStringsFile",
                        "Unused-string replacement requires an explicit confirmed-unused file",
                        "Set confirmedUnusedStringsFile or disable replaceUnusedStrings").failure();
            }
            var unusedFile = getConfirmedUnusedStringsFile().get().getAsFile().toPath();
            var unusedNames = new java.util.TreeSet<String>();
            for (var rawLine : Files.readAllLines(unusedFile, StandardCharsets.UTF_8)) {
                var line = rawLine.trim();
                if (line.isBlank() || line.startsWith("#")) continue;
                if (line.startsWith("string/")) line = line.substring("string/".length());
                if (!line.matches("[a-z][a-z0-9_]*") || !unusedNames.add(line)) {
                    throw new KaleidoDiagnostic("KLD-CONFIG-001",
                            getConsumerProjectPath().get(), getVariantName().get(),
                            "adoption-plan", "kaleido.resources", rawLine,
                            "Confirmed-unused string entry is malformed or duplicated",
                            "Use one exact string resource name per line").failure();
                }
            }
            if (unusedNames.isEmpty()) {
                throw new KaleidoDiagnostic("KLD-CONFIG-001",
                        getConsumerProjectPath().get(), getVariantName().get(),
                        "adoption-plan", "kaleido.resources", "confirmedUnusedStringsFile",
                        "Confirmed-unused string file resolves to zero exact targets",
                        "List at least one exact string resource name").failure();
            }
            values.put("resources.confirmedUnusedStrings", String.join(",", unusedNames));
            values.put("resources.confirmedUnusedStringsSha256",
                    RewriteClassesAndManifestTask.sha256(Files.readAllBytes(unusedFile)));
        } else {
            values.put("resources.confirmedUnusedStrings", "");
            values.put("resources.confirmedUnusedStringsSha256", "");
        }
        values.put("protection.classEscapeHatches",
                String.join(",", getClassEscapeHatches().get().stream().sorted().toList()));
        values.put("protection.resourceEscapeHatches",
                String.join(",", getResourceEscapeHatches().get().stream().sorted().toList()));
        plan = new AdoptionPlan(values);
        var output = getPlanFile().get().getAsFile().toPath();
        Files.createDirectories(output.getParent());
        var temporary = output.resolveSibling(output.getFileName() + ".tmp");
        Files.writeString(temporary, plan.canonicalText(), StandardCharsets.UTF_8);
        Files.move(temporary, output,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
