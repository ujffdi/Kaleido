package com.tongsr.kaleido.gradle;

import java.util.List;

public final class KaleidoCachePolicy {
    public static final String SCHEMA = "KaleidoTaskCachePolicy.v1";

    private KaleidoCachePolicy() {}

    public static List<Stage> stages() {
        return List.of(
                new Stage("adoption-plan", ResolveKaleidoAdoptionPlanTask.class,
                        Mode.CACHEABLE_DETERMINISTIC, "ResolveAdoptionPlanCache.v1"),
                new Stage("generation", GenerateSafeContentTask.class,
                        Mode.CACHEABLE_DETERMINISTIC, "GenerateContentCache.v1"),
                new Stage("semantic-xml", RewriteSemanticXmlTask.class,
                        Mode.CACHEABLE_DETERMINISTIC, "SemanticXmlRewriteCache.v2"),
                new Stage("manifest-rewrite", RewriteManifestReferencesTask.class,
                        Mode.CACHEABLE_DETERMINISTIC, "ManifestRewriteCache.v2"),
                new Stage("class-protection-rewrite", RewriteClassesAndManifestTask.class,
                        Mode.CACHEABLE_DETERMINISTIC, "ClassRewriteCache.v1"),
                new Stage("r8-configuration", GenerateR8ConfigurationTask.class,
                        Mode.CACHEABLE_DETERMINISTIC, "R8ConfigurationCache.v1"),
                new Stage("mapping-composition", ComposeR8MappingsTask.class,
                        Mode.CACHEABLE_DETERMINISTIC, "R8MappingCompositionCache.v1"),
                new Stage("unsigned-bundle-rewrite", KaleidoBundleFinalizeTask.class,
                        Mode.CACHEABLE_DETERMINISTIC, "UnsignedBundleRewriteCache.v1"),
                new Stage("final-validation", VerifyComposeFinalDexTask.class,
                        Mode.NONCACHEABLE_ALWAYS_VALIDATE, "none"),
                new Stage("credential-signing", KaleidoSignAndVerifyBundleTask.class,
                        Mode.NONCACHEABLE_ALWAYS_VALIDATE, "none"),
                new Stage("atomic-publication", PublishKaleidoReleaseTask.class,
                        Mode.NONCACHEABLE_ALWAYS_VALIDATE, "none"));
    }

    public enum Mode { CACHEABLE_DETERMINISTIC, NONCACHEABLE_ALWAYS_VALIDATE }

    public record Stage(
            String name, Class<? extends org.gradle.api.Task> taskType,
            Mode mode, String cacheSchema) {}
}
