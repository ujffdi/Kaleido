package com.tongsr.kaleido.gradle

import java.nio.charset.StandardCharsets
import java.util.TreeMap
import java.util.TreeSet
import org.gradle.api.GradleException
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

internal object ComposeCompiledValidator {
    const val SCHEMA: String = "ComposeCompiledInventory.v1"

    @JvmStatic
    fun validate(
        adoption: Map<String, String>,
        classes: Map<String, ByteArray>,
        manifest: ByteArray,
        project: String,
        variant: String,
    ): Result {
        val expected = ComposeGenerationEngine.plan(adoption)
        if (expected.facades.isEmpty()) return Result(emptyList(), emptyMap())
        val facadeInternalNames = expected.facades.map { it.replace('.', '/') }.toSet()
        val expectedFunctions = expected.functions.associateBy { "${it.facade}#${it.name}" }
        val methods = TreeMap<String, MethodIdentity>()
        val edges = TreeMap<String, MutableSet<String>>()

        for (facade in expected.facades) {
            val bytes = classes[facade] ?: throw failure(
                project,
                variant,
                facade,
                "Generated Compose facade is absent from compiled PROJECT classes",
                "Enable built-in Kotlin and rebuild the generated Kotlin directory",
            )
            validateGeneratedClass(
                bytes,
                facade,
                facadeInternalNames,
                expectedFunctions,
                methods,
                edges,
                project,
                variant,
            )
            if (String(manifest, StandardCharsets.UTF_8).contains(facade)) {
                throw failure(
                    project,
                    variant,
                    facade,
                    "Generated Compose facade appears in the merged Manifest",
                    "Remove every Android component or startup reference to generated Compose code",
                )
            }
        }
        if (methods.keys != expectedFunctions.keys) {
            val missing = TreeSet(expectedFunctions.keys)
            missing.removeAll(methods.keys)
            throw failure(
                project,
                variant,
                missing.joinToString(","),
                "Compiled Composer-lowered function inventory is incomplete",
                "Use the matching Compose compiler and Runtime versions",
            )
        }
        validateNoIncomingEdges(classes, facadeInternalNames, project, variant)
        validateAcyclic(edges, project, variant)
        return Result(
            methods.values.sortedWith(compareBy(MethodIdentity::facade, MethodIdentity::name)),
            edges.toMap(),
        )
    }

