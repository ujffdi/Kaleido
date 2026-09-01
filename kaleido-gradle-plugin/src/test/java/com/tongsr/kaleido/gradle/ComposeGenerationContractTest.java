package com.tongsr.kaleido.gradle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

public final class ComposeGenerationContractTest {
    private static final String DESCRIPTOR =
            "(ILandroidx/compose/runtime/Composer;I)I";

    @Test
    public void generationIsDeterministicBoundedRuntimeOnlyAndAcyclic() {
        var first = ComposeGenerationEngine.plan(plan("seed-a", 64, 8));
        var repeated = ComposeGenerationEngine.plan(plan("seed-a", 64, 8));
        var changed = ComposeGenerationEngine.plan(plan("seed-b", 64, 8));

        assertEquals(first, repeated);
        assertNotEquals(first, changed);
        assertEquals(64, first.kotlinFiles().size());
        assertEquals(512, first.functions().size());
        for (var source : first.kotlinFiles().values()) {
            assertTrue(source.contains("import androidx.compose.runtime.Composable"));
            assertFalse(source.matches("(?s).*(androidx\\.compose\\.(ui|foundation|material)|"
                    + "Preview|android\\.|kotlinx\\.|java\\.(io|net)).*"));
        }
        for (var index = 0; index < first.functions().size() - 1; index++) {
            var current = first.functions().get(index);
            var next = first.functions().get(index + 1);
            var source = first.kotlinFiles().values().stream()
                    .filter(value -> value.contains("fun " + current.name() + "("))
                    .findFirst().orElseThrow();
            assertTrue(source.contains("= " + next.name() + "(mixed)"));
        }
    }

    @Test
    public void compiledValidationRejectsForbiddenApiCycleIncomingEdgeAndManifestEntry() {
        var adoption = plan("validator", 1, 2);
        var generated = ComposeGenerationEngine.plan(adoption);
        var facade = generated.facades().get(0);
        var first = generated.functions().get(0).name();
        var second = generated.functions().get(1).name();

        var forbidden = facade(facade, first, second, false, true);
        var forbiddenFailure = assertThrows(org.gradle.api.GradleException.class, () ->
                ComposeCompiledValidator.validate(adoption, Map.of(facade, forbidden),
                        "<manifest/>".getBytes(StandardCharsets.UTF_8), ":app", "release"));
        assertTrue(forbiddenFailure.getMessage().contains("forbidden API"));

        var cycle = facade(facade, first, second, true, false);
        var cycleFailure = assertThrows(org.gradle.api.GradleException.class, () ->
                ComposeCompiledValidator.validate(adoption, Map.of(facade, cycle),
                        "<manifest/>".getBytes(StandardCharsets.UTF_8), ":app", "release"));
        assertTrue(cycleFailure.getMessage().contains("contains a cycle"));

        var valid = facade(facade, first, second, false, false);
        var consumer = consumerCalling(facade, first);
        var incomingFailure = assertThrows(org.gradle.api.GradleException.class, () ->
                ComposeCompiledValidator.validate(adoption,
                        Map.of(facade, valid, "example.Consumer", consumer),
                        "<manifest/>".getBytes(StandardCharsets.UTF_8), ":app", "release"));
        assertTrue(incomingFailure.getMessage().contains("incoming edge"));

        var manifestFailure = assertThrows(org.gradle.api.GradleException.class, () ->
                ComposeCompiledValidator.validate(adoption, Map.of(facade, valid),
                        ("<activity android:name=\"" + facade + "\"/>")
                                .getBytes(StandardCharsets.UTF_8),
                        ":app", "release"));
        assertTrue(manifestFailure.getMessage().contains("appears in the merged Manifest"));
    }

    private static Map<String, String> plan(
            String seed, int files, int functionsPerFile) {
        var values = new HashMap<String, String>();
        values.put("project", ":app");
        values.put("variant.name", "release");
        values.put("generation.packageBase", "example.generated");
        values.put("generation.compose.enabled", "true");
        values.put("generation.compose.fileCount", Integer.toString(files));
        values.put("generation.compose.functionsPerFile", Integer.toString(functionsPerFile));
        values.put("seed.domain.generation-compose",
                SeedDerivation.derive(SeedDerivation.fingerprint(seed),
                        "generation-compose", "release|release|"));
        return Map.copyOf(values);
    }

    private static byte[] facade(
            String dottedName, String first, String second,
            boolean cycle, boolean forbidden) {
        var owner = dottedName.replace('.', '/');
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                owner, null, "java/lang/Object", null);
        method(writer, owner, first, second, forbidden);
        method(writer, owner, second, cycle ? first : null, false);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void method(
            ClassWriter writer, String owner, String name,
            String generatedTarget, boolean forbidden) {
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                name, DESCRIPTOR, null, null);
        method.visitCode();
        if (forbidden) {
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitInsn(Opcodes.ICONST_0);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream",
                    "println", "(I)V", false);
        }
        if (generatedTarget != null) {
            method.visitVarInsn(Opcodes.ILOAD, 0);
            method.visitVarInsn(Opcodes.ALOAD, 1);
            method.visitVarInsn(Opcodes.ILOAD, 2);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, owner,
                    generatedTarget, DESCRIPTOR, false);
            method.visitInsn(Opcodes.IRETURN);
        } else {
            method.visitVarInsn(Opcodes.ILOAD, 0);
            method.visitInsn(Opcodes.IRETURN);
        }
        method.visitMaxs(3, 3);
        method.visitEnd();
    }

    private static byte[] consumerCalling(String facade, String function) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                "example/Consumer", null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "entry", "()I", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ICONST_0);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, facade.replace('.', '/'),
                function, DESCRIPTOR, false);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(3, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
