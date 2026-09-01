package com.tongsr.kaleido.gradle

import org.gradle.api.Task

object KaleidoCachePolicy {
    const val SCHEMA: String = "KaleidoTaskCachePolicy.v1"

    @JvmStatic
    fun stages(): List<Stage> = listOf(
        Stage(
            "adoption-plan",
            ResolveKaleidoAdoptionPlanTask::class.java,
            Mode.CACHEABLE_DETERMINISTIC,
            "ResolveAdoptionPlanCache.v1",
        ),
        Stage(
            "generation",
            GenerateSafeContentTask::class.java,
            Mode.CACHEABLE_DETERMINISTIC,
            "GenerateContentCache.v2",
        ),
        Stage(
            "semantic-xml",
            RewriteSemanticXmlTask::class.java,
            Mode.CACHEABLE_DETERMINISTIC,
            "SemanticXmlRewriteCache.v2",
        ),
        Stage(
            "manifest-rewrite",
            RewriteManifestReferencesTask::class.java,
            Mode.CACHEABLE_DETERMINISTIC,
            "ManifestRewriteCache.v2",
        ),
        Stage(
            "class-protection-rewrite",
            RewriteClassesAndManifestTask::class.java,
            Mode.CACHEABLE_DETERMINISTIC,
            "ClassRewriteCache.v1",
        ),
        Stage(
            "r8-configuration",
            GenerateR8ConfigurationTask::class.java,
            Mode.CACHEABLE_DETERMINISTIC,
            "R8ConfigurationCache.v1",
        ),
        Stage(
            "mapping-composition",
            ComposeR8MappingsTask::class.java,
            Mode.CACHEABLE_DETERMINISTIC,
            "R8MappingCompositionCache.v1",
        ),
        Stage(
            "unsigned-bundle-rewrite",
            KaleidoBundleFinalizeTask::class.java,
            Mode.CACHEABLE_DETERMINISTIC,
            "UnsignedBundleRewriteCache.v1",
        ),
        Stage(
            "final-validation",
            VerifyComposeFinalDexTask::class.java,
            Mode.NONCACHEABLE_ALWAYS_VALIDATE,
            "none",
        ),
        Stage(
            "credential-signing",
            KaleidoSignAndVerifyBundleTask::class.java,
            Mode.NONCACHEABLE_ALWAYS_VALIDATE,
            "none",
        ),
        Stage(
            "atomic-publication",
            PublishKaleidoReleaseTask::class.java,
            Mode.NONCACHEABLE_ALWAYS_VALIDATE,
            "none",
        ),
    )

    enum class Mode {
        CACHEABLE_DETERMINISTIC,
        NONCACHEABLE_ALWAYS_VALIDATE,
    }

    @JvmRecord
    data class Stage(
        val name: String,
        val taskType: Class<out Task>,
        val mode: Mode,
        val cacheSchema: String,
    )
}
