package com.tongsr.kaleido.gradle;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

final class ComposeCompiledValidator {
    static final String SCHEMA = "ComposeCompiledInventory.v1";

    private ComposeCompiledValidator() {}

    static Result validate(
            Map<String, String> adoption,
            Map<String, byte[]> classes,
            byte[] manifest,
            String project,
            String variant) {
        var expected = ComposeGenerationEngine.plan(adoption);
        if (expected.facades().isEmpty()) return new Result(List.of(), Map.of());
        var facadeInternalNames = expected.facades().stream()
                .map(name -> name.replace('.', '/')).collect(java.util.stream.Collectors.toSet());
        var expectedFunctions = expected.functions().stream().collect(
                java.util.stream.Collectors.toMap(
                        function -> function.facade() + "#" + function.name(),
                        java.util.function.Function.identity()));
        var methods = new TreeMap<String, MethodIdentity>();
        var edges = new TreeMap<String, Set<String>>();

        for (var facade : expected.facades()) {
            var bytes = classes.get(facade);
            if (bytes == null) {
                throw failure(project, variant, facade,
                        "Generated Compose facade is absent from compiled PROJECT classes",
                        "Enable built-in Kotlin and rebuild the generated Kotlin directory");
            }
            validateGeneratedClass(bytes, facade, facadeInternalNames, expectedFunctions,
                    methods, edges, project, variant);
            if (new String(manifest, StandardCharsets.UTF_8).contains(facade)) {
                throw failure(project, variant, facade,
                        "Generated Compose facade appears in the merged Manifest",
                        "Remove every Android component or startup reference to generated Compose code");
            }
        }
        if (!methods.keySet().equals(expectedFunctions.keySet())) {
            var missing = new TreeSet<>(expectedFunctions.keySet());
            missing.removeAll(methods.keySet());
            throw failure(project, variant, String.join(",", missing),
                    "Compiled Composer-lowered function inventory is incomplete",
                    "Use the matching Compose compiler and Runtime versions");
        }
        validateNoIncomingEdges(classes, facadeInternalNames, project, variant);
        validateAcyclic(edges, project, variant);
        return new Result(methods.values().stream()
                .sorted(Comparator.comparing(MethodIdentity::facade)
                        .thenComparing(MethodIdentity::name)).toList(), Map.copyOf(edges));
    }

