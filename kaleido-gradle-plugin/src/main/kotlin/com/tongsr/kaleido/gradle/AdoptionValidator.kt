package com.tongsr.kaleido.gradle

internal object AdoptionValidator {
    fun validateDsl(snapshot: DslSnapshot) {
        if (snapshot.dynamicFeatures.isNotEmpty()) {
            throw diagnostic(
                "KLD-TOPOLOGY-002",
                snapshot.projectPath,
                "<none>",
                "android.dynamicFeatures",
                snapshot.dynamicFeatures.toSortedSet().toString(),
                "Dynamic Feature modules are unsupported",
                "Remove Dynamic Feature modules from the Kaleido application",
            ).failure()
        }
        if (snapshot.assetPacks.isNotEmpty()) {
            throw diagnostic(
                "KLD-TOPOLOGY-003",
                snapshot.projectPath,
                "<none>",
                "android.assetPacks",
                snapshot.assetPacks.toSortedSet().toString(),
                "Asset Pack modules are unsupported",
                "Remove Asset Pack modules from the Kaleido application",
            ).failure()
        }
        if ("release" !in snapshot.buildTypes) {
            throw diagnostic(
                "KLD-TOPOLOGY-004",
                snapshot.projectPath,
                "<none>",
                "android.buildTypes",
                "release",
                "No exact release build type is declared",
                "Declare an exact release build type",
            ).failure()
        }
    }

    fun validateVariant(snapshot: VariantSnapshot) {
        if (snapshot.buildType != "release") {
            throw diagnostic(
                "KLD-TOPOLOGY-005",
                snapshot.projectPath,
                snapshot.variant,
                "AGP-variant-api",
                snapshot.buildType,
                "Only exact release build types are eligible",
                "Run an exact release variant",
            ).failure()
        }
        if (!snapshot.minifyEnabled) {
            throw diagnostic(
                "KLD-TOPOLOGY-006",
                snapshot.projectPath,
                snapshot.variant,
                "AGP-variant-api",
                snapshot.variant,
                "R8 minification is disabled for an eligible variant",
                "Enable minification for every release variant",
            ).failure()
        }
    }

    fun wrongProjectType(projectPath: String, pluginId: String): KaleidoDiagnostic =
        diagnostic(
            "KLD-TOPOLOGY-001",
            projectPath,
            "<none>",
            "Gradle-plugin-manager",
            pluginId,
            "Kaleido was applied to a non-application target",
            "Apply Kaleido only to a com.android.application project",
        )

    fun noEligibleVariant(projectPath: String): KaleidoDiagnostic =
        diagnostic(
            "KLD-TOPOLOGY-004",
            projectPath,
            "<none>",
            "AGP-variant-api",
            "release",
            "No enabled exact release variant was discovered",
            "Enable at least one exact release variant",
        )

    private fun diagnostic(
        code: String,
        project: String,
        variant: String,
        origin: String,
        target: String,
        reason: String,
        repair: String,
    ): KaleidoDiagnostic = KaleidoDiagnostic(
        code, project, variant, "adoption", origin, target, reason, repair,
    )

    @JvmRecord
    data class DslSnapshot(
        val projectPath: String,
        val dynamicFeatures: Set<String>,
        val assetPacks: Set<String>,
        val buildTypes: Set<String>,
    )

    @JvmRecord
    data class VariantSnapshot(
        val projectPath: String,
        val variant: String,
        val buildType: String,
        val productFlavors: List<String>,
        val minifyEnabled: Boolean,
    )
}
