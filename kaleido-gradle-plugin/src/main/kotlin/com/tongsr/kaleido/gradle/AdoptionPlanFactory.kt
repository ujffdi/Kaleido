package com.tongsr.kaleido.gradle

import com.tongsr.kaleido.gradle.dsl.KaleidoProfile
import java.util.Locale
import java.util.TreeMap

internal object AdoptionPlanFactory {
    private val PACKAGE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
    private val NATIVE_LIBRARY = Regex("lib[A-Za-z0-9_.-]+\\.so")
    private val LANGUAGE_TAG = Regex("[a-z]{2,3}(-[A-Z][a-z]{3})?(-([A-Z]{2}|[0-9]{3}))?")
    private val SEED_DOMAINS = listOf(
        "bundle-resource",
        "class-rewrite",
        "generation-compose",
        "generation-ordinary",
        "r8-dictionary",
    )

    @JvmStatic
    fun create(input: Input): AdoptionPlan {
        validate(input)
        val identity = input.variantName + "|" + input.buildType + "|" + input.flavors.joinToString(",")
        val values = TreeMap<String, String>()
        values["schema"] = "AdoptionPlan.v1"
        values["project"] = input.projectPath
        values["variant.name"] = input.variantName
        values["variant.buildType"] = input.buildType
        values["variant.flavors"] = input.flavors.joinToString(",")
        values["applicationId"] = input.applicationId
        values["profile"] = input.profile.name
        values["defaultsVersion"] = "SafeDefaults.v1"
        values["generation.packageBase"] = input.packageBase
        values["generation.packageCount"] = input.packageCount.toString()
        values["generation.classesPerPackage"] = input.classesPerPackage.toString()
        values["generation.methodsPerClass"] = input.methodsPerClass.toString()
        values["generation.layoutCount"] = input.layoutCount.toString()
        values["generation.drawableCount"] = input.drawableCount.toString()
        values["generation.stringCount"] = input.stringCount.toString()
        values["generation.activityCount"] = input.activityCount.toString()
        values["generation.compose.enabled"] = input.composeEnabled.toString()
        values["generation.compose.buildFeatureEnabled"] = input.composeBuildFeatureEnabled.toString()
        values["generation.compose.compilerPluginApplied"] = input.composeCompilerPluginApplied.toString()
        values["generation.compose.fileCount"] = input.composeFileCount.toString()
        values["generation.compose.functionsPerFile"] = input.composeFunctionsPerFile.toString()
        values["resources.nativeLibrariesToDelete"] =
            input.nativeLibrariesToDelete.sorted().joinToString(",")
        values["resources.metadataToDelete"] =
            input.metadataToDelete.sorted().joinToString(",")
        values["resources.replaceUnusedStrings"] = input.replaceUnusedStrings.toString()
        values["resources.retainedLanguages"] =
            input.retainedLanguages.sorted().joinToString(",")
        values["protection.originalClassNames"] =
            input.originalClassNames.sorted().joinToString(",")
        values["protection.resourceNames"] =
            input.resourceNames.sorted().joinToString(",")
        values["protection.packagedPaths"] =
            input.packagedPaths.sorted().joinToString(",")
        values["seed.policyVersion"] = SeedDerivation.SEED_POLICY_VERSION
        values["seed.fingerprint"] = input.seedFingerprint
        for (domain in SEED_DOMAINS) {
            values["seed.domain.$domain"] =
                SeedDerivation.derive(input.seedFingerprint, domain, identity)
        }
        values["resources.prefix"] = "kld_" +
            SeedDerivation.derive(input.seedFingerprint, "resource-prefix", identity)
                .substring(0, 8) + "_"
        return AdoptionPlan(values)
    }

