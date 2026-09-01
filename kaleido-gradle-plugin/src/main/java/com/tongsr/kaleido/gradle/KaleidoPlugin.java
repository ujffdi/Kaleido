package com.tongsr.kaleido.gradle;

import com.android.build.api.artifact.SingleArtifact;
import com.android.build.api.artifact.ScopedArtifact;
import com.android.build.api.dsl.ApplicationExtension;
import com.android.build.api.variant.ApplicationAndroidComponentsExtension;
import com.android.build.api.variant.ApplicationVariant;
import com.android.build.api.variant.ScopedArtifacts;
import com.tongsr.kaleido.gradle.dsl.KaleidoExtension;
import com.tongsr.kaleido.gradle.dsl.KaleidoEscapeHatch;
import com.tongsr.kaleido.gradle.dsl.KaleidoProfile;
import com.tongsr.kaleido.gradle.dsl.KaleidoSigning;
import java.util.List;
import java.util.stream.Collectors;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

public final class KaleidoPlugin implements Plugin<Project> {
    private static final List<String> INCOMPATIBLE_PLUGIN_IDS = List.of(
            "com.android.library",
            "com.android.dynamic-feature",
            "com.android.asset-pack",
            "com.android.asset-pack-bundle",
            "com.android.ai-pack",
            "com.android.test",
            "com.android.kotlin.multiplatform.library");

    static final String APPLICATION_REQUIRED =
            "KLD-ADOPTION-001 project=%s variant=<none> stage=adoption "
                    + "origin=com.tongsr.kaleido target=%s "
                    + "reason=Android application plugin is not available "
                    + "repair=Apply com.tongsr.kaleido after com.android.application";

    @Override
    public void apply(Project project) {
        if (!project.getPluginManager().hasPlugin("com.android.application")) {
            var incompatiblePlugin = INCOMPATIBLE_PLUGIN_IDS.stream()
                    .filter(project.getPluginManager()::hasPlugin)
                    .findFirst();
            if (incompatiblePlugin.isPresent()) {
                throw AdoptionValidator.wrongProjectType(
                        project.getPath(), incompatiblePlugin.get()).failure();
            }
            throw new GradleException(APPLICATION_REQUIRED.formatted(
                    project.getPath(), project.getPath()));
        }

        var extension = project.getExtensions().create(
                "kaleido", KaleidoExtension.class);
        configureSafeDefaults(extension);
        var androidComponents = project.getExtensions()
                .getByType(ApplicationAndroidComponentsExtension.class);
        var validateAdoption = project.getTasks().register(
                "validateKaleidoAdoption",
                KaleidoValidateAdoptionTask.class,
                task -> {
                    task.setGroup("kaleido");
                    task.setDescription("Validates Kaleido adoption before any hardening output");
                    task.getConsumerProjectPath().set(project.getPath());
                    task.getEligibleVariants().convention(List.of());
                });
        project.getTasks().matching(task -> task.getName().equals("bundle")).configureEach(
                task -> task.dependsOn(validateAdoption));

        androidComponents.finalizeDsl((Action<ApplicationExtension>) android -> {
            AdoptionValidator.validateDsl(new AdoptionValidator.DslSnapshot(
                    project.getPath(),
                    android.getDynamicFeatures(),
                    android.getAssetPacks(),
                    android.getBuildTypes().getNames()));
            if (extension.getGeneration().getCompose().getEnabled().getOrElse(false)) {
                if (!Boolean.TRUE.equals(android.getBuildFeatures().getCompose())) {
                    throw composeConfigurationFailure(project, "buildFeatures.compose",
                            "Compose Generator requires buildFeatures.compose to be true",
                            "Enable the Consumer Project Compose build feature");
                }
                if (!project.getPluginManager().hasPlugin(
                        "org.jetbrains.kotlin.plugin.compose")) {
                    throw composeConfigurationFailure(project, "compiler-plugin",
                            "Compose Generator requires org.jetbrains.kotlin.plugin.compose",
                            "Apply the matching Compose compiler plugin to the Consumer Project");
                }
            }
        });
        androidComponents.onVariants(
                androidComponents.selector().withBuildType("release"),
                (Action<ApplicationVariant>) variant ->
                        configureReleaseVariant(project, variant, validateAdoption, extension));
    }

