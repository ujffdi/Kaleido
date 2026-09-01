package com.tongsr.kaleido.gradle;

import static org.junit.Assert.assertFalse;

import com.tongsr.kaleido.gradle.dsl.KaleidoComposeGeneration;
import com.tongsr.kaleido.gradle.dsl.KaleidoExtension;
import com.tongsr.kaleido.gradle.dsl.KaleidoGeneration;
import com.tongsr.kaleido.gradle.dsl.KaleidoProtection;
import com.tongsr.kaleido.gradle.dsl.KaleidoResources;
import com.tongsr.kaleido.gradle.dsl.KaleidoSeed;
import com.tongsr.kaleido.gradle.dsl.KaleidoSigning;
import java.util.List;
import org.junit.Test;

public final class DslSurfaceTest {
    @Test
    public void publicDslDoesNotExposeGradleTasksProjectsVariantsOrEngineTypes() {
        for (var type : List.of(
                KaleidoExtension.class,
                KaleidoSeed.class,
                KaleidoGeneration.class,
                KaleidoComposeGeneration.class,
                KaleidoResources.class,
                KaleidoProtection.class,
                KaleidoSigning.class)) {
            for (var method : type.getMethods()) {
                assertAllowed(method.getReturnType());
                for (var parameter : method.getParameterTypes()) {
                    assertAllowed(parameter);
                }
            }
        }
    }

    private static void assertAllowed(Class<?> type) {
        var name = type.getName();
        assertFalse(name.equals("org.gradle.api.Project"));
        assertFalse(name.equals("org.gradle.api.Task"));
        assertFalse(name.startsWith("com.android.build.api."));
        assertFalse(name.contains("Engine"));
    }
}
