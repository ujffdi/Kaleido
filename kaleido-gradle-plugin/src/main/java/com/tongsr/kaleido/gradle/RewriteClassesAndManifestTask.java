package com.tongsr.kaleido.gradle;

import com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.Directory;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFile;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.CacheableTask;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

@CacheableTask
public abstract class RewriteClassesAndManifestTask extends DefaultTask {
    @Input public String getKaleidoCacheSchema() { return "ClassRewriteCache.v1"; }
    private static final String ANDROID_NAMESPACE =
            "http://schemas.android.com/apk/res/android";
    private static final Set<String> RESOURCE_TYPES = Set.of(
            "anim", "animator", "array", "attr", "bool", "color", "dimen",
            "drawable", "font", "fraction", "id", "integer", "interpolator",
            "layout", "menu", "mipmap", "navigation", "plurals", "raw",
            "string", "style", "transition", "xml");
    private static final Map<String, Set<String>> MANIFEST_REGISTRY = Map.of(
            "application", Set.of("name", "backupAgent", "appComponentFactory",
                    "manageSpaceActivity", "zygotePreloadName"),
            "activity", Set.of("name", "parentActivityName"),
            "service", Set.of("name"),
            "receiver", Set.of("name"),
            "provider", Set.of("name"),
            "activity-alias", Set.of("targetActivity"),
            "instrumentation", Set.of("name"));

    @Input public abstract Property<String> getConsumerProjectPath();
    @Input public abstract Property<String> getVariantName();

    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ListProperty<RegularFile> getInputJars();

    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ListProperty<Directory> getInputDirectories();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInputManifest();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getAdoptionPlan();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getManifestRewriteIntent();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getXmlRewriteIntent();

    @OutputFile public abstract RegularFileProperty getOutputClasses();
    @OutputFile public abstract RegularFileProperty getRewritePlan();
    @OutputFile public abstract RegularFileProperty getRawMapping();
    @OutputFile public abstract RegularFileProperty getTransformReceipt();
    @OutputFile public abstract RegularFileProperty getResourceProtectionEvidence();
    @OutputFile public abstract RegularFileProperty getComposeCompiledInventory();
    @OutputDirectory public abstract DirectoryProperty getProtectionKeepRulesOutputDirectory();

