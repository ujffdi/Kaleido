package com.tongsr.kaleido.gradle

import com.tongsr.kaleido.gradle.dsl.KaleidoProfile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.TreeMap
import java.util.TreeSet
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class ResolveKaleidoAdoptionPlanTask : DefaultTask() {
    @get:Input
    val kaleidoCacheSchema: String
        get() = "ResolveAdoptionPlanCache.v1"

    @get:Input
    abstract val consumerProjectPath: Property<String>

    @get:Input
    abstract val variantName: Property<String>

    @get:Input
    abstract val buildType: Property<String>

    @get:Input
    abstract val flavorIdentities: ListProperty<String>

    @get:Input
    abstract val applicationId: Property<String>

    @get:Input
    abstract val profile: Property<KaleidoProfile>

    @get:Input
    abstract val packageBase: Property<String>

    @get:Input
    abstract val packageCount: Property<Int>

    @get:Input
    abstract val classesPerPackage: Property<Int>

    @get:Input
    abstract val methodsPerClass: Property<Int>

    @get:Input
    abstract val layoutCount: Property<Int>

    @get:Input
    abstract val drawableCount: Property<Int>

    @get:Input
    abstract val stringCount: Property<Int>

    @get:Input
    abstract val activityCount: Property<Int>

    @get:Input
    abstract val composeEnabled: Property<Boolean>

    @get:Input
    abstract val composeBuildFeatureEnabled: Property<Boolean>

    @get:Input
    abstract val composeCompilerPluginApplied: Property<Boolean>

    @get:Input
    abstract val composeFileCount: Property<Int>

    @get:Input
    abstract val composeFunctionsPerFile: Property<Int>

    @get:Input
    abstract val nativeLibrariesToDelete: SetProperty<String>

    @get:Input
    abstract val metadataToDelete: SetProperty<String>

    @get:Input
    abstract val replaceUnusedStrings: Property<Boolean>

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val confirmedUnusedStringsFile: RegularFileProperty

    @get:Input
    abstract val retainedLanguages: SetProperty<String>

    @get:Input
    abstract val originalClassNames: SetProperty<String>

    @get:Input
    abstract val resourceNames: SetProperty<String>

    @get:Input
    abstract val packagedPaths: SetProperty<String>

    @get:Input
    abstract val classEscapeHatches: ListProperty<String>

    @get:Input
    abstract val resourceEscapeHatches: ListProperty<String>

    @get:Input
    abstract val seedFingerprint: Property<String>

    @get:OutputFile
    abstract val planFile: RegularFileProperty

    @TaskAction
    fun resolvePlan() {
        val project = consumerProjectPath.get()
        val variant = variantName.get()
        val confirmedUnusedFilePresent = confirmedUnusedStringsFile.isPresent
        val replaceUnusedStrings =
            this.replaceUnusedStrings.get() || confirmedUnusedFilePresent
        var plan = AdoptionPlanFactory.create(
            AdoptionPlanFactory.Input(
                project,
                variant,
                buildType.get(),
                flavorIdentities.get(),
                applicationId.get(),
                profile.get(),
                packageBase.get(),
                packageCount.get(),
                classesPerPackage.get(),
                methodsPerClass.get(),
                layoutCount.get(),
                drawableCount.get(),
                stringCount.get(),
                activityCount.get(),
                composeEnabled.get(),
                composeBuildFeatureEnabled.get(),
                composeCompilerPluginApplied.get(),
                composeFileCount.get(),
                composeFunctionsPerFile.get(),
                nativeLibrariesToDelete.get(),
                metadataToDelete.get(),
                replaceUnusedStrings,
                retainedLanguages.get(),
                originalClassNames.get(),
                resourceNames.get(),
                packagedPaths.get(),
                seedFingerprint.get(),
            ),
        )
        val classHatches = classEscapeHatches.get().map { value ->
            EscapeHatchDeclaration.parse(
                value,
                EscapeHatchDeclaration.Kind.CLASS,
                project,
                variant,
            )
        }
        val resourceHatches = resourceEscapeHatches.get().map { value ->
            EscapeHatchDeclaration.parse(
                value,
                EscapeHatchDeclaration.Kind.RESOURCE,
                project,
                variant,
            )
        }
        val ids = mutableSetOf<String>()
        for (declaration in classHatches + resourceHatches) {
            if (!ids.add(declaration.id)) {
                throw KaleidoDiagnostic(
                    "KLD-PROTECTION-001",
                    project,
                    variant,
                    "protection",
                    "kaleido.protection",
                    declaration.id,
                    "Escape Hatch ID is duplicated across typed blocks",
                    "Use one globally unique stable ID per variant",
                ).failure()
            }
        }
        val values = TreeMap(plan.values)
        if (replaceUnusedStrings) {
            if (!confirmedUnusedFilePresent) {
                throw KaleidoDiagnostic(
                    "KLD-CONFIG-001",
                    project,
                    variant,
                    "adoption-plan",
                    "kaleido.resources",
                    "confirmedUnusedStringsFile",
                    "Unused-string replacement requires an explicit confirmed-unused file",
                    "Set confirmedUnusedStringsFile or disable replaceUnusedStrings",
                ).failure()
            }
            val unusedFile = confirmedUnusedStringsFile.get().asFile.toPath()
            val unusedNames = TreeSet<String>()
            for (rawLine in Files.readAllLines(unusedFile, StandardCharsets.UTF_8)) {
                var line = rawLine.trim()
                if (line.isBlank() || line.startsWith("#")) continue
                if (line.startsWith("string/")) line = line.substring("string/".length)
                if (!line.matches(CONFIRMED_UNUSED_STRING) || !unusedNames.add(line)) {
                    throw KaleidoDiagnostic(
                        "KLD-CONFIG-001",
                        project,
                        variant,
                        "adoption-plan",
                        "kaleido.resources",
                        rawLine,
                        "Confirmed-unused string entry is malformed or duplicated",
                        "Use one exact string resource name per line",
                    ).failure()
                }
            }
            if (unusedNames.isEmpty()) {
                throw KaleidoDiagnostic(
                    "KLD-CONFIG-001",
                    project,
                    variant,
                    "adoption-plan",
                    "kaleido.resources",
                    "confirmedUnusedStringsFile",
                    "Confirmed-unused string file resolves to zero exact targets",
                    "List at least one exact string resource name",
                ).failure()
            }
            values["resources.confirmedUnusedStrings"] = unusedNames.joinToString(",")
            values["resources.confirmedUnusedStringsSha256"] =
                RewriteClassesAndManifestTask.sha256(Files.readAllBytes(unusedFile))
        } else {
            values["resources.confirmedUnusedStrings"] = ""
            values["resources.confirmedUnusedStringsSha256"] = ""
        }
        values["protection.classEscapeHatches"] =
            classEscapeHatches.get().sorted().joinToString(",")
        values["protection.resourceEscapeHatches"] =
            resourceEscapeHatches.get().sorted().joinToString(",")
        plan = AdoptionPlan(values)
        val output = planFile.get().asFile.toPath()
        Files.createDirectories(output.parent)
        val temporary = output.resolveSibling("${output.fileName}.tmp")
        Files.writeString(temporary, plan.canonicalText(), StandardCharsets.UTF_8)
        Files.move(
            temporary,
            output,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    companion object {
        private val CONFIRMED_UNUSED_STRING = Regex("[a-z][a-z0-9_]*")
    }
}