    private fun validate(input: Input) {
        if (!PACKAGE_NAME.matches(input.packageBase)) {
            throw failure(
                input,
                "generation.packageBase",
                "Generation package base is not a legal dotted Java package",
                "Use legal Java identifiers separated by dots",
            )
        }
        range(input, "generation.packageCount", input.packageCount, 1, 64)
        range(input, "generation.classesPerPackage", input.classesPerPackage, 1, 64)
        range(input, "generation.methodsPerClass", input.methodsPerClass, 1, 128)
        range(input, "generation.layoutCount", input.layoutCount, 1, 256)
        range(input, "generation.drawableCount", input.drawableCount, 1, 512)
        range(input, "generation.stringCount", input.stringCount, 1, 4096)
        range(input, "generation.activityCount", input.activityCount, 0, 64)
        range(input, "generation.compose.fileCount", input.composeFileCount, 1, 64)
        range(
            input,
            "generation.compose.functionsPerFile",
            input.composeFunctionsPerFile,
            1,
            32,
        )
        if (input.composeFileCount.toLong() * input.composeFunctionsPerFile > 512) {
            throw failure(
                input,
                "generation.compose",
                "Compose function total exceeds 512",
                "Reduce fileCount or functionsPerFile",
            )
        }
        if (input.composeEnabled && !input.composeBuildFeatureEnabled) {
            throw failure(
                input,
                "generation.compose.buildFeatureEnabled",
                "Compose Generator requires buildFeatures.compose to be true",
                "Enable the Consumer Project Compose build feature",
            )
        }
        if (input.composeEnabled && !input.composeCompilerPluginApplied) {
            throw failure(
                input,
                "generation.compose.compilerPluginApplied",
                "Compose Generator requires org.jetbrains.kotlin.plugin.compose",
                "Apply the matching Compose compiler plugin to the Consumer Project",
            )
        }
        if (!input.seedFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            throw failure(
                input,
                "seed.fingerprint",
                "Seed fingerprint is not canonical SHA-256",
                "Use the Kaleido seed Provider without pre-hashing it",
            )
        }
        validateBoundedSet(input, "protection.originalClassNames", input.originalClassNames)
        validateBoundedSet(input, "protection.resourceNames", input.resourceNames)
        validateBoundedSet(input, "protection.packagedPaths", input.packagedPaths)
        if (input.nativeLibrariesToDelete.any { !NATIVE_LIBRARY.matches(it) }) {
            throw failure(
                input,
                "resources.nativeLibrariesToDelete",
                "Native deletion selectors must be exact lib*.so file names",
                "Use a bounded native library basename without a path",
            )
        }
        if (input.metadataToDelete.any { !permittedMetadataSelector(it) }) {
            throw failure(
                input,
                "resources.metadataToDelete",
                "Metadata deletion selector is outside the permitted META-INF contract",
                "Select an exact META-INF LICENSE, NOTICE, DEPENDENCIES, or INDEX.LIST file",
            )
        }
        if (input.retainedLanguages.any { !LANGUAGE_TAG.matches(it) }) {
            throw failure(
                input,
                "resources.retainedLanguages",
                "Retained language is not a canonical bounded language tag",
                "Use tags such as en, fr, en-US, or zh-Hans-CN",
            )
        }
        val fullOnlySelected = input.activityCount > 0 ||
            input.nativeLibrariesToDelete.isNotEmpty() ||
            input.metadataToDelete.isNotEmpty() ||
            input.replaceUnusedStrings ||
            input.retainedLanguages.isNotEmpty()
        if (input.profile == KaleidoProfile.SAFE && fullOnlySelected) {
            throw failure(
                input,
                "profile",
                "Safe Profile cannot select Full-only generation or resource controls",
                "Select FULL explicitly or remove every Full-only declaration",
            )
        }
    }

    private fun permittedMetadataSelector(selector: String): Boolean {
        if (!selector.matches(Regex("META-INF/[A-Za-z0-9_.-]+"))) return false
        val name = selector.substring("META-INF/".length).uppercase(Locale.ROOT)
        return name == "INDEX.LIST" || name == "DEPENDENCIES" ||
            name.startsWith("LICENSE") || name.startsWith("NOTICE")
    }

    private fun range(input: Input, target: String, value: Int, minimum: Int, maximum: Int) {
        if (value < minimum || value > maximum) {
            throw failure(
                input,
                target,
                "Configured value is outside $minimum..$maximum",
                "Choose a value inside the documented finite range",
            )
        }
    }

    private fun validateBoundedSet(input: Input, target: String, values: Set<String>) {
        if (values.size > 1024 || values.any { it.isBlank() }) {
            throw failure(
                input,
                target,
                "Declaration set is blank or exceeds the 1024-entry bound",
                "Use finite nonblank declarations",
            )
        }
    }

    private fun failure(input: Input, target: String, reason: String, repair: String): Nothing {
        throw KaleidoDiagnostic(
            "KLD-CONFIG-001",
            input.projectPath,
            input.variantName,
            "adoption-plan",
            "kaleido.dsl",
            target,
            reason,
            repair,
        ).failure()
    }

    @JvmRecord
    data class Input(
        val projectPath: String,
        val variantName: String,
        val buildType: String,
        val flavors: List<String>,
        val applicationId: String,
        val profile: KaleidoProfile,
        val packageBase: String,
        val packageCount: Int,
        val classesPerPackage: Int,
        val methodsPerClass: Int,
        val layoutCount: Int,
        val drawableCount: Int,
        val stringCount: Int,
        val activityCount: Int,
        val composeEnabled: Boolean,
        val composeBuildFeatureEnabled: Boolean,
        val composeCompilerPluginApplied: Boolean,
        val composeFileCount: Int,
        val composeFunctionsPerFile: Int,
        val nativeLibrariesToDelete: Set<String>,
        val metadataToDelete: Set<String>,
        val replaceUnusedStrings: Boolean,
        val retainedLanguages: Set<String>,
        val originalClassNames: Set<String>,
        val resourceNames: Set<String>,
        val packagedPaths: Set<String>,
        val seedFingerprint: String,
    )
}