    private static void configureReleaseVariant(
            Project project,
            ApplicationVariant variant,
            org.gradle.api.tasks.TaskProvider<KaleidoValidateAdoptionTask> validateAdoption,
            KaleidoExtension extension) {
        var flavorIdentities = variant.getProductFlavors().stream()
                .map(flavor -> flavor.getFirst() + "=" + flavor.getSecond())
                .toList();
        AdoptionValidator.validateVariant(new AdoptionValidator.VariantSnapshot(
                project.getPath(),
                variant.getName(),
                variant.getBuildType(),
                flavorIdentities,
                variant.isMinifyEnabled()));
        validateAdoption.configure(task -> task.getEligibleVariants().add(variant.getName()));

        var variantIdentity = variant.getName()
                + "|" + variant.getBuildType()
                + "|" + String.join(",", flavorIdentities);
        var projectPath = project.getPath();
        var variantName = variant.getName();
        var defaultSeedFingerprint = variant.getApplicationId().map(applicationId ->
                SeedDerivation.defaultFingerprint(applicationId, variantIdentity));
        org.gradle.api.provider.Provider<String> missingSeedFingerprint =
                project.getProviders().provider(() -> {
            throw SeedDerivation.missingProviderValue(projectPath, variantName);
        });
        var seedFingerprint = extension.getSeed().isConfigured()
                ? extension.getSeed().getProvider().map(rawSeed ->
                        SeedDerivation.fingerprint(rawSeed, projectPath, variantName))
                        .orElse(missingSeedFingerprint)
                : defaultSeedFingerprint;
        var generation = extension.getGeneration();
        var resources = extension.getResources();
        var protection = extension.getProtection();
        var classEscapeHatches = canonicalEscapeHatches(
                protection.getClassEscapeHatches(), projectPath, variantName);
        var resourceEscapeHatches = canonicalEscapeHatches(
                protection.getResourceEscapeHatches(), projectPath, variantName);
        var resolvePlan = project.getTasks().register(
                "resolveKaleido" + capitalize(variant.getName()) + "AdoptionPlan",
                ResolveKaleidoAdoptionPlanTask.class,
                task -> {
                    task.setGroup("kaleido");
                    task.setDescription("Resolves the immutable Kaleido Adoption Plan for "
                            + variant.getName());
                    task.getConsumerProjectPath().set(projectPath);
                    task.getVariantName().set(variantName);
                    task.getBuildType().set(variant.getBuildType());
                    task.getFlavorIdentities().set(flavorIdentities);
                    task.getApplicationId().set(variant.getApplicationId());
                    task.getProfile().set(extension.getProfile());
                    task.getPackageBase().set(generation.getPackageBase().orElse(
                            variant.getNamespace().map(namespace -> namespace + ".kaleido.generated")));
                    task.getPackageCount().set(generation.getPackageCount());
                    task.getClassesPerPackage().set(generation.getClassesPerPackage());
                    task.getMethodsPerClass().set(generation.getMethodsPerClass());
                    task.getLayoutCount().set(generation.getLayoutCount());
                    task.getDrawableCount().set(generation.getDrawableCount());
                    task.getStringCount().set(generation.getStringCount());
                    task.getActivityCount().set(generation.getActivityCount());
                    task.getComposeEnabled().set(generation.getCompose().getEnabled());
                    var buildFeatures = project.getExtensions()
                            .getByType(ApplicationExtension.class).getBuildFeatures();
                    task.getComposeBuildFeatureEnabled().set(
                            Boolean.TRUE.equals(buildFeatures.getCompose()));
                    task.getComposeCompilerPluginApplied().set(
                            project.getPluginManager().hasPlugin(
                                    "org.jetbrains.kotlin.plugin.compose"));
                    task.getComposeFileCount().set(generation.getCompose().getFileCount());
                    task.getComposeFunctionsPerFile().set(
                            generation.getCompose().getFunctionsPerFile());
                    task.getNativeLibrariesToDelete().set(
                            resources.getNativeLibrariesToDelete());
                    task.getMetadataToDelete().set(resources.getMetadataToDelete());
                    task.getReplaceUnusedStrings().set(resources.getReplaceUnusedStrings());
                    task.getConfirmedUnusedStringsFile().set(
                            resources.getConfirmedUnusedStringsFile());
                    task.getRetainedLanguages().set(resources.getRetainedLanguages());
                    task.getOriginalClassNames().set(protection.getOriginalClassNames());
                    task.getResourceNames().set(protection.getResourceNames());
                    task.getPackagedPaths().set(protection.getPackagedPaths());
                    task.getClassEscapeHatches().set(classEscapeHatches);
                    task.getResourceEscapeHatches().set(resourceEscapeHatches);
                    task.getSeedFingerprint().set(seedFingerprint);
                    task.getPlanFile().set(project.getLayout().getBuildDirectory().file(
                            "intermediates/kaleido/" + variant.getName()
                                    + "/adoption-plan.properties"));
                });

        var generateSafeContent = project.getTasks().register(
                "generateKaleido" + capitalize(variant.getName()) + "SafeContent",
                GenerateSafeContentTask.class,
                task -> {
                    task.setGroup("kaleido");
                    task.setDescription("Generates deterministic Safe Profile content for "
                            + variant.getName());
                    task.dependsOn(resolvePlan);
                    task.getAdoptionPlan().set(resolvePlan.flatMap(
                            ResolveKaleidoAdoptionPlanTask::getPlanFile));
                    task.getConsumerResourceDirectories().from(
                            variant.getSources().getRes().getStatic());
                    task.getConsumerSourceDirectories().from(
                            variant.getSources().getJava().getStatic());
                    task.getConsumerSourceDirectories().from(
                            variant.getSources().getKotlin().getStatic());
                    task.getCompileClasspath().from(project.provider(() ->
                            generation.getCompose().getEnabled().get()
                                    ? variant.getCompileConfiguration()
                                    : java.util.List.of()));
                    task.getCompileComponents().set(project.provider(() -> {
                        if (!generation.getCompose().getEnabled().get()) {
                            return java.util.List.of();
                        }
                        return variant.getCompileConfiguration().getIncoming().getArtifacts()
                                .getResolvedArtifacts().get().stream()
                                .map(artifact -> artifact.getId().getComponentIdentifier())
                                .filter(identifier -> identifier instanceof
                                        org.gradle.api.artifacts.component
                                                .ModuleComponentIdentifier)
                                .map(identifier -> (org.gradle.api.artifacts.component
                                        .ModuleComponentIdentifier) identifier)
                                .map(identifier -> identifier.getGroup() + ":"
                                        + identifier.getModule() + ":" + identifier.getVersion())
                                .distinct().sorted().toList();
                    }));
                    var generatedRoot = "generated/kaleido/" + variant.getName();
                    task.getJavaOutputDirectory().set(project.getLayout().getBuildDirectory()
                            .dir(generatedRoot + "/java"));
                    task.getKotlinOutputDirectory().set(project.getLayout().getBuildDirectory()
                            .dir(generatedRoot + "/kotlin"));
                    task.getResourceOutputDirectory().set(project.getLayout().getBuildDirectory()
                            .dir(generatedRoot + "/res"));
                    task.getManifestOutputFile().set(project.getLayout().getBuildDirectory()
                            .file(generatedRoot + "/manifest/AndroidManifest.xml"));
                    task.getKeepRulesOutputDirectory().set(project.getLayout().getBuildDirectory()
                            .dir(generatedRoot + "/rules"));
                    task.getInventoryFile().set(project.getLayout().getBuildDirectory()
                            .file("intermediates/kaleido/" + variant.getName()
                                    + "/generated-inventory.properties"));
                });

        variant.getSources().getJava().addGeneratedSourceDirectory(
                generateSafeContent, GenerateSafeContentTask::getJavaOutputDirectory);
        variant.getSources().getKotlin().addGeneratedSourceDirectory(
                generateSafeContent, GenerateSafeContentTask::getKotlinOutputDirectory);
        variant.getSources().getRes().addGeneratedSourceDirectory(
                generateSafeContent, GenerateSafeContentTask::getResourceOutputDirectory);
        variant.getSources().getManifests().addGeneratedManifestFile(
                generateSafeContent, GenerateSafeContentTask::getManifestOutputFile);
        variant.getSources().getKeepRules().addGeneratedSourceDirectory(
                generateSafeContent, GenerateSafeContentTask::getKeepRulesOutputDirectory);

        var rewriteXml = project.getTasks().register(
                "rewriteKaleido" + capitalize(variant.getName()) + "SemanticXml",
                RewriteSemanticXmlTask.class,
                task -> {
                    task.setGroup("kaleido");
                    task.setDescription("Rewrites registered semantic XML class references for "
                            + variant.getName());
                    task.getConsumerProjectPath().set(projectPath);
                    task.getVariantName().set(variantName);
                    task.getAdoptionPlan().set(resolvePlan.flatMap(
                            ResolveKaleidoAdoptionPlanTask::getPlanFile));
                    task.getConsumerSourceDirectories().from(
                            variant.getSources().getJava().getStatic());
                    task.getConsumerSourceDirectories().from(
                            variant.getSources().getKotlin().getStatic());
                    task.getConsumerResourceDirectories().from(
                            variant.getSources().getRes().getStatic());
                    task.getOutputResources().set(project.getLayout().getBuildDirectory()
                            .dir("generated/kaleido/" + variant.getName() + "/semantic-xml"));
                    task.getRewriteIntent().set(project.getLayout().getBuildDirectory()
                            .file("intermediates/kaleido/" + variant.getName()
                                    + "/class-rewrite/xml-rewrite-intent.properties"));
                });
        variant.getSources().getRes().addGeneratedSourceDirectory(
                rewriteXml, RewriteSemanticXmlTask::getOutputResources);

        var rewriteManifest = project.getTasks().register(
                "rewriteKaleido" + capitalize(variant.getName()) + "ManifestReferences",
                RewriteManifestReferencesTask.class,
                task -> {
                    task.setGroup("kaleido");
                    task.setDescription("Plans and rewrites semantic Manifest class references for "
                            + variant.getName());
                    task.getConsumerProjectPath().set(projectPath);
                    task.getVariantName().set(variantName);
                    task.getAdoptionPlan().set(resolvePlan.flatMap(
                            ResolveKaleidoAdoptionPlanTask::getPlanFile));
                    task.getConsumerSourceDirectories().from(
                            variant.getSources().getJava().getStatic());
                    task.getConsumerSourceDirectories().from(
                            variant.getSources().getKotlin().getStatic());
                    task.getRewriteIntent().set(project.getLayout().getBuildDirectory()
                            .file("intermediates/kaleido/" + variant.getName()
                                    + "/class-rewrite/manifest-rewrite-intent.properties"));
                });
        variant.getArtifacts()
                .use(rewriteManifest)
                .wiredWithFiles(
                        RewriteManifestReferencesTask::getInputManifest,
                        RewriteManifestReferencesTask::getOutputManifest)
                .toTransform(SingleArtifact.MERGED_MANIFEST.INSTANCE);

        var rewriteClasses = project.getTasks().register(
                "rewriteKaleido" + capitalize(variant.getName()) + "ClassesAndManifest",
                RewriteClassesAndManifestTask.class,
                task -> {
                    task.setGroup("kaleido");
                    task.setDescription("Plans and rewrites representative PROJECT classes and "
                            + "semantic Manifest references for " + variant.getName());
                    task.getConsumerProjectPath().set(projectPath);
                    task.getVariantName().set(variantName);
                    task.getAdoptionPlan().set(resolvePlan.flatMap(
                            ResolveKaleidoAdoptionPlanTask::getPlanFile));
                    task.getInputManifest().set(rewriteManifest.flatMap(
                            RewriteManifestReferencesTask::getOutputManifest));
                    task.getManifestRewriteIntent().set(rewriteManifest.flatMap(
                            RewriteManifestReferencesTask::getRewriteIntent));
                    task.getXmlRewriteIntent().set(rewriteXml.flatMap(
                            RewriteSemanticXmlTask::getRewriteIntent));
                    var evidenceRoot = "intermediates/kaleido/" + variant.getName()
                            + "/class-rewrite";
                    task.getRewritePlan().set(project.getLayout().getBuildDirectory()
                            .file(evidenceRoot + "/class-rewrite-plan.pb"));
                    task.getRawMapping().set(project.getLayout().getBuildDirectory()
                            .file(evidenceRoot + "/raw-kaleido-mapping.txt"));
                    task.getTransformReceipt().set(project.getLayout().getBuildDirectory()
                            .file(evidenceRoot + "/transform-receipt.pb"));
                    task.getResourceProtectionEvidence().set(
                            project.getLayout().getBuildDirectory()
                                    .file(evidenceRoot + "/resource-protection.properties"));
                    task.getComposeCompiledInventory().set(
                            project.getLayout().getBuildDirectory()
                                    .file(evidenceRoot + "/compose-compiled-inventory.properties"));
                    task.getProtectionKeepRulesOutputDirectory().set(
                            project.getLayout().getBuildDirectory().dir(evidenceRoot + "/rules"));
                });
        variant.getSources().getKeepRules().addGeneratedSourceDirectory(
                rewriteClasses,
                RewriteClassesAndManifestTask::getProtectionKeepRulesOutputDirectory);
        variant.getArtifacts()
                .forScope(ScopedArtifacts.Scope.PROJECT)
                .use(rewriteClasses)
                .toTransform(
                        ScopedArtifact.CLASSES.INSTANCE,
                        RewriteClassesAndManifestTask::getInputJars,
                        RewriteClassesAndManifestTask::getInputDirectories,
                        RewriteClassesAndManifestTask::getOutputClasses);

        var r8Root = "intermediates/kaleido/" + variant.getName() + "/r8";
        var generateR8Configuration = project.getTasks().register(
                "generateKaleido" + capitalize(variant.getName()) + "R8Configuration",
                GenerateR8ConfigurationTask.class,
                task -> {
                    task.setGroup("kaleido");
                    task.setDescription("Generates deterministic R8 dictionaries and rules for "
                            + variant.getName());
                    task.getConsumerProjectPath().set(projectPath);
                    task.getVariantName().set(variantName);
                    task.getAdoptionPlan().set(resolvePlan.flatMap(
                            ResolveKaleidoAdoptionPlanTask::getPlanFile));
                    task.getClassRewritePlan().set(rewriteClasses.flatMap(
                            RewriteClassesAndManifestTask::getRewritePlan));
                    task.getRulesOutputDirectory().set(project.getLayout().getBuildDirectory()
                            .dir(r8Root + "/config/rules"));
                    task.getDictionariesOutputDirectory().set(project.getLayout().getBuildDirectory()
                            .dir(r8Root + "/config/dictionaries"));
                    task.getConfigurationEvidence().set(project.getLayout().getBuildDirectory()
                            .file(r8Root + "/configuration.properties"));
                });
        variant.getSources().getKeepRules().addGeneratedSourceDirectory(
                generateR8Configuration,
                GenerateR8ConfigurationTask::getRulesOutputDirectory);

        var composeMappings = project.getTasks().register(
                "composeKaleido" + capitalize(variant.getName()) + "R8Mappings",
                ComposeR8MappingsTask.class,
                task -> {
                    task.setGroup("kaleido");
                    task.setDescription("Captures and composes Kaleido and R8 mappings for "
                            + variant.getName());
                    task.getConsumerProjectPath().set(projectPath);
                    task.getVariantName().set(variantName);
                    task.getRetraceToolCoordinates().set(
                            "com.android.tools.build:builder:"
                                    + com.android.build.api.AndroidPluginVersion.getCurrent()
                                            .getVersion());
                    task.getRawKaleidoMapping().set(rewriteClasses.flatMap(
                            RewriteClassesAndManifestTask::getRawMapping));
                    task.getInputRawR8Mapping().set(variant.getArtifacts().get(
                            SingleArtifact.OBFUSCATION_MAPPING_FILE.INSTANCE));
                    task.getCapturedRawR8Mapping().set(project.getLayout().getBuildDirectory()
                            .file(r8Root + "/raw-r8-mapping.txt"));
                    task.getComposedMapping().set(project.getLayout().getBuildDirectory()
                            .file(r8Root + "/composed-mapping.txt"));
                    task.getCompositionMetadata().set(project.getLayout().getBuildDirectory()
                            .file(r8Root + "/mapping-metadata.properties"));
                });

        var taskName = "finalizeKaleido" + capitalize(variant.getName()) + "Bundle";
        var finalizeTask = project.getTasks().register(
                taskName,
                KaleidoBundleFinalizeTask.class,
                task -> {
                    task.setGroup("kaleido");
                    task.setDescription("Finalizes the " + variant.getName() + " Bundle through Kaleido");
                    task.dependsOn(validateAdoption, resolvePlan, generateSafeContent);
                    task.getConsumerProjectPath().set(project.getPath());
                    task.getVariantName().set(variant.getName());
                    task.getAdoptionPlan().set(resolvePlan.flatMap(
                            ResolveKaleidoAdoptionPlanTask::getPlanFile));
                    task.getGenerationInventory().set(generateSafeContent.flatMap(
                            GenerateSafeContentTask::getInventoryFile));
                    task.getClassRewriteEvidence().from(
                            rewriteClasses.flatMap(RewriteClassesAndManifestTask::getRewritePlan),
                            rewriteClasses.flatMap(RewriteClassesAndManifestTask::getRawMapping),
                            rewriteClasses.flatMap(RewriteClassesAndManifestTask::getTransformReceipt),
                            rewriteClasses.flatMap(
                                    RewriteClassesAndManifestTask::getResourceProtectionEvidence),
                            rewriteClasses.flatMap(
                                    RewriteClassesAndManifestTask::getComposeCompiledInventory),
                            rewriteClasses.flatMap(
                                    RewriteClassesAndManifestTask::getProtectionKeepRulesOutputDirectory));
                    task.getResourceProtectionEvidence().set(rewriteClasses.flatMap(
                            RewriteClassesAndManifestTask::getResourceProtectionEvidence));
                    task.getR8Evidence().from(
                            generateR8Configuration.flatMap(
                                    GenerateR8ConfigurationTask::getRulesOutputDirectory),
                            generateR8Configuration.flatMap(
                                    GenerateR8ConfigurationTask::getDictionariesOutputDirectory),
                            generateR8Configuration.flatMap(
                                    GenerateR8ConfigurationTask::getConfigurationEvidence),
                            composeMappings.flatMap(ComposeR8MappingsTask::getCapturedRawR8Mapping),
                            composeMappings.flatMap(ComposeR8MappingsTask::getComposedMapping),
                            composeMappings.flatMap(ComposeR8MappingsTask::getCompositionMetadata));
                    task.getApplicationResourceDirectories().from(
                            variant.getSources().getRes().getStatic(),
                            generateSafeContent.flatMap(
                                    GenerateSafeContentTask::getResourceOutputDirectory),
                            rewriteXml.flatMap(RewriteSemanticXmlTask::getOutputResources));
                    task.getApplicationNativeDirectories().from(
                            variant.getSources().getJniLibs().getStatic());
                    task.getApplicationMetadataDirectories().from(
                            variant.getSources().getResources().getStatic());
                    var bundleRewriteRoot = "intermediates/kaleido/" + variant.getName()
                            + "/bundle-rewrite";
                    task.getBundleRewritePlan().set(project.getLayout().getBuildDirectory()
                            .file(bundleRewriteRoot + "/bundle-rewrite-plan.pb"));
                    task.getResourceMapping().set(project.getLayout().getBuildDirectory()
                            .file(bundleRewriteRoot + "/resource-mapping.txt"));
                    task.getBundleTransformReceipt().set(project.getLayout().getBuildDirectory()
                            .file(bundleRewriteRoot + "/transform-receipt.pb"));
                    task.getUnsignedCandidateEvidence().set(
                            project.getLayout().getBuildDirectory()
                                    .file(bundleRewriteRoot + "/unsigned-candidate.aab"));
                    task.getUnsignedCandidateDigest().set(
                            project.getLayout().getBuildDirectory()
                                    .file(bundleRewriteRoot + "/unsigned-candidate.sha256"));
                    task.getOutputBundle().convention(project.getLayout().getBuildDirectory()
                            .file("intermediates/kaleido/" + variant.getName() + "/final.aab"));
                });

        variant.getArtifacts()
                .use(finalizeTask)
                .wiredWithFiles(
                        KaleidoBundleFinalizeTask::getInputBundle,
                        KaleidoBundleFinalizeTask::getOutputBundle)
                .toTransform(SingleArtifact.BUNDLE.INSTANCE);

        var verifyCompose = project.getTasks().register(
                "verifyKaleido" + capitalize(variant.getName()) + "ComposeFinalDex",
                VerifyComposeFinalDexTask.class,
                task -> {
                    task.setGroup("kaleido");
                    task.setDescription("Verifies mapped generated Compose members in final "
                            + variant.getName() + " DEX");
                    task.getConsumerProjectPath().set(projectPath);
                    task.getVariantName().set(variantName);
                    task.getInputBundle().set(finalizeTask.flatMap(
                            KaleidoBundleFinalizeTask::getOutputBundle));
                    task.getCompiledInventory().set(rewriteClasses.flatMap(
                            RewriteClassesAndManifestTask::getComposeCompiledInventory));
                    task.getComposedMapping().set(composeMappings.flatMap(
                            ComposeR8MappingsTask::getComposedMapping));
                    task.getVerificationReceipt().set(project.getLayout().getBuildDirectory()
                            .file("intermediates/kaleido/" + variantName
                                    + "/compose/final-dex-receipt.properties"));
                });

        var signTask = project.getTasks().register(
                "signAndVerifyKaleido" + capitalize(variant.getName()) + "Bundle",
                KaleidoSignAndVerifyBundleTask.class,
                task -> {
                    task.setGroup("kaleido");
                    task.setDescription("Selects, signs, and verifies the exact canonical "
                            + variant.getName() + " Bundle candidate");
                    task.dependsOn(verifyCompose);
                    task.getConsumerProjectPath().set(projectPath);
                    task.getVariantName().set(variantName);
                    configureSigningSource(extension.getVariantSigning().get(variantName),
                            task.getExactKeyStore(), task.getExactStorePassword(),
                            task.getExactKeyAlias(), task.getExactKeyPassword(),
                            task.getExactCertificateSha256());
                    configureSigningSource(extension.getSigning(),
                            task.getTopKeyStore(), task.getTopStorePassword(),
                            task.getTopKeyAlias(), task.getTopKeyPassword(),
                            task.getTopCertificateSha256());
                    var providers = project.getProviders();
                    task.getEnvironmentKeyStore().set(
                            providers.environmentVariable("KALEIDO_UPLOAD_KEYSTORE"));
                    task.getEnvironmentStorePassword().set(
                            providers.environmentVariable("KALEIDO_UPLOAD_STORE_PASSWORD"));
                    task.getEnvironmentKeyAlias().set(
                            providers.environmentVariable("KALEIDO_UPLOAD_KEY_ALIAS"));
                    task.getEnvironmentKeyPassword().set(
                            providers.environmentVariable("KALEIDO_UPLOAD_KEY_PASSWORD"));
                    task.getEnvironmentCertificateSha256().set(
                            providers.environmentVariable("KALEIDO_UPLOAD_CERTIFICATE_SHA256"));
                    task.getPropertyKeyStore().set(
                            providers.gradleProperty("kaleido.uploadSigning.keyStoreFile"));
                    task.getPropertyStorePassword().set(
                            providers.gradleProperty("kaleido.uploadSigning.storePassword"));
                    task.getPropertyKeyAlias().set(
                            providers.gradleProperty("kaleido.uploadSigning.keyAlias"));
                    task.getPropertyKeyPassword().set(
                            providers.gradleProperty("kaleido.uploadSigning.keyPassword"));
                    task.getPropertyCertificateSha256().set(
                            providers.gradleProperty(
                                    "kaleido.uploadSigning.expectedCertificateSha256"));
                    task.getSigningReceipt().set(project.getLayout().getBuildDirectory().file(
                            "intermediates/kaleido/" + variantName
                                    + "/signing/signing-receipt.properties"));
                    task.getInputBundle().set(finalizeTask.flatMap(
                            KaleidoBundleFinalizeTask::getOutputBundle));
                    task.getExpectedUnsignedDigest().set(finalizeTask.flatMap(
                            KaleidoBundleFinalizeTask::getUnsignedCandidateDigest));
                    task.getOutputBundle().set(project.getLayout().getBuildDirectory().file(
                            "intermediates/kaleido/" + variantName
                                    + "/signing/staged-signed-candidate.aab"));
                });

        var publishTask = project.getTasks().register(
                "publishKaleido" + capitalize(variantName) + "ReleaseEvidence",
                PublishKaleidoReleaseTask.class,
                task -> {
                    task.setGroup("kaleido");
                    task.setDescription("Atomically publishes the final signed " + variantName
                            + " Bundle and complete Release Evidence Set");
                    task.dependsOn(signTask);
                    task.getConsumerProjectPath().set(projectPath);
                    task.getVariantName().set(variantName);
                    task.getPluginVersion().set(project.getVersion().toString());
                    task.getConsumerProjectDirectory().set(project.getLayout()
                            .getProjectDirectory());
                    task.getStagedSignedBundle().set(signTask.flatMap(
                            KaleidoSignAndVerifyBundleTask::getOutputBundle));
                    task.getSigningReceipt().set(signTask.flatMap(
                            KaleidoSignAndVerifyBundleTask::getSigningReceipt));
                    task.getDeterministicEvidence().from(
                            resolvePlan.flatMap(ResolveKaleidoAdoptionPlanTask::getPlanFile),
                            generateSafeContent.flatMap(
                                    GenerateSafeContentTask::getJavaOutputDirectory),
                            generateSafeContent.flatMap(
                                    GenerateSafeContentTask::getKotlinOutputDirectory),
                            generateSafeContent.flatMap(
                                    GenerateSafeContentTask::getResourceOutputDirectory),
                            generateSafeContent.flatMap(
                                    GenerateSafeContentTask::getKeepRulesOutputDirectory),
                            generateSafeContent.flatMap(GenerateSafeContentTask::getManifestOutputFile),
                            generateSafeContent.flatMap(GenerateSafeContentTask::getInventoryFile),
                            rewriteManifest.flatMap(
                                    RewriteManifestReferencesTask::getRewriteIntent),
                            rewriteXml.flatMap(RewriteSemanticXmlTask::getRewriteIntent),
                            rewriteClasses.flatMap(RewriteClassesAndManifestTask::getRewritePlan),
                            rewriteClasses.flatMap(RewriteClassesAndManifestTask::getRawMapping),
                            rewriteClasses.flatMap(
                                    RewriteClassesAndManifestTask::getTransformReceipt),
                            rewriteClasses.flatMap(
                                    RewriteClassesAndManifestTask::getResourceProtectionEvidence),
                            rewriteClasses.flatMap(
                                    RewriteClassesAndManifestTask::getComposeCompiledInventory),
                            rewriteClasses.flatMap(
                                    RewriteClassesAndManifestTask::getProtectionKeepRulesOutputDirectory),
                            generateR8Configuration.flatMap(
                                    GenerateR8ConfigurationTask::getRulesOutputDirectory),
                            generateR8Configuration.flatMap(
                                    GenerateR8ConfigurationTask::getDictionariesOutputDirectory),
                            generateR8Configuration.flatMap(
                                    GenerateR8ConfigurationTask::getConfigurationEvidence),
                            composeMappings.flatMap(ComposeR8MappingsTask::getCapturedRawR8Mapping),
                            composeMappings.flatMap(ComposeR8MappingsTask::getComposedMapping),
                            composeMappings.flatMap(ComposeR8MappingsTask::getCompositionMetadata),
                            finalizeTask.flatMap(KaleidoBundleFinalizeTask::getBundleRewritePlan),
                            finalizeTask.flatMap(KaleidoBundleFinalizeTask::getResourceMapping),
                            finalizeTask.flatMap(
                                    KaleidoBundleFinalizeTask::getBundleTransformReceipt),
                            finalizeTask.flatMap(
                                    KaleidoBundleFinalizeTask::getUnsignedCandidateEvidence),
                            finalizeTask.flatMap(
                                    KaleidoBundleFinalizeTask::getUnsignedCandidateDigest),
                            verifyCompose.flatMap(
                                    VerifyComposeFinalDexTask::getVerificationReceipt));
                    task.getPublishedEvidenceDirectory().set(project.getLayout()
                            .getBuildDirectory().dir("reports/kaleido/" + variantName
                                    + "/release-evidence-set"));
                });
        variant.getArtifacts()
                .use(publishTask)
                .wiredWithFiles(
                        PublishKaleidoReleaseTask::getInputBundle,
                        PublishKaleidoReleaseTask::getOutputBundle)
                .toTransform(SingleArtifact.BUNDLE.INSTANCE);
        project.getTasks().matching(task -> task.getName().equals(
                "bundle" + capitalize(variantName))).configureEach(
                        task -> task.dependsOn(publishTask));

        var flavors = flavorIdentities.stream().collect(Collectors.joining(",", "[", "]"));
        project.getLogger().lifecycle(
                "KLD-ADOPTION-002 project={} variant={} stage=adoption origin=AGP-variant-api "
                        + "target={} reason=Eligible release variant discovered "
                        + "repair=<none> buildType={} flavors={} applicationId={}",
                project.getPath(),
                variant.getName(),
                variant.getName(),
                variant.getBuildType(),
                flavors,
                variant.getApplicationId().get());
    }

