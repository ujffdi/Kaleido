package com.tongsr.kaleido.gradle

import com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.EnumSet
import java.util.HexFormat
import java.util.LinkedHashMap
import java.util.TreeMap
import java.util.TreeSet
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import org.objectweb.asm.commons.SimpleRemapper
import org.w3c.dom.Document
import org.w3c.dom.Element

@CacheableTask
abstract class RewriteClassesAndManifestTask : DefaultTask() {
    @get:Input
    val kaleidoCacheSchema: String
        get() = "ClassRewriteCache.v1"

    @get:Input
    abstract val consumerProjectPath: Property<String>

    @get:Input
    abstract val variantName: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputJars: ListProperty<RegularFile>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputDirectories: ListProperty<Directory>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputManifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val adoptionPlan: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifestRewriteIntent: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val xmlRewriteIntent: RegularFileProperty

    @get:OutputFile
    abstract val outputClasses: RegularFileProperty

    @get:OutputFile
    abstract val rewritePlan: RegularFileProperty

    @get:OutputFile
    abstract val rawMapping: RegularFileProperty

    @get:OutputFile
    abstract val transformReceipt: RegularFileProperty

    @get:OutputFile
    abstract val resourceProtectionEvidence: RegularFileProperty

    @get:OutputFile
    abstract val composeCompiledInventory: RegularFileProperty

    @get:OutputDirectory
    abstract val protectionKeepRulesOutputDirectory: DirectoryProperty