    @TaskAction
    public void rewrite() throws Exception {
        var project = getConsumerProjectPath().get();
        var variant = getVariantName().get();
        var adoptionPath = getAdoptionPlan().get().getAsFile().toPath();
        var manifestPath = getInputManifest().get().getAsFile().toPath();
        var adoptionBytes = Files.readAllBytes(adoptionPath);
        var manifestBytes = Files.readAllBytes(manifestPath);
        var intentBytes = Files.readAllBytes(
                getManifestRewriteIntent().get().getAsFile().toPath());
        var intent = readManifestIntent(intentBytes, project, variant);
        var xmlIntentBytes = Files.readAllBytes(getXmlRewriteIntent().get().getAsFile().toPath());
        var xmlIntent = readXmlIntent(xmlIntentBytes, project, variant);
        var adoption = readProperties(adoptionBytes);
        requireSchema(adoption, project, variant);

        var sourceArtifacts = sourceArtifacts();
        var inventory = inventory(sourceArtifacts, project, variant);
        var composeInventory = ComposeCompiledValidator.validate(
                adoption,
                inventory.classes().entrySet().stream().collect(
                        java.util.stream.Collectors.toMap(Map.Entry::getKey,
                                entry -> entry.getValue().bytes())),
                manifestBytes,
                project,
                variant);
        var resourceLookups = inventory.classes().values().stream()
                .flatMap(classEntry -> exactResourceLookups(
                        classEntry.bytes(), classEntry.name()).stream())
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        var protectedNames = new TreeSet<>(
                commaSeparated(adoption.get("protection.originalClassNames")));
        var protectionReasons = new HashMap<String, TreeSet<String>>();
        var protectionDimensions = new HashMap<String,
                java.util.EnumSet<KaleidoProtectionDimension>>();
        protectedNames.forEach(name -> protectionReasons
                .computeIfAbsent(name, ignored -> new TreeSet<>()).add("legacy-original-name"));
        protectedNames.forEach(name -> protectionDimensions
                .computeIfAbsent(name, ignored -> java.util.EnumSet.noneOf(
                        KaleidoProtectionDimension.class))
                .add(KaleidoProtectionDimension.ORIGINAL_IDENTITY));
        var classHatches = parseEscapeHatches(adoption.get("protection.classEscapeHatches"),
                EscapeHatchDeclaration.Kind.CLASS, project, variant);
        for (var hatch : classHatches) {
            var matches = inventory.classes().keySet().stream().filter(hatch::matches).sorted().toList();
            if (matches.isEmpty()) {
                throw protectionFailure(project, variant, hatch.id(),
                        "Escape Hatch resolves to zero PROJECT classes",
                        "Remove the stale declaration or select an existing bounded class target");
            }
            for (var match : matches) {
                protectionReasons.computeIfAbsent(match, ignored -> new TreeSet<>()).add(
                        "escape-hatch:" + hatch.id() + ":" + hatch.dimensions().stream()
                                .map(Enum::name).sorted().collect(java.util.stream.Collectors.joining("+")));
                protectionDimensions.computeIfAbsent(match, ignored -> java.util.EnumSet.noneOf(
                        KaleidoProtectionDimension.class)).addAll(hatch.dimensions());
            }
            if (hatch.protects(KaleidoProtectionDimension.ORIGINAL_IDENTITY)) {
                protectedNames.addAll(matches);
            }
            if (hatch.protects(KaleidoProtectionDimension.DESCRIPTOR_CLOSURE)) {
                matches.stream().map(inventory.classes()::get)
                        .flatMap(entry -> referencedTypes(entry.bytes()).stream())
                        .filter(inventory.classes()::containsKey)
                        .forEach(name -> {
                            protectedNames.add(name);
                            protectionReasons.computeIfAbsent(name, ignored -> new TreeSet<>())
                                    .add("descriptor-closure:" + hatch.id());
                            protectionDimensions.computeIfAbsent(name,
                                    ignored -> java.util.EnumSet.noneOf(
                                            KaleidoProtectionDimension.class))
                                    .addAll(java.util.EnumSet.of(
                                            KaleidoProtectionDimension.REACHABILITY,
                                            KaleidoProtectionDimension.ORIGINAL_IDENTITY,
                                            KaleidoProtectionDimension.DESCRIPTOR_CLOSURE));
                        });
            }
        }
        for (var classEntry : inventory.classes().values()) {
            for (var reflected : exactReflectionTargets(classEntry.bytes())) {
                if (inventory.classes().containsKey(reflected)) {
                    protectedNames.add(reflected);
                    protectionReasons.computeIfAbsent(reflected, ignored -> new TreeSet<>())
                            .add("inferred-exact-reflection");
                    protectionDimensions.computeIfAbsent(reflected,
                            ignored -> java.util.EnumSet.noneOf(
                                    KaleidoProtectionDimension.class))
                            .addAll(java.util.EnumSet.of(
                                    KaleidoProtectionDimension.REACHABILITY,
                                    KaleidoProtectionDimension.ORIGINAL_IDENTITY,
                                    KaleidoProtectionDimension.DESCRIPTOR_CLOSURE,
                                    KaleidoProtectionDimension.RUNTIME_ATTRIBUTES));
                }
            }
            if (hasNativeMethods(classEntry.bytes())) {
                protectedNames.add(classEntry.name());
                protectionReasons.computeIfAbsent(classEntry.name(), ignored -> new TreeSet<>())
                        .add("inferred-native-declaration");
                protectionDimensions.computeIfAbsent(classEntry.name(),
                        ignored -> java.util.EnumSet.noneOf(
                                KaleidoProtectionDimension.class))
                        .addAll(java.util.EnumSet.of(
                                KaleidoProtectionDimension.ORIGINAL_IDENTITY,
                                KaleidoProtectionDimension.DESCRIPTOR_CLOSURE));
                referencedTypes(classEntry.bytes()).stream()
                        .filter(inventory.classes()::containsKey)
                        .forEach(name -> {
                            protectedNames.add(name);
                            protectionReasons.computeIfAbsent(name, ignored -> new TreeSet<>())
                                    .add("native-descriptor-closure:" + classEntry.name());
                        });
            }
        }
        closeClassFamilies(protectedNames, inventory.classes().keySet());
        closeKotlinAssociations(protectedNames, inventory.classes());
        protectedNames.forEach(name -> protectionDimensions
                .computeIfAbsent(name, ignored -> java.util.EnumSet.noneOf(
                        KaleidoProtectionDimension.class))
                .add(KaleidoProtectionDimension.ORIGINAL_IDENTITY));
        var roots = new TreeSet<>(intent.mapping().keySet());
        roots.addAll(xmlIntent.mapping().keySet());
        var packageBase = required(adoption, "generation.packageBase", project, variant);
        inventory.classes().keySet().stream()
                .filter(name -> name.startsWith(packageBase + "."))
                .forEach(roots::add);
        closeClassFamilies(roots, inventory.classes().keySet());
        closeKotlinAssociations(roots, inventory.classes());

        var mapping = allocateMapping(
                roots,
                protectedNames,
                inventory.classes().keySet(),
                required(adoption, "seed.domain.class-rewrite", project, variant));
        for (var entry : intent.mapping().entrySet()) {
            if (!entry.getValue().equals(mapping.get(entry.getKey()))) {
                throw failure(project, variant, entry.getKey(),
                        "Manifest identity target collides with compiled class inventory",
                        "Change the seed or protect the colliding class identity");
            }
        }
        for (var entry : xmlIntent.mapping().entrySet()) {
            if (!entry.getValue().equals(mapping.get(entry.getKey()))) {
                throw failure(project, variant, entry.getKey(),
                        "XML identity target collides with compiled class inventory",
                        "Change the seed or protect the colliding class identity");
            }
        }
        var decisions = new ArrayList<ClassRewriteArtifacts.ClassDecision>();
        for (var classEntry : inventory.classes().values().stream()
                .sorted(Comparator.comparing(ClassEntry::name)).toList()) {
            var target = mapping.getOrDefault(classEntry.name(), classEntry.name());
            var action = mapping.containsKey(classEntry.name()) ? "REWRITE"
                    : protectedNames.contains(classEntry.name()) ? "PROTECTED" : "UNTOUCHED";
            var reason = action.equals("REWRITE") ? "reference-driven-root-or-closure"
                    : action.equals("PROTECTED")
                            ? String.join("+", protectionReasons.getOrDefault(
                                    classEntry.name(), new TreeSet<>(Set.of("family-closure"))))
                            : "outside-rewrite-roots";
            decisions.add(new ClassRewriteArtifacts.ClassDecision(
                    classEntry.name(), classEntry.origin(), sha256(classEntry.bytes()),
                    action, target, reason));
        }
        var sites = java.util.stream.Stream.concat(
                intent.sites().stream(), xmlIntent.sites().stream())
                .sorted(Comparator.comparing(ClassRewriteArtifacts.ManifestSite::location))
                .toList();
        var inputs = new ArrayList<>(sourceArtifacts.stream()
                .map(source -> new ClassRewriteArtifacts.InputArtifact(
                        source.origin(), source.sha256()))
                .sorted(Comparator.comparing(ClassRewriteArtifacts.InputArtifact::origin))
                .toList());
        inputs.add(new ClassRewriteArtifacts.InputArtifact(
                "manifest-rewrite-intent", sha256(intentBytes)));
        inputs.add(new ClassRewriteArtifacts.InputArtifact(
                "xml-rewrite-intent", sha256(xmlIntentBytes)));
        var expectedOutputs = decisions.stream()
                .map(ClassRewriteArtifacts.ClassDecision::target).sorted().toList();
        var plan = new ClassRewriteArtifacts.Plan(
                ClassRewriteArtifacts.PLAN_SCHEMA,
                ClassRewriteArtifacts.PRODUCER,
                project,
                variant,
                sha256(adoptionBytes),
                intent.originalManifestSha256(),
                inputs,
                decisions,
                sites,
                expectedOutputs);
        var planBytes = ClassRewriteArtifacts.encodePlan(plan);
        var executablePlan = ClassRewriteArtifacts.decodePlan(planBytes, project, variant);
        verifyInputsUnchanged(sourceArtifacts, adoptionPath, adoptionBytes,
                manifestPath, manifestBytes,
                getManifestRewriteIntent().get().getAsFile().toPath(), intentBytes,
                getXmlRewriteIntent().get().getAsFile().toPath(), xmlIntentBytes,
                project, variant);

        var executableMapping = executablePlan.decisions().stream()
                .filter(decision -> decision.action().equals("REWRITE"))
                .collect(java.util.stream.Collectors.toMap(
                        ClassRewriteArtifacts.ClassDecision::original,
                        ClassRewriteArtifacts.ClassDecision::target));
        var outputEntries = rewriteClasses(inventory, executableMapping, project, variant);
        validateClosure(outputEntries, inventory, executableMapping, project, variant);

        var outputJar = getOutputClasses().get().getAsFile().toPath();
        writeJar(outputJar, outputEntries);
        writeBytes(getRewritePlan().get().getAsFile().toPath(), planBytes);
        writeText(getRawMapping().get().getAsFile().toPath(), rawMapping(executableMapping));
        writeText(getResourceProtectionEvidence().get().getAsFile().toPath(),
                resourceProtectionEvidence(resourceLookups));
        writeText(getComposeCompiledInventory().get().getAsFile().toPath(),
                ComposeCompiledValidator.inventoryText(composeInventory, executableMapping));
        writeProtectionRules(getProtectionKeepRulesOutputDirectory().get().getAsFile().toPath(),
                protectionDimensions, executableMapping);
        writeText(getProtectionKeepRulesOutputDirectory().get().getAsFile().toPath()
                        .resolve("compose.keep"),
                ComposeCompiledValidator.keepRules(composeInventory, executableMapping));
        var receipt = new ClassRewriteArtifacts.Receipt(
                ClassRewriteArtifacts.RECEIPT_SCHEMA,
                ClassRewriteArtifacts.PRODUCER,
                project,
                variant,
                sha256(planBytes),
                sha256(Files.readAllBytes(outputJar)),
                sha256(manifestBytes),
                executableMapping.size(),
                true,
                true,
                sourceArtifacts.stream().map(SourceArtifact::sha256).sorted().toList());
        writeBytes(getTransformReceipt().get().getAsFile().toPath(),
                ClassRewriteArtifacts.encodeReceipt(receipt));
    }

