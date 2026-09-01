package com.tongsr.kaleido.gradle

import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.gradle.api.GradleException

internal object BundleRewriteArtifacts {
    const val PLAN_SCHEMA: String = "BundleRewritePlan.v1"
    const val RECEIPT_SCHEMA: String = "TransformReceipt.v1"
    const val PRODUCER: String = "KaleidoBundleRewrite/1"

    @JvmStatic
    @Throws(IOException::class)
    fun encodePlan(plan: Plan): ByteArray {
        val resources = plan.resources.sortedWith { left, right ->
            Integer.compareUnsigned(left.id, right.id)
        }
        val entries = plan.entries.sortedBy(EntryDecision::inputPath)
        val expectedOutputs = plan.expectedOutputs.sorted()
        val references = plan.references.sortedWith(
            compareBy(ReferenceDecision::origin)
                .thenBy(ReferenceDecision::fieldPath)
                .thenBy(ReferenceDecision::kind),
        )
        val controls = plan.controls.sortedWith(
            compareBy(ControlDecision::kind).thenBy(ControlDecision::target),
        )
        return message { output ->
            output.writeString(1, plan.schema)
            output.writeString(2, plan.producer)
            output.writeString(3, plan.project)
            output.writeString(4, plan.variant)
            output.writeString(5, plan.inputAabSha256)
            output.writeString(6, plan.resourceTableSha256)
            for (resource in resources) {
                output.writeByteArray(7, message { nested ->
                    nested.writeUInt32(1, resource.id)
                    nested.writeString(2, resource.packageName)
                    nested.writeString(3, resource.type)
                    nested.writeString(4, resource.originalName)
                    nested.writeString(5, resource.targetName)
                    nested.writeString(6, resource.action)
                    nested.writeString(7, resource.reason)
                    for (path in resource.originalPaths) nested.writeString(8, path)
                    for (path in resource.targetPaths) nested.writeString(9, path)
                    nested.writeBool(10, resource.nameProtected)
                    nested.writeBool(11, resource.pathProtected)
                })
            }
            for (entry in entries) {
                output.writeByteArray(8, message { nested ->
                    nested.writeString(1, entry.inputPath)
                    nested.writeString(2, entry.inputSha256)
                    nested.writeUInt32(3, entry.compressionMethod)
                    nested.writeString(4, entry.outputPath)
                    nested.writeString(5, entry.action)
                    nested.writeBool(6, entry.preservePayload)
                })
            }
            for (outputPath in expectedOutputs) output.writeString(9, outputPath)
            for (reference in references) {
                output.writeByteArray(10, message { nested ->
                    nested.writeString(1, reference.origin)
                    nested.writeString(2, reference.fieldPath)
                    nested.writeString(3, reference.kind)
                    nested.writeUInt32(4, reference.resourceId)
                    nested.writeString(5, reference.originalValue)
                    nested.writeString(6, reference.targetValue)
                })
            }
            for (control in controls) {
                output.writeByteArray(12, message { nested ->
                    nested.writeString(1, control.kind)
                    nested.writeString(2, control.target)
                    nested.writeString(3, control.inputSha256)
                    nested.writeString(4, control.outputSha256)
                    nested.writeString(5, control.action)
                    nested.writeString(6, control.reason)
                    nested.writeBool(7, control.protectedTarget)
                })
            }
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun decodePlan(bytes: ByteArray, project: String, variant: String): Plan {
        val input = CodedInputStream.newInstance(bytes)
        val values = arrayOf("", "", "", "", "", "")
        val resources = ArrayList<ResourceDecision>()
        val entries = ArrayList<EntryDecision>()
        val outputs = ArrayList<String>()
        val references = ArrayList<ReferenceDecision>()
        val controls = ArrayList<ControlDecision>()
        while (!input.isAtEnd) {
            val tag = input.readTag()
            when (tag) {
                10, 18, 26, 34, 42, 50 ->
                    values[(tag - 10) / 8] = input.readStringRequireUtf8()
                58 -> resources.add(decodeResource(input.readByteArray()))
                66 -> entries.add(decodeEntry(input.readByteArray()))
                74 -> outputs.add(input.readStringRequireUtf8())
                82 -> references.add(decodeReference(input.readByteArray()))
                98 -> controls.add(decodeControl(input.readByteArray()))
                0 -> { }
                else -> input.skipField(tag)
            }
        }
        if (PLAN_SCHEMA != values[0]) {
            throw failure(
                project,
                variant,
                "schema",
                "Unknown Bundle Rewrite Plan major: " + values[0],
                "Regenerate the plan with this Kaleido version",
            )
        }
        return Plan(
            values[0], values[1], values[2], values[3], values[4], values[5],
            resources, entries, outputs, references, controls,
        )
    }

    @JvmStatic
    @Throws(IOException::class)
    fun encodeReceipt(receipt: Receipt): ByteArray {
        val preservedPayloadDigests = receipt.preservedPayloadDigests.sorted()
        val protectionDigests = receipt.protectionDigests.sorted()
        return message { output ->
            output.writeString(1, receipt.schema)
            output.writeString(2, receipt.producer)
            output.writeString(3, receipt.project)
            output.writeString(4, receipt.variant)
            output.writeString(5, receipt.planSha256)
            output.writeString(6, receipt.inputAabSha256)
            output.writeString(7, receipt.outputAabSha256)
            output.writeString(8, receipt.resourceMappingSha256)
            output.writeUInt32(9, receipt.resourceIdCount)
            output.writeUInt32(10, receipt.renamedResourceCount)
            output.writeUInt32(11, receipt.renamedPathCount)
            output.writeBool(12, receipt.referenceClosureValidated)
            output.writeBool(13, receipt.preservedPayloadsValidated)
            output.writeBool(14, receipt.bundletoolValidated)
            output.writeUInt32(15, receipt.referenceCount)
            for (digest in preservedPayloadDigests) output.writeString(16, digest)
            for (digest in protectionDigests) output.writeString(17, digest)
        }
    }

    @Throws(IOException::class)
    private fun decodeResource(bytes: ByteArray): ResourceDecision {
        val input = CodedInputStream.newInstance(bytes)
        var id = 0
        val values = arrayOf("", "", "", "", "", "")
        val originalPaths = ArrayList<String>()
        val targetPaths = ArrayList<String>()
        var nameProtected = false
        var pathProtected = false
        while (!input.isAtEnd) {
            val tag = input.readTag()
            when (tag) {
                8 -> id = input.readUInt32()
                18, 26, 34, 42, 50, 58 ->
                    values[(tag - 18) / 8] = input.readStringRequireUtf8()
                66 -> originalPaths.add(input.readStringRequireUtf8())
                74 -> targetPaths.add(input.readStringRequireUtf8())
                80 -> nameProtected = input.readBool()
                88 -> pathProtected = input.readBool()
                0 -> { }
                else -> input.skipField(tag)
            }
        }
        return ResourceDecision(
            id, values[0], values[1], values[2], values[3],
            values[4], values[5], originalPaths, targetPaths,
            nameProtected, pathProtected,
        )
    }

    @Throws(IOException::class)
    private fun decodeEntry(bytes: ByteArray): EntryDecision {
        val input = CodedInputStream.newInstance(bytes)
        val values = arrayOf("", "", "", "")
        var method = 0
        var preserve = false
        while (!input.isAtEnd) {
            val tag = input.readTag()
            when (tag) {
                10 -> values[0] = input.readStringRequireUtf8()
                18 -> values[1] = input.readStringRequireUtf8()
                24 -> method = input.readUInt32()
                34 -> values[2] = input.readStringRequireUtf8()
                42 -> values[3] = input.readStringRequireUtf8()
                48 -> preserve = input.readBool()
                0 -> { }
                else -> input.skipField(tag)
            }
        }
        return EntryDecision(values[0], values[1], method, values[2], values[3], preserve)
    }

    @Throws(IOException::class)
    private fun decodeReference(bytes: ByteArray): ReferenceDecision {
        val input = CodedInputStream.newInstance(bytes)
        val values = arrayOf("", "", "", "", "")
        var id = 0
        while (!input.isAtEnd) {
            val tag = input.readTag()
            when (tag) {
                10, 18, 26 -> values[(tag - 10) / 8] = input.readStringRequireUtf8()
                32 -> id = input.readUInt32()
                42 -> values[3] = input.readStringRequireUtf8()
                50 -> values[4] = input.readStringRequireUtf8()
                0 -> { }
                else -> input.skipField(tag)
            }
        }
        return ReferenceDecision(values[0], values[1], values[2], id, values[3], values[4])
    }

    @Throws(IOException::class)
    private fun decodeControl(bytes: ByteArray): ControlDecision {
        val input = CodedInputStream.newInstance(bytes)
        val values = arrayOf("", "", "", "", "", "")
        var protectedTarget = false
        while (!input.isAtEnd) {
            val tag = input.readTag()
            when (tag) {
                10, 18, 26, 34, 42, 50 ->
                    values[(tag - 10) / 8] = input.readStringRequireUtf8()
                56 -> protectedTarget = input.readBool()
                0 -> { }
                else -> input.skipField(tag)
            }
        }
        return ControlDecision(
            values[0], values[1], values[2], values[3],
            values[4], values[5], protectedTarget,
        )
    }

    @Throws(IOException::class)
    private fun message(writer: (CodedOutputStream) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        val output = CodedOutputStream.newInstance(bytes)
        writer(output)
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
        "KLD-BUNDLE-001", project, variant, "bundle-rewrite",
        PLAN_SCHEMA, target, reason, repair,
    ).failure()

    @JvmRecord
    data class Plan private constructor(
        val schema: String,
        val producer: String,
        val project: String,
        val variant: String,
        val inputAabSha256: String,
        val resourceTableSha256: String,
        val resources: List<ResourceDecision>,
        val entries: List<EntryDecision>,
        val expectedOutputs: List<String>,
        val references: List<ReferenceDecision>,
        val controls: List<ControlDecision>,
        @Suppress("unused")
        private val canonical: Boolean,
    ) {
        constructor(
            schema: String,
            producer: String,
            project: String,
            variant: String,
            inputAabSha256: String,
            resourceTableSha256: String,
            resources: List<ResourceDecision>,
            entries: List<EntryDecision>,
            expectedOutputs: List<String>,
            references: List<ReferenceDecision>,
            controls: List<ControlDecision>,
        ) : this(
            schema,
            producer,
            project,
            variant,
            inputAabSha256,
            resourceTableSha256,
            resources.sortedWith { left, right -> Integer.compareUnsigned(left.id, right.id) },
            entries.sortedBy(EntryDecision::inputPath),
            expectedOutputs.sorted(),
            references.sortedWith(
                compareBy(ReferenceDecision::origin)
                    .thenBy(ReferenceDecision::fieldPath)
                    .thenBy(ReferenceDecision::kind),
            ),
            controls.sortedWith(
                compareBy(ControlDecision::kind).thenBy(ControlDecision::target),
            ),
            true,
        )
    }

    @JvmRecord
    data class ResourceDecision private constructor(
        val id: Int,
        val packageName: String,
        val type: String,
        val originalName: String,
        val targetName: String,
        val action: String,
        val reason: String,
        val originalPaths: List<String>,
        val targetPaths: List<String>,
        val nameProtected: Boolean,
        val pathProtected: Boolean,
        @Suppress("unused")
        private val canonical: Boolean,
    ) {
        constructor(
            id: Int,
            packageName: String,
            type: String,
            originalName: String,
            targetName: String,
            action: String,
            reason: String,
            originalPaths: List<String>,
            targetPaths: List<String>,
            nameProtected: Boolean,
            pathProtected: Boolean,
        ) : this(
            id,
            packageName,
            type,
            originalName,
            targetName,
            action,
            reason,
            canonicalOriginalPaths(originalPaths, targetPaths),
            canonicalTargetPaths(originalPaths, targetPaths),
            nameProtected,
            pathProtected,
            true,
        )
    }

    @JvmRecord
    data class EntryDecision(
        val inputPath: String,
        val inputSha256: String,
        val compressionMethod: Int,
        val outputPath: String,
        val action: String,
        val preservePayload: Boolean,
    )

    @JvmRecord
    data class ReferenceDecision(
        val origin: String,
        val fieldPath: String,
        val kind: String,
        val resourceId: Int,
        val originalValue: String,
        val targetValue: String,
    )

    @JvmRecord
    data class ControlDecision(
        val kind: String,
        val target: String,
        val inputSha256: String,
        val outputSha256: String,
        val action: String,
        val reason: String,
        val protectedTarget: Boolean,
    )

    @JvmRecord
    data class Receipt private constructor(
        val schema: String,
        val producer: String,
        val project: String,
        val variant: String,
        val planSha256: String,
        val inputAabSha256: String,
        val outputAabSha256: String,
        val resourceMappingSha256: String,
        val resourceIdCount: Int,
        val renamedResourceCount: Int,
        val renamedPathCount: Int,
        val referenceClosureValidated: Boolean,
        val preservedPayloadsValidated: Boolean,
        val bundletoolValidated: Boolean,
        val referenceCount: Int,
        val preservedPayloadDigests: List<String>,
        val protectionDigests: List<String>,
        @Suppress("unused")
        private val canonical: Boolean,
    ) {
        constructor(
            schema: String,
            producer: String,
            project: String,
            variant: String,
            planSha256: String,
            inputAabSha256: String,
            outputAabSha256: String,
            resourceMappingSha256: String,
            resourceIdCount: Int,
            renamedResourceCount: Int,
            renamedPathCount: Int,
            referenceClosureValidated: Boolean,
            preservedPayloadsValidated: Boolean,
            bundletoolValidated: Boolean,
            referenceCount: Int,
            preservedPayloadDigests: List<String>,
            protectionDigests: List<String>,
        ) : this(
            schema,
            producer,
            project,
            variant,
            planSha256,
            inputAabSha256,
            outputAabSha256,
            resourceMappingSha256,
            resourceIdCount,
            renamedResourceCount,
            renamedPathCount,
            referenceClosureValidated,
            preservedPayloadsValidated,
            bundletoolValidated,
            referenceCount,
            preservedPayloadDigests.sorted(),
            protectionDigests.sorted(),
            true,
        )
    }

    private fun canonicalOriginalPaths(
        originalPaths: List<String>,
        targetPaths: List<String>,
    ): List<String> {
        if (originalPaths.size != targetPaths.size) {
            throw IllegalArgumentException("Resource path mapping is not one-to-one")
        }
        return originalPaths.indices.sortedBy { originalPaths[it] }.map { originalPaths[it] }
    }

    private fun canonicalTargetPaths(
        originalPaths: List<String>,
        targetPaths: List<String>,
    ): List<String> {
        if (originalPaths.size != targetPaths.size) {
            throw IllegalArgumentException("Resource path mapping is not one-to-one")
        }
        return originalPaths.indices.sortedBy { originalPaths[it] }.map { targetPaths[it] }
    }
}