    @TaskAction
    fun rewrite() {
        val project = consumerProjectPath.get()
        val variant = variantName.get()
        val adoptionPath = adoptionPlan.get().asFile.toPath()
        val manifestPath = inputManifest.get().asFile.toPath()
        val adoptionBytes = Files.readAllBytes(adoptionPath)
        val manifestBytes = Files.readAllBytes(manifestPath)
        val intentBytes = Files.readAllBytes(manifestRewriteIntent.get().asFile.toPath())
        val intent = readManifestIntent(intentBytes, project, variant)
        val xmlIntentBytes = Files.readAllBytes(xmlRewriteIntent.get().asFile.toPath())
        val xmlIntent = readXmlIntent(xmlIntentBytes, project, variant)
        val adoption = readProperties(adoptionBytes)
        requireSchema(adoption, project, variant)

        val sourceArtifacts = sourceArtifacts()
        val inventory = Companion.inventory(sourceArtifacts, project, variant)
        val composeInventory = ComposeCompiledValidator.validate(
            adoption,
            inventory.classes.mapValues { it.value.bytes },
            manifestBytes,
            project,
            variant,
        )
        val resourceLookups = inventory.classes.values
            .flatMap { classEntry -> exactResourceLookups(classEntry.bytes, classEntry.name) }
            .toCollection(TreeSet())
        val protectedNames = TreeSet(commaSeparated(adoption["protection.originalClassNames"]))
        val protectionReasons = HashMap<String, TreeSet<String>>()
        val protectionDimensions = HashMap<String, EnumSet<KaleidoProtectionDimension>>()
        protectedNames.forEach { name ->
            protectionReasons.computeIfAbsent(name) { TreeSet() }.add("legacy-original-name")
        }
        protectedNames.forEach { name ->
            protectionDimensions.computeIfAbsent(name) {
                EnumSet.noneOf(KaleidoProtectionDimension::class.java)
            }.add(KaleidoProtectionDimension.ORIGINAL_IDENTITY)
        }
        val classHatches = parseEscapeHatches(
            adoption["protection.classEscapeHatches"],
            EscapeHatchDeclaration.Kind.CLASS,
            project,
            variant,
        )
        for (hatch in classHatches) {
            val matches = inventory.classes.keys.filter { hatch.matches(it) }.sorted()
            if (matches.isEmpty()) {
                throw protectionFailure(
                    project,
                    variant,
                    hatch.id,
                    "Escape Hatch resolves to zero PROJECT classes",
                    "Remove the stale declaration or select an existing bounded class target",
                )
            }
            for (match in matches) {
                protectionReasons.computeIfAbsent(match) { TreeSet() }.add(
                    "escape-hatch:" + hatch.id + ":" +
                        hatch.dimensions.map { it.name }.sorted().joinToString("+"),
                )
                protectionDimensions.computeIfAbsent(match) {
                    EnumSet.noneOf(KaleidoProtectionDimension::class.java)
                }.addAll(hatch.dimensions)
            }
            if (hatch.protects(KaleidoProtectionDimension.ORIGINAL_IDENTITY)) {
                protectedNames.addAll(matches)
            }
            if (hatch.protects(KaleidoProtectionDimension.DESCRIPTOR_CLOSURE)) {
                matches.map { inventory.classes.getValue(it) }
                    .flatMap { entry -> referencedTypes(entry.bytes) }
                    .filter { inventory.classes.containsKey(it) }
                    .forEach { name ->
                        protectedNames.add(name)
                        protectionReasons.computeIfAbsent(name) { TreeSet() }
                            .add("descriptor-closure:" + hatch.id)
                        protectionDimensions.computeIfAbsent(name) {
                            EnumSet.noneOf(KaleidoProtectionDimension::class.java)
                        }.addAll(
                            EnumSet.of(
                                KaleidoProtectionDimension.REACHABILITY,
                                KaleidoProtectionDimension.ORIGINAL_IDENTITY,
                                KaleidoProtectionDimension.DESCRIPTOR_CLOSURE,
                            ),
                        )
                    }
            }
        }
        for (classEntry in inventory.classes.values) {
            for (reflected in exactReflectionTargets(classEntry.bytes)) {
                if (inventory.classes.containsKey(reflected)) {
                    protectedNames.add(reflected)
                    protectionReasons.computeIfAbsent(reflected) { TreeSet() }
                        .add("inferred-exact-reflection")
                    protectionDimensions.computeIfAbsent(reflected) {
                        EnumSet.noneOf(KaleidoProtectionDimension::class.java)
                    }.addAll(
                        EnumSet.of(
                            KaleidoProtectionDimension.REACHABILITY,
                            KaleidoProtectionDimension.ORIGINAL_IDENTITY,
                            KaleidoProtectionDimension.DESCRIPTOR_CLOSURE,
                            KaleidoProtectionDimension.RUNTIME_ATTRIBUTES,
                        ),
                    )
                }
            }
            if (hasNativeMethods(classEntry.bytes)) {
                protectedNames.add(classEntry.name)
                protectionReasons.computeIfAbsent(classEntry.name) { TreeSet() }
                    .add("inferred-native-declaration")
                protectionDimensions.computeIfAbsent(classEntry.name) {
                    EnumSet.noneOf(KaleidoProtectionDimension::class.java)
                }.addAll(
                    EnumSet.of(
                        KaleidoProtectionDimension.ORIGINAL_IDENTITY,
                        KaleidoProtectionDimension.DESCRIPTOR_CLOSURE,
                    ),
                )
                referencedTypes(classEntry.bytes)
                    .filter { inventory.classes.containsKey(it) }
                    .forEach { name ->
                        protectedNames.add(name)
                        protectionReasons.computeIfAbsent(name) { TreeSet() }
                            .add("native-descriptor-closure:" + classEntry.name)
                    }
            }
        }
        closeClassFamilies(protectedNames, inventory.classes.keys)
        closeKotlinAssociations(protectedNames, inventory.classes)
        protectedNames.forEach { name ->
            protectionDimensions.computeIfAbsent(name) {
                EnumSet.noneOf(KaleidoProtectionDimension::class.java)
            }.add(KaleidoProtectionDimension.ORIGINAL_IDENTITY)
        }
        val roots = TreeSet(intent.mapping.keys)
        roots.addAll(xmlIntent.mapping.keys)
        val packageBase = required(adoption, "generation.packageBase", project, variant)
        inventory.classes.keys
            .filter { name -> name.startsWith("$packageBase.") }
            .forEach { roots.add(it) }
        closeClassFamilies(roots, inventory.classes.keys)
        closeKotlinAssociations(roots, inventory.classes)

        val mapping = allocateMapping(
            roots,
            protectedNames,
            inventory.classes.keys,
            required(adoption, "seed.domain.class-rewrite", project, variant),
        )
        for (entry in intent.mapping.entries) {
            if (entry.value != mapping[entry.key]) {
                throw failure(
                    project,
                    variant,
                    entry.key,
                    "Manifest identity target collides with compiled class inventory",
                    "Change the seed or protect the colliding class identity",
                )
            }
        }
        for (entry in xmlIntent.mapping.entries) {
            if (entry.value != mapping[entry.key]) {
                throw failure(
                    project,
                    variant,
                    entry.key,
                    "XML identity target collides with compiled class inventory",
                    "Change the seed or protect the colliding class identity",
                )
            }
        }
        val decisions = ArrayList<ClassRewriteArtifacts.ClassDecision>()
        for (classEntry in inventory.classes.values.sortedBy { it.name }) {
            val target = mapping.getOrDefault(classEntry.name, classEntry.name)
            val action = when {
                mapping.containsKey(classEntry.name) -> "REWRITE"
                protectedNames.contains(classEntry.name) -> "PROTECTED"
                else -> "UNTOUCHED"
            }
            val reason = when (action) {
                "REWRITE" -> "reference-driven-root-or-closure"
                "PROTECTED" -> protectionReasons.getOrDefault(
                    classEntry.name,
                    TreeSet(setOf("family-closure")),
                ).joinToString("+")
                else -> "outside-rewrite-roots"
            }
            decisions.add(
                ClassRewriteArtifacts.ClassDecision(
                    classEntry.name,
                    classEntry.origin,
                    sha256(classEntry.bytes),
                    action,
                    target,
                    reason,
                ),
            )
        }
        val sites = (intent.sites + xmlIntent.sites).sortedBy { it.location }
        val inputs = ArrayList(
            sourceArtifacts.map { source ->
                ClassRewriteArtifacts.InputArtifact(source.origin, source.sha256)
            }.sortedBy { it.origin },
        )
        inputs.add(ClassRewriteArtifacts.InputArtifact("manifest-rewrite-intent", sha256(intentBytes)))
        inputs.add(ClassRewriteArtifacts.InputArtifact("xml-rewrite-intent", sha256(xmlIntentBytes)))
        val expectedOutputs = decisions.map { it.target }.sorted()
        val plan = ClassRewriteArtifacts.Plan(
            ClassRewriteArtifacts.PLAN_SCHEMA,
            ClassRewriteArtifacts.PRODUCER,
            project,
            variant,
            sha256(adoptionBytes),
            intent.originalManifestSha256,
            inputs,
            decisions,
            sites,
            expectedOutputs,
        )
        val planBytes = ClassRewriteArtifacts.encodePlan(plan)
        val executablePlan = ClassRewriteArtifacts.decodePlan(planBytes, project, variant)
        verifyInputsUnchanged(
            sourceArtifacts,
            adoptionPath,
            adoptionBytes,
            manifestPath,
            manifestBytes,
            manifestRewriteIntent.get().asFile.toPath(),
            intentBytes,
            xmlRewriteIntent.get().asFile.toPath(),
            xmlIntentBytes,
            project,
            variant,
        )

        val executableMapping = executablePlan.decisions
            .filter { decision -> decision.action == "REWRITE" }
            .associate { it.original to it.target }
        val outputEntries = rewriteClasses(inventory, executableMapping, project, variant)
        validateClosure(outputEntries, inventory, executableMapping, project, variant)

        val outputJar = outputClasses.get().asFile.toPath()
        writeJar(outputJar, outputEntries)
        writeBytes(rewritePlan.get().asFile.toPath(), planBytes)
        writeText(rawMapping.get().asFile.toPath(), formatRawMapping(executableMapping))
        writeText(
            resourceProtectionEvidence.get().asFile.toPath(),
            formatResourceProtectionEvidence(resourceLookups),
        )
        writeText(
            composeCompiledInventory.get().asFile.toPath(),
            ComposeCompiledValidator.inventoryText(composeInventory, executableMapping),
        )
        writeProtectionRules(
            protectionKeepRulesOutputDirectory.get().asFile.toPath(),
            protectionDimensions,
            executableMapping,
        )
        writeText(
            protectionKeepRulesOutputDirectory.get().asFile.toPath().resolve("compose.keep"),
            ComposeCompiledValidator.keepRules(composeInventory, executableMapping),
        )
        val receipt = ClassRewriteArtifacts.Receipt(
            ClassRewriteArtifacts.RECEIPT_SCHEMA,
            ClassRewriteArtifacts.PRODUCER,
            project,
            variant,
            sha256(planBytes),
            sha256(Files.readAllBytes(outputJar)),
            sha256(manifestBytes),
            executableMapping.size,
            true,
            true,
            sourceArtifacts.map { it.sha256 }.sorted(),
        )
        writeBytes(transformReceipt.get().asFile.toPath(), ClassRewriteArtifacts.encodeReceipt(receipt))
    }

