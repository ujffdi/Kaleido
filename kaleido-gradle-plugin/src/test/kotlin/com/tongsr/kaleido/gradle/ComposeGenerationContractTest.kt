package com.tongsr.kaleido.gradle

import java.nio.charset.StandardCharsets
import java.util.HashMap
import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class ComposeGenerationContractTest {
    @Test
    fun generationIsDeterministicBoundedRuntimeOnlyAndAcyclic() {
        val first = ComposeGenerationEngine.plan(plan("seed-a", 64, 8))
        val repeated = ComposeGenerationEngine.plan(plan("seed-a", 64, 8))
        val changed = ComposeGenerationEngine.plan(plan("seed-b", 64, 8))

        assertEquals(first, repeated)
        assertNotEquals(first, changed)
        assertEquals(64, first.kotlinFiles.size)
        assertEquals(512, first.functions.size)
        for (source in first.kotlinFiles.values) {
            assertTrue(source.contains("import androidx.compose.runtime.Composable"))
            assertFalse(
                source.matches(
                    Regex(
                        "(?s).*(androidx\\.compose\\.(ui|foundation|material)|" +
                            "Preview|android\\.|kotlinx\\.|java\\.(io|net)).*",
                    ),
                ),
            )
        }
        for (index in 0 until first.functions.size - 1) {
            val current = first.functions[index]
            val next = first.functions[index + 1]
            val source = first.kotlinFiles.values.first { value ->
                value.contains("fun " + current.name + "(")
            }
            assertTrue(source.contains("= " + next.name + "(mixed)"))
        }
    }

    @Test
    fun compiledValidationRejectsForbiddenApiCycleIncomingEdgeAndManifestEntry() {
        val adoption = plan("validator", 1, 2)
        val generated = ComposeGenerationEngine.plan(adoption)
        val facade = generated.facades[0]
        val first = generated.functions[0].name
        val second = generated.functions[1].name

        val forbidden = facade(facade, first, second, cycle = false, forbidden = true)
        val forbiddenFailure = assertThrows(GradleException::class.java) {
            ComposeCompiledValidator.validate(
                adoption,
                mapOf(facade to forbidden),
                "<manifest/>".toByteArray(StandardCharsets.UTF_8),
                ":app",
                "release",
            )
        }
        assertTrue(forbiddenFailure.message!!.contains("forbidden API"))

        val cycle = facade(facade, first, second, cycle = true, forbidden = false)
        val cycleFailure = assertThrows(GradleException::class.java) {
            ComposeCompiledValidator.validate(
                adoption,
                mapOf(facade to cycle),
                "<manifest/>".toByteArray(StandardCharsets.UTF_8),
                ":app",
                "release",
            )
        }
        assertTrue(cycleFailure.message!!.contains("contains a cycle"))

        val valid = facade(facade, first, second, cycle = false, forbidden = false)
        val consumer = consumerCalling(facade, first)
        val incomingFailure = assertThrows(GradleException::class.java) {
            ComposeCompiledValidator.validate(
                adoption,
                mapOf(facade to valid, "example.Consumer" to consumer),
                "<manifest/>".toByteArray(StandardCharsets.UTF_8),
                ":app",
                "release",
            )
        }
        assertTrue(incomingFailure.message!!.contains("incoming edge"))

        val manifestFailure = assertThrows(GradleException::class.java) {
            ComposeCompiledValidator.validate(
                adoption,
                mapOf(facade to valid),
                ("<activity android:name=\"" + facade + "\"/>")
                    .toByteArray(StandardCharsets.UTF_8),
                ":app",
                "release",
            )
        }
        assertTrue(manifestFailure.message!!.contains("appears in the merged Manifest"))
    }

    private fun plan(seed: String, files: Int, functionsPerFile: Int): Map<String, String> {
        val values = HashMap<String, String>()
        values["project"] = ":app"
        values["variant.name"] = "release"
        values["generation.packageBase"] = "example.generated"
        values["generation.compose.enabled"] = "true"
        values["generation.compose.fileCount"] = files.toString()
        values["generation.compose.functionsPerFile"] = functionsPerFile.toString()
        values["seed.domain.generation-compose"] =
            SeedDerivation.derive(
                SeedDerivation.fingerprint(seed),
                "generation-compose",
                "release|release|",
            )
        return values.toMap()
    }

    private fun facade(
        dottedName: String,
        first: String,
        second: String,
        cycle: Boolean,
        forbidden: Boolean,
    ): ByteArray {
        val owner = dottedName.replace('.', '/')
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V17,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            owner,
            null,
            "java/lang/Object",
            null,
        )
        method(writer, owner, first, second, forbidden)
        method(writer, owner, second, if (cycle) first else null, false)
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun method(
        writer: ClassWriter,
        owner: String,
        name: String,
        generatedTarget: String?,
        forbidden: Boolean,
    ) {
        val method = writer.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            name,
            DESCRIPTOR,
            null,
            null,
        )
        method.visitCode()
        if (forbidden) {
            method.visitInsn(Opcodes.ACONST_NULL)
            method.visitInsn(Opcodes.ICONST_0)
            method.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/io/PrintStream",
                "println",
                "(I)V",
                false,
            )
        }
        if (generatedTarget != null) {
            method.visitVarInsn(Opcodes.ILOAD, 0)
            method.visitVarInsn(Opcodes.ALOAD, 1)
            method.visitVarInsn(Opcodes.ILOAD, 2)
            method.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                owner,
                generatedTarget,
                DESCRIPTOR,
                false,
            )
            method.visitInsn(Opcodes.IRETURN)
        } else {
            method.visitVarInsn(Opcodes.ILOAD, 0)
            method.visitInsn(Opcodes.IRETURN)
        }
        method.visitMaxs(3, 3)
        method.visitEnd()
    }

    private fun consumerCalling(facade: String, function: String): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V17,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            "example/Consumer",
            null,
            "java/lang/Object",
            null,
        )
        val method = writer.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "entry",
            "()I",
            null,
            null,
        )
        method.visitCode()
        method.visitInsn(Opcodes.ICONST_0)
        method.visitInsn(Opcodes.ACONST_NULL)
        method.visitInsn(Opcodes.ICONST_0)
        method.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            facade.replace('.', '/'),
            function,
            DESCRIPTOR,
            false,
        )
        method.visitInsn(Opcodes.IRETURN)
        method.visitMaxs(3, 0)
        method.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    companion object {
        private const val DESCRIPTOR = "(ILandroidx/compose/runtime/Composer;I)I"
    }
}