    private List<SourceArtifact> sourceArtifacts() throws IOException {
        var sources = new ArrayList<SourceArtifact>();
        for (var regularFile : getInputJars().get()) {
            var path = regularFile.getAsFile().toPath();
            var digest = sha256(Files.readAllBytes(path));
            sources.add(new SourceArtifact(path, true, "jar/" + digest, digest));
        }
        for (var directory : getInputDirectories().get()) {
            var path = directory.getAsFile().toPath();
            var digest = directoryDigest(path);
            sources.add(new SourceArtifact(path, false, "directory/" + digest, digest));
        }
        return sources.stream().sorted(Comparator.comparing(SourceArtifact::origin)).toList();
    }

    private static Inventory inventory(
            List<SourceArtifact> sources, String project, String variant) throws IOException {
        var entries = new TreeMap<String, byte[]>();
        var origins = new HashMap<String, String>();
        for (var source : sources) {
            if (source.jar()) {
                try (var zip = new ZipFile(source.path().toFile())) {
                    for (var entry : zip.stream().filter(item -> !item.isDirectory())
                            .sorted(Comparator.comparing(ZipEntry::getName)).toList()) {
                        addEntry(entries, origins, entry.getName(),
                                zip.getInputStream(entry).readAllBytes(),
                                source.origin() + "!" + entry.getName(), project, variant);
                    }
                }
            } else if (Files.exists(source.path())) {
                try (var paths = Files.walk(source.path())) {
                    for (var path : paths.filter(Files::isRegularFile).sorted().toList()) {
                        var relative = source.path().relativize(path).toString()
                                .replace(java.io.File.separatorChar, '/');
                        addEntry(entries, origins, relative, Files.readAllBytes(path),
                                source.origin() + "!" + relative, project, variant);
                    }
                }
            }
        }
        var classes = new TreeMap<String, ClassEntry>();
        for (var entry : entries.entrySet()) {
            if (!entry.getKey().endsWith(".class")) {
                continue;
            }
            var reader = new ClassReader(entry.getValue());
            var name = reader.getClassName().replace('/', '.');
            var expectedPath = reader.getClassName() + ".class";
            if (!entry.getKey().equals(expectedPath) || classes.containsKey(name)) {
                throw failure(project, variant, entry.getKey(),
                        "Class entry path, this_class, or uniqueness invariant failed",
                        "Remove duplicate or misnamed Application class outputs");
            }
            classes.put(name, new ClassEntry(name, entry.getKey(), origins.get(entry.getKey()),
                    entry.getValue(), hasKotlinMetadata(reader)));
        }
        return new Inventory(Map.copyOf(entries), Map.copyOf(classes));
    }