    private fun sourceArtifacts(): List<SourceArtifact> {
        val sources = ArrayList<SourceArtifact>()
        for (regularFile in inputJars.get()) {
            val path = regularFile.asFile.toPath()
            val digest = sha256(Files.readAllBytes(path))
            sources.add(SourceArtifact(path, true, "jar/$digest", digest))
        }
        for (directory in inputDirectories.get()) {
            val path = directory.asFile.toPath()
            val digest = directoryDigest(path)
            sources.add(SourceArtifact(path, false, "directory/$digest", digest))
        }
        return sources.sortedBy { it.origin }
    }

    @JvmRecord
    data class ManifestReference(
        val location: String,
        val element: String,
        val ordinal: Int,
        val attribute: String,
        val lexicalValue: String,
        val resolvedIdentity: String,
    )

    @JvmRecord
    private data class SourceArtifact(
        val path: Path,
        val jar: Boolean,
        val origin: String,
        val sha256: String,
    )

    @JvmRecord
    private data class ClassEntry(
        val name: String,
        val path: String,
        val origin: String,
        val bytes: ByteArray,
        val hasKotlinMetadata: Boolean,
    )

    @JvmRecord
    private data class Inventory(
        val entries: Map<String, ByteArray>,
        val classes: Map<String, ClassEntry>,
    )

    @JvmRecord
    private data class ResourceLookup(
        val type: String,
        val name: String,
        val origin: String,
    ) : Comparable<ResourceLookup> {
        override fun compareTo(other: ResourceLookup): Int {
            val typeOrder = type.compareTo(other.type)
            if (typeOrder != 0) return typeOrder
            val nameOrder = name.compareTo(other.name)
            return if (nameOrder != 0) nameOrder else origin.compareTo(other.origin)
        }
    }

    @JvmRecord
    private data class ManifestIntent(
        val originalManifestSha256: String,
        val mapping: Map<String, String>,
        val sites: List<ClassRewriteArtifacts.ManifestSite>,
    )

    @JvmRecord
    private data class XmlIntent(
        val mapping: Map<String, String>,
        val sites: List<ClassRewriteArtifacts.ManifestSite>,
    )

