package com.tongsr.kaleido.gradle

import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Internal
import org.gradle.work.DisableCachingByDefault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KaleidoCachePolicyTest {
    @Test
    fun deterministicAndSensitiveStagesHaveDisjointExecutionPolicies() {
        assertEquals("KaleidoTaskCachePolicy.v1", KaleidoCachePolicy.SCHEMA)
        val names = KaleidoCachePolicy.stages().map { it.name }.toSet()
        assertEquals(KaleidoCachePolicy.stages().size, names.size)
        for (stage in KaleidoCachePolicy.stages()) {
            if (stage.mode == KaleidoCachePolicy.Mode.CACHEABLE_DETERMINISTIC) {
                assertTrue(stage.taskType.isAnnotationPresent(CacheableTask::class.java))
                assertFalse(stage.taskType.isAnnotationPresent(DisableCachingByDefault::class.java))
                assertTrue(stage.cacheSchema.matches(Regex("[A-Za-z0-9]+Cache\\.v[0-9]+")))
            } else {
                assertTrue(stage.taskType.isAnnotationPresent(DisableCachingByDefault::class.java))
                assertFalse(stage.taskType.isAnnotationPresent(CacheableTask::class.java))
                assertEquals("none", stage.cacheSchema)
            }
        }
    }

    @Test
    fun everySigningCredentialSurfaceRemainsInternal() {
        val credentialNames = setOf(
            "getExactKeyStore", "getExactStorePassword", "getExactKeyAlias",
            "getExactKeyPassword", "getExactCertificateSha256", "getTopKeyStore",
            "getTopStorePassword", "getTopKeyAlias", "getTopKeyPassword",
            "getTopCertificateSha256", "getEnvironmentKeyStore",
            "getEnvironmentStorePassword", "getEnvironmentKeyAlias",
            "getEnvironmentKeyPassword", "getEnvironmentCertificateSha256",
            "getPropertyKeyStore", "getPropertyStorePassword", "getPropertyKeyAlias",
            "getPropertyKeyPassword", "getPropertyCertificateSha256",
        )
        val methods = KaleidoSignAndVerifyBundleTask::class.java.methods
            .filter { method -> credentialNames.contains(method.name) }
        assertEquals(credentialNames.size, methods.size)
        assertTrue(
            methods.all { method ->
                method.isAnnotationPresent(Internal::class.java)
            },
        )
    }

    @Test
    fun cacheableStagesExposeVersionedSchemaInputs() {
        for (stage in KaleidoCachePolicy.stages().filter { item ->
            item.mode == KaleidoCachePolicy.Mode.CACHEABLE_DETERMINISTIC
        }) {
            val method = stage.taskType.methods.first { candidate ->
                candidate.name == "getKaleidoCacheSchema"
            }
            assertTrue(method.isAnnotationPresent(org.gradle.api.tasks.Input::class.java))
            assertEquals(String::class.java, method.returnType)
        }
    }
}
