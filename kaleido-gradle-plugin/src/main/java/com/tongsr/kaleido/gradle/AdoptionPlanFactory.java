package com.tongsr.kaleido.gradle;

import com.tongsr.kaleido.gradle.dsl.KaleidoProfile;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

final class AdoptionPlanFactory {
    private static final Pattern PACKAGE_NAME = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+");
    private static final Pattern NATIVE_LIBRARY = Pattern.compile(
            "lib[A-Za-z0-9_.-]+\\.so");
    private static final Pattern LANGUAGE_TAG = Pattern.compile(
            "[a-z]{2,3}(-[A-Z][a-z]{3})?(-([A-Z]{2}|[0-9]{3}))?");
    private static final List<String> SEED_DOMAINS = List.of(
            "bundle-resource",
            "class-rewrite",
            "generation-compose",
            "generation-ordinary",
            "r8-dictionary");

    private AdoptionPlanFactory() {}

    static AdoptionPlan create(Input input) {
        validate(input);
        var identity = input.variantName()
                + "|" + input.buildType()
                + "|" + String.join(",", input.flavors());
        var values = new TreeMap<String, String>();
        values.put("schema", "AdoptionPlan.v1");
        values.put("project", input.projectPath());
        values.put("variant.name", input.variantName());
        values.put("variant.buildType", input.buildType());
        values.put("variant.flavors", String.join(",", input.flavors()));
        values.put("applicationId", input.applicationId());
        values.put("profile", input.profile().name());
        values.put("defaultsVersion", "SafeDefaults.v1");
        values.put("generation.packageBase", input.packageBase());
        values.put("generation.packageCount", Integer.toString(input.packageCount()));
        values.put("generation.classesPerPackage", Integer.toString(input.classesPerPackage()));
        values.put("generation.methodsPerClass", Integer.toString(input.methodsPerClass()));
        values.put("generation.layoutCount", Integer.toString(input.layoutCount()));
        values.put("generation.drawableCount", Integer.toString(input.drawableCount()));
        values.put("generation.stringCount", Integer.toString(input.stringCount()));
        values.put("generation.activityCount", Integer.toString(input.activityCount()));
        values.put("generation.compose.enabled", Boolean.toString(input.composeEnabled()));
        values.put("generation.compose.buildFeatureEnabled",
                Boolean.toString(input.composeBuildFeatureEnabled()));
        values.put("generation.compose.compilerPluginApplied",
                Boolean.toString(input.composeCompilerPluginApplied()));
        values.put("generation.compose.fileCount", Integer.toString(input.composeFileCount()));
        values.put("generation.compose.functionsPerFile",
                Integer.toString(input.composeFunctionsPerFile()));
        values.put("resources.nativeLibrariesToDelete",
                String.join(",", input.nativeLibrariesToDelete().stream().sorted().toList()));
        values.put("resources.metadataToDelete",
                String.join(",", input.metadataToDelete().stream().sorted().toList()));
        values.put("resources.replaceUnusedStrings",
                Boolean.toString(input.replaceUnusedStrings()));
        values.put("resources.retainedLanguages",
                String.join(",", input.retainedLanguages().stream().sorted().toList()));
        values.put("protection.originalClassNames",
                String.join(",", input.originalClassNames().stream().sorted().toList()));
        values.put("protection.resourceNames",
                String.join(",", input.resourceNames().stream().sorted().toList()));
        values.put("protection.packagedPaths",
                String.join(",", input.packagedPaths().stream().sorted().toList()));
        values.put("seed.policyVersion", SeedDerivation.SEED_POLICY_VERSION);
        values.put("seed.fingerprint", input.seedFingerprint());
        for (var domain : SEED_DOMAINS) {
            values.put("seed.domain." + domain,
                    SeedDerivation.derive(input.seedFingerprint(), domain, identity));
        }
        values.put("resources.prefix", "kld_"
                + SeedDerivation.derive(input.seedFingerprint(), "resource-prefix", identity)
                        .substring(0, 8)
                + "_");
        return new AdoptionPlan(values);
    }

