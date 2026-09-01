package com.tongsr.kaleido.gradle

import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.gradle.api.GradleException

internal object ClassRewriteArtifacts {
    const val PLAN_SCHEMA: String = "ClassRewritePlan.v1"
    const val RECEIPT_SCHEMA: String = "TransformReceipt.v1"
    const val PRODUCER: String = "KaleidoClassRewrite/1"

    @JvmStatic
    fun encodePlan(plan: Plan): ByteArray = message { output ->
        output.writeString(1, plan.schema)
        output.writeString(2, plan.producer)
        output.writeString(3, plan.project)
        output.writeString(4, plan.variant)
        output.writeString(5, plan.adoptionPlanSha256)
        output.writeString(6, plan.manifestSha256)
        for (input in plan.inputs) {
            output.writeByteArray(7, message { nested ->
                nested.writeString(1, input.origin)
                nested.writeString(2, input.sha256)
            })
        }
        for (decision in plan.decisions) {
            output.writeByteArray(8, message { nested ->
                nested.writeString(1, decision.original)
                nested.writeString(2, decision.origin)
                nested.writeString(3, decision.inputSha256)
                nested.writeString(4, decision.action)
                nested.writeString(5, decision.target)
                nested.writeString(6, decision.reason)
            })
        }
        for (site in plan.manifestSites) {
            output.writeByteArray(9, message { nested ->
                nested.writeString(1, site.location)
                nested.writeString(2, site.original)
                nested.writeString(3, site.target)
            })
        }
        for (outputIdentity in plan.expectedOutputs) {
            output.writeString(10, outputIdentity)
        }
    }

    @JvmStatic
    fun decodePlan(bytes: ByteArray, project: String, variant: String): Plan {
        val input = CodedInputStream.newInstance(bytes)
        var schema = ""
        var producer = ""
        var encodedProject = ""
        var encodedVariant = ""
        var adoptionPlanSha256 = ""
        var manifestSha256 = ""
        val inputs = ArrayList<InputArtifact>()
        val decisions = ArrayList<ClassDecision>()
        val sites = ArrayList<ManifestSite>()
        val outputs = ArrayList<String>()
        while (!input.isAtEnd) {
            when (input.readTag()) {
                10 -> schema = input.readStringRequireUtf8()
                18 -> producer = input.readStringRequireUtf8()
                26 -> encodedProject = input.readStringRequireUtf8()
                34 -> encodedVariant = input.readStringRequireUtf8()
                42 -> adoptionPlanSha256 = input.readStringRequireUtf8()
                50 -> manifestSha256 = input.readStringRequireUtf8()
                58 -> inputs.add(decodeInput(input.readByteArray()))
                66 -> decisions.add(decodeDecision(input.readByteArray()))
                74 -> sites.add(decodeSite(input.readByteArray()))
                82 -> outputs.add(input.readStringRequireUtf8())
                0 -> {}
                else -> input.skipField(input.lastTag)
            }
        }
        if (schema != PLAN_SCHEMA) {
            throw failure(
                project,
                variant,
                "schema",
                "Unknown Class Rewrite Plan major: $schema",
                "Regenerate the plan with this Kaleido version",
            )
        }
        return Plan(
            schema,
            producer,
            encodedProject,
            encodedVariant,
            adoptionPlanSha256,
            manifestSha256,
            inputs,
            decisions,
            sites,
            outputs,
        )
    }

    @JvmStatic
    fun encodeReceipt(receipt: Receipt): ByteArray = message { output ->
        output.writeString(1, receipt.schema)
        output.writeString(2, receipt.producer)
        output.writeString(3, receipt.project)
        output.writeString(4, receipt.variant)
        output.writeString(5, receipt.planSha256)
        output.writeString(6, receipt.outputJarSha256)
        output.writeString(7, receipt.outputManifestSha256)
        output.writeUInt32(8, receipt.appliedMappings)
        output.writeBool(9, receipt.inputDigestsRechecked)
        output.writeBool(10, receipt.outputClosureValidated)
        for (inputDigest in receipt.inputDigests) {
            output.writeString(11, inputDigest)
        }
    }

    private fun decodeInput(bytes: ByteArray): InputArtifact {
        val input = CodedInputStream.newInstance(bytes)
        var origin = ""
        var sha = ""
        while (!input.isAtEnd) {
            when (input.readTag()) {
                10 -> origin = input.readStringRequireUtf8()
                18 -> sha = input.readStringRequireUtf8()
                0 -> {}
                else -> input.skipField(input.lastTag)
            }
        }
        return InputArtifact(origin, sha)
    }

    private fun decodeDecision(bytes: ByteArray): ClassDecision {
        val input = CodedInputStream.newInstance(bytes)
        val values = arrayOf("", "", "", "", "", "")
        while (!input.isAtEnd) {
            val tag = input.readTag()
            if (tag in 10..50 && tag % 8 == 2) {
                values[(tag - 10) / 8] = input.readStringRequireUtf8()
            } else if (tag != 0) {
                input.skipField(tag)
            }
        }
        return ClassDecision(values[0], values[1], values[2], values[3], values[4], values[5])
    }

    private fun decodeSite(bytes: ByteArray): ManifestSite {
        val input = CodedInputStream.newInstance(bytes)
        val values = arrayOf("", "", "")
        while (!input.isAtEnd) {
            val tag = input.readTag()
            if (tag in 10..26 && tag % 8 == 2) {
                values[(tag - 10) / 8] = input.readStringRequireUtf8()
            } else if (tag != 0) {
                input.skipField(tag)
            }
        }
        return ManifestSite(values[0], values[1], values[2])
    }

