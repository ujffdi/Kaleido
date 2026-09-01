package com.tongsr.kaleido.gradle;

import com.android.aapt.Resources;
import com.android.tools.build.bundletool.commands.ValidateBundleCommand;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

final class BundleRewriteModule {
    private static final String RESOURCE_TABLE = "base/resources.pb";

    private BundleRewriteModule() {}

    static BundleRewriteArtifacts.Plan plan(
            Path inputAab,
            Context context,
            Set<ResourceKey> applicationResources,
            Set<String> protectedNames,
            Set<String> protectedPaths,
            List<EscapeHatchDeclaration> resourceHatches,
            String seedStream,
            FullControls fullControls) throws IOException {
        try (var zip = new ZipFile(inputAab.toFile())) {
            var entries = inventoryEntries(zip, context);
            var resourceEntry = zip.getEntry(RESOURCE_TABLE);
            if (resourceEntry == null) {
                throw failure(context, RESOURCE_TABLE, "Base resource table is missing",
                        "Rebuild a complete base-module App Bundle");
            }
            var tableBytes = read(zip, resourceEntry);
            var table = parseTable(tableBytes, context);
            var facts = resourceFacts(table);
            var compiledXmlPaths = facts.stream()
                    .flatMap(fact -> fact.fileTypes().entrySet().stream())
                    .filter(item -> item.getValue()
                            == Resources.FileReference.Type.PROTO_XML_VALUE)
                    .map(item -> "base/" + item.getKey())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            var allPaths = entries.keySet();
            var pathRefCounts = new HashMap<String, Integer>();
            facts.forEach(fact -> fact.filePaths().forEach(path ->
                    pathRefCounts.merge(path, 1, Integer::sum)));
            for (var path : pathRefCounts.keySet()) {
                if (!allPaths.contains("base/" + path)) {
                    throw failure(context, path, "Resource table has a dangling file reference",
                            "Restore the referenced base-module resource payload");
                }
            }
            for (var path : allPaths.stream().filter(p -> p.startsWith("base/res/")).toList()) {
                if (!pathRefCounts.containsKey(path.substring("base/".length()))) {
                    throw failure(context, path, "Base resource payload is orphaned from resources.pb",
                            "Remove the orphan or restore its resource-table reference");
                }
            }

            var reservedNames = new HashMap<String, Set<String>>();
            for (var fact : facts) reservedNames.computeIfAbsent(
                    fact.packageName() + ":" + fact.type(), ignored -> new HashSet<>())
                    .add(fact.name());
            var reservedPaths = new HashSet<>(allPaths);
            var resourceDecisions = new ArrayList<BundleRewriteArtifacts.ResourceDecision>();
            var pathMapping = new TreeMap<String, String>();
            for (var fact : facts) {
                var key = new ResourceKey(fact.type(), fact.name());
                var owned = applicationResources.contains(key);
                var hatchNameProtected = resourceHatches.stream().anyMatch(hatch ->
                        hatch.matches(fact.name()) && hatch.protects(
                                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.RESOURCE_NAME));
                var hatchPathProtected = resourceHatches.stream().anyMatch(hatch ->
                        hatch.matches(fact.name()) && hatch.protects(
                                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.PACKAGED_PATH));
                var nameProtected = protectedNames.contains(fact.name()) || hatchNameProtected;
                var anyDeclaredPathProtected = fact.filePaths().stream().anyMatch(path ->
                        protectedPaths.contains(path) || protectedPaths.contains("base/" + path));
                var pathProtected = hatchPathProtected || anyDeclaredPathProtected;
                var targetName = fact.name();
                if (owned && !nameProtected) {
                    targetName = allocateName(fact, seedStream,
                            reservedNames.get(fact.packageName() + ":" + fact.type()));
                }
                var originalPaths = new ArrayList<>(fact.filePaths());
                var targetPaths = new ArrayList<String>();
                for (var path : originalPaths) {
                    var targetPath = path;
                    if (owned && !pathProtected && pathRefCounts.getOrDefault(path, 0) == 1) {
                        targetPath = allocatePath(fact, path, targetName, seedStream, reservedPaths);
                        pathMapping.put(path, targetPath);
                    }
                    targetPaths.add(targetPath);
                }
                var renamedName = !targetName.equals(fact.name());
                var renamedPath = !originalPaths.equals(targetPaths);
                var action = renamedName && renamedPath ? "REWRITE_NAME_AND_PATH"
                        : renamedName ? "REWRITE_NAME"
                        : renamedPath ? "REWRITE_PATH"
                        : owned && (nameProtected || pathProtected) ? "PROTECTED"
                        : owned ? "UNCHANGED" : "DEPENDENCY";
                var reason = !owned ? "not-application-owned"
                        : nameProtected || pathProtected ? "protection-requirement"
                        : !fact.filePaths().isEmpty()
                                && fact.filePaths().stream().anyMatch(path ->
                                        pathRefCounts.getOrDefault(path, 0) > 1)
                                ? "shared-file-reference-retained"
                                : "eligible-application-resource";
                resourceDecisions.add(new BundleRewriteArtifacts.ResourceDecision(
                        fact.id(), fact.packageName(), fact.type(), fact.name(), targetName,
                        action, reason, originalPaths, targetPaths,
                        nameProtected, pathProtected));
            }

            var deduplication = deduplicate(zip, facts, resourceDecisions, pathMapping, entries,
                    context);
            resourceDecisions = new ArrayList<>(deduplication.resources());
            var zipDecisions = new ArrayList<BundleRewriteArtifacts.EntryDecision>();
            var expected = new TreeSet<String>();
            var plannedNamesById = new HashMap<Integer, FullResourceName>();
            var plannedNamesByOriginal = new HashMap<String, FullResourceName>();
            for (var decision : resourceDecisions) {
                if (!decision.originalName().equals(decision.targetName())) {
                    var target = new FullResourceName(decision.packageName(), decision.type(),
                            decision.targetName());
                    plannedNamesById.put(decision.id(), target);
                    plannedNamesByOriginal.put(decision.packageName() + ":" + decision.type()
                            + "/" + decision.originalName(), target);
                    plannedNamesByOriginal.put(decision.type() + "/" + decision.originalName(),
                            target);
                }
            }
            var references = new ArrayList<BundleRewriteArtifacts.ReferenceDecision>();
            collectReferenceDecisions(table, RESOURCE_TABLE, "$", plannedNamesById,
                    plannedNamesByOriginal, pathMapping, references);
            for (var entry : entries.values()) {
                if (compiledXmlPaths.contains(entry.path())
                        || entry.path().equals("base/manifest/AndroidManifest.xml")) {
                    collectReferenceDecisions(parseXml(read(zip, zip.getEntry(entry.path())),
                                    entry.path(), context), entry.path(), "$",
                            plannedNamesById, plannedNamesByOriginal, pathMapping, references);
                }
            }
            var controlPlan = planFullControls(zip, table, facts, resourceDecisions,
                    applicationResources, protectedPaths, entries, references,
                    fullControls, context);
            for (var entry : entries.values()) {
                var signature = isSignature(entry.path());
                var relative = entry.path().startsWith("base/")
                        ? entry.path().substring("base/".length()) : "";
                var outputPath = pathMapping.containsKey(relative)
                        ? "base/" + pathMapping.get(relative) : entry.path();
                var deduplicated = deduplication.redundantInputPaths().contains(entry.path());
                var fullControlDeleted = controlPlan.deletedEntries().contains(entry.path());
                if (deduplicated) outputPath = "";
                if (fullControlDeleted) outputPath = "";
                var action = fullControlDeleted ? "DELETE_FULL_CONTROL"
                        : signature ? "DELETE_INVALID_SIGNATURE"
                        : deduplicated ? "DELETE_DEDUPLICATED_PAYLOAD"
                        : RESOURCE_TABLE.equals(entry.path()) ? "REWRITE_RESOURCE_TABLE"
                        : compiledXmlPaths.contains(entry.path())
                                || entry.path().equals("base/manifest/AndroidManifest.xml")
                                ? "REWRITE_COMPILED_XML"
                        : !outputPath.equals(entry.path()) ? "RENAME_RESOURCE_PAYLOAD" : "COPY";
                var preserve = isProtectedPayload(entry.path())
                        || deduplication.protectedPayloadPaths().contains(entry.path());
                if (fullControlDeleted) preserve = false;
                zipDecisions.add(new BundleRewriteArtifacts.EntryDecision(
                        entry.path(), entry.sha256(), entry.method(),
                        signature || deduplicated || fullControlDeleted ? "" : outputPath,
                        action, preserve));
                if (!signature && !deduplicated && !fullControlDeleted
                        && !expected.add(outputPath)) {
                    throw failure(context, outputPath, "Bundle output path collision",
                            "Change the seed or protect one colliding resource path");
                }
            }
            return new BundleRewriteArtifacts.Plan(
                    BundleRewriteArtifacts.PLAN_SCHEMA,
                    BundleRewriteArtifacts.PRODUCER,
                    context.project(), context.variant(), sha256(inputAab), sha256(tableBytes),
                    resourceDecisions, zipDecisions, List.copyOf(expected), references,
                    controlPlan.decisions());
        }
    }

