package com.tongsr.kaleido.gradle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.stream.Collectors;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Internal;
import org.gradle.work.DisableCachingByDefault;
import org.junit.Test;

public final class KaleidoCachePolicyTest {
    @Test
    public void deterministicAndSensitiveStagesHaveDisjointExecutionPolicies() {
        assertEquals("KaleidoTaskCachePolicy.v1", KaleidoCachePolicy.SCHEMA);
        var names = KaleidoCachePolicy.stages().stream()
                .map(KaleidoCachePolicy.Stage::name).collect(Collectors.toSet());
        assertEquals(KaleidoCachePolicy.stages().size(), names.size());
        for (var stage : KaleidoCachePolicy.stages()) {
            if (stage.mode() == KaleidoCachePolicy.Mode.CACHEABLE_DETERMINISTIC) {
                assertTrue(stage.taskType().isAnnotationPresent(CacheableTask.class));
                assertFalse(stage.taskType().isAnnotationPresent(DisableCachingByDefault.class));
                assertTrue(stage.cacheSchema().matches("[A-Za-z0-9]+Cache\\.v[0-9]+"));
            } else {
                assertTrue(stage.taskType().isAnnotationPresent(DisableCachingByDefault.class));
                assertFalse(stage.taskType().isAnnotationPresent(CacheableTask.class));
                assertEquals("none", stage.cacheSchema());
            }
        }
    }

    @Test
    public void everySigningCredentialSurfaceRemainsInternal() {
        var credentialNames = Set.of(
                "getExactKeyStore", "getExactStorePassword", "getExactKeyAlias",
                "getExactKeyPassword", "getExactCertificateSha256", "getTopKeyStore",
                "getTopStorePassword", "getTopKeyAlias", "getTopKeyPassword",
                "getTopCertificateSha256", "getEnvironmentKeyStore",
                "getEnvironmentStorePassword", "getEnvironmentKeyAlias",
                "getEnvironmentKeyPassword", "getEnvironmentCertificateSha256",
                "getPropertyKeyStore", "getPropertyStorePassword", "getPropertyKeyAlias",
                "getPropertyKeyPassword", "getPropertyCertificateSha256");
        var methods = java.util.Arrays.stream(KaleidoSignAndVerifyBundleTask.class.getMethods())
                .filter(method -> credentialNames.contains(method.getName())).toList();
        assertEquals(credentialNames.size(), methods.size());
        assertTrue(methods.stream().allMatch(method ->
                method.isAnnotationPresent(Internal.class)));
    }

    @Test
    public void cacheableStagesExposeVersionedSchemaInputs() {
        for (var stage : KaleidoCachePolicy.stages().stream()
                .filter(item -> item.mode()
                        == KaleidoCachePolicy.Mode.CACHEABLE_DETERMINISTIC).toList()) {
            var method = java.util.Arrays.stream(stage.taskType().getMethods())
                    .filter(candidate -> candidate.getName().equals("getKaleidoCacheSchema"))
                    .findFirst().orElseThrow();
            assertTrue(method.isAnnotationPresent(org.gradle.api.tasks.Input.class));
            assertEquals(String.class, method.getReturnType());
        }
    }
}