    private fun message(writer: Writer): ByteArray {
        val bytes = ByteArrayOutputStream()
        val output = CodedOutputStream.newInstance(bytes)
        writer.write(output)
        output.flush()
        return bytes.toByteArray()
    }

    private fun failure(
        project: String,
        variant: String,
        target: String,
        reason: String,
        repair: String,
    ): GradleException = KaleidoDiagnostic(
        "KLD-CLASS-001",
        project,
        variant,
        "class-rewrite",
        PLAN_SCHEMA,
        target,
        reason,
        repair,
    ).failure()

    private fun interface Writer {
        @Throws(IOException::class)
        fun write(output: CodedOutputStream)
    }

    class Plan(
        @get:JvmName("schema") val schema: String,
        @get:JvmName("producer") val producer: String,
        @get:JvmName("project") val project: String,
        @get:JvmName("variant") val variant: String,
        @get:JvmName("adoptionPlanSha256") val adoptionPlanSha256: String,
        @get:JvmName("manifestSha256") val manifestSha256: String,
        inputs: List<InputArtifact>,
        decisions: List<ClassDecision>,
        manifestSites: List<ManifestSite>,
        expectedOutputs: List<String>,
    ) {
        @get:JvmName("inputs")
        val inputs: List<InputArtifact> = inputs.sortedBy { it.origin }

        @get:JvmName("decisions")
        val decisions: List<ClassDecision> = decisions.sortedBy { it.original }

        @get:JvmName("manifestSites")
        val manifestSites: List<ManifestSite> = manifestSites.sortedBy { it.location }

        @get:JvmName("expectedOutputs")
        val expectedOutputs: List<String> = expectedOutputs.sorted()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Plan) return false
            return schema == other.schema &&
                producer == other.producer &&
                project == other.project &&
                variant == other.variant &&
                adoptionPlanSha256 == other.adoptionPlanSha256 &&
                manifestSha256 == other.manifestSha256 &&
                inputs == other.inputs &&
                decisions == other.decisions &&
                manifestSites == other.manifestSites &&
                expectedOutputs == other.expectedOutputs
        }

        override fun hashCode(): Int = listOf(
            schema,
            producer,
            project,
            variant,
            adoptionPlanSha256,
            manifestSha256,
            inputs,
            decisions,
            manifestSites,
            expectedOutputs,
        ).hashCode()

        override fun toString(): String =
            "Plan(schema=$schema, producer=$producer, project=$project, variant=$variant, " +
                "adoptionPlanSha256=$adoptionPlanSha256, manifestSha256=$manifestSha256, " +
                "inputs=$inputs, decisions=$decisions, manifestSites=$manifestSites, " +
                "expectedOutputs=$expectedOutputs)"
    }

    @JvmRecord
    data class InputArtifact(val origin: String, val sha256: String)

    @JvmRecord
    data class ClassDecision(
        val original: String,
        val origin: String,
        val inputSha256: String,
        val action: String,
        val target: String,
        val reason: String,
    )

    @JvmRecord
    data class ManifestSite(val location: String, val original: String, val target: String)

    class Receipt(
        @get:JvmName("schema") val schema: String,
        @get:JvmName("producer") val producer: String,
        @get:JvmName("project") val project: String,
        @get:JvmName("variant") val variant: String,
        @get:JvmName("planSha256") val planSha256: String,
        @get:JvmName("outputJarSha256") val outputJarSha256: String,
        @get:JvmName("outputManifestSha256") val outputManifestSha256: String,
        @get:JvmName("appliedMappings") val appliedMappings: Int,
        @get:JvmName("inputDigestsRechecked") val inputDigestsRechecked: Boolean,
        @get:JvmName("outputClosureValidated") val outputClosureValidated: Boolean,
        inputDigests: List<String>,
    ) {
        @get:JvmName("inputDigests")
        val inputDigests: List<String> = inputDigests.sorted()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Receipt) return false
            return schema == other.schema &&
                producer == other.producer &&
                project == other.project &&
                variant == other.variant &&
                planSha256 == other.planSha256 &&
                outputJarSha256 == other.outputJarSha256 &&
                outputManifestSha256 == other.outputManifestSha256 &&
                appliedMappings == other.appliedMappings &&
                inputDigestsRechecked == other.inputDigestsRechecked &&
                outputClosureValidated == other.outputClosureValidated &&
                inputDigests == other.inputDigests
        }

        override fun hashCode(): Int = listOf(
            schema,
            producer,
            project,
            variant,
            planSha256,
            outputJarSha256,
            outputManifestSha256,
            appliedMappings,
            inputDigestsRechecked,
            outputClosureValidated,
            inputDigests,
        ).hashCode()

        override fun toString(): String =
            "Receipt(schema=$schema, producer=$producer, project=$project, variant=$variant, " +
                "planSha256=$planSha256, outputJarSha256=$outputJarSha256, " +
                "outputManifestSha256=$outputManifestSha256, appliedMappings=$appliedMappings, " +
                "inputDigestsRechecked=$inputDigestsRechecked, " +
                "outputClosureValidated=$outputClosureValidated, inputDigests=$inputDigests)"
    }
}