    static Execution execute(
            Path inputAab,
            Path outputAab,
            BundleRewriteArtifacts.Plan plan,
            byte[] planBytes,
            Context context) throws IOException {
        if (!plan.project().equals(context.project()) || !plan.variant().equals(context.variant())) {
            throw failure(context, "BundleRewritePlan.v1",
                    "Bundle Rewrite Plan belongs to a different project or variant",
                    "Regenerate the plan for this exact Release variant");
        }
        if (!sha256(inputAab).equals(plan.inputAabSha256())) {
            throw failure(context, inputAab.toString(),
                    "Input AAB digest differs from Bundle Rewrite Plan",
                    "Regenerate and execute the plan against one stable input AAB");
        }
        var nameById = new HashMap<Integer, FullResourceName>();
        var targetByOriginalName = new HashMap<String, FullResourceName>();
        var paths = new TreeMap<String, String>();
        for (var decision : plan.resources()) {
            if (!decision.originalName().equals(decision.targetName())) {
                var target = new FullResourceName(
                        decision.packageName(), decision.type(), decision.targetName());
                nameById.put(decision.id(), target);
                targetByOriginalName.put(decision.packageName() + ":" + decision.type()
                        + "/" + decision.originalName(), target);
                targetByOriginalName.put(decision.type() + "/" + decision.originalName(), target);
            }
            for (var index = 0; index < decision.originalPaths().size(); index++) {
                var original = decision.originalPaths().get(index);
                var target = decision.targetPaths().get(index);
                if (!original.equals(target)) paths.put(original, target);
            }
        }
        var entryPlan = plan.entries().stream().collect(java.util.stream.Collectors.toMap(
                BundleRewriteArtifacts.EntryDecision::inputPath,
                java.util.function.Function.identity()));
        var changedPayloads = new HashMap<String, byte[]>();
        var preservedDigests = new ArrayList<String>();
        try (var zip = new ZipFile(inputAab.toFile())) {
            for (var decision : plan.entries()) {
                var entry = zip.getEntry(decision.inputPath());
                if (entry == null || !sha256(zip.getInputStream(entry)).equals(decision.inputSha256())) {
                    throw failure(context, decision.inputPath(),
                            "Planned Bundle entry is missing or changed",
                            "Regenerate the Bundle Rewrite Plan from stable input");
                }
                if (decision.preservePayload()) {
                    preservedDigests.add(decision.inputPath() + "|" + decision.inputSha256());
                }
                if ("REWRITE_RESOURCE_TABLE".equals(decision.action())) {
                    var table = parseTable(read(zip, entry), context);
                    changedPayloads.put(decision.inputPath(), rewriteTable(table, plan,
                            nameById, targetByOriginalName, paths, context).toByteArray());
                } else if ("REWRITE_COMPILED_XML".equals(decision.action())) {
                    var node = parseXml(read(zip, entry), decision.inputPath(), context);
                    changedPayloads.put(decision.inputPath(),
                            rewriteMessage(node, nameById, targetByOriginalName, paths).toByteArray());
                }
            }
            writeBundle(zip, outputAab, plan, entryPlan, changedPayloads, context);
        }

        validateOutput(outputAab, plan, nameById, targetByOriginalName, paths, context);
        try {
            ValidateBundleCommand.builder().setBundlePath(outputAab).setPrintOutput(false)
                    .build().execute();
        } catch (RuntimeException invalid) {
            throw failure(context, outputAab.toString(),
                    "bundletool rejected the rewritten unsigned App Bundle: "
                            + String.valueOf(invalid.getMessage()),
                    "Inspect the Bundle rewrite plan and restore reference closure");
        }
        var mapping = resourceMapping(plan);
        var mappingBytes = mapping.getBytes(StandardCharsets.UTF_8);
        var receipt = new BundleRewriteArtifacts.Receipt(
                BundleRewriteArtifacts.RECEIPT_SCHEMA,
                BundleRewriteArtifacts.PRODUCER,
                context.project(), context.variant(),
                sha256(planBytes), plan.inputAabSha256(), sha256(outputAab),
                sha256(mappingBytes), plan.resources().size(),
                (int) plan.resources().stream().filter(item ->
                        !item.originalName().equals(item.targetName())).count(),
                paths.size(), true, true, true, plan.references().size(), preservedDigests,
                protectionDigests(plan, context));
        return new Execution(mapping, BundleRewriteArtifacts.encodeReceipt(receipt));
    }