    private static String capitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static void configureSigningSource(
            KaleidoSigning source,
            org.gradle.api.provider.Property<String> keyStore,
            org.gradle.api.provider.Property<String> storePassword,
            org.gradle.api.provider.Property<String> alias,
            org.gradle.api.provider.Property<String> keyPassword,
            org.gradle.api.provider.Property<String> certificate) {
        if (source == null) return;
        keyStore.set(source.getKeyStoreFile().map(file ->
                file.getAsFile().getPath()));
        storePassword.set(source.getStorePassword());
        alias.set(source.getKeyAlias());
        keyPassword.set(source.getKeyPassword());
        certificate.set(source.getExpectedCertificateSha256());
    }

    private static List<String> canonicalEscapeHatches(
            Iterable<? extends KaleidoEscapeHatch> hatches,
            String project,
            String variant) {
        var values = new java.util.ArrayList<String>();
        try {
            hatches.forEach(hatch -> values.add(hatch.canonicalDeclaration()));
        } catch (RuntimeException failure) {
            throw new KaleidoDiagnostic("KLD-PROTECTION-001", project, variant,
                    "protection", "kaleido.protection", "declaration",
                    "Escape Hatch is missing selector, dimensions, or reason",
                    "Complete every typed Escape Hatch declaration").failure();
        }
        return values.stream().sorted().toList();
    }