    private fun validateGeneratedClass(
        bytes: ByteArray,
        facade: String,
        generatedOwners: Set<String>,
        expectedFunctions: Map<String, ComposeGenerationEngine.FunctionIdentity>,
        methods: MutableMap<String, MethodIdentity>,
        edges: MutableMap<String, MutableSet<String>>,
        project: String,
        variant: String,
    ) {
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(
                    descriptor: String,
                    visible: Boolean,
                ): AnnotationVisitor? {
                    if (descriptor.contains("/Preview;")) {
                        throw failure(
                            project,
                            variant,
                            facade,
                            "Generated Compose bytecode contains a Preview surface",
                            "Generate Runtime-only composables without Preview annotations",
                        )
                    }
                    return null
                }

                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    val key = "$facade#$name"
                    val expected = expectedFunctions[key]
                    if (expected == null) {
                        return forbiddenReferenceVisitor(
                            facade,
                            null,
                            generatedOwners,
                            emptyMap(),
                            project,
                            variant,
                        )
                    }
                    if ((access and Opcodes.ACC_STATIC) == 0 ||
                        !descriptor.contains("Landroidx/compose/runtime/Composer;")
                    ) {
                        throw failure(
                            project,
                            variant,
                            key,
                            "Generated function lacks the compiled Composer-lowered signature",
                            "Apply the matching Compose compiler plugin to this module",
                        )
                    }
                    if (methods.putIfAbsent(
                            key,
                            MethodIdentity(facade, name, descriptor, expected.graphIndex),
                        ) != null
                    ) {
                        throw failure(
                            project,
                            variant,
                            key,
                            "Generated function has ambiguous compiled overloads",
                            "Regenerate the fixed one-method-per-function Compose graph",
                        )
                    }
                    edges[key] = TreeSet()
                    return object : ReferenceVisitor(
                        facade,
                        key,
                        generatedOwners,
                        expectedFunctions,
                        project,
                        variant,
                    ) {
                        override fun generatedCall(calledOwner: String, calledName: String) {
                            val target = "${calledOwner.replace('/', '.')}#$calledName"
                            if (target !in expectedFunctions) {
                                throw failure(
                                    project,
                                    variant,
                                    target,
                                    "Generated Compose call targets an uninventoried member",
                                    "Regenerate the closed generated-only call graph",
                                )
                            }
                            edges.getValue(key).add(target)
                        }
                    }
                }
            },
            ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
    }

    private fun forbiddenReferenceVisitor(
        facade: String,
        currentMethod: String?,
        generatedOwners: Set<String>,
        expectedFunctions: Map<String, ComposeGenerationEngine.FunctionIdentity>,
        project: String,
        variant: String,
    ): ReferenceVisitor =
        ReferenceVisitor(
            facade,
            currentMethod,
            generatedOwners,
            expectedFunctions,
            project,
            variant,
        )

    private fun validateNoIncomingEdges(
        classes: Map<String, ByteArray>,
        generatedOwners: Set<String>,
        project: String,
        variant: String,
    ) {
        for ((className, classBytes) in classes) {
            val owner = className.replace('.', '/')
            if (owner in generatedOwners) continue
            ClassReader(classBytes).accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor {
                        return object : MethodVisitor(Opcodes.ASM9) {
                            fun check(targetOwner: String) {
                                if (targetOwner in generatedOwners) {
                                    throw failure(
                                        project,
                                        variant,
                                        "$className#$name -> ${targetOwner.replace('/', '.')}",
                                        "Consumer bytecode has an incoming edge to generated Compose code",
                                        "Remove every ordinary call or exact generated-symbol reference",
                                    )
                                }
                            }

                            override fun visitMethodInsn(
                                opcode: Int,
                                targetOwner: String,
                                methodName: String,
                                methodDescriptor: String,
                                isInterface: Boolean,
                            ) {
                                check(targetOwner)
                            }

                            override fun visitFieldInsn(
                                opcode: Int,
                                targetOwner: String,
                                fieldName: String,
                                fieldDescriptor: String,
                            ) {
                                check(targetOwner)
                            }

                            override fun visitTypeInsn(opcode: Int, type: String) {
                                check(type)
                            }

                            override fun visitInvokeDynamicInsn(
                                invokedName: String,
                                invokedDescriptor: String,
                                bootstrapMethodHandle: Handle,
                                vararg bootstrapMethodArguments: Any?,
                            ) {
                                for (argument in bootstrapMethodArguments) {
                                    if (argument is Handle) check(argument.owner)
                                }
                            }
                        }
                    }
                },
                ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
            )
        }
    }

    private fun validateAcyclic(
        edges: Map<String, Set<String>>,
        project: String,
        variant: String,
    ) {
        val active = mutableSetOf<String>()
        val complete = mutableSetOf<String>()
        for (node in edges.keys) visit(node, edges, active, complete, project, variant)
    }

    private fun visit(
        node: String,
        edges: Map<String, Set<String>>,
        active: MutableSet<String>,
        complete: MutableSet<String>,
        project: String,
        variant: String,
    ) {
        if (node in complete) return
        if (!active.add(node)) {
            throw failure(
                project,
                variant,
                node,
                "Generated Compose call graph contains a cycle",
                "Regenerate the fixed forward-only acyclic graph",
            )
        }
        for (target in edges[node].orEmpty()) {
            visit(target, edges, active, complete, project, variant)
        }
        active.remove(node)
        complete.add(node)
    }

    @JvmStatic
    fun inventoryText(result: Result, mapping: Map<String, String>): String {
        val output = StringBuilder("schema=").append(SCHEMA).append('\n')
            .append("enabled=").append(result.methods.isNotEmpty()).append('\n')
            .append("facades=").append(result.methods.map { it.facade }.distinct().count())
            .append('\n')
            .append("functions=").append(result.methods.size).append('\n')
        result.methods.map { it.facade }.distinct().sorted().forEach { facade ->
            output.append("facade=").append(facade).append('|')
                .append(mapping[facade] ?: facade).append('\n')
        }
        result.methods.forEach { method ->
            output.append("method=")
                .append(method.facade).append('#').append(method.name)
                .append('|').append(method.descriptor).append('|')
                .append(method.graphIndex).append('\n')
        }
        return output.toString()
    }

    @JvmStatic
    fun keepRules(result: Result, mapping: Map<String, String>): String {
        val output = StringBuilder("# Kaleido Compose exact retention v1\n")
        result.methods.map { it.facade }.distinct().sorted().forEach { facade ->
            output.append("-keep,allowoptimization,allowobfuscation class ")
                .append(mapping[facade] ?: facade).append(" { *; }\n")
        }
        return output.toString()
    }

    private fun failure(
        project: String,
        variant: String,
        target: String,
        reason: String,
        repair: String,
    ): GradleException =
        KaleidoDiagnostic(
            "KLD-COMPOSE-001",
            project,
            variant,
            "compose-generation",
            SCHEMA,
            target,
            reason,
            repair,
        ).failure()

    @JvmRecord
    data class MethodIdentity(
        val facade: String,
        val name: String,
        val descriptor: String,
        val graphIndex: Int,
    )

    @JvmRecord
    data class Result(
        val methods: List<MethodIdentity>,
        val edges: Map<String, Set<String>>,
    )

    private open class ReferenceVisitor(
        private val facade: String,
        private val currentMethod: String?,
        private val generatedOwners: Set<String>,
        @Suppress("UNUSED_PARAMETER")
        expectedFunctions: Map<String, ComposeGenerationEngine.FunctionIdentity>,
        private val project: String,
        private val variant: String,
    ) : MethodVisitor(Opcodes.ASM9) {
        open fun generatedCall(owner: String, name: String) {}

        private fun checkOwner(owner: String) {
            if (owner in generatedOwners) return
            val dotted = owner.replace('/', '.')
            val allowed = dotted.startsWith("androidx.compose.runtime.") ||
                dotted.startsWith("java.lang.") ||
                dotted.startsWith("kotlin.")
            if (!allowed) {
                throw failure(
                    project,
                    variant,
                    "$facade#$currentMethod -> $dotted",
                    "Generated Compose bytecode references a forbidden API or Consumer symbol",
                    "Use only Compose Runtime, pure computation, and generated-to-generated calls",
                )
            }
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean,
        ) {
            if (owner in generatedOwners) generatedCall(owner, name)
            else checkOwner(owner)
            for (type in Type.getArgumentTypes(descriptor)) checkType(type)
            checkType(Type.getReturnType(descriptor))
        }

        override fun visitFieldInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
        ) {
            checkOwner(owner)
            checkType(Type.getType(descriptor))
        }

        override fun visitTypeInsn(opcode: Int, type: String) {
            checkOwner(type)
        }

        private fun checkType(type: Type) {
            if (type.sort == Type.ARRAY) checkType(type.elementType)
            else if (type.sort == Type.OBJECT) checkOwner(type.internalName)
        }
    }
}
