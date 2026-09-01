package com.tongsr.kaleido.gradle

import java.util.Locale
import java.util.TreeSet

internal object SafeGenerationEngine {
    @JvmStatic
    fun plan(
        adoptionPlan: Map<String, String>,
        consumerResources: Set<GenerateSafeContentTask.ResourceIdentity>,
    ): GeneratedContent = plan(adoptionPlan, consumerResources, emptySet())

    @JvmStatic
    fun plan(
        adoptionPlan: Map<String, String>,
        consumerResources: Set<GenerateSafeContentTask.ResourceIdentity>,
        consumerClasses: Set<String>,
    ): GeneratedContent {
        val packageBase = required(adoptionPlan, "generation.packageBase")
        val packageCount = integer(adoptionPlan, "generation.packageCount")
        val classesPerPackage = integer(adoptionPlan, "generation.classesPerPackage")
        val methodsPerClass = integer(adoptionPlan, "generation.methodsPerClass")
        val layoutCount = integer(adoptionPlan, "generation.layoutCount")
        val drawableCount = integer(adoptionPlan, "generation.drawableCount")
        val stringCount = integer(adoptionPlan, "generation.stringCount")
        val prefix = required(adoptionPlan, "resources.prefix")
        val stream = required(adoptionPlan, "seed.domain.generation-ordinary")

        val kotlinFiles = LinkedHashMap<String, String>()
        val generatedClasses = TreeSet<String>()
        var methodTotal = 0
        for (packageIndex in 0 until packageCount) {
            val packageName = packageBase + ".p_" + token(stream, "package", packageIndex, 8)
            for (classIndex in 0 until classesPerPackage) {
                val identity = "$packageIndex:$classIndex"
                val className = "C_" + token(stream, "class", identity, 10)
                generatedClasses.add("$packageName.$className")
                val source = StringBuilder()
                    .append("package ").append(packageName).append("\n\n")
                    .append("internal class ").append(className)
                    .append(" private constructor() {\n")
                for (methodIndex in 0 until methodsPerClass) {
                    val methodName = "m_" + token(stream, "method", "$identity:$methodIndex", 10)
                    val constant = token(stream, "constant", "$identity:$methodIndex", 8)
                    source.append("    fun ").append(methodName)
                        .append("(value: Int): Int {\n")
                        .append("        return Integer.rotateLeft(value xor 0x")
                        .append(constant).append(".toInt(), ")
                        .append((methodIndex % 31) + 1).append(")\n")
                        .append("    }\n\n")
                    methodTotal++
                }
                source.append("}\n")
                kotlinFiles["${packageName.replace('.', '/')}/$className.kt"] = source.toString()
            }
        }

        val resourceFiles = LinkedHashMap<String, String>()
        val generatedResources = TreeSet(
            compareBy(GenerateSafeContentTask.ResourceIdentity::type)
                .thenBy(GenerateSafeContentTask.ResourceIdentity::name),
        )
        for (index in 0 until layoutCount) {
            val name = prefix + "layout_" + token(stream, "layout", index, 8)
            addResource(adoptionPlan, consumerResources, generatedResources, "layout", name)
            resourceFiles["layout/$name.xml"] = """
                <?xml version="1.0" encoding="utf-8"?>
                <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent" />
            """.trimIndent() + "\n"
        }
        for (index in 0 until drawableCount) {
            val name = prefix + "drawable_" + token(stream, "drawable", index, 8)
            addResource(adoptionPlan, consumerResources, generatedResources, "drawable", name)
            val color = token(stream, "color", index, 6).uppercase(Locale.ROOT)
            resourceFiles["drawable/$name.xml"] = """
                <?xml version="1.0" encoding="utf-8"?>
                <shape xmlns:android="http://schemas.android.com/apk/res/android"
                    android:shape="rectangle">
                    <solid android:color="#$color" />
                </shape>
            """.trimIndent() + "\n"
        }
        val strings = StringBuilder("<resources>\n")
        for (index in 0 until stringCount) {
            val name = prefix + "string_" + token(stream, "string-name", index, 8)
            addResource(adoptionPlan, consumerResources, generatedResources, "string", name)
            strings.append("    <string name=\"").append(name).append("\">kld_")
                .append(token(stream, "string-value", index, 16)).append("</string>\n")
        }
        strings.append("</resources>\n")
        resourceFiles["values/strings.xml"] = strings.toString()

        val components = FullComponentGenerationEngine.plan(
            adoptionPlan,
            consumerClasses,
            generatedClasses,
        )
        kotlinFiles.putAll(components.kotlinFiles)
        val keepRules = "-keep,allowoptimization,allowobfuscation class " +
            packageBase + ".** { *; }\n"
        return GeneratedContent(
            kotlinFiles = kotlinFiles.toMap(),
            resourceFiles = resourceFiles.toMap(),
            manifest = components.manifest,
            keepRules = keepRules,
            classCount = generatedClasses.size + components.activities.size,
            methodCount = methodTotal,
            layoutCount = layoutCount,
            drawableCount = drawableCount,
            stringCount = stringCount,
            activities = components.activities,
        )
    }

    private fun addResource(
        plan: Map<String, String>,
        consumerResources: Set<GenerateSafeContentTask.ResourceIdentity>,
        generatedResources: MutableSet<GenerateSafeContentTask.ResourceIdentity>,
        type: String,
        name: String,
    ) {
        val identity = GenerateSafeContentTask.ResourceIdentity(type, name)
        if (identity in consumerResources || !generatedResources.add(identity)) {
            throw generationFailure(
                plan,
                "$type/$name",
                "Generated resource identity collides with an existing identity",
                "Change the seed or generation package and rebuild",
            )
        }
    }

    private fun integer(plan: Map<String, String>, key: String): Int =
        required(plan, key).toInt()

    private fun required(plan: Map<String, String>, key: String): String =
        plan[key] ?: throw generationFailure(
            plan,
            key,
            "Adoption Plan is missing a required generation value",
            "Regenerate a complete AdoptionPlan.v1",
        )

    private fun token(stream: String, domain: String, identity: Any, length: Int): String =
        SeedDerivation.derive(stream, domain, identity.toString()).substring(0, length)

    private fun generationFailure(
        plan: Map<String, String>,
        target: String,
        reason: String,
        repair: String,
    ): Nothing {
        throw KaleidoDiagnostic(
            "KLD-GENERATION-001",
            plan.getOrDefault("project", "<consumer>"),
            plan.getOrDefault("variant.name", "<variant>"),
            "generation",
            "AdoptionPlan.v1",
            target,
            reason,
            repair,
        ).failure()
    }

    @JvmRecord
    data class GeneratedContent(
        val kotlinFiles: Map<String, String>,
        val resourceFiles: Map<String, String>,
        val manifest: String,
        val keepRules: String,
        val classCount: Int,
        val methodCount: Int,
        val layoutCount: Int,
        val drawableCount: Int,
        val stringCount: Int,
        val activities: List<String>,
    )
}