    private static void configureSafeDefaults(KaleidoExtension extension) {
        extension.getProfile().convention(KaleidoProfile.SAFE);
        var generation = extension.getGeneration();
        generation.getPackageCount().convention(4);
        generation.getClassesPerPackage().convention(4);
        generation.getMethodsPerClass().convention(4);
        generation.getLayoutCount().convention(8);
        generation.getDrawableCount().convention(16);
        generation.getStringCount().convention(32);
        generation.getActivityCount().convention(0);
        generation.getCompose().getEnabled().convention(false);
        generation.getCompose().getFileCount().convention(4);
        generation.getCompose().getFunctionsPerFile().convention(4);

        var resources = extension.getResources();
        resources.getNativeLibrariesToDelete().convention(java.util.Set.of());
        resources.getMetadataToDelete().convention(java.util.Set.of());
        resources.getReplaceUnusedStrings().convention(false);
        resources.getRetainedLanguages().convention(java.util.Set.of());

        var protection = extension.getProtection();
        protection.getOriginalClassNames().convention(java.util.Set.of());
        protection.getResourceNames().convention(java.util.Set.of());
        protection.getPackagedPaths().convention(java.util.Set.of());
    }

    private static GradleException composeConfigurationFailure(
            Project project, String target, String reason, String repair) {
        return new KaleidoDiagnostic("KLD-CONFIG-001", project.getPath(), "<release>",
                "adoption-plan", "kaleido.generation.compose", target, reason, repair).failure();
    }
}
