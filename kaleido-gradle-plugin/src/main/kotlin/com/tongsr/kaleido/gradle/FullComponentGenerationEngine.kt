package com.tongsr.kaleido.gradle

import java.util.TreeSet

internal object FullComponentGenerationEngine {
    const val SCHEMA: String = "FullComponentGeneration.v1"

    @JvmStatic
    fun plan(
        adoptionPlan: Map<String, String>,
        consumerClasses: Set<String>,
        ordinaryGeneratedClasses: Set<String>,
    ): Result {
        val count = integer(adoptionPlan, "generation.activityCount")
        if (count == 0) {
            return Result(emptyMap(), emptyList(), emptyManifest())
        }
        if (required(adoptionPlan, "profile") != "FULL") {
            throw failure(
                adoptionPlan,
                "generation.activityCount",
                "Android component generation is available only in Full Profile",
                "Select FULL explicitly or keep activityCount at zero",
            )
        }

        val packageName = required(adoptionPlan, "generation.packageBase") + ".components"
        val stream = required(adoptionPlan, "seed.domain.generation-ordinary")
        val files = LinkedHashMap<String, String>()
        val activities = TreeSet<String>()
        for (index in 0 until count) {
            val className = "A_" + token(stream, "activity", index, 12)
            val identity = "$packageName.$className"
            if (identity in consumerClasses ||
                identity in ordinaryGeneratedClasses ||
                !activities.add(identity)
            ) {
                throw failure(
                    adoptionPlan,
                    identity,
                    "Generated Activity identity collides with an existing class",
                    "Change the seed or generation package and rebuild",
                )
            }
            files["${packageName.replace('.', '/')}/$className.kt"] = """
                package $packageName

                class $className : android.app.Activity()

            """.trimIndent() + "\n"
        }
        val result = Result(files.toMap(), activities.toList(), manifest(activities))
        validateContract(adoptionPlan, result)
        return result
    }

    @JvmStatic
    fun validateContract(adoptionPlan: Map<String, String>, result: Result) {
        val expected = integer(adoptionPlan, "generation.activityCount")
        if (result.activities.size != expected ||
            result.kotlinFiles.size != expected ||
            result.activities.distinct().size != expected
        ) {
            throw failure(
                adoptionPlan,
                "generation.activityCount",
                "Generated component inventory is incomplete or duplicated",
                "Regenerate the complete deterministic component inventory",
            )
        }
        for (activity in result.activities) {
            val path = activity.replace('.', '/') + ".kt"
            val source = result.kotlinFiles[path]
            val simpleName = activity.substring(activity.lastIndexOf('.') + 1)
            if (source == null ||
                !source.contains("class $simpleName : android.app.Activity()") ||
                "android.content.Intent" in source ||
                "android.net." in source ||
                "java.net." in source ||
                "android.util.Log" in source
            ) {
                throw failure(
                    adoptionPlan,
                    activity,
                    "Generated Activity source violates the inert component contract",
                    "Generate only an empty public Activity with no Consumer or I/O references",
                )
            }
            val declaration =
                "<activity android:name=\"$activity\" android:exported=\"false\" />"
            if (declaration !in result.manifest) {
                throw failure(
                    adoptionPlan,
                    activity,
                    "Generated Activity is missing its exact inert Manifest declaration",
                    "Regenerate matching class and Manifest inventories",
                )
            }
        }
        val forbidden = listOf(
            "<uses-permission",
            "<intent-filter",
            "<service",
            "<receiver",
            "<provider",
            "android:exported=\"true\"",
            "android:permission=",
            "android:process=",
            "android:enabled=\"true\"",
            "android:authorities=",
        )
        val violation = forbidden.firstOrNull { it in result.manifest }
        if (violation != null) {
            throw failure(
                adoptionPlan,
                violation,
                "Generated Manifest violates the inert component contract",
                "Remove permissions, entry points, startup hooks, and unsupported attributes",
            )
        }
        val declarations = result.manifest.split("<activity ").size - 1
        if (declarations != expected) {
            throw failure(
                adoptionPlan,
                "AndroidManifest.xml",
                "Generated Manifest component declarations do not match the inventory",
                "Regenerate the complete deterministic component Manifest",
            )
        }
    }

    private fun manifest(activities: Set<String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n")
        append("    <application>\n")
        activities.forEach { activity ->
            append("        <activity android:name=\"")
            append(activity)
            append("\" android:exported=\"false\" />\n")
        }
        append("    </application>\n")
        append("</manifest>\n")
    }

    private fun emptyManifest(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <application />
        </manifest>
    """.trimIndent() + "\n"

    private fun integer(plan: Map<String, String>, key: String): Int =
        required(plan, key).toInt()

    private fun required(plan: Map<String, String>, key: String): String {
        val value = plan[key]
        if (value.isNullOrBlank()) {
            throw failure(
                plan,
                key,
                "Adoption Plan is missing a required component-generation value",
                "Regenerate a complete AdoptionPlan.v1",
            )
        }
        return value
    }

    private fun token(stream: String, domain: String, identity: Any, length: Int): String =
        SeedDerivation.derive(stream, domain, identity.toString()).substring(0, length)

    private fun failure(
        plan: Map<String, String>,
        target: String,
        reason: String,
        repair: String,
    ): Nothing {
        throw KaleidoDiagnostic(
            "KLD-COMPONENT-001",
            plan.getOrDefault("project", "<consumer>"),
            plan.getOrDefault("variant.name", "<variant>"),
            "component-generation",
            SCHEMA,
            target,
            reason,
            repair,
        ).failure()
    }

    @JvmRecord
    data class Result(
        val kotlinFiles: Map<String, String>,
        val activities: List<String>,
        val manifest: String,
    )
}
