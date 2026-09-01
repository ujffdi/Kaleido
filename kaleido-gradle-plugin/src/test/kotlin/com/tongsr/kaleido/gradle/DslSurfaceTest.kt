package com.tongsr.kaleido.gradle

import com.tongsr.kaleido.gradle.dsl.KaleidoComposeGeneration
import com.tongsr.kaleido.gradle.dsl.KaleidoExtension
import com.tongsr.kaleido.gradle.dsl.KaleidoGeneration
import com.tongsr.kaleido.gradle.dsl.KaleidoProtection
import com.tongsr.kaleido.gradle.dsl.KaleidoResources
import com.tongsr.kaleido.gradle.dsl.KaleidoSeed
import com.tongsr.kaleido.gradle.dsl.KaleidoSigning
import org.junit.Assert.assertFalse
import org.junit.Test

class DslSurfaceTest {
    @Test
    fun publicDslDoesNotExposeGradleTasksProjectsVariantsOrEngineTypes() {
        for (type in listOf(
            KaleidoExtension::class.java,
            KaleidoSeed::class.java,
            KaleidoGeneration::class.java,
            KaleidoComposeGeneration::class.java,
            KaleidoResources::class.java,
            KaleidoProtection::class.java,
            KaleidoSigning::class.java,
        )) {
            for (method in type.methods) {
                assertAllowed(method.returnType)
                for (parameter in method.parameterTypes) {
                    assertAllowed(parameter)
                }
            }
        }
    }

    private fun assertAllowed(type: Class<*>) {
        val name = type.name
        assertFalse(name == "org.gradle.api.Project")
        assertFalse(name == "org.gradle.api.Task")
        assertFalse(name.startsWith("com.android.build.api."))
        assertFalse(name.contains("Engine"))
    }
}