    private static Resources.ResourceTable rewriteTable(
            Resources.ResourceTable table,
            BundleRewriteArtifacts.Plan plan,
            Map<Integer, FullResourceName> nameById,
            Map<String, FullResourceName> targetByOriginalName,
            Map<String, String> paths,
            Context context) {
        var decisions = plan.resources().stream().collect(java.util.stream.Collectors.toMap(
                BundleRewriteArtifacts.ResourceDecision::id,
                java.util.function.Function.identity()));
        var replacementControls = plan.controls().stream()
                .filter(control -> control.kind().equals("REPLACE_UNUSED_STRING"))
                .collect(java.util.stream.Collectors.toMap(control ->
                                Integer.parseUnsignedInt(control.target().substring(2), 16),
                        java.util.function.Function.identity()));
        var languageControls = new HashMap<Integer, Map<Integer,
                BundleRewriteArtifacts.ControlDecision>>();
        for (var control : plan.controls().stream()
                .filter(item -> item.kind().equals("FILTER_LANGUAGE")).toList()) {
            var parts = control.target().split("\\|");
            var id = Integer.parseUnsignedInt(parts[0].substring(2), 16);
            languageControls.computeIfAbsent(id, ignored -> new HashMap<>())
                    .put(Integer.parseInt(parts[2]), control);
        }
        var builder = table.toBuilder();
        for (var packageIndex = 0; packageIndex < table.getPackageCount(); packageIndex++) {
            var pkg = table.getPackage(packageIndex);
            var packageBuilder = pkg.toBuilder();
            for (var typeIndex = 0; typeIndex < pkg.getTypeCount(); typeIndex++) {
                var type = pkg.getType(typeIndex);
                var typeBuilder = type.toBuilder();
                for (var entryIndex = 0; entryIndex < type.getEntryCount(); entryIndex++) {
                    var entry = type.getEntry(entryIndex);
                    var id = resourceId(pkg, type, entry);
                    var decision = decisions.get(id);
                    if (decision == null) {
                        throw failure(context, String.format("0x%08x", id),
                                "Resource ID was not inventoried by the plan",
                                "Regenerate the Bundle Rewrite Plan from this exact AAB");
                    }
                    var replacement = replacementControls.get(id);
                    var controlledEntry = entry;
                    if (replacement != null) {
                        if (!sha256(entry.toByteArray()).equals(replacement.inputSha256())) {
                            throw failure(context, replacement.target(),
                                    "Unused-string control input digest differs from the plan",
                                    "Regenerate the plan from this exact resource table");
                        }
                        controlledEntry = replaceStringEntry(entry, context);
                        if (!sha256(controlledEntry.toByteArray()).equals(
                                replacement.outputSha256())) {
                            throw failure(context, replacement.target(),
                                    "Unused-string replacement differs from the planned output",
                                    "Use the fixed Kaleido string replacement algorithm");
                        }
                    }
                    var entryBuilder = controlledEntry.toBuilder().setName(decision.targetName())
                            .clearConfigValue();
                    for (var index = 0; index < entry.getConfigValueCount(); index++) {
                        var languageControl = languageControls
                                .getOrDefault(id, Map.of()).get(index);
                        if (languageControl != null) {
                            if (!sha256(entry.getConfigValue(index).toByteArray()).equals(
                                    languageControl.inputSha256())) {
                                throw failure(context, languageControl.target(),
                                        "Language control input digest differs from the plan",
                                        "Regenerate the plan from this exact resource table");
                            }
                            continue;
                        }
                        entryBuilder.addConfigValue((Resources.ConfigValue) rewriteMessage(
                                controlledEntry.getConfigValue(index), nameById,
                                targetByOriginalName, paths));
                    }
                    for (var index = 0; index < entry.getFlagDisabledConfigValueCount(); index++) {
                        entryBuilder.setFlagDisabledConfigValue(index,
                                (Resources.ConfigValue) rewriteMessage(
                                        entry.getFlagDisabledConfigValue(index),
                                        nameById, targetByOriginalName, paths));
                    }
                    for (var index = 0; index < entry.getReadwriteFlagConfigValueCount(); index++) {
                        entryBuilder.setReadwriteFlagConfigValue(index,
                                (Resources.ConfigValue) rewriteMessage(
                                        entry.getReadwriteFlagConfigValue(index),
                                        nameById, targetByOriginalName, paths));
                    }
                    typeBuilder.setEntry(entryIndex, entryBuilder);
                }
                packageBuilder.setType(typeIndex, typeBuilder);
            }
            builder.setPackage(packageIndex, packageBuilder);
        }
        return builder.build();
    }

    private static Message rewriteMessage(
            Message message,
            Map<Integer, FullResourceName> nameById,
            Map<String, FullResourceName> targetByOriginalName,
            Map<String, String> paths) {
        if (message instanceof Resources.Reference reference) {
            var target = targetFor(reference, nameById, targetByOriginalName);
            if (target == null || reference.getName().isEmpty()) return reference;
            return reference.toBuilder().setName(rewriteReferenceName(reference.getName(), target))
                    .build();
        }
        if (message instanceof Resources.FileReference file) {
            var target = paths.get(file.getPath());
            return target == null ? file : file.toBuilder().setPath(target).build();
        }
        if (message instanceof Resources.XmlAttribute attribute) {
            var builder = attribute.toBuilder();
            if (attribute.hasCompiledItem() && attribute.getCompiledItem().hasRef()) {
                var target = nameById.get(attribute.getCompiledItem().getRef().getId());
                if (target == null) target = targetFor(attribute.getCompiledItem().getRef(),
                        nameById, targetByOriginalName);
                if (target != null) builder.setValue(
                        rewriteRawReference(attribute.getValue(), target));
            }
            if (attribute.hasCompiledItem()) {
                builder.setCompiledItem((Resources.Item) rewriteMessage(
                        attribute.getCompiledItem(), nameById, targetByOriginalName, paths));
            }
            return builder.build();
        }
        var builder = message.toBuilder();
        for (var field : message.getDescriptorForType().getFields()) {
            if (field.getJavaType() != Descriptors.FieldDescriptor.JavaType.MESSAGE) continue;
            if (field.isRepeated()) {
                var count = message.getRepeatedFieldCount(field);
                for (var index = 0; index < count; index++) {
                    builder.setRepeatedField(field, index, rewriteMessage(
                            (Message) message.getRepeatedField(field, index),
                            nameById, targetByOriginalName, paths));
                }
            } else if (message.hasField(field)) {
                builder.setField(field, rewriteMessage((Message) message.getField(field),
                        nameById, targetByOriginalName, paths));
            }
        }
        return builder.build();
    }

    private static FullResourceName targetFor(
            Resources.Reference reference,
            Map<Integer, FullResourceName> nameById,
            Map<String, FullResourceName> targetByOriginalName) {
        var target = nameById.get(reference.getId());
        return target != null ? target : targetByOriginalName.get(reference.getName());
    }