    private static void addEntry(
            Map<String, byte[]> entries,
            Map<String, String> origins,
            String path,
            byte[] bytes,
            String origin,
            String project,
            String variant) {
        if (entries.putIfAbsent(path, bytes) != null) {
            throw failure(project, variant, path, "Duplicate PROJECT class artifact entry",
                    "Remove duplicate Application class outputs before Kaleido");
        }
        origins.put(path, origin);
    }

    private static boolean hasKotlinMetadata(ClassReader reader) {
        var found = new boolean[1];
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if ("Lkotlin/Metadata;".equals(descriptor)) {
                    found[0] = true;
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    static Map<String, String> allocateMapping(
            Set<String> roots,
            Set<String> protectedNames,
            Set<String> allNames,
            String stream) {
        var mapping = new LinkedHashMap<String, String>();
        var reserved = new HashSet<>(allNames);
        var ordered = roots.stream().sorted(Comparator
                .comparingInt((String name) -> name.split("\\$", -1).length)
                .thenComparing(Function.identity())).toList();
        for (var original : ordered) {
            if (protectedNames.contains(original)) {
                continue;
            }
            var outer = original.contains("$") ? original.substring(0, original.indexOf('$')) : original;
            var outerTarget = mapping.get(outer);
            var digest = SeedDerivation.derive(stream, "class-identity", original);
            for (var length = 10; length <= digest.length(); length += 2) {
                var target = outerTarget != null && !outer.equals(original)
                        ? outerTarget + "$C" + digest.substring(0, length)
                        : packageName(original) + ".k" + digest.substring(0, 6)
                                + ".C" + digest.substring(0, length);
                if (reserved.add(target)) {
                    mapping.put(original, target);
                    break;
                }
            }
            if (!mapping.containsKey(original)) {
                throw new IllegalStateException("Unable to allocate class identity for " + original);
            }
        }
        return Map.copyOf(mapping);
    }

    private static Map<String, byte[]> rewriteClasses(
            Inventory inventory,
            Map<String, String> mapping,
            String project,
            String variant) {
        var internalMapping = mapping.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                entry -> entry.getKey().replace('.', '/'),
                entry -> entry.getValue().replace('.', '/')));
        var remapper = new SimpleRemapper(internalMapping);
        var output = new TreeMap<String, byte[]>();
        for (var entry : inventory.entries().entrySet()) {
            if (!entry.getKey().endsWith(".class")) {
                output.put(entry.getKey(), entry.getValue());
                continue;
            }
            var reader = new ClassReader(entry.getValue());
            var original = reader.getClassName().replace('/', '.');
            if (!mapping.containsKey(original)) {
                output.put(entry.getKey(), entry.getValue());
                continue;
            }
            var metadata = KotlinMetadataRewriter.rewrite(
                    entry.getValue(), mapping, remapper);
            var writer = new ClassWriter(reader, 0);
            var outputVisitor = KotlinMetadataRewriter.replacingVisitor(writer, metadata);
            reader.accept(new ClassRemapper(outputVisitor, remapper), 0);
            var transformed = writer.toByteArray();
            KotlinMetadataRewriter.validate(transformed);
            var actual = new ClassReader(transformed).getClassName().replace('/', '.');
            var expected = mapping.get(original);
            if (!expected.equals(actual)) {
                throw failure(project, variant, original, "ASM output identity differs from plan",
                        "Regenerate the plan and inspect the representative class fixture");
            }
            output.put(actual.replace('.', '/') + ".class", transformed);
        }
        return Map.copyOf(output);
    }

