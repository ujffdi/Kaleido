package com.tongsr.kaleido.gradle

import com.android.aapt.Resources
import com.android.tools.build.bundletool.commands.ValidateBundleCommand
import com.google.protobuf.Descriptors
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.Message
import com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.ArrayList
import java.util.Comparator
import java.util.HashMap
import java.util.HashSet
import java.util.HexFormat
import java.util.Locale
import java.util.TreeMap
import java.util.TreeSet
import java.util.function.Consumer
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.gradle.api.GradleException

internal object BundleRewriteModule {
    private const val RESOURCE_TABLE = "base/resources.pb"

    @JvmStatic
    @Throws(IOException::class)
    fun plan(
        inputAab: Path,
        context: Context,
        applicationResources: Set<ResourceKey>,
        protectedNames: Set<String>,
        protectedPaths: Set<String>,
        resourceHatches: List<EscapeHatchDeclaration>,
        seedStream: String,
        fullControls: FullControls,
    ): BundleRewriteArtifacts.Plan {
        ZipFile(inputAab.toFile()).use { zip ->
            val entries = inventoryEntries(zip, context)
            val resourceEntry = zip.getEntry(RESOURCE_TABLE)
                ?: throw failure(
                    context,
                    RESOURCE_TABLE,
                    "Base resource table is missing",
                    "Rebuild a complete base-module App Bundle",
                )
            val tableBytes = read(zip, resourceEntry)
            val table = parseTable(tableBytes, context)
            val facts = resourceFacts(table)
            val compiledXmlPaths = facts.asSequence()
                .flatMap { fact -> fact.fileTypes.entries.asSequence() }
                .filter { item -> item.value == Resources.FileReference.Type.PROTO_XML_VALUE }
                .map { item -> "base/" + item.key }
                .toSet()
            val allPaths = entries.keys
            val pathRefCounts = HashMap<String, Int>()
            facts.forEach { fact ->
                fact.filePaths().forEach { path ->
                    pathRefCounts.merge(path, 1, Int::plus)
                }
            }
            for (path in pathRefCounts.keys) {
                if (!allPaths.contains("base/$path")) {
                    throw failure(
                        context,
                        path,
                        "Resource table has a dangling file reference",
                        "Restore the referenced base-module resource payload",
                    )
                }
            }
            for (path in allPaths.filter { it.startsWith("base/res/") }) {
                if (!pathRefCounts.containsKey(path.substring("base/".length))) {
                    throw failure(
                        context,
                        path,
                        "Base resource payload is orphaned from resources.pb",
                        "Remove the orphan or restore its resource-table reference",
                    )
                }
            }

            val reservedNames = HashMap<String, MutableSet<String>>()
            for (fact in facts) {
                reservedNames.computeIfAbsent(fact.packageName + ":" + fact.type) { HashSet() }
                    .add(fact.name)
            }
            val reservedPaths = HashSet(allPaths)
            var resourceDecisions = ArrayList<BundleRewriteArtifacts.ResourceDecision>()
            val pathMapping = TreeMap<String, String>()
            for (fact in facts) {
                val key = ResourceKey(fact.type, fact.name)
                val owned = applicationResources.contains(key)
                val hatchNameProtected = resourceHatches.any { hatch ->
                    hatch.matches(fact.name) &&
                        hatch.protects(KaleidoProtectionDimension.RESOURCE_NAME)
                }
                val hatchPathProtected = resourceHatches.any { hatch ->
                    hatch.matches(fact.name) &&
                        hatch.protects(KaleidoProtectionDimension.PACKAGED_PATH)
                }
                val nameProtected = protectedNames.contains(fact.name) || hatchNameProtected
                val anyDeclaredPathProtected = fact.filePaths().any { path ->
                    protectedPaths.contains(path) || protectedPaths.contains("base/$path")
                }
                val pathProtected = hatchPathProtected || anyDeclaredPathProtected
                var targetName = fact.name
                if (owned && !nameProtected) {
                    targetName = allocateName(
                        fact,
                        seedStream,
                        reservedNames[fact.packageName + ":" + fact.type]!!,
                    )
                }
                val originalPaths = ArrayList(fact.filePaths())
                val targetPaths = ArrayList<String>()
                for (path in originalPaths) {
                    var targetPath = path
                    if (owned && !pathProtected && pathRefCounts.getOrDefault(path, 0) == 1) {
                        targetPath = allocatePath(
                            fact,
                            path,
                            targetName,
                            seedStream,
                            reservedPaths,
                        )
                        pathMapping[path] = targetPath
                    }
                    targetPaths.add(targetPath)
                }
                val renamedName = targetName != fact.name
                val renamedPath = originalPaths != targetPaths
                val action = when {
                    renamedName && renamedPath -> "REWRITE_NAME_AND_PATH"
                    renamedName -> "REWRITE_NAME"
                    renamedPath -> "REWRITE_PATH"
                    owned && (nameProtected || pathProtected) -> "PROTECTED"
                    owned -> "UNCHANGED"
                    else -> "DEPENDENCY"
                }
                val reason = when {
                    !owned -> "not-application-owned"
                    nameProtected || pathProtected -> "protection-requirement"
                    fact.filePaths().isNotEmpty() &&
                        fact.filePaths().any { path -> pathRefCounts.getOrDefault(path, 0) > 1 } ->
                        "shared-file-reference-retained"
                    else -> "eligible-application-resource"
                }
                resourceDecisions.add(
                    BundleRewriteArtifacts.ResourceDecision(
                        fact.id,
                        fact.packageName,
                        fact.type,
                        fact.name,
                        targetName,
                        action,
                        reason,
                        originalPaths,
                        targetPaths,
                        nameProtected,
                        pathProtected,
                    ),
                )
            }

            val deduplication = deduplicate(
                zip,
                facts,
                resourceDecisions,
                pathMapping,
                entries,
                context,
            )
            resourceDecisions = ArrayList(deduplication.resources)
            val zipDecisions = ArrayList<BundleRewriteArtifacts.EntryDecision>()
            val expected = TreeSet<String>()
            val plannedNamesById = HashMap<Int, FullResourceName>()
            val plannedNamesByOriginal = HashMap<String, FullResourceName>()
            for (decision in resourceDecisions) {
                if (decision.originalName != decision.targetName) {
                    val target = FullResourceName(
                        decision.packageName,
                        decision.type,
                        decision.targetName,
                    )
                    plannedNamesById[decision.id] = target
                    plannedNamesByOriginal[
                        decision.packageName + ":" + decision.type + "/" + decision.originalName,
                    ] = target
                    plannedNamesByOriginal[decision.type + "/" + decision.originalName] = target
                }
            }
            val references = ArrayList<BundleRewriteArtifacts.ReferenceDecision>()
            collectReferenceDecisions(
                table,
                RESOURCE_TABLE,
                "$",
                plannedNamesById,
                plannedNamesByOriginal,
                pathMapping,
                references,
            )
            for (entry in entries.values) {
                if (compiledXmlPaths.contains(entry.path) ||
                    entry.path == "base/manifest/AndroidManifest.xml"
                ) {
                    collectReferenceDecisions(
                        parseXml(read(zip, zip.getEntry(entry.path)), entry.path, context),
                        entry.path,
                        "$",
                        plannedNamesById,
                        plannedNamesByOriginal,
                        pathMapping,
                        references,
                    )
                }
            }
            val controlPlan = planFullControls(
                zip,
                table,
                facts,
                resourceDecisions,
                applicationResources,
                protectedPaths,
                entries,
                references,
                fullControls,
                context,
            )
            for (entry in entries.values) {
                val signature = isSignature(entry.path)
                val relative = if (entry.path.startsWith("base/")) {
                    entry.path.substring("base/".length)
                } else {
                    ""
                }
                var outputPath = if (pathMapping.containsKey(relative)) {
                    "base/" + pathMapping[relative]
                } else {
                    entry.path
                }
                val deduplicated = deduplication.redundantInputPaths.contains(entry.path)
                val fullControlDeleted = controlPlan.deletedEntries.contains(entry.path)
                if (deduplicated) outputPath = ""
                if (fullControlDeleted) outputPath = ""
                val action = when {
                    fullControlDeleted -> "DELETE_FULL_CONTROL"
                    signature -> "DELETE_INVALID_SIGNATURE"
                    deduplicated -> "DELETE_DEDUPLICATED_PAYLOAD"
                    RESOURCE_TABLE == entry.path -> "REWRITE_RESOURCE_TABLE"
                    compiledXmlPaths.contains(entry.path) ||
                        entry.path == "base/manifest/AndroidManifest.xml" -> "REWRITE_COMPILED_XML"
                    outputPath != entry.path -> "RENAME_RESOURCE_PAYLOAD"
                    else -> "COPY"
                }
                var preserve = isProtectedPayload(entry.path) ||
                    deduplication.protectedPayloadPaths.contains(entry.path)
                if (fullControlDeleted) preserve = false
                zipDecisions.add(
                    BundleRewriteArtifacts.EntryDecision(
                        entry.path,
                        entry.sha256,
                        entry.method,
                        if (signature || deduplicated || fullControlDeleted) "" else outputPath,
                        action,
                        preserve,
                    ),
                )
                if (!signature && !deduplicated && !fullControlDeleted && !expected.add(outputPath)) {
                    throw failure(
                        context,
                        outputPath,
                        "Bundle output path collision",
                        "Change the seed or protect one colliding resource path",
                    )
                }
            }
            return BundleRewriteArtifacts.Plan(
                BundleRewriteArtifacts.PLAN_SCHEMA,
                BundleRewriteArtifacts.PRODUCER,
                context.project,
                context.variant,
                sha256(inputAab),
                sha256(tableBytes),
                resourceDecisions,
                zipDecisions,
                expected.toList(),
                references,
                controlPlan.decisions,
            )
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun execute(
        inputAab: Path,
        outputAab: Path,
        plan: BundleRewriteArtifacts.Plan,
        planBytes: ByteArray,
        context: Context,
    ): Execution {
        if (plan.project != context.project || plan.variant != context.variant) {
            throw failure(
                context,
                "BundleRewritePlan.v1",
                "Bundle Rewrite Plan belongs to a different project or variant",
                "Regenerate the plan for this exact Release variant",
            )
        }
        if (sha256(inputAab) != plan.inputAabSha256) {
            throw failure(
                context,
                inputAab.toString(),
                "Input AAB digest differs from Bundle Rewrite Plan",
                "Regenerate and execute the plan against one stable input AAB",
            )
        }
        val nameById = HashMap<Int, FullResourceName>()
        val targetByOriginalName = HashMap<String, FullResourceName>()
        val paths = TreeMap<String, String>()
        for (decision in plan.resources) {
            if (decision.originalName != decision.targetName) {
                val target = FullResourceName(
                    decision.packageName,
                    decision.type,
                    decision.targetName,
                )
                nameById[decision.id] = target
                targetByOriginalName[
                    decision.packageName + ":" + decision.type + "/" + decision.originalName,
                ] = target
                targetByOriginalName[decision.type + "/" + decision.originalName] = target
            }
            for (index in decision.originalPaths.indices) {
                val original = decision.originalPaths[index]
                val target = decision.targetPaths[index]
                if (original != target) paths[original] = target
            }
        }
        val entryPlan = plan.entries.associateBy { it.inputPath }
        val changedPayloads = HashMap<String, ByteArray>()
        val preservedDigests = ArrayList<String>()
        ZipFile(inputAab.toFile()).use { zip ->
            for (decision in plan.entries) {
                val entry = zip.getEntry(decision.inputPath)
                if (entry == null || sha256(zip.getInputStream(entry)) != decision.inputSha256) {
                    throw failure(
                        context,
                        decision.inputPath,
                        "Planned Bundle entry is missing or changed",
                        "Regenerate the Bundle Rewrite Plan from stable input",
                    )
                }
                if (decision.preservePayload) {
                    preservedDigests.add(decision.inputPath + "|" + decision.inputSha256)
                }
                if (decision.action == "REWRITE_RESOURCE_TABLE") {
                    val table = parseTable(read(zip, entry), context)
                    changedPayloads[decision.inputPath] = rewriteTable(
                        table,
                        plan,
                        nameById,
                        targetByOriginalName,
                        paths,
                        context,
                    ).toByteArray()
                } else if (decision.action == "REWRITE_COMPILED_XML") {
                    val node = parseXml(read(zip, entry), decision.inputPath, context)
                    changedPayloads[decision.inputPath] = rewriteMessage(
                        node,
                        nameById,
                        targetByOriginalName,
                        paths,
                    ).toByteArray()
                }
            }
            writeBundle(zip, outputAab, plan, entryPlan, changedPayloads, context)
        }

        validateOutput(outputAab, plan, nameById, targetByOriginalName, paths, context)
        try {
            ValidateBundleCommand.builder().setBundlePath(outputAab).setPrintOutput(false)
                .build().execute()
        } catch (invalid: RuntimeException) {
            throw failure(
                context,
                outputAab.toString(),
                "bundletool rejected the rewritten unsigned App Bundle: " +
                    invalid.message.toString(),
                "Inspect the Bundle rewrite plan and restore reference closure",
            )
        }
        val mapping = resourceMapping(plan)
        val mappingBytes = mapping.toByteArray(StandardCharsets.UTF_8)
        val receipt = BundleRewriteArtifacts.Receipt(
            BundleRewriteArtifacts.RECEIPT_SCHEMA,
            BundleRewriteArtifacts.PRODUCER,
            context.project,
            context.variant,
            sha256(planBytes),
            plan.inputAabSha256,
            sha256(outputAab),
            sha256(mappingBytes),
            plan.resources.size,
            plan.resources.count { item -> item.originalName != item.targetName },
            paths.size,
            true,
            true,
            true,
            plan.references.size,
            preservedDigests,
            protectionDigests(plan, context),
        )
        return Execution(mapping, BundleRewriteArtifacts.encodeReceipt(receipt))
    }

    private fun rewriteTable(
        table: Resources.ResourceTable,
        plan: BundleRewriteArtifacts.Plan,
        nameById: Map<Int, FullResourceName>,
        targetByOriginalName: Map<String, FullResourceName>,
        paths: Map<String, String>,
        context: Context,
    ): Resources.ResourceTable {
        val decisions = plan.resources.associateBy { it.id }
        val replacementControls = plan.controls
            .filter { control -> control.kind == "REPLACE_UNUSED_STRING" }
            .associateBy { control -> Integer.parseUnsignedInt(control.target.substring(2), 16) }
        val languageControls = HashMap<Int, MutableMap<Int, BundleRewriteArtifacts.ControlDecision>>()
        for (control in plan.controls.filter { item -> item.kind == "FILTER_LANGUAGE" }) {
            val parts = control.target.split("|")
            val id = Integer.parseUnsignedInt(parts[0].substring(2), 16)
            languageControls.computeIfAbsent(id) { HashMap() }
                .put(parts[2].toInt(), control)
        }
        val builder = table.toBuilder()
        for (packageIndex in 0 until table.packageCount) {
            val pkg = table.getPackage(packageIndex)
            val packageBuilder = pkg.toBuilder()
            for (typeIndex in 0 until pkg.typeCount) {
                val type = pkg.getType(typeIndex)
                val typeBuilder = type.toBuilder()
                for (entryIndex in 0 until type.entryCount) {
                    val entry = type.getEntry(entryIndex)
                    val id = resourceId(pkg, type, entry)
                    val decision = decisions[id]
                        ?: throw failure(
                            context,
                            String.format("0x%08x", id),
                            "Resource ID was not inventoried by the plan",
                            "Regenerate the Bundle Rewrite Plan from this exact AAB",
                        )
                    val replacement = replacementControls[id]
                    var controlledEntry = entry
                    if (replacement != null) {
                        if (sha256(entry.toByteArray()) != replacement.inputSha256) {
                            throw failure(
                                context,
                                replacement.target,
                                "Unused-string control input digest differs from the plan",
                                "Regenerate the plan from this exact resource table",
                            )
                        }
                        controlledEntry = replaceStringEntry(entry, context)
                        if (sha256(controlledEntry.toByteArray()) != replacement.outputSha256) {
                            throw failure(
                                context,
                                replacement.target,
                                "Unused-string replacement differs from the planned output",
                                "Use the fixed Kaleido string replacement algorithm",
                            )
                        }
                    }
                    val entryBuilder = controlledEntry.toBuilder().setName(decision.targetName)
                        .clearConfigValue()
                    for (index in 0 until entry.configValueCount) {
                        val languageControl = languageControls.getOrDefault(id, emptyMap())[index]
                        if (languageControl != null) {
                            if (sha256(entry.getConfigValue(index).toByteArray()) !=
                                languageControl.inputSha256
                            ) {
                                throw failure(
                                    context,
                                    languageControl.target,
                                    "Language control input digest differs from the plan",
                                    "Regenerate the plan from this exact resource table",
                                )
                            }
                            continue
                        }
                        entryBuilder.addConfigValue(
                            rewriteMessage(
                                controlledEntry.getConfigValue(index),
                                nameById,
                                targetByOriginalName,
                                paths,
                            ) as Resources.ConfigValue,
                        )
                    }
                    for (index in 0 until entry.flagDisabledConfigValueCount) {
                        entryBuilder.setFlagDisabledConfigValue(
                            index,
                            rewriteMessage(
                                entry.getFlagDisabledConfigValue(index),
                                nameById,
                                targetByOriginalName,
                                paths,
                            ) as Resources.ConfigValue,
                        )
                    }
                    for (index in 0 until entry.readwriteFlagConfigValueCount) {
                        entryBuilder.setReadwriteFlagConfigValue(
                            index,
                            rewriteMessage(
                                entry.getReadwriteFlagConfigValue(index),
                                nameById,
                                targetByOriginalName,
                                paths,
                            ) as Resources.ConfigValue,
                        )
                    }
                    typeBuilder.setEntry(entryIndex, entryBuilder)
                }
                packageBuilder.setType(typeIndex, typeBuilder)
            }
            builder.setPackage(packageIndex, packageBuilder)
        }
        return builder.build()
    }

    private fun rewriteMessage(
        message: Message,
        nameById: Map<Int, FullResourceName>,
        targetByOriginalName: Map<String, FullResourceName>,
        paths: Map<String, String>,
    ): Message {
        if (message is Resources.Reference) {
            val target = targetFor(message, nameById, targetByOriginalName)
            if (target == null || message.name.isEmpty()) return message
            return message.toBuilder().setName(rewriteReferenceName(message.name, target)).build()
        }
        if (message is Resources.FileReference) {
            val target = paths[message.path]
            return if (target == null) message else message.toBuilder().setPath(target).build()
        }
        if (message is Resources.XmlAttribute) {
            val builder = message.toBuilder()
            if (message.hasCompiledItem() && message.compiledItem.hasRef()) {
                var target = nameById[message.compiledItem.ref.id]
                if (target == null) {
                    target = targetFor(
                        message.compiledItem.ref,
                        nameById,
                        targetByOriginalName,
                    )
                }
                if (target != null) {
                    builder.setValue(rewriteRawReference(message.value, target))
                }
            }
            if (message.hasCompiledItem()) {
                builder.setCompiledItem(
                    rewriteMessage(
                        message.compiledItem,
                        nameById,
                        targetByOriginalName,
                        paths,
                    ) as Resources.Item,
                )
            }
            return builder.build()
        }
        val builder = message.toBuilder()
        for (field in message.descriptorForType.fields) {
            if (field.javaType != Descriptors.FieldDescriptor.JavaType.MESSAGE) continue
            if (field.isRepeated) {
                val count = message.getRepeatedFieldCount(field)
                for (index in 0 until count) {
                    builder.setRepeatedField(
                        field,
                        index,
                        rewriteMessage(
                            message.getRepeatedField(field, index) as Message,
                            nameById,
                            targetByOriginalName,
                            paths,
                        ),
                    )
                }
            } else if (message.hasField(field)) {
                builder.setField(
                    field,
                    rewriteMessage(
                        message.getField(field) as Message,
                        nameById,
                        targetByOriginalName,
                        paths,
                    ),
                )
            }
        }
        return builder.build()
    }

    private fun targetFor(
        reference: Resources.Reference,
        nameById: Map<Int, FullResourceName>,
        targetByOriginalName: Map<String, FullResourceName>,
    ): FullResourceName? {
        val target = nameById[reference.id]
        return target ?: targetByOriginalName[reference.name]
    }

    @Throws(IOException::class)
    private fun writeBundle(
        input: ZipFile,
        output: Path,
        plan: BundleRewriteArtifacts.Plan,
        decisions: Map<String, BundleRewriteArtifacts.EntryDecision>,
        changedPayloads: Map<String, ByteArray>,
        context: Context,
    ) {
        Files.createDirectories(output.parent)
        val temporary = output.resolveSibling(output.fileName.toString() + ".tmp")
        Files.deleteIfExists(temporary)
        val byOutput = plan.entries
            .filter { item -> item.outputPath.isNotEmpty() }
            .sortedBy { it.outputPath }
        ZipOutputStream(Files.newOutputStream(temporary), StandardCharsets.UTF_8).use { stream ->
            stream.setLevel(9)
            for (decision in byOutput) {
                val inputEntry = input.getEntry(decision.inputPath)
                var bytes = changedPayloads[decision.inputPath]
                val outputEntry = ZipEntry(decision.outputPath)
                outputEntry.time = 0L
                outputEntry.method = decision.compressionMethod
                if (decision.compressionMethod == ZipEntry.STORED) {
                    if (bytes == null) bytes = read(input, inputEntry)
                    val crc = CRC32()
                    crc.update(bytes)
                    outputEntry.size = bytes.size.toLong()
                    outputEntry.compressedSize = bytes.size.toLong()
                    outputEntry.crc = crc.value
                }
                stream.putNextEntry(outputEntry)
                if (bytes != null) {
                    stream.write(bytes)
                } else {
                    input.getInputStream(inputEntry).use { source -> source.transferTo(stream) }
                }
                stream.closeEntry()
            }
        }
        Files.move(
            temporary,
            output,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    @Throws(IOException::class)
    private fun validateOutput(
        output: Path,
        plan: BundleRewriteArtifacts.Plan,
        changedNames: Map<Int, FullResourceName>,
        targetByOriginalName: Map<String, FullResourceName>,
        changedPaths: Map<String, String>,
        context: Context,
    ) {
        ZipFile(output.toFile()).use { zip ->
            val actualPaths = zip.stream().filter { !it.isDirectory }
                .map { it.name }.sorted().toList()
            if (actualPaths != plan.expectedOutputs) {
                throw failure(
                    context,
                    output.toString(),
                    "Bundle output entries differ from the immutable plan",
                    "Delete stale outputs and rebuild this Release variant",
                )
            }
            val existingPaths = actualPaths.toSet()
            val tableEntry = zip.getEntry(RESOURCE_TABLE)
            val table = parseTable(read(zip, tableEntry), context)
            validateReferenceClosure(
                table,
                changedNames,
                targetByOriginalName,
                changedPaths,
                existingPaths,
                context,
                RESOURCE_TABLE,
            )
            val ids = TreeSet(Integer::compareUnsigned)
            for (fact in resourceFacts(table)) {
                ids.add(fact.id)
                val expected = changedNames[fact.id]
                if (expected != null && expected.name != fact.name) {
                    throw failure(
                        context,
                        String.format("0x%08x", fact.id),
                        "Rewritten resource name differs from the plan",
                        "Regenerate and re-execute the Bundle Rewrite Plan",
                    )
                }
                for (path in fact.filePaths()) {
                    if (!existingPaths.contains("base/$path")) {
                        throw failure(
                            context,
                            path,
                            "Rewritten file reference is dangling",
                            "Restore the planned resource payload path",
                        )
                    }
                }
            }
            val expectedIds = TreeSet(Integer::compareUnsigned)
            plan.resources.mapTo(expectedIds) { it.id }
            if (ids != expectedIds) {
                throw failure(
                    context,
                    RESOURCE_TABLE,
                    "Numeric resource ID set changed during Bundle rewrite",
                    "Preserve every package, type, and entry ID",
                )
            }
            val compiledXmlPaths = resourceFacts(table).asSequence()
                .flatMap { fact -> fact.fileTypes.entries.asSequence() }
                .filter { item -> item.value == Resources.FileReference.Type.PROTO_XML_VALUE }
                .map { item -> "base/" + item.key }
                .toSet()
            for (entry in zip.stream().filter { item ->
                item.name == "base/manifest/AndroidManifest.xml" ||
                    compiledXmlPaths.contains(item.name)
            }.toList()) {
                val xml = parseXml(read(zip, entry), entry.name, context)
                validateReferenceClosure(
                    xml,
                    changedNames,
                    targetByOriginalName,
                    changedPaths,
                    existingPaths,
                    context,
                    entry.name,
                )
            }
            for (decision in plan.entries.filter { it.preservePayload }) {
                val entry = zip.getEntry(decision.outputPath)
                if (entry == null || sha256(zip.getInputStream(entry)) != decision.inputSha256) {
                    throw failure(
                        context,
                        decision.inputPath,
                        "DEX, native, or code-transparency payload drifted",
                        "Byte-preserve protected code payloads during resource rewrite",
                    )
                }
            }
        }
    }

    private fun validateReferenceClosure(
        message: Message,
        changedNames: Map<Int, FullResourceName>,
        targetByOriginalName: Map<String, FullResourceName>,
        changedPaths: Map<String, String>,
        existingPaths: Set<String>,
        context: Context,
        origin: String,
    ) {
        if (message is Resources.Reference) {
            val target = changedNames[message.id]
            if (target != null && message.name.isNotEmpty() &&
                !message.name.endsWith("/" + target.name)
            ) {
                throw failure(
                    context,
                    origin,
                    "Resource Reference.name did not close through the plan",
                    "Rewrite every name-bearing reference with its numeric resource ID",
                )
            }
            if (targetByOriginalName.containsKey(message.name)) {
                throw failure(
                    context,
                    origin,
                    "Name-only resource reference retains a mapped original identity",
                    "Rewrite the name-bearing reference through the resource mapping",
                )
            }
            return
        }
        if (message is Resources.FileReference) {
            if (changedPaths.containsKey(message.path)) {
                throw failure(
                    context,
                    origin,
                    "FileReference.path retains a mapped original path",
                    "Rewrite every file reference through the path mapping",
                )
            }
            if (!existingPaths.contains("base/" + message.path)) {
                throw failure(
                    context,
                    message.path,
                    "FileReference.path does not resolve to an output payload",
                    "Restore the planned base-module resource payload",
                )
            }
            return
        }
        for (field in message.descriptorForType.fields) {
            if (field.javaType != Descriptors.FieldDescriptor.JavaType.MESSAGE) continue
            if (field.isRepeated) {
                for (index in 0 until message.getRepeatedFieldCount(field)) {
                    validateReferenceClosure(
                        message.getRepeatedField(field, index) as Message,
                        changedNames,
                        targetByOriginalName,
                        changedPaths,
                        existingPaths,
                        context,
                        origin,
                    )
                }
            } else if (message.hasField(field)) {
                validateReferenceClosure(
                    message.getField(field) as Message,
                    changedNames,
                    targetByOriginalName,
                    changedPaths,
                    existingPaths,
                    context,
                    origin,
                )
            }
        }
    }

    private fun resourceMapping(plan: BundleRewriteArtifacts.Plan): String {
        val text = StringBuilder("schema=KaleidoResourceMapping.v1\n")
        for (decision in plan.resources) {
            if (decision.originalName != decision.targetName) {
                text.append(
                    String.format(
                        "resource=0x%08x|%s:%s/%s -> %s:%s/%s%n",
                        decision.id,
                        decision.packageName,
                        decision.type,
                        decision.originalName,
                        decision.packageName,
                        decision.type,
                        decision.targetName,
                    ),
                )
            }
            for (index in decision.originalPaths.indices) {
                val original = decision.originalPaths[index]
                val target = decision.targetPaths[index]
                if (original != target) {
                    text.append("path=base/").append(original).append(" -> base/")
                        .append(target).append('\n')
                }
            }
        }
        return text.toString()
    }

    private fun protectionDigests(
        plan: BundleRewriteArtifacts.Plan,
        context: Context,
    ): List<String> {
        val digests = TreeSet<String>()
        val protectedIds = TreeSet(Integer::compareUnsigned)
        for (resource in plan.resources) {
            if (!resource.nameProtected && !resource.pathProtected) continue
            if (resource.nameProtected) protectedIds.add(resource.id)
            if (resource.nameProtected && resource.originalName != resource.targetName) {
                throw failure(
                    context,
                    resource.originalName,
                    "Protected resource name drifted in the executable plan",
                    "Restore the original protected resource entry name",
                )
            }
            if (resource.pathProtected && resource.originalPaths != resource.targetPaths) {
                throw failure(
                    context,
                    resource.originalName,
                    "Protected packaged path drifted in the executable plan",
                    "Restore every original protected packaged path",
                )
            }
            val beforeIdentity = resource.packageName + ":" + resource.type +
                "/" + resource.originalName
            val afterIdentity = resource.packageName + ":" + resource.type +
                "/" + resource.targetName
            digests.add(
                "name|" + String.format("%08x", resource.id) + "|" +
                    sha256(beforeIdentity.toByteArray(StandardCharsets.UTF_8)) + "|" +
                    sha256(afterIdentity.toByteArray(StandardCharsets.UTF_8)),
            )
            for (index in resource.originalPaths.indices) {
                digests.add(
                    "path|" + String.format("%08x", resource.id) + "|" +
                        sha256(
                            resource.originalPaths[index].toByteArray(StandardCharsets.UTF_8),
                        ) + "|" +
                        sha256(
                            resource.targetPaths[index].toByteArray(StandardCharsets.UTF_8),
                        ),
                )
            }
        }
        for (reference in plan.references) {
            if (!protectedIds.contains(reference.resourceId)) continue
            if (reference.originalValue != reference.targetValue) {
                throw failure(
                    context,
                    reference.origin,
                    "Protected resource attribute or reference drifted in the plan",
                    "Retain the original protected name-bearing reference",
                )
            }
            digests.add(
                "reference|" + reference.kind + "|" +
                    String.format("%08x", reference.resourceId) + "|" +
                    sha256(reference.originalValue.toByteArray(StandardCharsets.UTF_8)) + "|" +
                    sha256(reference.targetValue.toByteArray(StandardCharsets.UTF_8)),
            )
        }
        return digests.toList()
    }

    @Throws(IOException::class)
    private fun planFullControls(
        zip: ZipFile,
        table: Resources.ResourceTable,
        facts: List<ResourceFact>,
        resources: List<BundleRewriteArtifacts.ResourceDecision>,
        applicationResources: Set<ResourceKey>,
        protectedPaths: Set<String>,
        entries: Map<String, EntryFact>,
        references: List<BundleRewriteArtifacts.ReferenceDecision>,
        controls: FullControls,
        context: Context,
    ): ControlPlan {
        val decisions = ArrayList<BundleRewriteArtifacts.ControlDecision>()
        val deletedEntries = TreeSet<String>()
        for (selector in controls.nativeLibrariesToDelete.sorted()) {
            if (!controls.ownedNativeLibraries.contains(selector)) {
                throw failure(
                    context,
                    selector,
                    "Native deletion target is not Application-owned",
                    "Select an exact native library from Application jniLibs sources",
                )
            }
            val matches = entries.values.filter { entry ->
                entry.path.startsWith("base/lib/") && entry.path.endsWith("/$selector")
            }
            if (matches.isEmpty()) {
                throw failure(
                    context,
                    selector,
                    "Native deletion selector resolves to zero Bundle entries",
                    "Select an exact packaged Application native library",
                )
            }
            val loadName = selector.substring("lib".length, selector.length - ".so".length)
            for (dex in entries.values.filter { entry -> entry.path.startsWith("base/dex/") }) {
                if (containsAscii(read(zip, zip.getEntry(dex.path)), loadName)) {
                    throw failure(
                        context,
                        selector,
                        "Native deletion intersects a modeled code loading reference",
                        "Remove the deletion or the System.loadLibrary reference",
                    )
                }
            }
            for (match in matches) {
                if (isPathProtected(match.path, protectedPaths)) {
                    throw failure(
                        context,
                        match.path,
                        "Native deletion intersects a packaged-path protection",
                        "Remove the deletion or the Protection Requirement",
                    )
                }
                deletedEntries.add(match.path)
                decisions.add(
                    BundleRewriteArtifacts.ControlDecision(
                        "DELETE_NATIVE",
                        match.path,
                        match.sha256,
                        sha256(ByteArray(0)),
                        "DELETE_ENTRY",
                        "explicit-full-profile-native-selector",
                        false,
                    ),
                )
            }
        }
        for (deleted in deletedEntries.filter { path -> path.startsWith("base/lib/") }) {
            val directory = deleted.substring(0, deleted.lastIndexOf('/') + 1)
            val retainedInTarget = entries.keys.any { path ->
                path.startsWith(directory) && !deletedEntries.contains(path)
            }
            if (!retainedInTarget) {
                throw failure(
                    context,
                    directory,
                    "Native deletion would leave an empty targeted ABI directory",
                    "Retain at least one native library for this ABI or remove the ABI upstream",
                )
            }
        }
        for (selector in controls.metadataToDelete.sorted()) {
            if (!controls.ownedMetadata.contains(selector)) {
                throw failure(
                    context,
                    selector,
                    "Metadata deletion target is not Application-owned",
                    "Select exact permitted metadata from Application resources sources",
                )
            }
            val flattenedPath = "base/root/" + selector.substring("META-INF/".length)
            val matches = entries.values.filter { entry ->
                entry.path.endsWith("/$selector") || entry.path == flattenedPath
            }
            if (matches.isEmpty()) {
                throw failure(
                    context,
                    selector,
                    "Metadata deletion selector resolves to zero Bundle entries",
                    "Select an exact packaged Application META-INF entry",
                )
            }
            for (match in matches) {
                if (isPathProtected(match.path, protectedPaths) ||
                    isProtectedPayload(match.path) ||
                    isSignature(match.path)
                ) {
                    throw failure(
                        context,
                        match.path,
                        "Metadata deletion intersects protected or signing content",
                        "Select only permitted unprotected Application metadata",
                    )
                }
                deletedEntries.add(match.path)
                decisions.add(
                    BundleRewriteArtifacts.ControlDecision(
                        "DELETE_METADATA",
                        match.path,
                        match.sha256,
                        sha256(ByteArray(0)),
                        "DELETE_ENTRY",
                        "explicit-full-profile-metadata-selector",
                        false,
                    ),
                )
            }
        }

        val resourceById = resources.associateBy { it.id }
        val referencedIds = references.map { it.resourceId }.filter { id -> id != 0 }.toSet()
        for (unusedName in controls.confirmedUnusedStrings.sorted()) {
            val matches = facts.filter { fact ->
                fact.type == "string" &&
                    fact.name == unusedName &&
                    applicationResources.contains(ResourceKey("string", unusedName))
            }
            if (matches.size != 1) {
                throw failure(
                    context,
                    unusedName,
                    "Confirmed-unused string does not resolve to one Application resource",
                    "List one exact Application-owned string resource name",
                )
            }
            val fact = matches[0]
            val resource = resourceById[fact.id]!!
            if (resource.nameProtected || resource.pathProtected) {
                throw failure(
                    context,
                    unusedName,
                    "Unused-string replacement intersects a Protection Requirement",
                    "Remove the target from the confirmed-unused file or protection set",
                )
            }
            if (referencedIds.contains(fact.id)) {
                throw failure(
                    context,
                    unusedName,
                    "Confirmed-unused string remains in the modeled reference graph",
                    "Remove the structural reference or the confirmed-unused declaration",
                )
            }
            val entry = resourceEntry(table, fact.id)
            val replaced = replaceStringEntry(entry, context)
            decisions.add(
                BundleRewriteArtifacts.ControlDecision(
                    "REPLACE_UNUSED_STRING",
                    String.format("0x%08x", fact.id),
                    sha256(entry.toByteArray()),
                    sha256(replaced.toByteArray()),
                    "REPLACE_VALUE",
                    "explicit-confirmed-unused-input",
                    false,
                ),
            )
        }

        val matchedLanguages = TreeSet<String>()
        if (controls.retainedLanguages.isNotEmpty()) {
            for (fact in facts) {
                val resource = resourceById[fact.id]!!
                val owned = applicationResources.contains(ResourceKey(fact.type, fact.name))
                val entry = resourceEntry(table, fact.id)
                val hasDefault = entry.configValueList.any { value -> locale(value).isBlank() }
                for (index in 0 until entry.configValueCount) {
                    val value = entry.getConfigValue(index)
                    val locale = locale(value)
                    if (locale.isBlank()) continue
                    val retained = controls.retainedLanguages.any { requested ->
                        localeMatches(locale, requested)
                    }
                    if (retained) {
                        controls.retainedLanguages
                            .filter { requested -> localeMatches(locale, requested) }
                            .forEach { matchedLanguages.add(it) }
                        continue
                    }
                    if (!owned) continue
                    if (!hasDefault) {
                        throw failure(
                            context,
                            fact.type + "/" + fact.name,
                            "Language filtering cannot prove a default fallback",
                            "Add a default resource value or retain this locale",
                        )
                    }
                    if (resource.nameProtected || resource.pathProtected) {
                        throw failure(
                            context,
                            fact.type + "/" + fact.name,
                            "Language filtering intersects a Protection Requirement",
                            "Retain the locale or remove the resource protection",
                        )
                    }
                    decisions.add(
                        BundleRewriteArtifacts.ControlDecision(
                            "FILTER_LANGUAGE",
                            String.format("0x%08x|config|%d", fact.id, index),
                            sha256(value.toByteArray()),
                            sha256(ByteArray(0)),
                            "DELETE_CONFIG",
                            "locale=$locale",
                            false,
                        ),
                    )
                }
            }
            val unmatched = TreeSet(controls.retainedLanguages)
            unmatched.removeAll(matchedLanguages)
            if (unmatched.isNotEmpty()) {
                throw failure(
                    context,
                    unmatched.joinToString(","),
                    "Retained language declaration matches no Application resource configuration",
                    "Use only locales present in this exact Release resource table",
                )
            }
        }
        return ControlPlan(decisions.toList(), deletedEntries.toSet())
    }

    private fun resourceEntry(table: Resources.ResourceTable, targetId: Int): Resources.Entry {
        for (pkg in table.packageList) {
            for (type in pkg.typeList) {
                for (entry in type.entryList) {
                    if (resourceId(pkg, type, entry) == targetId) return entry
                }
            }
        }
        throw IllegalArgumentException(
            "Missing resource ID " + Integer.toUnsignedString(targetId),
        )
    }

    private fun replaceStringEntry(entry: Resources.Entry, context: Context): Resources.Entry {
        val builder = entry.toBuilder().clearConfigValue()
        for (value in entry.configValueList) {
            if (!value.hasValue() || !value.value.hasItem()) {
                throw failure(
                    context,
                    entry.name,
                    "Unused-string target has a non-item value contract",
                    "Confirm only plain string resources as unused",
                )
            }
            val item = value.value.item
            val itemBuilder = item.toBuilder()
            if (item.hasStr()) {
                itemBuilder.setStr(item.str.toBuilder().setValue("kld"))
            } else if (item.hasRawStr()) {
                itemBuilder.setRawStr(item.rawStr.toBuilder().setValue("kld"))
            } else {
                throw failure(
                    context,
                    entry.name,
                    "Unused-string target is styled, referenced, or otherwise structured",
                    "Confirm only unstyled literal string resources as unused",
                )
            }
            builder.addConfigValue(
                value.toBuilder().setValue(value.value.toBuilder().setItem(itemBuilder)),
            )
        }
        return builder.build()
    }

    private fun locale(value: Resources.ConfigValue): String {
        if (!value.hasConfig()) return ""
        var locale = value.config.locale
        if (locale.startsWith("b+")) locale = locale.substring(2).replace('+', '-')
        return locale.replace("-r", "-")
    }

    private fun localeMatches(actual: String, requested: String): Boolean =
        actual == requested || !requested.contains("-") && actual.startsWith("$requested-")

    private fun isPathProtected(path: String, protectedPaths: Set<String>): Boolean =
        protectedPaths.contains(path) ||
            (path.startsWith("base/") && protectedPaths.contains(path.substring("base/".length)))

    private fun containsAscii(bytes: ByteArray, target: String): Boolean {
        val needle = target.toByteArray(StandardCharsets.UTF_8)
        outer@ for (index in 0..bytes.size - needle.size) {
            for (offset in needle.indices) {
                if (bytes[index + offset] != needle[offset]) continue@outer
            }
            return true
        }
        return false
    }

    @Throws(IOException::class)
    private fun deduplicate(
        zip: ZipFile,
        facts: List<ResourceFact>,
        decisions: List<BundleRewriteArtifacts.ResourceDecision>,
        pathMapping: MutableMap<String, String>,
        entries: Map<String, EntryFact>,
        context: Context,
    ): Deduplication {
        val factById = facts.associateBy { it.id }
        val targets = ArrayList<MutableList<String>>()
        decisions.forEach { decision -> targets.add(ArrayList(decision.targetPaths)) }
        val protectedPayloads = TreeSet<String>()
        val identicalPayloadGroups = TreeMap<String, MutableList<DedupCandidate>>()
        val groups = TreeMap<DedupKey, MutableList<DedupCandidate>>(
            Comparator.comparing(DedupKey::directory)
                .thenComparingInt(DedupKey::representation)
                .thenComparing(DedupKey::suffix)
                .thenComparingLong(DedupKey::size)
                .thenComparing(DedupKey::sha256),
        )
        for (decisionIndex in decisions.indices) {
            val decision = decisions[decisionIndex]
            if (decision.nameProtected || decision.pathProtected) {
                decision.originalPaths.forEach { path -> protectedPayloads.add("base/$path") }
            }
            val fact = factById[decision.id]!!
            for (pathIndex in decision.originalPaths.indices) {
                val original = decision.originalPaths[pathIndex]
                val input = entries["base/$original"]
                    ?: throw failure(
                        context,
                        original,
                        "Deduplication candidate is missing its Bundle payload",
                        "Restore the planned base-module resource payload",
                    )
                identicalPayloadGroups.computeIfAbsent(input.size.toString() + "|" + input.sha256) {
                    ArrayList()
                }.add(
                    DedupCandidate(
                        decisionIndex,
                        pathIndex,
                        original,
                        targets[decisionIndex][pathIndex],
                    ),
                )
                if (decision.nameProtected || decision.pathProtected ||
                    decision.action == "DEPENDENCY"
                ) {
                    continue
                }
                val slash = original.lastIndexOf('/')
                val fileName = original.substring(slash + 1)
                if (!fileName.startsWith(fact.name + ".")) {
                    continue
                }
                val key = DedupKey(
                    original.substring(0, slash),
                    fact.fileTypes[original]!!,
                    suffix(fileName, fact.name),
                    input.size,
                    input.sha256,
                )
                groups.computeIfAbsent(key) { ArrayList() }.add(
                    DedupCandidate(
                        decisionIndex,
                        pathIndex,
                        original,
                        targets[decisionIndex][pathIndex],
                    ),
                )
            }
        }
        val redundant = TreeSet<String>()
        val deduplicatedDecisions = HashSet<Int>()
        for (group in groups.values) {
            val candidatesByPath = group.groupByTo(TreeMap()) { it.originalPath }
            if (candidatesByPath.size < 2) continue
            val representatives = candidatesByPath.values.map { items -> items[0] }
            val firstBytes = read(zip, zip.getEntry("base/" + representatives[0].originalPath))
            var identical = true
            for (candidate in representatives.subList(1, representatives.size)) {
                if (!firstBytes.contentEquals(
                        read(zip, zip.getEntry("base/" + candidate.originalPath)),
                    )
                ) {
                    identical = false
                    break
                }
            }
            if (!identical) continue
            val canonical = representatives.minWith(
                Comparator.comparing(DedupCandidate::targetPath)
                    .thenComparing(DedupCandidate::originalPath),
            )
            for (pathGroup in candidatesByPath.values) {
                for (candidate in pathGroup) {
                    targets[candidate.decisionIndex][candidate.pathIndex] = canonical.targetPath
                    pathMapping[candidate.originalPath] = canonical.targetPath
                    deduplicatedDecisions.add(candidate.decisionIndex)
                }
                if (pathGroup[0].originalPath != canonical.originalPath) {
                    redundant.add("base/" + pathGroup[0].originalPath)
                }
            }
        }
        for (identicalGroup in identicalPayloadGroups.entries) {
            if (identicalGroup.value.size < 2) continue
            val retainedTargets = TreeSet<String>()
            identicalGroup.value.mapTo(retainedTargets) { candidate ->
                targets[candidate.decisionIndex][candidate.pathIndex]
            }
            if (retainedTargets.size > 1) {
                val digest = identicalGroup.key.substring(identicalGroup.key.indexOf('|') + 1)
                context.warn(
                    "KLD-RESOURCE-002 project=" + context.project +
                        " variant=" + context.variant +
                        " stage=bundle-rewrite origin=deduplication target=" +
                        digest.substring(0, 16) +
                        " reason=Byte-identical payloads retained across protection, ownership," +
                        " qualifier, or representation boundary repair=<none>",
                )
            }
        }
        val revised = ArrayList<BundleRewriteArtifacts.ResourceDecision>()
        for (index in decisions.indices) {
            val decision = decisions[index]
            revised.add(
                BundleRewriteArtifacts.ResourceDecision(
                    decision.id,
                    decision.packageName,
                    decision.type,
                    decision.originalName,
                    decision.targetName,
                    if (deduplicatedDecisions.contains(index)) {
                        decision.action + "_DEDUP"
                    } else {
                        decision.action
                    },
                    if (deduplicatedDecisions.contains(index)) {
                        decision.reason + "+byte-identical-dedup"
                    } else {
                        decision.reason
                    },
                    decision.originalPaths,
                    targets[index],
                    decision.nameProtected,
                    decision.pathProtected,
                ),
            )
        }
        return Deduplication(revised.toList(), redundant.toSet(), protectedPayloads.toSet())
    }

    private fun resourceFacts(table: Resources.ResourceTable): List<ResourceFact> {
        val facts = ArrayList<ResourceFact>()
        for (pkg in table.packageList) {
            for (type in pkg.typeList) {
                for (entry in type.entryList) {
                    val paths = TreeMap<String, Int>()
                    entry.configValueList.forEach { value -> collectFilePaths(value, paths) }
                    entry.flagDisabledConfigValueList.forEach { value ->
                        collectFilePaths(value, paths)
                    }
                    entry.readwriteFlagConfigValueList.forEach { value ->
                        collectFilePaths(value, paths)
                    }
                    facts.add(
                        ResourceFact(
                            resourceId(pkg, type, entry),
                            pkg.packageName,
                            type.name,
                            entry.name,
                            paths.toMap(),
                        ),
                    )
                }
            }
        }
        return facts.sortedWith(
            Comparator.comparingInt(ResourceFact::id)
                .thenComparing(ResourceFact::type)
                .thenComparing(ResourceFact::name),
        )
    }

    private fun collectReferenceDecisions(
        message: Message,
        origin: String,
        fieldPath: String,
        nameById: Map<Int, FullResourceName>,
        targetByOriginalName: Map<String, FullResourceName>,
        paths: Map<String, String>,
        output: MutableList<BundleRewriteArtifacts.ReferenceDecision>,
    ) {
        if (message is Resources.Reference) {
            val target = targetFor(message, nameById, targetByOriginalName)
            val targetValue = if (target == null || message.name.isEmpty()) {
                message.name
            } else {
                rewriteReferenceName(message.name, target)
            }
            output.add(
                BundleRewriteArtifacts.ReferenceDecision(
                    origin,
                    fieldPath,
                    "RESOURCE_REFERENCE",
                    message.id,
                    message.name,
                    targetValue,
                ),
            )
            return
        }
        if (message is Resources.FileReference) {
            output.add(
                BundleRewriteArtifacts.ReferenceDecision(
                    origin,
                    fieldPath,
                    "FILE_REFERENCE",
                    0,
                    message.path,
                    paths.getOrDefault(message.path, message.path),
                ),
            )
            return
        }
        if (message is Resources.XmlAttribute &&
            message.hasCompiledItem() &&
            message.compiledItem.hasRef()
        ) {
            val reference = message.compiledItem.ref
            val target = targetFor(reference, nameById, targetByOriginalName)
            output.add(
                BundleRewriteArtifacts.ReferenceDecision(
                    origin,
                    "$fieldPath.value",
                    "RAW_XML_ATTRIBUTE",
                    reference.id,
                    message.value,
                    if (target == null) message.value else rewriteRawReference(message.value, target),
                ),
            )
        }
        for (field in message.descriptorForType.fields) {
            if (field.javaType != Descriptors.FieldDescriptor.JavaType.MESSAGE) continue
            if (field.isRepeated) {
                for (index in 0 until message.getRepeatedFieldCount(field)) {
                    collectReferenceDecisions(
                        message.getRepeatedField(field, index) as Message,
                        origin,
                        fieldPath + "." + field.name + "[" + index + "]",
                        nameById,
                        targetByOriginalName,
                        paths,
                        output,
                    )
                }
            } else if (message.hasField(field)) {
                collectReferenceDecisions(
                    message.getField(field) as Message,
                    origin,
                    fieldPath + "." + field.name,
                    nameById,
                    targetByOriginalName,
                    paths,
                    output,
                )
            }
        }
    }

    private fun collectFilePaths(message: Message, paths: MutableMap<String, Int>) {
        if (message is Resources.FileReference) {
            val previous = paths.putIfAbsent(message.path, message.typeValue)
            if (previous != null && previous != message.typeValue) {
                throw IllegalArgumentException(
                    "FileReference path has incompatible representations: " + message.path,
                )
            }
            return
        }
        for (field in message.descriptorForType.fields) {
            if (field.javaType != Descriptors.FieldDescriptor.JavaType.MESSAGE) continue
            if (field.isRepeated) {
                for (index in 0 until message.getRepeatedFieldCount(field)) {
                    collectFilePaths(message.getRepeatedField(field, index) as Message, paths)
                }
            } else if (message.hasField(field)) {
                collectFilePaths(message.getField(field) as Message, paths)
            }
        }
    }

    private fun resourceId(
        pkg: Resources.Package,
        type: Resources.Type,
        entry: Resources.Entry,
    ): Int = (pkg.packageId.id shl 24) or (type.typeId.id shl 16) or entry.entryId.id

    private fun allocateName(fact: ResourceFact, stream: String, reserved: MutableSet<String>): String {
        val digest = SeedDerivation.derive(
            stream,
            "resource-entry",
            fact.packageName + ":" + fact.type + "/" + fact.name,
        )
        var length = 10
        while (length <= digest.length) {
            val target = "k" + digest.substring(0, length)
            if (reserved.add(target)) return target
            length += 2
        }
        throw IllegalStateException("Unable to allocate resource name for " + fact.name)
    }

    private fun allocatePath(
        fact: ResourceFact,
        path: String,
        targetName: String,
        stream: String,
        reservedPaths: MutableSet<String>,
    ): String {
        var allocatedName = targetName
        val slash = path.lastIndexOf('/')
        val file = path.substring(slash + 1)
        val suffix = suffix(file, fact.name)
        val directory = path.substring(0, slash + 1)
        val digest = SeedDerivation.derive(stream, "resource-path", path)
        var length = 10
        while (length <= digest.length) {
            val base = if (allocatedName == fact.name) {
                "f" + digest.substring(0, length)
            } else {
                allocatedName
            }
            val target = directory + base + suffix
            if (reservedPaths.add("base/$target")) return target
            if (allocatedName != fact.name) allocatedName = "k" + digest.substring(0, length)
            length += 2
        }
        throw IllegalStateException("Unable to allocate resource path for $path")
    }

    private fun suffix(file: String, resourceName: String): String {
        if (!file.startsWith(resourceName) || file.length == resourceName.length ||
            file[resourceName.length] != '.'
        ) {
            throw IllegalArgumentException(
                "File-backed resource path does not match entry name: $file",
            )
        }
        return file.substring(resourceName.length)
    }

    private fun rewriteReferenceName(original: String, target: FullResourceName): String {
        val slash = original.lastIndexOf('/')
        if (slash < 0) return original
        return original.substring(0, slash + 1) + target.name
    }

    private fun rewriteRawReference(original: String, target: FullResourceName): String {
        if (!(original.startsWith("@") || original.startsWith("?"))) return original
        val slash = original.lastIndexOf('/')
        if (slash < 0) return original
        return original.substring(0, slash + 1) + target.name
    }

    @Throws(IOException::class)
    private fun inventoryEntries(zip: ZipFile, context: Context): Map<String, EntryFact> {
        val entries = TreeMap<String, EntryFact>()
        for (entry in zip.stream().filter { !it.isDirectory }
            .sorted(Comparator.comparing(ZipEntry::getName)).toList()
        ) {
            validatePath(entry.name, context)
            if (entries.putIfAbsent(
                    entry.name,
                    EntryFact(
                        entry.name,
                        sha256(zip.getInputStream(entry)),
                        entry.method,
                        entry.size,
                    ),
                ) != null
            ) {
                throw failure(
                    context,
                    entry.name,
                    "Duplicate normalized Bundle entry",
                    "Remove the duplicate entry before Kaleido processing",
                )
            }
        }
        return entries.toMap()
    }

    private fun validatePath(path: String, context: Context) {
        if (path.isBlank() || path.startsWith("/") || path.contains("\\") ||
            path.contains("//") || path == "." || path == ".." ||
            path.startsWith("../") || path.contains("/../") ||
            path.endsWith("/..") || path.startsWith("./") ||
            path.contains("/./") || path.endsWith("/.")
        ) {
            throw failure(
                context,
                path,
                "Bundle entry path is not canonical",
                "Use normalized relative UTF-8 Bundle entry paths",
            )
        }
    }

    private fun isSignature(path: String): Boolean {
        if (!path.startsWith("META-INF/")) return false
        val upper = path.uppercase(Locale.ROOT)
        return upper == "META-INF/MANIFEST.MF" || upper.endsWith(".SF") ||
            upper.endsWith(".RSA") || upper.endsWith(".DSA") ||
            upper.endsWith(".EC")
    }

    private fun isProtectedPayload(path: String): Boolean {
        val lower = path.lowercase(Locale.ROOT)
        return path.startsWith("base/dex/") || path.startsWith("base/lib/") ||
            lower.contains("code-transparency") || lower.contains("code_transparency")
    }

    private fun parseTable(bytes: ByteArray, context: Context): Resources.ResourceTable = try {
        Resources.ResourceTable.parseFrom(bytes)
    } catch (failure: InvalidProtocolBufferException) {
        throw failure(
            context,
            RESOURCE_TABLE,
            "Resource table protobuf is invalid",
            "Rebuild the App Bundle with the supported AGP/AAPT2 toolchain",
        )
    }

    private fun parseXml(bytes: ByteArray, path: String, context: Context): Resources.XmlNode = try {
        Resources.XmlNode.parseFrom(bytes)
    } catch (failure: InvalidProtocolBufferException) {
        throw failure(
            context,
            path,
            "Compiled XML protobuf is invalid",
            "Rebuild the App Bundle with the supported AGP/AAPT2 toolchain",
        )
    }

    @Throws(IOException::class)
    private fun read(zip: ZipFile, entry: ZipEntry): ByteArray =
        zip.getInputStream(entry).use { it.readAllBytes() }

    @Throws(IOException::class)
    private fun sha256(path: Path): String =
        Files.newInputStream(path).use { sha256(it) }

    private fun sha256(bytes: ByteArray): String = try {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        HexFormat.of().formatHex(digest)
    } catch (impossible: NoSuchAlgorithmException) {
        throw IllegalStateException("SHA-256 is unavailable", impossible)
    }

    @Throws(IOException::class)
    private fun sha256(input: InputStream): String {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
            return HexFormat.of().formatHex(digest.digest())
        } catch (impossible: NoSuchAlgorithmException) {
            throw IllegalStateException("SHA-256 is unavailable", impossible)
        } finally {
            input.close()
        }
    }

    private fun failure(
        context: Context,
        target: String,
        reason: String,
        repair: String,
    ): GradleException = KaleidoDiagnostic(
        "KLD-BUNDLE-001",
        context.project,
        context.variant,
        "bundle-rewrite",
        BundleRewriteArtifacts.PLAN_SCHEMA,
        target,
        reason,
        repair,
    ).failure()

    @JvmRecord
    data class Context(
        val project: String,
        val variant: String,
        val warningSink: Consumer<String>,
    ) {
        constructor(project: String, variant: String) : this(project, variant, Consumer { })

        fun warn(warning: String) {
            warningSink.accept(warning)
        }
    }

    @JvmRecord
    data class FullControls(
        val nativeLibrariesToDelete: Set<String>,
        val metadataToDelete: Set<String>,
        val confirmedUnusedStrings: Set<String>,
        val retainedLanguages: Set<String>,
        val ownedNativeLibraries: Set<String>,
        val ownedMetadata: Set<String>,
    ) {
        companion object {
            @JvmStatic
            fun none(): FullControls = FullControls(
                emptySet(),
                emptySet(),
                emptySet(),
                emptySet(),
                emptySet(),
                emptySet(),
            )
        }
    }

    @JvmRecord
    data class ResourceKey(val type: String, val name: String)

    @JvmRecord
    data class Execution(val resourceMapping: String, val receiptBytes: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Execution) return false
            return resourceMapping == other.resourceMapping &&
                receiptBytes.contentEquals(other.receiptBytes)
        }

        override fun hashCode(): Int =
            31 * resourceMapping.hashCode() + receiptBytes.contentHashCode()
    }

    @JvmRecord
    private data class FullResourceName(val packageName: String, val type: String, val name: String)

    @JvmRecord
    private data class ResourceFact(
        val id: Int,
        val packageName: String,
        val type: String,
        val name: String,
        val fileTypes: Map<String, Int>,
    ) {
        fun filePaths(): List<String> = fileTypes.keys.sorted()
    }

    @JvmRecord
    private data class EntryFact(
        val path: String,
        val sha256: String,
        val method: Int,
        val size: Long,
    )

    @JvmRecord
    private data class DedupKey(
        val directory: String,
        val representation: Int,
        val suffix: String,
        val size: Long,
        val sha256: String,
    )

    @JvmRecord
    private data class DedupCandidate(
        val decisionIndex: Int,
        val pathIndex: Int,
        val originalPath: String,
        val targetPath: String,
    )

    @JvmRecord
    private data class Deduplication(
        val resources: List<BundleRewriteArtifacts.ResourceDecision>,
        val redundantInputPaths: Set<String>,
        val protectedPayloadPaths: Set<String>,
    )

    @JvmRecord
    private data class ControlPlan(
        val decisions: List<BundleRewriteArtifacts.ControlDecision>,
        val deletedEntries: Set<String>,
    )
}
