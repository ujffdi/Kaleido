package com.tongsr.kaleido.gradle;

import java.util.List;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

final class AdoptionValidator {
    private AdoptionValidator() {}

    static void validateDsl(DslSnapshot snapshot) {
        if (!snapshot.dynamicFeatures().isEmpty()) {
            throw diagnostic(
                    "KLD-TOPOLOGY-002",
                    snapshot.projectPath(),
                    "<none>",
                    "android.dynamicFeatures",
                    snapshot.dynamicFeatures().toString(),
                    "Dynamic Feature modules are unsupported",
                    "Remove Dynamic Feature modules from the Kaleido application").failure();
        }
        if (!snapshot.assetPacks().isEmpty()) {
            throw diagnostic(
                    "KLD-TOPOLOGY-003",
                    snapshot.projectPath(),
                    "<none>",
                    "android.assetPacks",
                    snapshot.assetPacks().toString(),
                    "Asset Pack modules are unsupported",
                    "Remove Asset Pack modules from the Kaleido application").failure();
        }
        if (!snapshot.buildTypes().contains("release")) {
            throw diagnostic(
                    "KLD-TOPOLOGY-004",
                    snapshot.projectPath(),
                    "<none>",
                    "android.buildTypes",
                    "release",
                    "No exact release build type is declared",
                    "Declare an exact release build type").failure();
        }
    }

    static void validateVariant(VariantSnapshot snapshot) {
        if (!"release".equals(snapshot.buildType())) {
            throw diagnostic(
                    "KLD-TOPOLOGY-005",
                    snapshot.projectPath(),
                    snapshot.variant(),
                    "AGP-variant-api",
                    snapshot.buildType(),
                    "Only exact release build types are eligible",
                    "Run an exact release variant").failure();
        }
        if (!snapshot.minifyEnabled()) {
            throw diagnostic(
                    "KLD-TOPOLOGY-006",
                    snapshot.projectPath(),
                    snapshot.variant(),
                    "AGP-variant-api",
                    snapshot.variant(),
                    "R8 minification is disabled for an eligible variant",
                    "Enable minification for every release variant").failure();
        }
    }

    static KaleidoDiagnostic wrongProjectType(String projectPath, String pluginId) {
        return diagnostic(
                "KLD-TOPOLOGY-001",
                projectPath,
                "<none>",
                "Gradle-plugin-manager",
                pluginId,
                "Kaleido was applied to a non-application target",
                "Apply Kaleido only to a com.android.application project");
    }

    static KaleidoDiagnostic noEligibleVariant(String projectPath) {
        return diagnostic(
                "KLD-TOPOLOGY-004",
                projectPath,
                "<none>",
                "AGP-variant-api",
                "release",
                "No enabled exact release variant was discovered",
                "Enable at least one exact release variant");
    }

    private static KaleidoDiagnostic diagnostic(
            String code,
            String project,
            String variant,
            String origin,
            String target,
            String reason,
            String repair) {
        return new KaleidoDiagnostic(
                code, project, variant, "adoption", origin, target, reason, repair);
    }

    record DslSnapshot(
            String projectPath,
            Set<String> dynamicFeatures,
            Set<String> assetPacks,
            Set<String> buildTypes) {
        DslSnapshot {
            dynamicFeatures = Collections.unmodifiableSet(new TreeSet<>(dynamicFeatures));
            assetPacks = Collections.unmodifiableSet(new TreeSet<>(assetPacks));
            buildTypes = Collections.unmodifiableSet(new TreeSet<>(buildTypes));
        }
    }

    record VariantSnapshot(
            String projectPath,
            String variant,
            String buildType,
            List<String> productFlavors,
            boolean minifyEnabled) {
        VariantSnapshot {
            productFlavors = List.copyOf(productFlavors);
        }
    }
}