    private static void validateClosure(
            Map<String, byte[]> outputs,
            Inventory inventory,
            Map<String, String> mapping,
            String project,
            String variant) {
        for (var entry : mapping.entrySet()) {
            var originalPath = entry.getKey().replace('.', '/') + ".class";
            var targetPath = entry.getValue().replace('.', '/') + ".class";
            if (outputs.containsKey(originalPath) || !outputs.containsKey(targetPath)) {
                throw failure(project, variant, entry.getKey(),
                        "Planned class output closure is incomplete",
                        "Inspect the Class Rewrite Plan and input class family");
            }
        }
        for (var classEntry : inventory.classes().values()) {
            if (!mapping.containsKey(classEntry.name())
                    && !java.util.Arrays.equals(classEntry.bytes(), outputs.get(classEntry.path()))) {
                throw failure(project, variant, classEntry.name(),
                        "Untouched class bytes changed", "Keep non-target PROJECT classes byte-identical");
            }
        }
    }

    static Document parseManifest(byte[] bytes, String project, String variant) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(bytes));
        } catch (Exception exception) {
            throw failure(project, variant, "MERGED_MANIFEST",
                    "Merged Manifest is not securely parseable",
                    "Fix the Consumer Manifest before class rewriting");
        }
    }

    static List<ManifestReference> manifestReferences(Document document, String applicationId) {
        var references = new ArrayList<ManifestReference>();
        var counters = new HashMap<String, Integer>();
        var elements = document.getElementsByTagName("*");
        for (var index = 0; index < elements.getLength(); index++) {
            var element = (Element) elements.item(index);
            var kind = element.getLocalName() == null ? element.getTagName() : element.getLocalName();
            var attributes = MANIFEST_REGISTRY.get(kind);
            if (attributes == null) {
                continue;
            }
            var ordinal = counters.merge(kind, 1, Integer::sum) - 1;
            for (var attribute : attributes.stream().sorted().toList()) {
                if (!element.hasAttributeNS(ANDROID_NAMESPACE, attribute)) {
                    continue;
                }
                var lexical = element.getAttributeNS(ANDROID_NAMESPACE, attribute);
                references.add(new ManifestReference(
                        "manifest/" + kind + "[" + ordinal + "]@android:" + attribute,
                        kind,
                        ordinal,
                        attribute,
                        lexical,
                        resolveManifestIdentity(lexical, applicationId)));
            }
        }
        return references.stream().sorted(Comparator.comparing(ManifestReference::location)).toList();
    }

    static byte[] rewriteManifest(
            Document document,
            List<ClassRewriteArtifacts.ManifestSite> sites,
            String project,
            String variant) {
        var planned = sites.stream().collect(java.util.stream.Collectors.toMap(
                ClassRewriteArtifacts.ManifestSite::location, Function.identity()));
        var counters = new HashMap<String, Integer>();
        var applied = new TreeSet<String>();
        var elements = document.getElementsByTagName("*");
        for (var index = 0; index < elements.getLength(); index++) {
            var element = (Element) elements.item(index);
            var kind = element.getLocalName() == null ? element.getTagName() : element.getLocalName();
            var attributes = MANIFEST_REGISTRY.get(kind);
            if (attributes == null) {
                continue;
            }
            var ordinal = counters.merge(kind, 1, Integer::sum) - 1;
            for (var attribute : attributes.stream().sorted().toList()) {
                var location = "manifest/" + kind + "[" + ordinal + "]@android:" + attribute;
                var site = planned.get(location);
                if (site == null) {
                    continue;
                }
                var actual = element.getAttributeNS(ANDROID_NAMESPACE, attribute);
                if (!site.original().equals(actual)) {
                    throw failure(project, variant, location, "Manifest value drifted after planning",
                            "Re-run from unchanged merged Manifest inputs");
                }
                element.setAttributeNS(ANDROID_NAMESPACE, "android:" + attribute, site.target());
                applied.add(location);
            }
        }
        if (!applied.equals(planned.keySet())) {
            throw failure(project, variant, "MERGED_MANIFEST",
                    "Not every planned Manifest site was applied",
                    "Regenerate the Class Rewrite Plan from the current Manifest");
        }
        try {
            var factory = TransformerFactory.newInstance();
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            var bytes = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(document), new StreamResult(bytes));
            return bytes.toByteArray();
        } catch (Exception exception) {
            throw failure(project, variant, "MERGED_MANIFEST",
                    "Rewritten Manifest could not be serialized",
                    "Inspect the planned semantic Manifest sites");
        }
    }

    private static void closeClassFamilies(Set<String> roots, Set<String> allNames) {
        var changed = true;
        while (changed) {
            changed = false;
            for (var name : allNames) {
                for (var root : List.copyOf(roots)) {
                    var rootOuter = root.contains("$") ? root.substring(0, root.indexOf('$')) : root;
                    var nameOuter = name.contains("$") ? name.substring(0, name.indexOf('$')) : name;
                    if (rootOuter.equals(nameOuter) && roots.add(name)) {
                        changed = true;
                    }
                }
            }
        }
    }

    private static void closeKotlinAssociations(
            Set<String> roots, Map<String, ClassEntry> classes) {
        var changed = true;
        while (changed) {
            changed = false;
            for (var root : List.copyOf(roots)) {
                var entry = classes.get(root);
                if (entry == null || !entry.hasKotlinMetadata()) continue;
                for (var associated : KotlinMetadataRewriter.associatedClassNames(entry.bytes())) {
                    if (classes.containsKey(associated) && roots.add(associated)) changed = true;
                }
            }
            if (changed) closeClassFamilies(roots, classes.keySet());
        }
    }

    private static void verifyInputsUnchanged(
            List<SourceArtifact> sources,
            Path adoptionPath,
            byte[] adoptionBytes,
            Path manifestPath,
            byte[] manifestBytes,
            Path intentPath,
            byte[] intentBytes,
            Path xmlIntentPath,
            byte[] xmlIntentBytes,
            String project,
            String variant) throws IOException {
        if (!sha256(Files.readAllBytes(adoptionPath)).equals(sha256(adoptionBytes))
                || !sha256(Files.readAllBytes(manifestPath)).equals(sha256(manifestBytes))
                || !sha256(Files.readAllBytes(intentPath)).equals(sha256(intentBytes))
                || !sha256(Files.readAllBytes(xmlIntentPath)).equals(sha256(xmlIntentBytes))) {
            throw failure(project, variant, "plan-input", "Plan input drift detected",
                    "Retry from stable Adoption Plan and merged Manifest inputs");
        }
        for (var source : sources) {
            var actual = source.jar() ? sha256(Files.readAllBytes(source.path()))
                    : directoryDigest(source.path());
            if (!source.sha256().equals(actual)) {
                throw failure(project, variant, source.origin(), "Class input drift detected",
                        "Retry from stable compiled PROJECT classes");
            }
        }
    }

    private static void writeJar(Path output, Map<String, byte[]> entries) throws IOException {
        Files.createDirectories(output.getParent());
        var temporary = output.resolveSibling(output.getFileName() + ".tmp");
        Files.deleteIfExists(temporary);
        try (var zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
            for (var entry : entries.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                var zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTime(0L);
                zip.putNextEntry(zipEntry);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        Files.move(temporary, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static String rawMapping(Map<String, String> mapping) {
        var text = new StringBuilder("schema=KaleidoRawClassMapping.v1\n");
        mapping.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                text.append(entry.getKey()).append(" -> ").append(entry.getValue()).append('\n'));
        return text.toString();
    }

    private static void writeProtectionRules(
            Path outputDirectory,
            Map<String, java.util.EnumSet<KaleidoProtectionDimension>> dimensions,
            Map<String, String> mapping) throws IOException {
        var rules = new StringBuilder("# Kaleido Protection Requirements v1\n");
        var needsRuntimeAttributes = false;
        for (var entry : dimensions.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            var identity = mapping.getOrDefault(entry.getKey(), entry.getKey());
            var selected = entry.getValue();
            var reachability = selected.contains(KaleidoProtectionDimension.REACHABILITY);
            var original = selected.contains(KaleidoProtectionDimension.ORIGINAL_IDENTITY);
            if (reachability) {
                rules.append("-keep,allowoptimization")
                        .append(original ? "" : ",allowobfuscation")
                        .append(" class ").append(identity).append(" { *; }\n");
            } else if (original) {
                rules.append("-keepnames class ").append(identity).append('\n');
            }
            needsRuntimeAttributes |= selected.contains(
                    KaleidoProtectionDimension.RUNTIME_ATTRIBUTES);
        }
        if (needsRuntimeAttributes) {
            rules.append("-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,")
                    .append("AnnotationDefault,Signature,InnerClasses,EnclosingMethod\n");
        }
        Files.createDirectories(outputDirectory);
        writeText(outputDirectory.resolve("protection.keep"), rules.toString());
    }

    private static String directoryDigest(Path root) throws IOException {
        var digest = digest();
        if (Files.exists(root)) {
            try (var paths = Files.walk(root)) {
                for (var path : paths.filter(Files::isRegularFile).sorted().toList()) {
                    var relative = root.relativize(path).toString()
                            .replace(java.io.File.separatorChar, '/');
                    digest.update(relative.getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    digest.update(Files.readAllBytes(path));
                    digest.update((byte) 0);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Map<String, String> readProperties(byte[] bytes) {
        var values = new HashMap<String, String>();
        for (var line : new String(bytes, StandardCharsets.UTF_8).split("\\n")) {
            var separator = line.indexOf('=');
            if (separator > 0) {
                values.put(line.substring(0, separator), line.substring(separator + 1));
            }
        }
        return Map.copyOf(values);
    }

    private static ManifestIntent readManifestIntent(
            byte[] bytes, String project, String variant) {
        var mapping = new TreeMap<String, String>();
        var sites = new ArrayList<ClassRewriteArtifacts.ManifestSite>();
        var schema = "";
        var originalManifestSha256 = "";
        for (var line : new String(bytes, StandardCharsets.UTF_8).split("\\n")) {
            if (line.startsWith("schema=")) {
                schema = line.substring("schema=".length());
            } else if (line.startsWith("originalManifestSha256=")) {
                originalManifestSha256 = line.substring("originalManifestSha256=".length());
            } else if (line.startsWith("mapping=")) {
                var values = line.substring("mapping=".length()).split("\\|", 2);
                if (values.length == 2 && mapping.putIfAbsent(values[0], values[1]) != null) {
                    throw failure(project, variant, values[0],
                            "Duplicate Manifest rewrite mapping",
                            "Regenerate a unique Manifest Rewrite Intent");
                }
            } else if (line.startsWith("site=")) {
                var values = line.substring("site=".length()).split("\\|", 3);
                if (values.length == 3) {
                    sites.add(new ClassRewriteArtifacts.ManifestSite(
                            values[0], values[1], values[2]));
                }
            }
        }
        if (!"ManifestRewriteIntent.v1".equals(schema)
                || !originalManifestSha256.matches("[0-9a-f]{64}")) {
            throw failure(project, variant, "ManifestRewriteIntent",
                    "Unknown or incomplete Manifest Rewrite Intent major",
                    "Regenerate the intent with this Kaleido version");
        }
        return new ManifestIntent(originalManifestSha256, Map.copyOf(mapping),
                sites.stream().sorted(Comparator.comparing(
                        ClassRewriteArtifacts.ManifestSite::location)).toList());
    }

    private static XmlIntent readXmlIntent(byte[] bytes, String project, String variant) {
        var mapping = new TreeMap<String, String>();
        var sites = new ArrayList<ClassRewriteArtifacts.ManifestSite>();
        var schema = "";
        for (var line : new String(bytes, StandardCharsets.UTF_8).split("\\n")) {
            if (line.startsWith("schema=")) {
                schema = line.substring("schema=".length());
            } else if (line.startsWith("mapping=")) {
                var values = line.substring("mapping=".length()).split("\\|", 2);
                if (values.length == 2 && mapping.putIfAbsent(values[0], values[1]) != null) {
                    throw failure(project, variant, values[0],
                            "Duplicate semantic XML rewrite mapping",
                            "Regenerate a unique XmlRewriteIntent.v1");
                }
            } else if (line.startsWith("site=")) {
                var values = line.substring("site=".length()).split("\\|", 3);
                if (values.length == 3) {
                    sites.add(new ClassRewriteArtifacts.ManifestSite(
                            values[0], values[1], values[2]));
                }
            }
        }
        if (!"XmlRewriteIntent.v1".equals(schema)) {
            throw failure(project, variant, "XmlRewriteIntent",
                    "Unknown semantic XML Rewrite Intent major",
                    "Regenerate the intent with this Kaleido version");
        }
        return new XmlIntent(Map.copyOf(mapping), sites.stream()
                .sorted(Comparator.comparing(ClassRewriteArtifacts.ManifestSite::location)).toList());
    }

    static List<EscapeHatchDeclaration> parseEscapeHatches(
            String declarations,
            EscapeHatchDeclaration.Kind kind,
            String project,
            String variant) {
        if (declarations == null || declarations.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(declarations.split(","))
                .map(value -> EscapeHatchDeclaration.parse(value, kind, project, variant))
                .sorted(Comparator.comparing(EscapeHatchDeclaration::id))
                .toList();
    }

    private static Set<String> referencedTypes(byte[] classBytes) {
        var references = new TreeSet<String>();
        var collector = new org.objectweb.asm.commons.Remapper(Opcodes.ASM9) {
            @Override
            public String map(String internalName) {
                references.add(internalName.replace('/', '.'));
                return internalName;
            }
        };
        new ClassReader(classBytes).accept(new ClassRemapper(
                new ClassVisitor(Opcodes.ASM9) {}, collector), 0);
        return Set.copyOf(references);
    }

    private static Set<String> exactReflectionTargets(byte[] classBytes) {
        var targets = new TreeSet<String>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(
                    int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    private String lastString;

                    @Override public void visitLdcInsn(Object value) {
                        lastString = value instanceof String text ? text : null;
                    }

                    @Override public void visitMethodInsn(
                            int opcode, String owner, String methodName,
                            String methodDescriptor, boolean isInterface) {
                        var exactClassLookup = owner.equals("java/lang/Class")
                                && methodName.equals("forName")
                                && methodDescriptor.startsWith("(Ljava/lang/String;");
                        var exactLoaderLookup = owner.equals("java/lang/ClassLoader")
                                && methodName.equals("loadClass")
                                && methodDescriptor.startsWith("(Ljava/lang/String;");
                        if (lastString != null && (exactClassLookup || exactLoaderLookup)) {
                            targets.add(lastString);
                        }
                        lastString = null;
                    }

                    @Override public void visitInsn(int opcode) { lastString = null; }
                    @Override public void visitIntInsn(int opcode, int operand) { lastString = null; }
                    @Override public void visitVarInsn(int opcode, int varIndex) { lastString = null; }
                    @Override public void visitTypeInsn(int opcode, String type) { lastString = null; }
                    @Override public void visitFieldInsn(
                            int opcode, String owner, String name, String descriptor) {
                        lastString = null;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return Set.copyOf(targets);
    }

    private static Set<ResourceLookup> exactResourceLookups(
            byte[] classBytes, String className) {
        var lookups = new TreeSet<ResourceLookup>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(
                    int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    private final ArrayList<String> recentStrings = new ArrayList<>();

                    @Override public void visitLdcInsn(Object value) {
                        if (value instanceof String text) {
                            recentStrings.add(text);
                            if (recentStrings.size() > 8) recentStrings.remove(0);
                        }
                    }

                    @Override public void visitMethodInsn(
                            int opcode, String owner, String methodName,
                            String methodDescriptor, boolean isInterface) {
                        if (owner.equals("android/content/res/Resources")
                                && methodName.equals("getIdentifier")
                                && methodDescriptor.equals(
                                        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I")) {
                            for (var index = recentStrings.size() - 2; index >= 0; index--) {
                                var candidateName = recentStrings.get(index);
                                var candidateType = recentStrings.get(index + 1);
                                if (candidateName.matches("[a-z][a-z0-9_]*")
                                        && RESOURCE_TYPES.contains(candidateType)) {
                                    lookups.add(new ResourceLookup(candidateType,
                                            candidateName, className + "#" + name + descriptor));
                                    break;
                                }
                            }
                            recentStrings.clear();
                        }
                    }

                    @Override public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
                        recentStrings.clear();
                    }

                    @Override public void visitLabel(org.objectweb.asm.Label label) {
                        recentStrings.clear();
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return Set.copyOf(lookups);
    }

    private static String resourceProtectionEvidence(Set<ResourceLookup> lookups) {
        var output = new StringBuilder("schema=ResourceProtectionEvidence.v1\n");
        for (var lookup : lookups) {
            output.append("resource=").append(lookup.type()).append('/')
                    .append(lookup.name()).append("|exact-getIdentifier|")
                    .append(lookup.origin()).append('\n');
        }
        return output.toString();
    }

    private static boolean hasNativeMethods(byte[] classBytes) {
        var found = new boolean[1];
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(
                    int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                found[0] |= (access & Opcodes.ACC_NATIVE) != 0;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static Set<String> commaSeparated(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Set.of(value.split(","));
    }

    private static String required(
            Map<String, String> values, String key, String project, String variant) {
        var value = values.get(key);
        if (value == null) {
            throw failure(project, variant, key, "Adoption Plan value is missing",
                    "Regenerate a complete AdoptionPlan.v1");
        }
        return value;
    }

    private static void requireSchema(Map<String, String> plan, String project, String variant) {
        if (!"AdoptionPlan.v1".equals(plan.get("schema"))) {
            throw failure(project, variant, "schema", "Unknown Adoption Plan major",
                    "Regenerate the plan with this Kaleido version");
        }
    }

    private static String resolveManifestIdentity(String lexical, String applicationId) {
        if (lexical.startsWith(".")) {
            return applicationId + lexical;
        }
        return lexical.contains(".") ? lexical : applicationId + "." + lexical;
    }

    static String renderManifestIdentity(
            String originalLexical, String applicationId, String target) {
        if (originalLexical.startsWith(".") && target.startsWith(applicationId + ".")) {
            return target.substring(applicationId.length());
        }
        return target;
    }

    private static String packageName(String identity) {
        var outer = identity.contains("$") ? identity.substring(0, identity.indexOf('$')) : identity;
        return outer.substring(0, outer.lastIndexOf('.'));
    }

    private static void writeText(Path path, String text) throws IOException {
        writeBytes(path, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
    }

    static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    static org.gradle.api.GradleException failure(
            String project, String variant, String target, String reason, String repair) {
        return new KaleidoDiagnostic("KLD-CLASS-001", project, variant, "class-rewrite",
                ClassRewriteArtifacts.PLAN_SCHEMA, target, reason, repair).failure();
    }

    static org.gradle.api.GradleException protectionFailure(
            String project, String variant, String target, String reason, String repair) {
        return new KaleidoDiagnostic("KLD-PROTECTION-001", project, variant, "protection",
                "kaleido.protection", target, reason, repair).failure();
    }

    private record SourceArtifact(Path path, boolean jar, String origin, String sha256) {}
    private record ClassEntry(String name, String path, String origin, byte[] bytes,
                              boolean hasKotlinMetadata) {}
    private record Inventory(Map<String, byte[]> entries, Map<String, ClassEntry> classes) {}
    private record ResourceLookup(String type, String name, String origin)
            implements Comparable<ResourceLookup> {
        @Override public int compareTo(ResourceLookup other) {
            var typeOrder = type.compareTo(other.type);
            if (typeOrder != 0) return typeOrder;
            var nameOrder = name.compareTo(other.name);
            return nameOrder != 0 ? nameOrder : origin.compareTo(other.origin);
        }
    }
    private record ManifestIntent(String originalManifestSha256,
                                  Map<String, String> mapping,
                                  List<ClassRewriteArtifacts.ManifestSite> sites) {}
    private record XmlIntent(Map<String, String> mapping,
                             List<ClassRewriteArtifacts.ManifestSite> sites) {}
    record ManifestReference(String location, String element, int ordinal,
                             String attribute, String lexicalValue,
                             String resolvedIdentity) {}
}