    private static void validateGeneratedClass(
            byte[] bytes,
            String facade,
            Set<String> generatedOwners,
            Map<String, ComposeGenerationEngine.FunctionIdentity> expectedFunctions,
            Map<String, MethodIdentity> methods,
            Map<String, Set<String>> edges,
            String project,
            String variant) {
        var owner = facade.replace('.', '/');
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public org.objectweb.asm.AnnotationVisitor visitAnnotation(
                    String descriptor, boolean visible) {
                if (descriptor.contains("/Preview;")) {
                    throw failure(project, variant, facade,
                            "Generated Compose bytecode contains a Preview surface",
                            "Generate Runtime-only composables without Preview annotations");
                }
                return null;
            }

            @Override
            public MethodVisitor visitMethod(
                    int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                var key = facade + "#" + name;
                var expected = expectedFunctions.get(key);
                if (expected == null) return forbiddenReferenceVisitor(
                        facade, null, generatedOwners, Map.of(), project, variant);
                if ((access & Opcodes.ACC_STATIC) == 0
                        || !descriptor.contains("Landroidx/compose/runtime/Composer;")) {
                    throw failure(project, variant, key,
                            "Generated function lacks the compiled Composer-lowered signature",
                            "Apply the matching Compose compiler plugin to this module");
                }
                if (methods.putIfAbsent(key,
                        new MethodIdentity(facade, name, descriptor, expected.graphIndex())) != null) {
                    throw failure(project, variant, key,
                            "Generated function has ambiguous compiled overloads",
                            "Regenerate the fixed one-method-per-function Compose graph");
                }
                edges.put(key, new TreeSet<>());
                return new ReferenceVisitor(
                        facade, key, generatedOwners, expectedFunctions, project, variant) {
                    @Override
                    void generatedCall(String calledOwner, String calledName) {
                        var target = calledOwner.replace('/', '.') + "#" + calledName;
                        if (!expectedFunctions.containsKey(target)) {
                            throw failure(project, variant, target,
                                    "Generated Compose call targets an uninventoried member",
                                    "Regenerate the closed generated-only call graph");
                        }
                        edges.get(key).add(target);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    private static ReferenceVisitor forbiddenReferenceVisitor(
            String facade, String currentMethod, Set<String> generatedOwners,
            Map<String, ComposeGenerationEngine.FunctionIdentity> expectedFunctions,
            String project, String variant) {
        return new ReferenceVisitor(facade, currentMethod, generatedOwners,
                expectedFunctions, project, variant);
    }

    private static void validateNoIncomingEdges(
            Map<String, byte[]> classes, Set<String> generatedOwners,
            String project, String variant) {
        for (var entry : classes.entrySet()) {
            var owner = entry.getKey().replace('.', '/');
            if (generatedOwners.contains(owner)) continue;
            new ClassReader(entry.getValue()).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(
                        int access, String name, String descriptor, String signature,
                        String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        private void check(String targetOwner) {
                            if (generatedOwners.contains(targetOwner)) {
                                throw failure(project, variant,
                                        entry.getKey() + "#" + name + " -> "
                                                + targetOwner.replace('/', '.'),
                                        "Consumer bytecode has an incoming edge to generated Compose code",
                                        "Remove every ordinary call or exact generated-symbol reference");
                            }
                        }

                        @Override public void visitMethodInsn(
                                int opcode, String targetOwner, String methodName,
                                String methodDescriptor, boolean isInterface) {
                            check(targetOwner);
                        }

                        @Override public void visitFieldInsn(
                                int opcode, String targetOwner, String fieldName,
                                String fieldDescriptor) {
                            check(targetOwner);
                        }

                        @Override public void visitTypeInsn(int opcode, String type) { check(type); }

                        @Override public void visitInvokeDynamicInsn(
                                String invokedName, String invokedDescriptor,
                                Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
                            for (var argument : bootstrapMethodArguments) {
                                if (argument instanceof Handle handle) check(handle.getOwner());
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
    }

    private static void validateAcyclic(
            Map<String, Set<String>> edges, String project, String variant) {
        var active = new HashSet<String>();
        var complete = new HashSet<String>();
        for (var node : edges.keySet()) visit(node, edges, active, complete, project, variant);
    }

    private static void visit(
            String node, Map<String, Set<String>> edges, Set<String> active,
            Set<String> complete, String project, String variant) {
        if (complete.contains(node)) return;
        if (!active.add(node)) {
            throw failure(project, variant, node,
                    "Generated Compose call graph contains a cycle",
                    "Regenerate the fixed forward-only acyclic graph");
        }
        for (var target : edges.getOrDefault(node, Set.of())) {
            visit(target, edges, active, complete, project, variant);
        }
        active.remove(node);
        complete.add(node);
    }

    static String inventoryText(Result result, Map<String, String> mapping) {
        var output = new StringBuilder("schema=").append(SCHEMA).append('\n')
                .append("enabled=").append(!result.methods().isEmpty()).append('\n')
                .append("facades=").append(result.methods().stream()
                        .map(MethodIdentity::facade).distinct().count()).append('\n')
                .append("functions=").append(result.methods().size()).append('\n');
        result.methods().stream().map(MethodIdentity::facade).distinct().sorted().forEach(facade ->
                output.append("facade=").append(facade).append('|')
                        .append(mapping.getOrDefault(facade, facade)).append('\n'));
        result.methods().forEach(method -> output.append("method=")
                .append(method.facade()).append('#').append(method.name())
                .append('|').append(method.descriptor()).append('|')
                .append(method.graphIndex()).append('\n'));
        return output.toString();
    }

    static String keepRules(Result result, Map<String, String> mapping) {
        var output = new StringBuilder("# Kaleido Compose exact retention v1\n");
        result.methods().stream().map(MethodIdentity::facade).distinct().sorted().forEach(facade ->
                output.append("-keep,allowoptimization,allowobfuscation class ")
                        .append(mapping.getOrDefault(facade, facade)).append(" { *; }\n"));
        return output.toString();
    }

    private static org.gradle.api.GradleException failure(
            String project, String variant, String target, String reason, String repair) {
        return new KaleidoDiagnostic("KLD-COMPOSE-001", project, variant,
                "compose-generation", SCHEMA, target, reason, repair).failure();
    }

    record MethodIdentity(String facade, String name, String descriptor, int graphIndex) {}
    record Result(List<MethodIdentity> methods, Map<String, Set<String>> edges) {}

    private static class ReferenceVisitor extends MethodVisitor {
        private final String facade;
        private final String currentMethod;
        private final Set<String> generatedOwners;
        private final String project;
        private final String variant;

        ReferenceVisitor(
                String facade, String currentMethod, Set<String> generatedOwners,
                Map<String, ComposeGenerationEngine.FunctionIdentity> expectedFunctions,
                String project, String variant) {
            super(Opcodes.ASM9);
            this.facade = facade;
            this.currentMethod = currentMethod;
            this.generatedOwners = generatedOwners;
            this.project = project;
            this.variant = variant;
        }

        void generatedCall(String owner, String name) {}

        private void checkOwner(String owner) {
            if (generatedOwners.contains(owner)) return;
            var dotted = owner.replace('/', '.');
            var allowed = dotted.startsWith("androidx.compose.runtime.")
                    || dotted.startsWith("java.lang.")
                    || dotted.startsWith("kotlin.");
            if (!allowed) {
                throw failure(project, variant, facade + "#" + currentMethod + " -> " + dotted,
                        "Generated Compose bytecode references a forbidden API or Consumer symbol",
                        "Use only Compose Runtime, pure computation, and generated-to-generated calls");
            }
        }

        @Override public void visitMethodInsn(
                int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (generatedOwners.contains(owner)) generatedCall(owner, name);
            else checkOwner(owner);
            for (var type : Type.getArgumentTypes(descriptor)) checkType(type);
            checkType(Type.getReturnType(descriptor));
        }

        @Override public void visitFieldInsn(
                int opcode, String owner, String name, String descriptor) {
            checkOwner(owner);
            checkType(Type.getType(descriptor));
        }

        @Override public void visitTypeInsn(int opcode, String type) { checkOwner(type); }

        private void checkType(Type type) {
            if (type.getSort() == Type.ARRAY) checkType(type.getElementType());
            else if (type.getSort() == Type.OBJECT) checkOwner(type.getInternalName());
        }
    }
}