    companion object {
        private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        private val RESOURCE_TYPES = setOf(
            "anim", "animator", "array", "attr", "bool", "color", "dimen",
            "drawable", "font", "fraction", "id", "integer", "interpolator",
            "layout", "menu", "mipmap", "navigation", "plurals", "raw",
            "string", "style", "transition", "xml",
        )
        private val MANIFEST_REGISTRY = mapOf(
            "application" to setOf(
                "name", "backupAgent", "appComponentFactory",
                "manageSpaceActivity", "zygotePreloadName",
            ),
            "activity" to setOf("name", "parentActivityName"),
            "service" to setOf("name"),
            "receiver" to setOf("name"),
            "provider" to setOf("name"),
            "activity-alias" to setOf("targetActivity"),
            "instrumentation" to setOf("name"),
        )

        private fun inventory(
            sources: List<SourceArtifact>,
            project: String,
            variant: String,
        ): Inventory {
            val entries = TreeMap<String, ByteArray>()
            val origins = HashMap<String, String>()
            for (source in sources) {
                if (source.jar) {
                    ZipFile(source.path.toFile()).use { zip ->
                        for (entry in zip.stream()
                            .filter { item -> !item.isDirectory }
                            .sorted(compareBy { it.name })
                            .toList()) {
                            addEntry(
                                entries,
                                origins,
                                entry.name,
                                zip.getInputStream(entry).readAllBytes(),
                                source.origin + "!" + entry.name,
                                project,
                                variant,
                            )
                        }
                    }
                } else if (Files.exists(source.path)) {
                    Files.walk(source.path).use { paths ->
                        for (path in paths.filter { Files.isRegularFile(it) }.sorted().toList()) {
                            val relative = source.path.relativize(path).toString()
                                .replace(File.separatorChar, '/')
                            addEntry(
                                entries,
                                origins,
                                relative,
                                Files.readAllBytes(path),
                                source.origin + "!" + relative,
                                project,
                                variant,
                            )
                        }
                    }
                }
            }
            val classes = TreeMap<String, ClassEntry>()
            for (entry in entries.entries) {
                if (!entry.key.endsWith(".class")) continue
                val reader = ClassReader(entry.value)
                val name = reader.className.replace('/', '.')
                val expectedPath = reader.className + ".class"
                if (entry.key != expectedPath || classes.containsKey(name)) {
                    throw failure(
                        project,
                        variant,
                        entry.key,
                        "Class entry path, this_class, or uniqueness invariant failed",
                        "Remove duplicate or misnamed Application class outputs",
                    )
                }
                classes[name] = ClassEntry(
                    name,
                    entry.key,
                    origins.getValue(entry.key),
                    entry.value,
                    hasKotlinMetadata(reader),
                )
            }
            return Inventory(entries.toMap(), classes.toMap())
        }

        private fun addEntry(
            entries: MutableMap<String, ByteArray>,
            origins: MutableMap<String, String>,
            path: String,
            bytes: ByteArray,
            origin: String,
            project: String,
            variant: String,
        ) {
            if (entries.putIfAbsent(path, bytes) != null) {
                throw failure(
                    project,
                    variant,
                    path,
                    "Duplicate PROJECT class artifact entry",
                    "Remove duplicate Application class outputs before Kaleido",
                )
            }
            origins[path] = origin
        }

        private fun hasKotlinMetadata(reader: ClassReader): Boolean {
            var found = false
            reader.accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitAnnotation(
                        descriptor: String,
                        visible: Boolean,
                    ): AnnotationVisitor? {
                        if (descriptor == "Lkotlin/Metadata;") {
                            found = true
                        }
                        return null
                    }
                },
                ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
            )
            return found
        }

        @JvmStatic
        fun allocateMapping(
            roots: Set<String>,
            protectedNames: Set<String>,
            allNames: Set<String>,
            stream: String,
        ): Map<String, String> {
            val mapping = LinkedHashMap<String, String>()
            val reserved = HashSet(allNames)
            val ordered = roots.sortedWith(
                compareBy<String> { it.split('$').size }.thenBy { it },
            )
            for (original in ordered) {
                if (protectedNames.contains(original)) continue
                val outer = if (original.contains('$')) {
                    original.substring(0, original.indexOf('$'))
                } else {
                    original
                }
                val outerTarget = mapping[outer]
                val digest = SeedDerivation.derive(stream, "class-identity", original)
                var length = 10
                while (length <= digest.length) {
                    val target = if (outerTarget != null && outer != original) {
                        outerTarget + "\$C" + digest.substring(0, length)
                    } else {
                        packageName(original) + ".k" + digest.substring(0, 6) +
                            ".C" + digest.substring(0, length)
                    }
                    if (reserved.add(target)) {
                        mapping[original] = target
                        break
                    }
                    length += 2
                }
                if (!mapping.containsKey(original)) {
                    throw IllegalStateException("Unable to allocate class identity for $original")
                }
            }
            return mapping.toMap()
        }

        private fun rewriteClasses(
            inventory: Inventory,
            mapping: Map<String, String>,
            project: String,
            variant: String,
        ): Map<String, ByteArray> {
            val internalMapping = mapping.entries.associate { (key, value) ->
                key.replace('.', '/') to value.replace('.', '/')
            }
            @Suppress("DEPRECATION")
            val remapper = SimpleRemapper(internalMapping)
            val output = TreeMap<String, ByteArray>()
            for (entry in inventory.entries.entries) {
                if (!entry.key.endsWith(".class")) {
                    output[entry.key] = entry.value
                    continue
                }
                val reader = ClassReader(entry.value)
                val original = reader.className.replace('/', '.')
                if (!mapping.containsKey(original)) {
                    output[entry.key] = entry.value
                    continue
                }
                val metadata = KotlinMetadataRewriter.rewrite(entry.value, mapping, remapper)
                val writer = ClassWriter(reader, 0)
                val outputVisitor = KotlinMetadataRewriter.replacingVisitor(writer, metadata)
                reader.accept(ClassRemapper(outputVisitor, remapper), 0)
                val transformed = writer.toByteArray()
                KotlinMetadataRewriter.validate(transformed)
                val actual = ClassReader(transformed).className.replace('/', '.')
                val expected = mapping.getValue(original)
                if (expected != actual) {
                    throw failure(
                        project,
                        variant,
                        original,
                        "ASM output identity differs from plan",
                        "Regenerate the plan and inspect the representative class fixture",
                    )
                }
                output[actual.replace('.', '/') + ".class"] = transformed
            }
            return output.toMap()
        }

        private fun validateClosure(
            outputs: Map<String, ByteArray>,
            inventory: Inventory,
            mapping: Map<String, String>,
            project: String,
            variant: String,
        ) {
            for (entry in mapping.entries) {
                val originalPath = entry.key.replace('.', '/') + ".class"
                val targetPath = entry.value.replace('.', '/') + ".class"
                if (outputs.containsKey(originalPath) || !outputs.containsKey(targetPath)) {
                    throw failure(
                        project,
                        variant,
                        entry.key,
                        "Planned class output closure is incomplete",
                        "Inspect the Class Rewrite Plan and input class family",
                    )
                }
            }
            for (classEntry in inventory.classes.values) {
                if (!mapping.containsKey(classEntry.name) &&
                    !classEntry.bytes.contentEquals(outputs[classEntry.path])
                ) {
                    throw failure(
                        project,
                        variant,
                        classEntry.name,
                        "Untouched class bytes changed",
                        "Keep non-target PROJECT classes byte-identical",
                    )
                }
            }
        }

        @JvmStatic
        fun parseManifest(bytes: ByteArray, project: String, variant: String): Document {
            try {
                val factory = DocumentBuilderFactory.newInstance()
                factory.isNamespaceAware = true
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
            } catch (exception: Exception) {
                throw failure(
                    project,
                    variant,
                    "MERGED_MANIFEST",
                    "Merged Manifest is not securely parseable",
                    "Fix the Consumer Manifest before class rewriting",
                )
            }
        }

        @JvmStatic
        fun manifestReferences(document: Document, applicationId: String): List<ManifestReference> {
            val references = ArrayList<ManifestReference>()
            val counters = HashMap<String, Int>()
            val elements = document.getElementsByTagName("*")
            for (index in 0 until elements.length) {
                val element = elements.item(index) as Element
                val kind = element.localName ?: element.tagName
                val attributes = MANIFEST_REGISTRY[kind] ?: continue
                val ordinal = counters.merge(kind, 1, Int::plus)!! - 1
                for (attribute in attributes.sorted()) {
                    if (!element.hasAttributeNS(ANDROID_NAMESPACE, attribute)) continue
                    val lexical = element.getAttributeNS(ANDROID_NAMESPACE, attribute)
                    references.add(
                        ManifestReference(
                            "manifest/$kind[$ordinal]@android:$attribute",
                            kind,
                            ordinal,
                            attribute,
                            lexical,
                            resolveManifestIdentity(lexical, applicationId),
                        ),
                    )
                }
            }
            return references.sortedBy { it.location }
        }

        internal fun rewriteManifest(
            document: Document,
            sites: List<ClassRewriteArtifacts.ManifestSite>,
            project: String,
            variant: String,
        ): ByteArray {
            val planned = sites.associateBy { it.location }
            val counters = HashMap<String, Int>()
            val applied = TreeSet<String>()
            val elements = document.getElementsByTagName("*")
            for (index in 0 until elements.length) {
                val element = elements.item(index) as Element
                val kind = element.localName ?: element.tagName
                val attributes = MANIFEST_REGISTRY[kind] ?: continue
                val ordinal = counters.merge(kind, 1, Int::plus)!! - 1
                for (attribute in attributes.sorted()) {
                    val location = "manifest/$kind[$ordinal]@android:$attribute"
                    val site = planned[location] ?: continue
                    val actual = element.getAttributeNS(ANDROID_NAMESPACE, attribute)
                    if (site.original != actual) {
                        throw failure(
                            project,
                            variant,
                            location,
                            "Manifest value drifted after planning",
                            "Re-run from unchanged merged Manifest inputs",
                        )
                    }
                    element.setAttributeNS(ANDROID_NAMESPACE, "android:$attribute", site.target)
                    applied.add(location)
                }
            }
            if (applied != planned.keys) {
                throw failure(
                    project,
                    variant,
                    "MERGED_MANIFEST",
                    "Not every planned Manifest site was applied",
                    "Regenerate the Class Rewrite Plan from the current Manifest",
                )
            }
            try {
                val factory = TransformerFactory.newInstance()
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
                val transformer = factory.newTransformer()
                transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
                transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
                transformer.setOutputProperty(OutputKeys.INDENT, "no")
                val bytes = ByteArrayOutputStream()
                transformer.transform(DOMSource(document), StreamResult(bytes))
                return bytes.toByteArray()
            } catch (exception: Exception) {
                throw failure(
                    project,
                    variant,
                    "MERGED_MANIFEST",
                    "Rewritten Manifest could not be serialized",
                    "Inspect the planned semantic Manifest sites",
                )
            }
        }

        private fun closeClassFamilies(roots: MutableSet<String>, allNames: Set<String>) {
            var changed = true
            while (changed) {
                changed = false
                for (name in allNames) {
                    for (root in roots.toList()) {
                        val rootOuter = if (root.contains('$')) {
                            root.substring(0, root.indexOf('$'))
                        } else {
                            root
                        }
                        val nameOuter = if (name.contains('$')) {
                            name.substring(0, name.indexOf('$'))
                        } else {
                            name
                        }
                        if (rootOuter == nameOuter && roots.add(name)) {
                            changed = true
                        }
                    }
                }
            }
        }

        private fun closeKotlinAssociations(
            roots: MutableSet<String>,
            classes: Map<String, ClassEntry>,
        ) {
            var changed = true
            while (changed) {
                changed = false
                for (root in roots.toList()) {
                    val entry = classes[root]
                    if (entry == null || !entry.hasKotlinMetadata) continue
                    for (associated in KotlinMetadataRewriter.associatedClassNames(entry.bytes)) {
                        if (classes.containsKey(associated) && roots.add(associated)) changed = true
                    }
                }
                if (changed) closeClassFamilies(roots, classes.keys)
            }
        }

        private fun verifyInputsUnchanged(
            sources: List<SourceArtifact>,
            adoptionPath: Path,
            adoptionBytes: ByteArray,
            manifestPath: Path,
            manifestBytes: ByteArray,
            intentPath: Path,
            intentBytes: ByteArray,
            xmlIntentPath: Path,
            xmlIntentBytes: ByteArray,
            project: String,
            variant: String,
        ) {
            if (sha256(Files.readAllBytes(adoptionPath)) != sha256(adoptionBytes) ||
                sha256(Files.readAllBytes(manifestPath)) != sha256(manifestBytes) ||
                sha256(Files.readAllBytes(intentPath)) != sha256(intentBytes) ||
                sha256(Files.readAllBytes(xmlIntentPath)) != sha256(xmlIntentBytes)
            ) {
                throw failure(
                    project,
                    variant,
                    "plan-input",
                    "Plan input drift detected",
                    "Retry from stable Adoption Plan and merged Manifest inputs",
                )
            }
            for (source in sources) {
                val actual = if (source.jar) {
                    sha256(Files.readAllBytes(source.path))
                } else {
                    directoryDigest(source.path)
                }
                if (source.sha256 != actual) {
                    throw failure(
                        project,
                        variant,
                        source.origin,
                        "Class input drift detected",
                        "Retry from stable compiled PROJECT classes",
                    )
                }
            }
        }

        private fun writeJar(output: Path, entries: Map<String, ByteArray>) {
            Files.createDirectories(output.parent)
            val temporary = output.resolveSibling(output.fileName.toString() + ".tmp")
            Files.deleteIfExists(temporary)
            ZipOutputStream(Files.newOutputStream(temporary)).use { zip ->
                for (entry in entries.entries.sortedBy { it.key }) {
                    val zipEntry = ZipEntry(entry.key)
                    zipEntry.time = 0L
                    zip.putNextEntry(zipEntry)
                    zip.write(entry.value)
                    zip.closeEntry()
                }
            }
            Files.move(
                temporary,
                output,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }

        private fun formatRawMapping(mapping: Map<String, String>): String {
            val text = StringBuilder("schema=KaleidoRawClassMapping.v1\n")
            mapping.entries.sortedBy { it.key }.forEach { entry ->
                text.append(entry.key).append(" -> ").append(entry.value).append('\n')
            }
            return text.toString()
        }

        private fun writeProtectionRules(
            outputDirectory: Path,
            dimensions: Map<String, EnumSet<KaleidoProtectionDimension>>,
            mapping: Map<String, String>,
        ) {
            val rules = StringBuilder("# Kaleido Protection Requirements v1\n")
            var needsRuntimeAttributes = false
            for (entry in dimensions.entries.sortedBy { it.key }) {
                val identity = mapping.getOrDefault(entry.key, entry.key)
                val selected = entry.value
                val reachability = selected.contains(KaleidoProtectionDimension.REACHABILITY)
                val original = selected.contains(KaleidoProtectionDimension.ORIGINAL_IDENTITY)
                if (reachability) {
                    rules.append("-keep,allowoptimization")
                        .append(if (original) "" else ",allowobfuscation")
                        .append(" class ").append(identity).append(" { *; }\n")
                } else if (original) {
                    rules.append("-keepnames class ").append(identity).append('\n')
                }
                needsRuntimeAttributes = needsRuntimeAttributes ||
                    selected.contains(KaleidoProtectionDimension.RUNTIME_ATTRIBUTES)
            }
            if (needsRuntimeAttributes) {
                rules.append("-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,")
                    .append("AnnotationDefault,Signature,InnerClasses,EnclosingMethod\n")
            }
            Files.createDirectories(outputDirectory)
            writeText(outputDirectory.resolve("protection.keep"), rules.toString())
        }

        private fun directoryDigest(root: Path): String {
            val digest = digest()
            if (Files.exists(root)) {
                Files.walk(root).use { paths ->
                    for (path in paths.filter { Files.isRegularFile(it) }.sorted().toList()) {
                        val relative = root.relativize(path).toString()
                            .replace(File.separatorChar, '/')
                        digest.update(relative.toByteArray(StandardCharsets.UTF_8))
                        digest.update(0)
                        digest.update(Files.readAllBytes(path))
                        digest.update(0)
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest())
        }

        private fun readProperties(bytes: ByteArray): Map<String, String> {
            val values = HashMap<String, String>()
            for (line in String(bytes, StandardCharsets.UTF_8).split('\n')) {
                val separator = line.indexOf('=')
                if (separator > 0) {
                    values[line.substring(0, separator)] = line.substring(separator + 1)
                }
            }
            return values.toMap()
        }

        private fun readManifestIntent(
            bytes: ByteArray,
            project: String,
            variant: String,
        ): ManifestIntent {
            val mapping = TreeMap<String, String>()
            val sites = ArrayList<ClassRewriteArtifacts.ManifestSite>()
            var schema = ""
            var originalManifestSha256 = ""
            for (line in String(bytes, StandardCharsets.UTF_8).split('\n')) {
                if (line.startsWith("schema=")) {
                    schema = line.substring("schema=".length)
                } else if (line.startsWith("originalManifestSha256=")) {
                    originalManifestSha256 = line.substring("originalManifestSha256=".length)
                } else if (line.startsWith("mapping=")) {
                    val values = line.substring("mapping=".length).split('|', limit = 2)
                    if (values.size == 2 && mapping.putIfAbsent(values[0], values[1]) != null) {
                        throw failure(
                            project,
                            variant,
                            values[0],
                            "Duplicate Manifest rewrite mapping",
                            "Regenerate a unique Manifest Rewrite Intent",
                        )
                    }
                } else if (line.startsWith("site=")) {
                    val values = line.substring("site=".length).split('|', limit = 3)
                    if (values.size == 3) {
                        sites.add(
                            ClassRewriteArtifacts.ManifestSite(values[0], values[1], values[2]),
                        )
                    }
                }
            }
            if (schema != "ManifestRewriteIntent.v1" ||
                !originalManifestSha256.matches(Regex("[0-9a-f]{64}"))
            ) {
                throw failure(
                    project,
                    variant,
                    "ManifestRewriteIntent",
                    "Unknown or incomplete Manifest Rewrite Intent major",
                    "Regenerate the intent with this Kaleido version",
                )
            }
            return ManifestIntent(
                originalManifestSha256,
                mapping.toMap(),
                sites.sortedBy { it.location },
            )
        }

        private fun readXmlIntent(bytes: ByteArray, project: String, variant: String): XmlIntent {
            val mapping = TreeMap<String, String>()
            val sites = ArrayList<ClassRewriteArtifacts.ManifestSite>()
            var schema = ""
            for (line in String(bytes, StandardCharsets.UTF_8).split('\n')) {
                if (line.startsWith("schema=")) {
                    schema = line.substring("schema=".length)
                } else if (line.startsWith("mapping=")) {
                    val values = line.substring("mapping=".length).split('|', limit = 2)
                    if (values.size == 2 && mapping.putIfAbsent(values[0], values[1]) != null) {
                        throw failure(
                            project,
                            variant,
                            values[0],
                            "Duplicate semantic XML rewrite mapping",
                            "Regenerate a unique XmlRewriteIntent.v1",
                        )
                    }
                } else if (line.startsWith("site=")) {
                    val values = line.substring("site=".length).split('|', limit = 3)
                    if (values.size == 3) {
                        sites.add(
                            ClassRewriteArtifacts.ManifestSite(values[0], values[1], values[2]),
                        )
                    }
                }
            }
            if (schema != "XmlRewriteIntent.v1") {
                throw failure(
                    project,
                    variant,
                    "XmlRewriteIntent",
                    "Unknown semantic XML Rewrite Intent major",
                    "Regenerate the intent with this Kaleido version",
                )
            }
            return XmlIntent(mapping.toMap(), sites.sortedBy { it.location })
        }

        @JvmStatic
        internal fun parseEscapeHatches(
            declarations: String?,
            kind: EscapeHatchDeclaration.Kind,
            project: String,
            variant: String,
        ): List<EscapeHatchDeclaration> {
            if (declarations.isNullOrBlank()) return emptyList()
            return declarations.split(',')
                .map { value -> EscapeHatchDeclaration.parse(value, kind, project, variant) }
                .sortedBy { it.id }
        }

        private fun referencedTypes(classBytes: ByteArray): Set<String> {
            val references = TreeSet<String>()
            val collector = object : Remapper(Opcodes.ASM9) {
                override fun map(internalName: String): String {
                    references.add(internalName.replace('/', '.'))
                    return internalName
                }
            }
            ClassReader(classBytes).accept(
                ClassRemapper(object : ClassVisitor(Opcodes.ASM9) {}, collector),
                0,
            )
            return references.toSet()
        }

        private fun exactReflectionTargets(classBytes: ByteArray): Set<String> {
            val targets = TreeSet<String>()
            ClassReader(classBytes).accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<String>?,
                    ): MethodVisitor {
                        return object : MethodVisitor(Opcodes.ASM9) {
                            private var lastString: String? = null

                            override fun visitLdcInsn(value: Any?) {
                                lastString = value as? String
                            }

                            override fun visitMethodInsn(
                                opcode: Int,
                                owner: String,
                                methodName: String,
                                methodDescriptor: String,
                                isInterface: Boolean,
                            ) {
                                val exactClassLookup = owner == "java/lang/Class" &&
                                    methodName == "forName" &&
                                    methodDescriptor.startsWith("(Ljava/lang/String;")
                                val exactLoaderLookup = owner == "java/lang/ClassLoader" &&
                                    methodName == "loadClass" &&
                                    methodDescriptor.startsWith("(Ljava/lang/String;")
                                val captured = lastString
                                if (captured != null && (exactClassLookup || exactLoaderLookup)) {
                                    targets.add(captured)
                                }
                                lastString = null
                            }

                            override fun visitInsn(opcode: Int) {
                                lastString = null
                            }

                            override fun visitIntInsn(opcode: Int, operand: Int) {
                                lastString = null
                            }

                            override fun visitVarInsn(opcode: Int, varIndex: Int) {
                                lastString = null
                            }

                            override fun visitTypeInsn(opcode: Int, type: String) {
                                lastString = null
                            }

                            override fun visitFieldInsn(
                                opcode: Int,
                                owner: String,
                                name: String,
                                descriptor: String,
                            ) {
                                lastString = null
                            }
                        }
                    }
                },
                ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
            )
            return targets.toSet()
        }

        private fun exactResourceLookups(
            classBytes: ByteArray,
            className: String,
        ): Set<ResourceLookup> {
            val lookups = TreeSet<ResourceLookup>()
            ClassReader(classBytes).accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<String>?,
                    ): MethodVisitor {
                        return object : MethodVisitor(Opcodes.ASM9) {
                            private val recentStrings = ArrayList<String>()

                            override fun visitLdcInsn(value: Any?) {
                                if (value is String) {
                                    recentStrings.add(value)
                                    if (recentStrings.size > 8) recentStrings.removeAt(0)
                                }
                            }

                            override fun visitMethodInsn(
                                opcode: Int,
                                owner: String,
                                methodName: String,
                                methodDescriptor: String,
                                isInterface: Boolean,
                            ) {
                                if (owner == "android/content/res/Resources" &&
                                    methodName == "getIdentifier" &&
                                    methodDescriptor ==
                                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I"
                                ) {
                                    var index = recentStrings.size - 2
                                    while (index >= 0) {
                                        val candidateName = recentStrings[index]
                                        val candidateType = recentStrings[index + 1]
                                        if (candidateName.matches(Regex("[a-z][a-z0-9_]*")) &&
                                            RESOURCE_TYPES.contains(candidateType)
                                        ) {
                                            lookups.add(
                                                ResourceLookup(
                                                    candidateType,
                                                    candidateName,
                                                    className + "#" + name + descriptor,
                                                ),
                                            )
                                            break
                                        }
                                        index--
                                    }
                                    recentStrings.clear()
                                }
                            }

                            override fun visitJumpInsn(opcode: Int, label: Label) {
                                recentStrings.clear()
                            }

                            override fun visitLabel(label: Label) {
                                recentStrings.clear()
                            }
                        }
                    }
                },
                ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
            )
            return lookups.toSet()
        }

        private fun formatResourceProtectionEvidence(lookups: Set<ResourceLookup>): String {
            val output = StringBuilder("schema=ResourceProtectionEvidence.v1\n")
            for (lookup in lookups) {
                output.append("resource=").append(lookup.type).append('/')
                    .append(lookup.name).append("|exact-getIdentifier|")
                    .append(lookup.origin).append('\n')
            }
            return output.toString()
        }

        private fun hasNativeMethods(classBytes: ByteArray): Boolean {
            var found = false
            ClassReader(classBytes).accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<String>?,
                    ): MethodVisitor? {
                        found = found or ((access and Opcodes.ACC_NATIVE) != 0)
                        return null
                    }
                },
                ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
            )
            return found
        }

        private fun commaSeparated(value: String?): Set<String> {
            if (value.isNullOrBlank()) return emptySet()
            return value.split(',').toSet()
        }

        private fun required(
            values: Map<String, String>,
            key: String,
            project: String,
            variant: String,
        ): String {
            val value = values[key]
            if (value == null) {
                throw failure(
                    project,
                    variant,
                    key,
                    "Adoption Plan value is missing",
                    "Regenerate a complete AdoptionPlan.v1",
                )
            }
            return value
        }

        private fun requireSchema(plan: Map<String, String>, project: String, variant: String) {
            if (plan["schema"] != "AdoptionPlan.v1") {
                throw failure(
                    project,
                    variant,
                    "schema",
                    "Unknown Adoption Plan major",
                    "Regenerate the plan with this Kaleido version",
                )
            }
        }

        private fun resolveManifestIdentity(lexical: String, applicationId: String): String {
            if (lexical.startsWith(".")) return applicationId + lexical
            return if (lexical.contains(".")) lexical else "$applicationId.$lexical"
        }

        @JvmStatic
        fun renderManifestIdentity(
            originalLexical: String,
            applicationId: String,
            target: String,
        ): String {
            if (originalLexical.startsWith(".") && target.startsWith("$applicationId.")) {
                return target.substring(applicationId.length)
            }
            return target
        }

        private fun packageName(identity: String): String {
            val outer = if (identity.contains('$')) {
                identity.substring(0, identity.indexOf('$'))
            } else {
                identity
            }
            return outer.substring(0, outer.lastIndexOf('.'))
        }

        private fun writeText(path: Path, text: String) {
            writeBytes(path, text.toByteArray(StandardCharsets.UTF_8))
        }

        private fun writeBytes(path: Path, bytes: ByteArray) {
            Files.createDirectories(path.parent)
            Files.write(path, bytes)
        }

        @JvmStatic
        fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(digest().digest(bytes))

        private fun digest(): MessageDigest {
            try {
                return MessageDigest.getInstance("SHA-256")
            } catch (impossible: NoSuchAlgorithmException) {
                throw IllegalStateException("SHA-256 is unavailable", impossible)
            }
        }

        @JvmStatic
        fun failure(
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
            ClassRewriteArtifacts.PLAN_SCHEMA,
            target,
            reason,
            repair,
        ).failure()

        @JvmStatic
        fun protectionFailure(
            project: String,
            variant: String,
            target: String,
            reason: String,
            repair: String,
        ): GradleException = KaleidoDiagnostic(
            "KLD-PROTECTION-001",
            project,
            variant,
            "protection",
            "kaleido.protection",
            target,
            reason,
            repair,
        ).failure()
    }
}
