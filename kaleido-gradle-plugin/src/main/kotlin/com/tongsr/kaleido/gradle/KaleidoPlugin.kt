package com.tongsr.kaleido.gradle

import com.android.build.api.AndroidPluginVersion
import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.ScopedArtifacts
import com.tongsr.kaleido.gradle.dsl.KaleidoEscapeHatch
import com.tongsr.kaleido.gradle.dsl.KaleidoExtension
import com.tongsr.kaleido.gradle.dsl.KaleidoProfile
import com.tongsr.kaleido.gradle.dsl.KaleidoSigning
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider

class KaleidoPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (!project.pluginManager.hasPlugin("com.android.application")) {
            val incompatiblePlugin = INCOMPATIBLE_PLUGIN_IDS.firstOrNull(project.pluginManager::hasPlugin)
            if (incompatiblePlugin != null) {
                throw AdoptionValidator.wrongProjectType(project.path, incompatiblePlugin).failure()
            }
            throw GradleException(APPLICATION_REQUIRED.format(project.path, project.path))
        }

        val extension = project.extensions.create("kaleido", KaleidoExtension::class.java)
        configureSafeDefaults(extension)
        val androidComponents = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
        val validateAdoption = project.tasks.register(
            "validateKaleidoAdoption",
            KaleidoValidateAdoptionTask::class.java,
        ) { task ->
            task.group = "kaleido"
            task.description = "Validates Kaleido adoption before any hardening output"
            task.consumerProjectPath.set(project.path)
            task.eligibleVariants.convention(emptyList())
        }
        project.tasks.matching { task -> task.name == "bundle" }.configureEach { task ->
            task.dependsOn(validateAdoption)
        }

        androidComponents.finalizeDsl { android ->
            AdoptionValidator.validateDsl(
                AdoptionValidator.DslSnapshot(
                    project.path,
                    android.dynamicFeatures,
                    android.assetPacks,
                    android.buildTypes.names,
                ),
            )
            if (extension.generation.compose.enabled.getOrElse(false)) {
                if (android.buildFeatures.compose != true) {
                    throw composeConfigurationFailure(
                        project,
                        "buildFeatures.compose",
                        "Compose Generator requires buildFeatures.compose to be true",
                        "Enable the Consumer Project Compose build feature",
                    )
                }
                if (!project.pluginManager.hasPlugin("org.jetbrains.kotlin.plugin.compose")) {
                    throw composeConfigurationFailure(
                        project,
                        "compiler-plugin",
                        "Compose Generator requires org.jetbrains.kotlin.plugin.compose",
                        "Apply the matching Compose compiler plugin to the Consumer Project",
                    )
                }
            }
        }
        androidComponents.onVariants(
            androidComponents.selector().withBuildType("release"),
        ) { variant ->
            configureReleaseVariant(project, variant, validateAdoption, extension)
        }
    }

    internal companion object {
        private val INCOMPATIBLE_PLUGIN_IDS = listOf(
            "com.android.library",
            "com.android.dynamic-feature",
            "com.android.asset-pack",
            "com.android.asset-pack-bundle",
            "com.android.ai-pack",
            "com.android.test",
            "com.android.kotlin.multiplatform.library",
        )

        const val APPLICATION_REQUIRED: String =
            "KLD-ADOPTION-001 project=%s variant=<none> stage=adoption " +
                "origin=com.tongsr.kaleido target=%s " +
                "reason=Android application plugin is not available " +
                "repair=Apply com.tongsr.kaleido after com.android.application"

        private fun configureReleaseVariant(
            project: Project,
            variant: ApplicationVariant,
            validateAdoption: TaskProvider<KaleidoValidateAdoptionTask>,
            extension: KaleidoExtension,
        ) {
            val flavorIdentities = variant.productFlavors.map { flavor ->
                "${flavor.first}=${flavor.second}"
            }
            AdoptionValidator.validateVariant(
                AdoptionValidator.VariantSnapshot(
                    project.path,
                    variant.name,
                    variant.buildType!!,
                    flavorIdentities,
                    variant.isMinifyEnabled,
                ),
            )
            validateAdoption.configure { task ->
                task.eligibleVariants.add(variant.name)
            }

            val variantIdentity = variant.name + "|" + variant.buildType + "|" + flavorIdentities.joinToString(",")
            val projectPath = project.path
            val variantName = variant.name
            val defaultSeedFingerprint = variant.applicationId.map { applicationId ->
                SeedDerivation.defaultFingerprint(applicationId, variantIdentity)
            }
            val missingSeedFingerprint: Provider<String> = project.providers.provider {
                throw SeedDerivation.missingProviderValue(projectPath, variantName)
            }
            val seedFingerprint = if (extension.seed.isConfigured()) {
                extension.seed.getProvider().map { rawSeed ->
                    SeedDerivation.fingerprint(rawSeed, projectPath, variantName)
                }.orElse(missingSeedFingerprint)
            } else {
                defaultSeedFingerprint
            }
            val generation = extension.generation
            val resources = extension.resources
            val protection = extension.protection
            val classEscapeHatches = canonicalEscapeHatches(
                protection.classEscapeHatches,
                projectPath,
                variantName,
            )
            val resourceEscapeHatches = canonicalEscapeHatches(
                protection.resourceEscapeHatches,
                projectPath,
                variantName,
            )
            val resolvePlan = project.tasks.register(
                "resolveKaleido" + capitalize(variant.name) + "AdoptionPlan",
                ResolveKaleidoAdoptionPlanTask::class.java,
            ) { task ->
                task.group = "kaleido"
                task.description = "Resolves the immutable Kaleido Adoption Plan for " + variant.name
                task.consumerProjectPath.set(projectPath)
                task.variantName.set(variantName)
                task.buildType.set(variant.buildType)
                task.flavorIdentities.set(flavorIdentities)
                task.applicationId.set(variant.applicationId)
                task.profile.set(extension.profile)
                task.packageBase.set(
                    generation.packageBase.orElse(
                        variant.namespace.map { namespace -> namespace + ".kaleido.generated" },
                    ),
                )
                task.packageCount.set(generation.packageCount)
                task.classesPerPackage.set(generation.classesPerPackage)
                task.methodsPerClass.set(generation.methodsPerClass)
                task.layoutCount.set(generation.layoutCount)
                task.drawableCount.set(generation.drawableCount)
                task.stringCount.set(generation.stringCount)
                task.activityCount.set(generation.activityCount)
                task.composeEnabled.set(generation.compose.enabled)
                val buildFeatures = project.extensions.getByType(ApplicationExtension::class.java).buildFeatures
                task.composeBuildFeatureEnabled.set(buildFeatures.compose == true)
                task.composeCompilerPluginApplied.set(
                    project.pluginManager.hasPlugin("org.jetbrains.kotlin.plugin.compose"),
                )
                task.composeFileCount.set(generation.compose.fileCount)
                task.composeFunctionsPerFile.set(generation.compose.functionsPerFile)
                task.nativeLibrariesToDelete.set(resources.nativeLibrariesToDelete)
                task.metadataToDelete.set(resources.metadataToDelete)
                task.replaceUnusedStrings.set(resources.replaceUnusedStrings)
                task.confirmedUnusedStringsFile.set(resources.confirmedUnusedStringsFile)
                task.retainedLanguages.set(resources.retainedLanguages)
                task.originalClassNames.set(protection.originalClassNames)
                task.resourceNames.set(protection.resourceNames)
                task.packagedPaths.set(protection.packagedPaths)
                task.classEscapeHatches.set(classEscapeHatches)
                task.resourceEscapeHatches.set(resourceEscapeHatches)
                task.seedFingerprint.set(seedFingerprint)
                task.planFile.set(
                    project.layout.buildDirectory.file(
                        "intermediates/kaleido/" + variant.name + "/adoption-plan.properties",
                    ),
                )
            }

            val generateSafeContent = project.tasks.register(
                "generateKaleido" + capitalize(variant.name) + "SafeContent",
                GenerateSafeContentTask::class.java,
            ) { task ->
                task.group = "kaleido"
                task.description = "Generates deterministic Safe Profile content for " + variant.name
                task.dependsOn(resolvePlan)
                task.adoptionPlan.set(resolvePlan.flatMap(ResolveKaleidoAdoptionPlanTask::planFile))
                task.consumerResourceDirectories.from(variant.sources.res!!.static)
                task.consumerSourceDirectories.from(variant.sources.java!!.static)
                task.consumerSourceDirectories.from(variant.sources.kotlin!!.static)
                task.compileClasspath.from(
                    project.provider {
                        if (generation.compose.enabled.get()) {
                            variant.compileConfiguration
                        } else {
                            emptyList()
                        }
                    },
                )
                task.compileComponents.set(
                    project.provider {
                        if (!generation.compose.enabled.get()) {
                            emptyList()
                        } else {
                            variant.compileConfiguration.incoming.artifacts
                                .resolvedArtifacts.get()
                                .map { artifact -> artifact.id.componentIdentifier }
                                .filterIsInstance<ModuleComponentIdentifier>()
                                .map { identifier ->
                                    identifier.group + ":" + identifier.module + ":" + identifier.version
                                }
                                .distinct()
                                .sorted()
                        }
                    },
                )
                val generatedRoot = "generated/kaleido/" + variant.name
                task.kotlinOutputDirectory.set(
                    project.layout.buildDirectory.dir(generatedRoot + "/kotlin"),
                )
                task.resourceOutputDirectory.set(
                    project.layout.buildDirectory.dir(generatedRoot + "/res"),
                )
                task.manifestOutputFile.set(
                    project.layout.buildDirectory.file(generatedRoot + "/manifest/AndroidManifest.xml"),
                )
                task.keepRulesOutputDirectory.set(
                    project.layout.buildDirectory.dir(generatedRoot + "/rules"),
                )
                task.inventoryFile.set(
                    project.layout.buildDirectory.file(
                        "intermediates/kaleido/" + variant.name + "/generated-inventory.properties",
                    ),
                )
            }

            variant.sources.kotlin!!.addGeneratedSourceDirectory(
                generateSafeContent,
                GenerateSafeContentTask::kotlinOutputDirectory,
            )
            variant.sources.res!!.addGeneratedSourceDirectory(
                generateSafeContent,
                GenerateSafeContentTask::resourceOutputDirectory,
            )
            variant.sources.manifests.addGeneratedManifestFile(
                generateSafeContent,
                GenerateSafeContentTask::manifestOutputFile,
            )
            variant.sources.keepRules!!.addGeneratedSourceDirectory(
                generateSafeContent,
                GenerateSafeContentTask::keepRulesOutputDirectory,
            )

            val rewriteXml = project.tasks.register(
                "rewriteKaleido" + capitalize(variant.name) + "SemanticXml",
                RewriteSemanticXmlTask::class.java,
            ) { task ->
                task.group = "kaleido"
                task.description = "Rewrites registered semantic XML class references for " + variant.name
                task.consumerProjectPath!!.set(projectPath)
                task.variantName!!.set(variantName)
                task.adoptionPlan!!.set(resolvePlan.flatMap(ResolveKaleidoAdoptionPlanTask::planFile))
                task.consumerSourceDirectories!!.from(variant.sources.java!!.static)
                task.consumerSourceDirectories!!.from(variant.sources.kotlin!!.static)
                task.consumerResourceDirectories!!.from(variant.sources.res!!.static)
                task.outputResources!!.set(
                    project.layout.buildDirectory.dir(
                        "generated/kaleido/" + variant.name + "/semantic-xml",
                    ),
                )
                task.rewriteIntent!!.set(
                    project.layout.buildDirectory.file(
                        "intermediates/kaleido/" + variant.name +
                            "/class-rewrite/xml-rewrite-intent.properties",
                    ),
                )
            }
            variant.sources.res!!.addGeneratedSourceDirectory(
                rewriteXml,
                { it.outputResources!! },
            )

            val rewriteManifest = project.tasks.register(
                "rewriteKaleido" + capitalize(variant.name) + "ManifestReferences",
                RewriteManifestReferencesTask::class.java,
            ) { task ->
                task.group = "kaleido"
                task.description = "Plans and rewrites semantic Manifest class references for " + variant.name
                task.consumerProjectPath!!.set(projectPath)
                task.variantName!!.set(variantName)
                task.adoptionPlan!!.set(resolvePlan.flatMap(ResolveKaleidoAdoptionPlanTask::planFile))
                task.consumerSourceDirectories!!.from(variant.sources.java!!.static)
                task.consumerSourceDirectories!!.from(variant.sources.kotlin!!.static)
                task.rewriteIntent!!.set(
                    project.layout.buildDirectory.file(
                        "intermediates/kaleido/" + variant.name +
                            "/class-rewrite/manifest-rewrite-intent.properties",
                    ),
                )
            }
            variant.artifacts
                .use(rewriteManifest)
                .wiredWithFiles(
                    { it.inputManifest!! },
                    { it.outputManifest!! },
                )
                .toTransform(SingleArtifact.MERGED_MANIFEST)

            val rewriteClasses = project.tasks.register(
                "rewriteKaleido" + capitalize(variant.name) + "ClassesAndManifest",
                RewriteClassesAndManifestTask::class.java,
            ) { task ->
                task.group = "kaleido"
                task.description = "Plans and rewrites representative PROJECT classes and " +
                    "semantic Manifest references for " + variant.name
                task.consumerProjectPath!!.set(projectPath)
                task.variantName!!.set(variantName)
                task.adoptionPlan!!.set(resolvePlan.flatMap(ResolveKaleidoAdoptionPlanTask::planFile))
                task.inputManifest!!.set(rewriteManifest.flatMap { it.outputManifest!! })
                task.manifestRewriteIntent!!.set(
                    rewriteManifest.flatMap { it.rewriteIntent!! },
                )
                task.xmlRewriteIntent!!.set(rewriteXml.flatMap { it.rewriteIntent!! })
                val evidenceRoot = "intermediates/kaleido/" + variant.name + "/class-rewrite"
                task.rewritePlan!!.set(
                    project.layout.buildDirectory.file(evidenceRoot + "/class-rewrite-plan.pb"),
                )
                task.rawMapping!!.set(
                    project.layout.buildDirectory.file(evidenceRoot + "/raw-kaleido-mapping.txt"),
                )
                task.transformReceipt!!.set(
                    project.layout.buildDirectory.file(evidenceRoot + "/transform-receipt.pb"),
                )
                task.resourceProtectionEvidence!!.set(
                    project.layout.buildDirectory.file(evidenceRoot + "/resource-protection.properties"),
                )
                task.composeCompiledInventory!!.set(
                    project.layout.buildDirectory.file(evidenceRoot + "/compose-compiled-inventory.properties"),
                )
                task.protectionKeepRulesOutputDirectory!!.set(
                    project.layout.buildDirectory.dir(evidenceRoot + "/rules"),
                )
            }
            variant.sources.keepRules!!.addGeneratedSourceDirectory(
                rewriteClasses,
                { it.protectionKeepRulesOutputDirectory!! },
            )
            variant.artifacts
                .forScope(ScopedArtifacts.Scope.PROJECT)
                .use(rewriteClasses)
                .toTransform(
                    ScopedArtifact.CLASSES,
                    { it.inputJars!! as ListProperty<RegularFile> },
                    { it.inputDirectories!! as ListProperty<Directory> },
                    { it.outputClasses!! },
                )

            val r8Root = "intermediates/kaleido/" + variant.name + "/r8"
            val generateR8Configuration = project.tasks.register(
                "generateKaleido" + capitalize(variant.name) + "R8Configuration",
                GenerateR8ConfigurationTask::class.java,
            ) { task ->
                task.group = "kaleido"
                task.description = "Generates deterministic R8 dictionaries and rules for " + variant.name
                task.consumerProjectPath.set(projectPath)
                task.variantName.set(variantName)
                task.adoptionPlan.set(resolvePlan.flatMap(ResolveKaleidoAdoptionPlanTask::planFile))
                task.classRewritePlan.set(rewriteClasses.flatMap { it.rewritePlan!! })
                task.rulesOutputDirectory.set(
                    project.layout.buildDirectory.dir(r8Root + "/config/rules"),
                )
                task.dictionariesOutputDirectory.set(
                    project.layout.buildDirectory.dir(r8Root + "/config/dictionaries"),
                )
                task.configurationEvidence.set(
                    project.layout.buildDirectory.file(r8Root + "/configuration.properties"),
                )
            }
            variant.sources.keepRules!!.addGeneratedSourceDirectory(
                generateR8Configuration,
                GenerateR8ConfigurationTask::rulesOutputDirectory,
            )

            val composeMappings = project.tasks.register(
                "composeKaleido" + capitalize(variant.name) + "R8Mappings",
                ComposeR8MappingsTask::class.java,
            ) { task ->
                task.group = "kaleido"
                task.description = "Captures and composes Kaleido and R8 mappings for " + variant.name
                task.consumerProjectPath.set(projectPath)
                task.variantName.set(variantName)
                task.retraceToolCoordinates.set(
                    "com.android.tools.build:builder:" + AndroidPluginVersion.getCurrent().version,
                )
                task.rawKaleidoMapping.set(rewriteClasses.flatMap { it.rawMapping!! })
                task.inputRawR8Mapping.set(
                    variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE),
                )
                task.capturedRawR8Mapping.set(
                    project.layout.buildDirectory.file(r8Root + "/raw-r8-mapping.txt"),
                )
                task.composedMapping.set(
                    project.layout.buildDirectory.file(r8Root + "/composed-mapping.txt"),
                )
                task.compositionMetadata.set(
                    project.layout.buildDirectory.file(r8Root + "/mapping-metadata.properties"),
                )
            }

            val taskName = "finalizeKaleido" + capitalize(variant.name) + "Bundle"
            val finalizeTask = project.tasks.register(
                taskName,
                KaleidoBundleFinalizeTask::class.java,
            ) { task ->
                task.group = "kaleido"
                task.description = "Finalizes the " + variant.name + " Bundle through Kaleido"
                task.dependsOn(validateAdoption, resolvePlan, generateSafeContent)
                task.consumerProjectPath!!.set(project.path)
                task.variantName!!.set(variant.name)
                task.adoptionPlan!!.set(resolvePlan.flatMap(ResolveKaleidoAdoptionPlanTask::planFile))
                task.generationInventory!!.set(
                    generateSafeContent.flatMap(GenerateSafeContentTask::inventoryFile),
                )
                task.classRewriteEvidence!!.from(
                    rewriteClasses.flatMap { it.rewritePlan!! },
                    rewriteClasses.flatMap { it.rawMapping!! },
                    rewriteClasses.flatMap { it.transformReceipt!! },
                    rewriteClasses.flatMap { it.resourceProtectionEvidence!! },
                    rewriteClasses.flatMap { it.composeCompiledInventory!! },
                    rewriteClasses.flatMap { it.protectionKeepRulesOutputDirectory!! },
                )
                task.resourceProtectionEvidence!!.set(
                    rewriteClasses.flatMap { it.resourceProtectionEvidence!! },
                )
                task.r8Evidence!!.from(
                    generateR8Configuration.flatMap(GenerateR8ConfigurationTask::rulesOutputDirectory),
                    generateR8Configuration.flatMap(GenerateR8ConfigurationTask::dictionariesOutputDirectory),
                    generateR8Configuration.flatMap(GenerateR8ConfigurationTask::configurationEvidence),
                    composeMappings.flatMap(ComposeR8MappingsTask::capturedRawR8Mapping),
                    composeMappings.flatMap(ComposeR8MappingsTask::composedMapping),
                    composeMappings.flatMap(ComposeR8MappingsTask::compositionMetadata),
                )
                task.applicationResourceDirectories!!.from(
                    variant.sources.res!!.static,
                    generateSafeContent.flatMap(GenerateSafeContentTask::resourceOutputDirectory),
                    rewriteXml.flatMap { it.outputResources!! },
                )
                task.applicationNativeDirectories!!.from(variant.sources.jniLibs!!.static)
                task.applicationMetadataDirectories!!.from(variant.sources.resources!!.static)
                val bundleRewriteRoot = "intermediates/kaleido/" + variant.name + "/bundle-rewrite"
                task.bundleRewritePlan!!.set(
                    project.layout.buildDirectory.file(bundleRewriteRoot + "/bundle-rewrite-plan.pb"),
                )
                task.resourceMapping!!.set(
                    project.layout.buildDirectory.file(bundleRewriteRoot + "/resource-mapping.txt"),
                )
                task.bundleTransformReceipt!!.set(
                    project.layout.buildDirectory.file(bundleRewriteRoot + "/transform-receipt.pb"),
                )
                task.unsignedCandidateEvidence!!.set(
                    project.layout.buildDirectory.file(bundleRewriteRoot + "/unsigned-candidate.aab"),
                )
                task.unsignedCandidateDigest!!.set(
                    project.layout.buildDirectory.file(bundleRewriteRoot + "/unsigned-candidate.sha256"),
                )
                task.outputBundle!!.convention(
                    project.layout.buildDirectory.file(
                        "intermediates/kaleido/" + variant.name + "/final.aab",
                    ),
                )
            }

            variant.artifacts
                .use(finalizeTask)
                .wiredWithFiles(
                    { it.inputBundle!! },
                    { it.outputBundle!! },
                )
                .toTransform(SingleArtifact.BUNDLE)

            val verifyCompose = project.tasks.register(
                "verifyKaleido" + capitalize(variant.name) + "ComposeFinalDex",
                VerifyComposeFinalDexTask::class.java,
            ) { task ->
                task.group = "kaleido"
                task.description = "Verifies mapped generated Compose members in final " +
                    variant.name + " DEX"
                task.consumerProjectPath.set(projectPath)
                task.variantName.set(variantName)
                task.inputBundle.set(finalizeTask.flatMap { it.outputBundle!! })
                task.compiledInventory.set(
                    rewriteClasses.flatMap { it.composeCompiledInventory!! },
                )
                task.composedMapping.set(composeMappings.flatMap(ComposeR8MappingsTask::composedMapping))
                task.verificationReceipt.set(
                    project.layout.buildDirectory.file(
                        "intermediates/kaleido/" + variantName + "/compose/final-dex-receipt.properties",
                    ),
                )
            }

            val signTask = project.tasks.register(
                "signAndVerifyKaleido" + capitalize(variant.name) + "Bundle",
                KaleidoSignAndVerifyBundleTask::class.java,
            ) { task ->
                task.group = "kaleido"
                task.description = "Selects, signs, and verifies the exact canonical " +
                    variant.name + " Bundle candidate"
                task.dependsOn(verifyCompose)
                task.consumerProjectPath!!.set(projectPath)
                task.variantName!!.set(variantName)
                @Suppress("UNCHECKED_CAST")
                configureSigningSource(
                    extension.variantSigning[variantName],
                    task.exactKeyStore as Property<String>,
                    task.exactStorePassword as Property<String>,
                    task.exactKeyAlias as Property<String>,
                    task.exactKeyPassword as Property<String>,
                    task.exactCertificateSha256 as Property<String>,
                )
                @Suppress("UNCHECKED_CAST")
                configureSigningSource(
                    extension.signing,
                    task.topKeyStore as Property<String>,
                    task.topStorePassword as Property<String>,
                    task.topKeyAlias as Property<String>,
                    task.topKeyPassword as Property<String>,
                    task.topCertificateSha256 as Property<String>,
                )
                val providers = project.providers
                task.environmentKeyStore!!.set(providers.environmentVariable("KALEIDO_UPLOAD_KEYSTORE"))
                task.environmentStorePassword!!.set(
                    providers.environmentVariable("KALEIDO_UPLOAD_STORE_PASSWORD"),
                )
                task.environmentKeyAlias!!.set(providers.environmentVariable("KALEIDO_UPLOAD_KEY_ALIAS"))
                task.environmentKeyPassword!!.set(
                    providers.environmentVariable("KALEIDO_UPLOAD_KEY_PASSWORD"),
                )
                task.environmentCertificateSha256!!.set(
                    providers.environmentVariable("KALEIDO_UPLOAD_CERTIFICATE_SHA256"),
                )
                task.propertyKeyStore!!.set(providers.gradleProperty("kaleido.uploadSigning.keyStoreFile"))
                task.propertyStorePassword!!.set(
                    providers.gradleProperty("kaleido.uploadSigning.storePassword"),
                )
                task.propertyKeyAlias!!.set(providers.gradleProperty("kaleido.uploadSigning.keyAlias"))
                task.propertyKeyPassword!!.set(providers.gradleProperty("kaleido.uploadSigning.keyPassword"))
                task.propertyCertificateSha256!!.set(
                    providers.gradleProperty("kaleido.uploadSigning.expectedCertificateSha256"),
                )
                task.signingReceipt!!.set(
                    project.layout.buildDirectory.file(
                        "intermediates/kaleido/" + variantName + "/signing/signing-receipt.properties",
                    ),
                )
                task.inputBundle!!.set(finalizeTask.flatMap { it.outputBundle!! })
                task.expectedUnsignedDigest!!.set(
                    finalizeTask.flatMap { it.unsignedCandidateDigest!! },
                )
                task.outputBundle!!.set(
                    project.layout.buildDirectory.file(
                        "intermediates/kaleido/" + variantName + "/signing/staged-signed-candidate.aab",
                    ),
                )
            }

            val publishTask = project.tasks.register(
                "publishKaleido" + capitalize(variantName) + "ReleaseEvidence",
                PublishKaleidoReleaseTask::class.java,
            ) { task ->
                task.group = "kaleido"
                task.description = "Atomically publishes the final signed " + variantName +
                    " Bundle and complete Release Evidence Set"
                task.dependsOn(signTask)
                task.consumerProjectPath!!.set(projectPath)
                task.variantName!!.set(variantName)
                task.pluginVersion!!.set(project.version.toString())
                task.consumerProjectDirectory!!.set(project.layout.projectDirectory)
                task.stagedSignedBundle!!.set(
                    signTask.flatMap { it.outputBundle!! },
                )
                task.signingReceipt!!.set(
                    signTask.flatMap { it.signingReceipt!! },
                )
                task.deterministicEvidence!!.from(
                    resolvePlan.flatMap(ResolveKaleidoAdoptionPlanTask::planFile),
                    generateSafeContent.flatMap(GenerateSafeContentTask::kotlinOutputDirectory),
                    generateSafeContent.flatMap(GenerateSafeContentTask::resourceOutputDirectory),
                    generateSafeContent.flatMap(GenerateSafeContentTask::keepRulesOutputDirectory),
                    generateSafeContent.flatMap(GenerateSafeContentTask::manifestOutputFile),
                    generateSafeContent.flatMap(GenerateSafeContentTask::inventoryFile),
                    rewriteManifest.flatMap { it.rewriteIntent!! },
                    rewriteXml.flatMap { it.rewriteIntent!! },
                    rewriteClasses.flatMap { it.rewritePlan!! },
                    rewriteClasses.flatMap { it.rawMapping!! },
                    rewriteClasses.flatMap { it.transformReceipt!! },
                    rewriteClasses.flatMap { it.resourceProtectionEvidence!! },
                    rewriteClasses.flatMap { it.composeCompiledInventory!! },
                    rewriteClasses.flatMap { it.protectionKeepRulesOutputDirectory!! },
                    generateR8Configuration.flatMap(GenerateR8ConfigurationTask::rulesOutputDirectory),
                    generateR8Configuration.flatMap(GenerateR8ConfigurationTask::dictionariesOutputDirectory),
                    generateR8Configuration.flatMap(GenerateR8ConfigurationTask::configurationEvidence),
                    composeMappings.flatMap(ComposeR8MappingsTask::capturedRawR8Mapping),
                    composeMappings.flatMap(ComposeR8MappingsTask::composedMapping),
                    composeMappings.flatMap(ComposeR8MappingsTask::compositionMetadata),
                    finalizeTask.flatMap { it.bundleRewritePlan!! },
                    finalizeTask.flatMap { it.resourceMapping!! },
                    finalizeTask.flatMap { it.bundleTransformReceipt!! },
                    finalizeTask.flatMap { it.unsignedCandidateEvidence!! },
                    finalizeTask.flatMap { it.unsignedCandidateDigest!! },
                    verifyCompose.flatMap(VerifyComposeFinalDexTask::verificationReceipt),
                )
                task.publishedEvidenceDirectory!!.set(
                    project.layout.buildDirectory.dir(
                        "reports/kaleido/" + variantName + "/release-evidence-set",
                    ),
                )
            }
            variant.artifacts
                .use(publishTask)
                .wiredWithFiles(
                    { it.inputBundle!! },
                    { it.outputBundle!! },
                )
                .toTransform(SingleArtifact.BUNDLE)
            project.tasks.matching { task ->
                task.name == "bundle" + capitalize(variantName)
            }.configureEach { task ->
                task.dependsOn(publishTask)
            }

            val flavors = flavorIdentities.joinToString(",", "[", "]")
            project.logger.lifecycle(
                "KLD-ADOPTION-002 project={} variant={} stage=adoption origin=AGP-variant-api " +
                    "target={} reason=Eligible release variant discovered " +
                    "repair=<none> buildType={} flavors={} applicationId={}",
                project.path,
                variant.name,
                variant.name,
                variant.buildType,
                flavors,
                variant.applicationId.get(),
            )
        }

        private fun capitalize(value: String): String {
            if (value.isEmpty()) {
                return value
            }
            return Character.toUpperCase(value[0]) + value.substring(1)
        }

        private fun configureSigningSource(
            source: KaleidoSigning?,
            keyStore: Property<String>,
            storePassword: Property<String>,
            alias: Property<String>,
            keyPassword: Property<String>,
            certificate: Property<String>,
        ) {
            if (source == null) return
            keyStore.set(source.keyStoreFile.map { file -> file.asFile.path })
            storePassword.set(source.storePassword)
            alias.set(source.keyAlias)
            keyPassword.set(source.keyPassword)
            certificate.set(source.expectedCertificateSha256)
        }

        private fun canonicalEscapeHatches(
            hatches: Iterable<KaleidoEscapeHatch>,
            project: String,
            variant: String,
        ): List<String> {
            val values = ArrayList<String>()
            try {
                hatches.forEach { hatch -> values.add(hatch.canonicalDeclaration()) }
            } catch (_: RuntimeException) {
                throw KaleidoDiagnostic(
                    "KLD-PROTECTION-001",
                    project,
                    variant,
                    "protection",
                    "kaleido.protection",
                    "declaration",
                    "Escape Hatch is missing selector, dimensions, or reason",
                    "Complete every typed Escape Hatch declaration",
                ).failure()
            }
            return values.sorted()
        }

        private fun configureSafeDefaults(extension: KaleidoExtension) {
            extension.profile.convention(KaleidoProfile.SAFE)
            val generation = extension.generation
            generation.packageCount.convention(4)
            generation.classesPerPackage.convention(4)
            generation.methodsPerClass.convention(4)
            generation.layoutCount.convention(8)
            generation.drawableCount.convention(16)
            generation.stringCount.convention(32)
            generation.activityCount.convention(0)
            generation.compose.enabled.convention(false)
            generation.compose.fileCount.convention(4)
            generation.compose.functionsPerFile.convention(4)

            val resources = extension.resources
            resources.nativeLibrariesToDelete.convention(emptySet())
            resources.metadataToDelete.convention(emptySet())
            resources.replaceUnusedStrings.convention(false)
            resources.retainedLanguages.convention(emptySet())

            val protection = extension.protection
            protection.originalClassNames.convention(emptySet())
            protection.resourceNames.convention(emptySet())
            protection.packagedPaths.convention(emptySet())
        }

        private fun composeConfigurationFailure(
            project: Project,
            target: String,
            reason: String,
            repair: String,
        ): GradleException {
            return KaleidoDiagnostic(
                "KLD-CONFIG-001",
                project.path,
                "<release>",
                "adoption-plan",
                "kaleido.generation.compose",
                target,
                reason,
                repair,
            ).failure()
        }
    }
}