    private static void validate(Input input) {
        if (!PACKAGE_NAME.matcher(input.packageBase()).matches()) {
            throw failure(input, "generation.packageBase",
                    "Generation package base is not a legal dotted Java package",
                    "Use legal Java identifiers separated by dots");
        }
        range(input, "generation.packageCount", input.packageCount(), 1, 64);
        range(input, "generation.classesPerPackage", input.classesPerPackage(), 1, 64);
        range(input, "generation.methodsPerClass", input.methodsPerClass(), 1, 128);
        range(input, "generation.layoutCount", input.layoutCount(), 1, 256);
        range(input, "generation.drawableCount", input.drawableCount(), 1, 512);
        range(input, "generation.stringCount", input.stringCount(), 1, 4096);
        range(input, "generation.activityCount", input.activityCount(), 0, 64);
        range(input, "generation.compose.fileCount", input.composeFileCount(), 1, 64);
        range(input, "generation.compose.functionsPerFile",
                input.composeFunctionsPerFile(), 1, 32);
        if ((long) input.composeFileCount() * input.composeFunctionsPerFile() > 512) {
            throw failure(input, "generation.compose",
                    "Compose function total exceeds 512",
                    "Reduce fileCount or functionsPerFile");
        }
        if (input.composeEnabled() && !input.composeBuildFeatureEnabled()) {
            throw failure(input, "generation.compose.buildFeatureEnabled",
                    "Compose Generator requires buildFeatures.compose to be true",
                    "Enable the Consumer Project Compose build feature");
        }
        if (input.composeEnabled() && !input.composeCompilerPluginApplied()) {
            throw failure(input, "generation.compose.compilerPluginApplied",
                    "Compose Generator requires org.jetbrains.kotlin.plugin.compose",
                    "Apply the matching Compose compiler plugin to the Consumer Project");
        }
        if (!input.seedFingerprint().matches("[0-9a-f]{64}")) {
            throw failure(input, "seed.fingerprint",
                    "Seed fingerprint is not canonical SHA-256",
                    "Use the Kaleido seed Provider without pre-hashing it");
        }
        validateBoundedSet(input, "protection.originalClassNames", input.originalClassNames());
        validateBoundedSet(input, "protection.resourceNames", input.resourceNames());
        validateBoundedSet(input, "protection.packagedPaths", input.packagedPaths());
        if (input.nativeLibrariesToDelete().stream().anyMatch(
                name -> !NATIVE_LIBRARY.matcher(name).matches())) {
            throw failure(input, "resources.nativeLibrariesToDelete",
                    "Native deletion selectors must be exact lib*.so file names",
                    "Use a bounded native library basename without a path");
        }
        if (input.metadataToDelete().stream().anyMatch(
                selector -> !permittedMetadataSelector(selector))) {
            throw failure(input, "resources.metadataToDelete",
                    "Metadata deletion selector is outside the permitted META-INF contract",
                    "Select an exact META-INF LICENSE, NOTICE, DEPENDENCIES, or INDEX.LIST file");
        }
        if (input.retainedLanguages().stream().anyMatch(
                locale -> !LANGUAGE_TAG.matcher(locale).matches())) {
            throw failure(input, "resources.retainedLanguages",
                    "Retained language is not a canonical bounded language tag",
                    "Use tags such as en, fr, en-US, or zh-Hans-CN");
        }
        var fullOnlySelected = input.activityCount() > 0
                || !input.nativeLibrariesToDelete().isEmpty()
                || !input.metadataToDelete().isEmpty()
                || input.replaceUnusedStrings()
                || !input.retainedLanguages().isEmpty();
        if (input.profile() == KaleidoProfile.SAFE && fullOnlySelected) {
            throw failure(input, "profile",
                    "Safe Profile cannot select Full-only generation or resource controls",
                    "Select FULL explicitly or remove every Full-only declaration");
        }
    }

    private static boolean permittedMetadataSelector(String selector) {
        if (!selector.matches("META-INF/[A-Za-z0-9_.-]+")) return false;
        var name = selector.substring("META-INF/".length()).toUpperCase(java.util.Locale.ROOT);
        return name.equals("INDEX.LIST") || name.equals("DEPENDENCIES")
                || name.startsWith("LICENSE") || name.startsWith("NOTICE");
    }

    private static void range(Input input, String target, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw failure(input, target,
                    "Configured value is outside " + minimum + ".." + maximum,
                    "Choose a value inside the documented finite range");
        }
    }

    private static void validateBoundedSet(Input input, String target, Set<String> values) {
        if (values.size() > 1024 || values.stream().anyMatch(String::isBlank)) {
            throw failure(input, target,
                    "Declaration set is blank or exceeds the 1024-entry bound",
                    "Use finite nonblank declarations");
        }
    }

    private static org.gradle.api.GradleException failure(
            Input input, String target, String reason, String repair) {
        return new KaleidoDiagnostic(
                "KLD-CONFIG-001",
                input.projectPath(),
                input.variantName(),
                "adoption-plan",
                "kaleido.dsl",
                target,
                reason,
                repair).failure();
    }

    record Input(
            String projectPath,
            String variantName,
            String buildType,
            List<String> flavors,
            String applicationId,
            KaleidoProfile profile,
            String packageBase,
            int packageCount,
            int classesPerPackage,
            int methodsPerClass,
            int layoutCount,
            int drawableCount,
            int stringCount,
            int activityCount,
            boolean composeEnabled,
            boolean composeBuildFeatureEnabled,
            boolean composeCompilerPluginApplied,
            int composeFileCount,
            int composeFunctionsPerFile,
            Set<String> nativeLibrariesToDelete,
            Set<String> metadataToDelete,
            boolean replaceUnusedStrings,
            Set<String> retainedLanguages,
            Set<String> originalClassNames,
            Set<String> resourceNames,
            Set<String> packagedPaths,
            String seedFingerprint) {
        Input {
            flavors = List.copyOf(flavors);
            nativeLibrariesToDelete = Set.copyOf(nativeLibrariesToDelete);
            metadataToDelete = Set.copyOf(metadataToDelete);
            retainedLanguages = Set.copyOf(retainedLanguages);
            originalClassNames = Set.copyOf(originalClassNames);
            resourceNames = Set.copyOf(resourceNames);
            packagedPaths = Set.copyOf(packagedPaths);
        }
    }
}