    private static void writeBundle(
            ZipFile input,
            Path output,
            BundleRewriteArtifacts.Plan plan,
            Map<String, BundleRewriteArtifacts.EntryDecision> decisions,
            Map<String, byte[]> changedPayloads,
            Context context) throws IOException {
        Files.createDirectories(output.getParent());
        var temporary = output.resolveSibling(output.getFileName() + ".tmp");
        Files.deleteIfExists(temporary);
        var byOutput = plan.entries().stream()
                .filter(item -> !item.outputPath().isEmpty())
                .sorted(Comparator.comparing(BundleRewriteArtifacts.EntryDecision::outputPath))
                .toList();
        try (var stream = new ZipOutputStream(Files.newOutputStream(temporary),
                StandardCharsets.UTF_8)) {
            stream.setLevel(9);
            for (var decision : byOutput) {
                var inputEntry = input.getEntry(decision.inputPath());
                var bytes = changedPayloads.get(decision.inputPath());
                var outputEntry = new ZipEntry(decision.outputPath());
                outputEntry.setTime(0L);
                outputEntry.setMethod(decision.compressionMethod());
                if (decision.compressionMethod() == ZipEntry.STORED) {
                    if (bytes == null) bytes = read(input, inputEntry);
                    var crc = new CRC32();
                    crc.update(bytes);
                    outputEntry.setSize(bytes.length);
                    outputEntry.setCompressedSize(bytes.length);
                    outputEntry.setCrc(crc.getValue());
                }
                stream.putNextEntry(outputEntry);
                if (bytes != null) stream.write(bytes);
                else try (var source = input.getInputStream(inputEntry)) { source.transferTo(stream); }
                stream.closeEntry();
            }
        }
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    private static void validateOutput(
            Path output,
            BundleRewriteArtifacts.Plan plan,
            Map<Integer, FullResourceName> changedNames,
            Map<String, FullResourceName> targetByOriginalName,
            Map<String, String> changedPaths,
            Context context) throws IOException {
        try (var zip = new ZipFile(output.toFile())) {
            var actualPaths = zip.stream().filter(Predicate.not(ZipEntry::isDirectory))
                    .map(ZipEntry::getName).sorted().toList();
            if (!actualPaths.equals(plan.expectedOutputs())) {
                throw failure(context, output.toString(),
                        "Bundle output entries differ from the immutable plan",
                        "Delete stale outputs and rebuild this Release variant");
            }
            var existingPaths = Set.copyOf(actualPaths);
            var tableEntry = zip.getEntry(RESOURCE_TABLE);
            var table = parseTable(read(zip, tableEntry), context);
            validateReferenceClosure(table, changedNames, targetByOriginalName,
                    changedPaths, existingPaths, context, RESOURCE_TABLE);
            var ids = new TreeSet<Integer>(Integer::compareUnsigned);
            for (var fact : resourceFacts(table)) {
                ids.add(fact.id());
                var expected = changedNames.get(fact.id());
                if (expected != null && !expected.name().equals(fact.name())) {
                    throw failure(context, String.format("0x%08x", fact.id()),
                            "Rewritten resource name differs from the plan",
                            "Regenerate and re-execute the Bundle Rewrite Plan");
                }
                for (var path : fact.filePaths()) {
                    if (!existingPaths.contains("base/" + path)) {
                        throw failure(context, path, "Rewritten file reference is dangling",
                                "Restore the planned resource payload path");
                    }
                }
            }
            var expectedIds = plan.resources().stream().map(
                    BundleRewriteArtifacts.ResourceDecision::id)
                    .collect(java.util.stream.Collectors.toCollection(
                            () -> new TreeSet<>(Integer::compareUnsigned)));
            if (!ids.equals(expectedIds)) {
                throw failure(context, RESOURCE_TABLE,
                        "Numeric resource ID set changed during Bundle rewrite",
                        "Preserve every package, type, and entry ID");
            }
            var compiledXmlPaths = resourceFacts(table).stream()
                    .flatMap(fact -> fact.fileTypes().entrySet().stream())
                    .filter(item -> item.getValue()
                            == Resources.FileReference.Type.PROTO_XML_VALUE)
                    .map(item -> "base/" + item.getKey())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            for (var entry : zip.stream().filter(item ->
                            item.getName().equals("base/manifest/AndroidManifest.xml")
                                    || compiledXmlPaths.contains(item.getName()))
                    .toList()) {
                var xml = parseXml(read(zip, entry), entry.getName(), context);
                validateReferenceClosure(xml, changedNames, targetByOriginalName,
                        changedPaths, existingPaths, context, entry.getName());
            }
            for (var decision : plan.entries().stream().filter(
                    BundleRewriteArtifacts.EntryDecision::preservePayload).toList()) {
                var entry = zip.getEntry(decision.outputPath());
                if (entry == null || !sha256(zip.getInputStream(entry)).equals(
                        decision.inputSha256())) {
                    throw failure(context, decision.inputPath(),
                            "DEX, native, or code-transparency payload drifted",
                            "Byte-preserve protected code payloads during resource rewrite");
                }
            }
        }
    }

    private static void validateReferenceClosure(
            Message message,
            Map<Integer, FullResourceName> changedNames,
            Map<String, FullResourceName> targetByOriginalName,
            Map<String, String> changedPaths,
            Set<String> existingPaths,
            Context context,
            String origin) {
        if (message instanceof Resources.Reference reference) {
            var target = changedNames.get(reference.getId());
            if (target != null && !reference.getName().isEmpty()
                    && !reference.getName().endsWith("/" + target.name())) {
                throw failure(context, origin,
                        "Resource Reference.name did not close through the plan",
                        "Rewrite every name-bearing reference with its numeric resource ID");
            }
            if (targetByOriginalName.containsKey(reference.getName())) {
                throw failure(context, origin,
                        "Name-only resource reference retains a mapped original identity",
                        "Rewrite the name-bearing reference through the resource mapping");
            }
            return;
        }
        if (message instanceof Resources.FileReference file) {
            if (changedPaths.containsKey(file.getPath())) {
                throw failure(context, origin,
                        "FileReference.path retains a mapped original path",
                        "Rewrite every file reference through the path mapping");
            }
            if (!existingPaths.contains("base/" + file.getPath())) {
                throw failure(context, file.getPath(),
                        "FileReference.path does not resolve to an output payload",
                        "Restore the planned base-module resource payload");
            }
            return;
        }
        for (var field : message.getDescriptorForType().getFields()) {
            if (field.getJavaType() != Descriptors.FieldDescriptor.JavaType.MESSAGE) continue;
            if (field.isRepeated()) {
                for (var index = 0; index < message.getRepeatedFieldCount(field); index++) {
                    validateReferenceClosure((Message) message.getRepeatedField(field, index),
                            changedNames, targetByOriginalName, changedPaths,
                            existingPaths, context, origin);
                }
            } else if (message.hasField(field)) {
                validateReferenceClosure((Message) message.getField(field),
                        changedNames, targetByOriginalName, changedPaths,
                        existingPaths, context, origin);
            }
        }
    }

    private static String resourceMapping(BundleRewriteArtifacts.Plan plan) {
        var text = new StringBuilder("schema=KaleidoResourceMapping.v1\n");
        for (var decision : plan.resources()) {
            if (!decision.originalName().equals(decision.targetName())) {
                text.append(String.format("resource=0x%08x|%s:%s/%s -> %s:%s/%s%n",
                        decision.id(), decision.packageName(), decision.type(),
                        decision.originalName(), decision.packageName(), decision.type(),
                        decision.targetName()));
            }
            for (var index = 0; index < decision.originalPaths().size(); index++) {
                var original = decision.originalPaths().get(index);
                var target = decision.targetPaths().get(index);
                if (!original.equals(target)) {
                    text.append("path=base/").append(original).append(" -> base/")
                            .append(target).append('\n');
                }
            }
        }
        return text.toString();
    }

    private static List<String> protectionDigests(
            BundleRewriteArtifacts.Plan plan, Context context) {
        var digests = new TreeSet<String>();
        var protectedIds = new TreeSet<Integer>(Integer::compareUnsigned);
        for (var resource : plan.resources()) {
            if (!resource.nameProtected() && !resource.pathProtected()) continue;
            if (resource.nameProtected()) protectedIds.add(resource.id());
            if (resource.nameProtected()
                    && !resource.originalName().equals(resource.targetName())) {
                throw failure(context, resource.originalName(),
                        "Protected resource name drifted in the executable plan",
                        "Restore the original protected resource entry name");
            }
            if (resource.pathProtected()
                    && !resource.originalPaths().equals(resource.targetPaths())) {
                throw failure(context, resource.originalName(),
                        "Protected packaged path drifted in the executable plan",
                        "Restore every original protected packaged path");
            }
            var beforeIdentity = resource.packageName() + ":" + resource.type()
                    + "/" + resource.originalName();
            var afterIdentity = resource.packageName() + ":" + resource.type()
                    + "/" + resource.targetName();
            digests.add("name|" + String.format("%08x", resource.id()) + "|"
                    + sha256(beforeIdentity.getBytes(StandardCharsets.UTF_8)) + "|"
                    + sha256(afterIdentity.getBytes(StandardCharsets.UTF_8)));
            for (var index = 0; index < resource.originalPaths().size(); index++) {
                digests.add("path|" + String.format("%08x", resource.id()) + "|"
                        + sha256(resource.originalPaths().get(index)
                                .getBytes(StandardCharsets.UTF_8)) + "|"
                        + sha256(resource.targetPaths().get(index)
                                .getBytes(StandardCharsets.UTF_8)));
            }
        }
        for (var reference : plan.references()) {
            if (!protectedIds.contains(reference.resourceId())) continue;
            if (!reference.originalValue().equals(reference.targetValue())) {
                throw failure(context, reference.origin(),
                        "Protected resource attribute or reference drifted in the plan",
                        "Retain the original protected name-bearing reference");
            }
            digests.add("reference|" + reference.kind() + "|"
                    + String.format("%08x", reference.resourceId()) + "|"
                    + sha256(reference.originalValue().getBytes(StandardCharsets.UTF_8)) + "|"
                    + sha256(reference.targetValue().getBytes(StandardCharsets.UTF_8)));
        }
        return List.copyOf(digests);
    }

    private static ControlPlan planFullControls(
            ZipFile zip,
            Resources.ResourceTable table,
            List<ResourceFact> facts,
            List<BundleRewriteArtifacts.ResourceDecision> resources,
            Set<ResourceKey> applicationResources,
            Set<String> protectedPaths,
            Map<String, EntryFact> entries,
            List<BundleRewriteArtifacts.ReferenceDecision> references,
            FullControls controls,
            Context context) throws IOException {
        var decisions = new ArrayList<BundleRewriteArtifacts.ControlDecision>();
        var deletedEntries = new TreeSet<String>();
        for (var selector : controls.nativeLibrariesToDelete().stream().sorted().toList()) {
            if (!controls.ownedNativeLibraries().contains(selector)) {
                throw failure(context, selector,
                        "Native deletion target is not Application-owned",
                        "Select an exact native library from Application jniLibs sources");
            }
            var matches = entries.values().stream()
                    .filter(entry -> entry.path().startsWith("base/lib/")
                            && entry.path().endsWith("/" + selector))
                    .toList();
            if (matches.isEmpty()) throw failure(context, selector,
                    "Native deletion selector resolves to zero Bundle entries",
                    "Select an exact packaged Application native library");
            var loadName = selector.substring("lib".length(),
                    selector.length() - ".so".length());
            for (var dex : entries.values().stream()
                    .filter(entry -> entry.path().startsWith("base/dex/")).toList()) {
                if (containsAscii(read(zip, zip.getEntry(dex.path())), loadName)) {
                    throw failure(context, selector,
                            "Native deletion intersects a modeled code loading reference",
                            "Remove the deletion or the System.loadLibrary reference");
                }
            }
            for (var match : matches) {
                if (isPathProtected(match.path(), protectedPaths)) {
                    throw failure(context, match.path(),
                            "Native deletion intersects a packaged-path protection",
                            "Remove the deletion or the Protection Requirement");
                }
                deletedEntries.add(match.path());
                decisions.add(new BundleRewriteArtifacts.ControlDecision(
                        "DELETE_NATIVE", match.path(), match.sha256(), sha256(new byte[0]),
                        "DELETE_ENTRY", "explicit-full-profile-native-selector", false));
            }
        }
        for (var deleted : deletedEntries.stream()
                .filter(path -> path.startsWith("base/lib/")).toList()) {
            var directory = deleted.substring(0, deleted.lastIndexOf('/') + 1);
            var retainedInTarget = entries.keySet().stream().anyMatch(path ->
                    path.startsWith(directory) && !deletedEntries.contains(path));
            if (!retainedInTarget) throw failure(context, directory,
                    "Native deletion would leave an empty targeted ABI directory",
                    "Retain at least one native library for this ABI or remove the ABI upstream");
        }
        for (var selector : controls.metadataToDelete().stream().sorted().toList()) {
            if (!controls.ownedMetadata().contains(selector)) {
                throw failure(context, selector,
                        "Metadata deletion target is not Application-owned",
                        "Select exact permitted metadata from Application resources sources");
            }
            var flattenedPath = "base/root/"
                    + selector.substring("META-INF/".length());
            var matches = entries.values().stream().filter(entry ->
                    entry.path().endsWith("/" + selector)
                            || entry.path().equals(flattenedPath)).toList();
            if (matches.isEmpty()) throw failure(context, selector,
                    "Metadata deletion selector resolves to zero Bundle entries",
                    "Select an exact packaged Application META-INF entry");
            for (var match : matches) {
                if (isPathProtected(match.path(), protectedPaths)
                        || isProtectedPayload(match.path()) || isSignature(match.path())) {
                    throw failure(context, match.path(),
                            "Metadata deletion intersects protected or signing content",
                            "Select only permitted unprotected Application metadata");
                }
                deletedEntries.add(match.path());
                decisions.add(new BundleRewriteArtifacts.ControlDecision(
                        "DELETE_METADATA", match.path(), match.sha256(), sha256(new byte[0]),
                        "DELETE_ENTRY", "explicit-full-profile-metadata-selector", false));
            }
        }

        var resourceById = resources.stream().collect(java.util.stream.Collectors.toMap(
                BundleRewriteArtifacts.ResourceDecision::id,
                java.util.function.Function.identity()));
        var referencedIds = references.stream()
                .map(BundleRewriteArtifacts.ReferenceDecision::resourceId)
                .filter(id -> id != 0).collect(java.util.stream.Collectors.toSet());
        for (var unusedName : controls.confirmedUnusedStrings().stream().sorted().toList()) {
            var matches = facts.stream().filter(fact -> fact.type().equals("string")
                            && fact.name().equals(unusedName)
                            && applicationResources.contains(new ResourceKey("string", unusedName)))
                    .toList();
            if (matches.size() != 1) throw failure(context, unusedName,
                    "Confirmed-unused string does not resolve to one Application resource",
                    "List one exact Application-owned string resource name");
            var fact = matches.get(0);
            var resource = resourceById.get(fact.id());
            if (resource.nameProtected() || resource.pathProtected()) {
                throw failure(context, unusedName,
                        "Unused-string replacement intersects a Protection Requirement",
                        "Remove the target from the confirmed-unused file or protection set");
            }
            if (referencedIds.contains(fact.id())) throw failure(context, unusedName,
                    "Confirmed-unused string remains in the modeled reference graph",
                    "Remove the structural reference or the confirmed-unused declaration");
            var entry = resourceEntry(table, fact.id());
            var replaced = replaceStringEntry(entry, context);
            decisions.add(new BundleRewriteArtifacts.ControlDecision(
                    "REPLACE_UNUSED_STRING", String.format("0x%08x", fact.id()),
                    sha256(entry.toByteArray()), sha256(replaced.toByteArray()),
                    "REPLACE_VALUE", "explicit-confirmed-unused-input", false));
        }

        var matchedLanguages = new TreeSet<String>();
        if (!controls.retainedLanguages().isEmpty()) {
            for (var fact : facts) {
                var resource = resourceById.get(fact.id());
                var owned = applicationResources.contains(new ResourceKey(fact.type(), fact.name()));
                var entry = resourceEntry(table, fact.id());
                var hasDefault = entry.getConfigValueList().stream()
                        .anyMatch(value -> locale(value).isBlank());
                for (var index = 0; index < entry.getConfigValueCount(); index++) {
                    var value = entry.getConfigValue(index);
                    var locale = locale(value);
                    if (locale.isBlank()) continue;
                    var retained = controls.retainedLanguages().stream()
                            .anyMatch(requested -> localeMatches(locale, requested));
                    if (retained) {
                        controls.retainedLanguages().stream()
                                .filter(requested -> localeMatches(locale, requested))
                                .forEach(matchedLanguages::add);
                        continue;
                    }
                    if (!owned) continue;
                    if (!hasDefault) throw failure(context, fact.type() + "/" + fact.name(),
                            "Language filtering cannot prove a default fallback",
                            "Add a default resource value or retain this locale");
                    if (resource.nameProtected() || resource.pathProtected()) {
                        throw failure(context, fact.type() + "/" + fact.name(),
                                "Language filtering intersects a Protection Requirement",
                                "Retain the locale or remove the resource protection");
                    }
                    decisions.add(new BundleRewriteArtifacts.ControlDecision(
                            "FILTER_LANGUAGE", String.format("0x%08x|config|%d", fact.id(), index),
                            sha256(value.toByteArray()), sha256(new byte[0]),
                            "DELETE_CONFIG", "locale=" + locale, false));
                }
            }
            var unmatched = new TreeSet<>(controls.retainedLanguages());
            unmatched.removeAll(matchedLanguages);
            if (!unmatched.isEmpty()) throw failure(context, String.join(",", unmatched),
                    "Retained language declaration matches no Application resource configuration",
                    "Use only locales present in this exact Release resource table");
        }
        return new ControlPlan(List.copyOf(decisions), Set.copyOf(deletedEntries));
    }

    private static Resources.Entry resourceEntry(Resources.ResourceTable table, int targetId) {
        for (var pkg : table.getPackageList()) for (var type : pkg.getTypeList()) {
            for (var entry : type.getEntryList()) {
                if (resourceId(pkg, type, entry) == targetId) return entry;
            }
        }
        throw new IllegalArgumentException("Missing resource ID " + Integer.toUnsignedString(targetId));
    }

    private static Resources.Entry replaceStringEntry(Resources.Entry entry, Context context) {
        var builder = entry.toBuilder().clearConfigValue();
        for (var value : entry.getConfigValueList()) {
            if (!value.hasValue() || !value.getValue().hasItem()) {
                throw failure(context, entry.getName(),
                        "Unused-string target has a non-item value contract",
                        "Confirm only plain string resources as unused");
            }
            var item = value.getValue().getItem();
            var itemBuilder = item.toBuilder();
            if (item.hasStr()) itemBuilder.setStr(item.getStr().toBuilder().setValue("kld"));
            else if (item.hasRawStr()) itemBuilder.setRawStr(
                    item.getRawStr().toBuilder().setValue("kld"));
            else throw failure(context, entry.getName(),
                    "Unused-string target is styled, referenced, or otherwise structured",
                    "Confirm only unstyled literal string resources as unused");
            builder.addConfigValue(value.toBuilder().setValue(
                    value.getValue().toBuilder().setItem(itemBuilder)));
        }
        return builder.build();
    }

    private static String locale(Resources.ConfigValue value) {
        if (!value.hasConfig()) return "";
        var locale = value.getConfig().getLocale();
        if (locale.startsWith("b+")) locale = locale.substring(2).replace('+', '-');
        return locale.replace("-r", "-");
    }

    private static boolean localeMatches(String actual, String requested) {
        return actual.equals(requested) || !requested.contains("-")
                && actual.startsWith(requested + "-");
    }

    private static boolean isPathProtected(String path, Set<String> protectedPaths) {
        return protectedPaths.contains(path)
                || path.startsWith("base/")
                        && protectedPaths.contains(path.substring("base/".length()));
    }

    private static boolean containsAscii(byte[] bytes, String target) {
        var needle = target.getBytes(StandardCharsets.UTF_8);
        outer: for (var index = 0; index <= bytes.length - needle.length; index++) {
            for (var offset = 0; offset < needle.length; offset++) {
                if (bytes[index + offset] != needle[offset]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static Deduplication deduplicate(
            ZipFile zip,
            List<ResourceFact> facts,
            List<BundleRewriteArtifacts.ResourceDecision> decisions,
            Map<String, String> pathMapping,
            Map<String, EntryFact> entries,
            Context context) throws IOException {
        var factById = facts.stream().collect(java.util.stream.Collectors.toMap(
                ResourceFact::id, java.util.function.Function.identity()));
        var targets = new ArrayList<List<String>>();
        decisions.forEach(decision -> targets.add(new ArrayList<>(decision.targetPaths())));
        var protectedPayloads = new TreeSet<String>();
        var identicalPayloadGroups = new TreeMap<String, List<DedupCandidate>>();
        var groups = new TreeMap<DedupKey, List<DedupCandidate>>(Comparator
                .comparing(DedupKey::directory)
                .thenComparingInt(DedupKey::representation)
                .thenComparing(DedupKey::suffix)
                .thenComparingLong(DedupKey::size)
                .thenComparing(DedupKey::sha256));
        for (var decisionIndex = 0; decisionIndex < decisions.size(); decisionIndex++) {
            var decision = decisions.get(decisionIndex);
            if (decision.nameProtected() || decision.pathProtected()) {
                decision.originalPaths().forEach(path -> protectedPayloads.add("base/" + path));
            }
            var fact = factById.get(decision.id());
            for (var pathIndex = 0; pathIndex < decision.originalPaths().size(); pathIndex++) {
                var original = decision.originalPaths().get(pathIndex);
                var input = entries.get("base/" + original);
                if (input == null) {
                    throw failure(context, original,
                            "Deduplication candidate is missing its Bundle payload",
                            "Restore the planned base-module resource payload");
                }
                identicalPayloadGroups.computeIfAbsent(
                                input.size() + "|" + input.sha256(), ignored -> new ArrayList<>())
                        .add(new DedupCandidate(decisionIndex, pathIndex, original,
                                targets.get(decisionIndex).get(pathIndex)));
                if (decision.nameProtected() || decision.pathProtected()
                        || "DEPENDENCY".equals(decision.action())) continue;
                var slash = original.lastIndexOf('/');
                var fileName = original.substring(slash + 1);
                if (!fileName.startsWith(fact.name() + ".")) {
                    continue;
                }
                var key = new DedupKey(original.substring(0, slash),
                        fact.fileTypes().get(original), suffix(
                                fileName, fact.name()),
                        input.size(), input.sha256());
                groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(
                        new DedupCandidate(decisionIndex, pathIndex, original,
                                targets.get(decisionIndex).get(pathIndex)));
            }
        }
        var redundant = new TreeSet<String>();
        var deduplicatedDecisions = new HashSet<Integer>();
        for (var group : groups.values()) {
            var candidatesByPath = group.stream().collect(java.util.stream.Collectors.groupingBy(
                    DedupCandidate::originalPath, TreeMap::new,
                    java.util.stream.Collectors.toList()));
            if (candidatesByPath.size() < 2) continue;
            var representatives = candidatesByPath.values().stream()
                    .map(items -> items.get(0)).toList();
            var firstBytes = read(zip,
                    zip.getEntry("base/" + representatives.get(0).originalPath()));
            var identical = true;
            for (var candidate : representatives.subList(1, representatives.size())) {
                if (!java.util.Arrays.equals(firstBytes,
                        read(zip, zip.getEntry("base/" + candidate.originalPath())))) {
                    identical = false;
                    break;
                }
            }
            if (!identical) continue;
            var canonical = representatives.stream().min(Comparator
                    .comparing(DedupCandidate::targetPath)
                    .thenComparing(DedupCandidate::originalPath)).orElseThrow();
            for (var pathGroup : candidatesByPath.values()) {
                for (var candidate : pathGroup) {
                    targets.get(candidate.decisionIndex()).set(candidate.pathIndex(),
                            canonical.targetPath());
                    pathMapping.put(candidate.originalPath(), canonical.targetPath());
                    deduplicatedDecisions.add(candidate.decisionIndex());
                }
                if (!pathGroup.get(0).originalPath().equals(canonical.originalPath())) {
                    redundant.add("base/" + pathGroup.get(0).originalPath());
                }
            }
        }
        for (var identicalGroup : identicalPayloadGroups.entrySet()) {
            if (identicalGroup.getValue().size() < 2) continue;
            var retainedTargets = identicalGroup.getValue().stream()
                    .map(candidate -> targets.get(candidate.decisionIndex())
                            .get(candidate.pathIndex()))
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
            if (retainedTargets.size() > 1) {
                var digest = identicalGroup.getKey().substring(
                        identicalGroup.getKey().indexOf('|') + 1);
                context.warn("KLD-RESOURCE-002 project=" + context.project()
                        + " variant=" + context.variant()
                        + " stage=bundle-rewrite origin=deduplication target="
                        + digest.substring(0, 16)
                        + " reason=Byte-identical payloads retained across protection, ownership,"
                        + " qualifier, or representation boundary repair=<none>");
            }
        }
        var revised = new ArrayList<BundleRewriteArtifacts.ResourceDecision>();
        for (var index = 0; index < decisions.size(); index++) {
            var decision = decisions.get(index);
            revised.add(new BundleRewriteArtifacts.ResourceDecision(
                    decision.id(), decision.packageName(), decision.type(),
                    decision.originalName(), decision.targetName(),
                    deduplicatedDecisions.contains(index)
                            ? decision.action() + "_DEDUP" : decision.action(),
                    deduplicatedDecisions.contains(index)
                            ? decision.reason() + "+byte-identical-dedup" : decision.reason(),
                    decision.originalPaths(), targets.get(index),
                    decision.nameProtected(), decision.pathProtected()));
        }
        return new Deduplication(List.copyOf(revised), Set.copyOf(redundant),
                Set.copyOf(protectedPayloads));
    }

    private static List<ResourceFact> resourceFacts(Resources.ResourceTable table) {
        var facts = new ArrayList<ResourceFact>();
        for (var pkg : table.getPackageList()) {
            for (var type : pkg.getTypeList()) {
                for (var entry : type.getEntryList()) {
                    var paths = new TreeMap<String, Integer>();
                    entry.getConfigValueList().forEach(value -> collectFilePaths(value, paths));
                    entry.getFlagDisabledConfigValueList().forEach(value ->
                            collectFilePaths(value, paths));
                    entry.getReadwriteFlagConfigValueList().forEach(value ->
                            collectFilePaths(value, paths));
                    facts.add(new ResourceFact(resourceId(pkg, type, entry),
                            pkg.getPackageName(), type.getName(), entry.getName(),
                            Map.copyOf(paths)));
                }
            }
        }
        return facts.stream().sorted(Comparator.comparingInt(ResourceFact::id)
                .thenComparing(ResourceFact::type).thenComparing(ResourceFact::name)).toList();
    }

    private static void collectReferenceDecisions(
            Message message,
            String origin,
            String fieldPath,
            Map<Integer, FullResourceName> nameById,
            Map<String, FullResourceName> targetByOriginalName,
            Map<String, String> paths,
            List<BundleRewriteArtifacts.ReferenceDecision> output) {
        if (message instanceof Resources.Reference reference) {
            var target = targetFor(reference, nameById, targetByOriginalName);
            var targetValue = target == null || reference.getName().isEmpty()
                    ? reference.getName()
                    : rewriteReferenceName(reference.getName(), target);
            output.add(new BundleRewriteArtifacts.ReferenceDecision(
                    origin, fieldPath, "RESOURCE_REFERENCE", reference.getId(),
                    reference.getName(), targetValue));
            return;
        }
        if (message instanceof Resources.FileReference file) {
            output.add(new BundleRewriteArtifacts.ReferenceDecision(
                    origin, fieldPath, "FILE_REFERENCE", 0,
                    file.getPath(), paths.getOrDefault(file.getPath(), file.getPath())));
            return;
        }
        if (message instanceof Resources.XmlAttribute attribute
                && attribute.hasCompiledItem() && attribute.getCompiledItem().hasRef()) {
            var reference = attribute.getCompiledItem().getRef();
            var target = targetFor(reference, nameById, targetByOriginalName);
            output.add(new BundleRewriteArtifacts.ReferenceDecision(
                    origin, fieldPath + ".value", "RAW_XML_ATTRIBUTE", reference.getId(),
                    attribute.getValue(), target == null ? attribute.getValue()
                            : rewriteRawReference(attribute.getValue(), target)));
        }
        for (var field : message.getDescriptorForType().getFields()) {
            if (field.getJavaType() != Descriptors.FieldDescriptor.JavaType.MESSAGE) continue;
            if (field.isRepeated()) {
                for (var index = 0; index < message.getRepeatedFieldCount(field); index++) {
                    collectReferenceDecisions((Message) message.getRepeatedField(field, index),
                            origin, fieldPath + "." + field.getName() + "[" + index + "]",
                            nameById, targetByOriginalName, paths, output);
                }
            } else if (message.hasField(field)) {
                collectReferenceDecisions((Message) message.getField(field), origin,
                        fieldPath + "." + field.getName(), nameById,
                        targetByOriginalName, paths, output);
            }
        }
    }

    private static void collectFilePaths(Message message, Map<String, Integer> paths) {
        if (message instanceof Resources.FileReference file) {
            var previous = paths.putIfAbsent(file.getPath(), file.getTypeValue());
            if (previous != null && previous != file.getTypeValue()) {
                throw new IllegalArgumentException(
                        "FileReference path has incompatible representations: " + file.getPath());
            }
            return;
        }
        for (var field : message.getDescriptorForType().getFields()) {
            if (field.getJavaType() != Descriptors.FieldDescriptor.JavaType.MESSAGE) continue;
            if (field.isRepeated()) {
                for (var index = 0; index < message.getRepeatedFieldCount(field); index++) {
                    collectFilePaths((Message) message.getRepeatedField(field, index), paths);
                }
            } else if (message.hasField(field)) {
                collectFilePaths((Message) message.getField(field), paths);
            }
        }
    }

    private static int resourceId(
            Resources.Package pkg, Resources.Type type, Resources.Entry entry) {
        return (pkg.getPackageId().getId() << 24)
                | (type.getTypeId().getId() << 16)
                | entry.getEntryId().getId();
    }

    private static String allocateName(ResourceFact fact, String stream, Set<String> reserved) {
        var digest = SeedDerivation.derive(stream, "resource-entry",
                fact.packageName() + ":" + fact.type() + "/" + fact.name());
        for (var length = 10; length <= digest.length(); length += 2) {
            var target = "k" + digest.substring(0, length);
            if (reserved.add(target)) return target;
        }
        throw new IllegalStateException("Unable to allocate resource name for " + fact.name());
    }

    private static String allocatePath(
            ResourceFact fact, String path, String targetName, String stream,
            Set<String> reservedPaths) {
        var slash = path.lastIndexOf('/');
        var file = path.substring(slash + 1);
        var suffix = suffix(file, fact.name());
        var directory = path.substring(0, slash + 1);
        var digest = SeedDerivation.derive(stream, "resource-path", path);
        for (var length = 10; length <= digest.length(); length += 2) {
            var base = targetName.equals(fact.name())
                    ? "f" + digest.substring(0, length) : targetName;
            var target = directory + base + suffix;
            if (reservedPaths.add("base/" + target)) return target;
            if (!targetName.equals(fact.name())) targetName = "k" + digest.substring(0, length);
        }
        throw new IllegalStateException("Unable to allocate resource path for " + path);
    }

    private static String suffix(String file, String resourceName) {
        if (!file.startsWith(resourceName) || file.length() == resourceName.length()
                || file.charAt(resourceName.length()) != '.') {
            throw new IllegalArgumentException(
                    "File-backed resource path does not match entry name: " + file);
        }
        return file.substring(resourceName.length());
    }

    private static String rewriteReferenceName(String original, FullResourceName target) {
        var slash = original.lastIndexOf('/');
        if (slash < 0) return original;
        return original.substring(0, slash + 1) + target.name();
    }

    private static String rewriteRawReference(String original, FullResourceName target) {
        if (!(original.startsWith("@") || original.startsWith("?"))) return original;
        var slash = original.lastIndexOf('/');
        if (slash < 0) return original;
        return original.substring(0, slash + 1) + target.name();
    }

    private static Map<String, EntryFact> inventoryEntries(ZipFile zip, Context context)
            throws IOException {
        var entries = new TreeMap<String, EntryFact>();
        for (var entry : zip.stream().filter(Predicate.not(ZipEntry::isDirectory))
                .sorted(Comparator.comparing(ZipEntry::getName)).toList()) {
            validatePath(entry.getName(), context);
            if (entries.putIfAbsent(entry.getName(), new EntryFact(entry.getName(),
                    sha256(zip.getInputStream(entry)), entry.getMethod(), entry.getSize())) != null) {
                throw failure(context, entry.getName(), "Duplicate normalized Bundle entry",
                        "Remove the duplicate entry before Kaleido processing");
            }
        }
        return Map.copyOf(entries);
    }

    private static void validatePath(String path, Context context) {
        if (path.isBlank() || path.startsWith("/") || path.contains("\\")
                || path.contains("//") || path.equals(".") || path.equals("..")
                || path.startsWith("../") || path.contains("/../")
                || path.endsWith("/..") || path.startsWith("./")
                || path.contains("/./") || path.endsWith("/.")) {
            throw failure(context, path, "Bundle entry path is not canonical",
                    "Use normalized relative UTF-8 Bundle entry paths");
        }
    }

    private static boolean isSignature(String path) {
        if (!path.startsWith("META-INF/")) return false;
        var upper = path.toUpperCase(java.util.Locale.ROOT);
        return upper.equals("META-INF/MANIFEST.MF") || upper.endsWith(".SF")
                || upper.endsWith(".RSA") || upper.endsWith(".DSA")
                || upper.endsWith(".EC");
    }

    private static boolean isProtectedPayload(String path) {
        var lower = path.toLowerCase(java.util.Locale.ROOT);
        return path.startsWith("base/dex/") || path.startsWith("base/lib/")
                || lower.contains("code-transparency") || lower.contains("code_transparency");
    }

    private static Resources.ResourceTable parseTable(byte[] bytes, Context context) {
        try { return Resources.ResourceTable.parseFrom(bytes); }
        catch (com.google.protobuf.InvalidProtocolBufferException failure) {
            throw failure(context, RESOURCE_TABLE, "Resource table protobuf is invalid",
                    "Rebuild the App Bundle with the supported AGP/AAPT2 toolchain");
        }
    }

    private static Resources.XmlNode parseXml(byte[] bytes, String path, Context context) {
        try { return Resources.XmlNode.parseFrom(bytes); }
        catch (com.google.protobuf.InvalidProtocolBufferException failure) {
            throw failure(context, path, "Compiled XML protobuf is invalid",
                    "Rebuild the App Bundle with the supported AGP/AAPT2 toolchain");
        }
    }

    private static byte[] read(ZipFile zip, ZipEntry entry) throws IOException {
        try (var input = zip.getInputStream(entry)) { return input.readAllBytes(); }
    }

    private static String sha256(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) { return sha256(input); }
    }

    private static String sha256(byte[] bytes) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String sha256(InputStream input) throws IOException {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            var buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) != -1; ) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        } finally {
            input.close();
        }
    }

    private static org.gradle.api.GradleException failure(
            Context context, String target, String reason, String repair) {
        return new KaleidoDiagnostic("KLD-BUNDLE-001", context.project(), context.variant(),
                "bundle-rewrite", BundleRewriteArtifacts.PLAN_SCHEMA,
                target, reason, repair).failure();
    }

    record Context(String project, String variant,
                   java.util.function.Consumer<String> warningSink) {
        Context(String project, String variant) {
            this(project, variant, ignored -> {});
        }

        void warn(String warning) { warningSink.accept(warning); }
    }
    record FullControls(Set<String> nativeLibrariesToDelete,
                        Set<String> metadataToDelete,
                        Set<String> confirmedUnusedStrings,
                        Set<String> retainedLanguages,
                        Set<String> ownedNativeLibraries,
                        Set<String> ownedMetadata) {
        FullControls {
            nativeLibrariesToDelete = Set.copyOf(nativeLibrariesToDelete);
            metadataToDelete = Set.copyOf(metadataToDelete);
            confirmedUnusedStrings = Set.copyOf(confirmedUnusedStrings);
            retainedLanguages = Set.copyOf(retainedLanguages);
            ownedNativeLibraries = Set.copyOf(ownedNativeLibraries);
            ownedMetadata = Set.copyOf(ownedMetadata);
        }

        static FullControls none() {
            return new FullControls(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
        }
    }
    record ResourceKey(String type, String name) {}
    record Execution(String resourceMapping, byte[] receiptBytes) {}
    private record FullResourceName(String packageName, String type, String name) {}
    private record ResourceFact(int id, String packageName, String type, String name,
                                Map<String, Integer> fileTypes) {
        List<String> filePaths() { return fileTypes.keySet().stream().sorted().toList(); }
    }
    private record EntryFact(String path, String sha256, int method, long size) {}
    private record DedupKey(String directory, int representation, String suffix,
                            long size, String sha256) {}
    private record DedupCandidate(int decisionIndex, int pathIndex,
                                  String originalPath, String targetPath) {}
    private record Deduplication(List<BundleRewriteArtifacts.ResourceDecision> resources,
                                 Set<String> redundantInputPaths,
                                 Set<String> protectedPayloadPaths) {}
    private record ControlPlan(List<BundleRewriteArtifacts.ControlDecision> decisions,
                               Set<String> deletedEntries) {}
}
