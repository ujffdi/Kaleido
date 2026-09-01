package com.tongsr.kaleido.gradle

import com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.TreeMap
import java.util.TreeSet
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
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
import org.w3c.dom.Document
import org.w3c.dom.Element

@CacheableTask
abstract class RewriteSemanticXmlTask : DefaultTask() {
    @get:Input
    val kaleidoCacheSchema: String
        get() = "SemanticXmlRewriteCache.v2"

    @get:Input
    abstract val consumerProjectPath: Property<String>

    @get:Input
    abstract val variantName: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val adoptionPlan: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val consumerResourceDirectories: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val consumerSourceDirectories: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputResources: DirectoryProperty

    @get:OutputFile
    abstract val rewriteIntent: RegularFileProperty

    @TaskAction
    fun rewrite() {
        val project = consumerProjectPath.get()
        val variant = variantName.get()
        val adoption = readProperties(Files.readAllBytes(adoptionPlan.get().asFile.toPath()))
        val applicationId = required(adoption, "applicationId", project, variant)
        val stream = required(adoption, "seed.domain.class-rewrite", project, variant)
        val protectedNames = TreeSet(commaSeparated(adoption["protection.originalClassNames"]))
        val hatches = RewriteClassesAndManifestTask.parseEscapeHatches(
            adoption["protection.classEscapeHatches"],
            EscapeHatchDeclaration.Kind.CLASS,
            project,
            variant,
        )

        val documents = TreeMap<String, XmlDocument>()
        val references = ArrayList<XmlReference>()
        val seenPaths = HashSet<String>()
        for (root in consumerResourceDirectories.files
            .map { it.toPath() }
            .filter { Files.isDirectory(it) }
            .sortedBy { it.toString() }) {
            Files.walk(root).use { paths ->
                for (path in paths.filter { Files.isRegularFile(it) }
                    .filter { file -> file.fileName.toString().endsWith(".xml") }
                    .sorted()
                    .toList()) {
                    val relative = root.relativize(path).toString()
                        .replace(File.separatorChar, '/')
                    if (!seenPaths.add(relative)) {
                        throw RewriteClassesAndManifestTask.protectionFailure(
                            project,
                            variant,
                            relative,
                            "Semantic XML path is ambiguous across source roots",
                            "Resolve the source overlay before Kaleido rewriting",
                        )
                    }
                    val document = parse(Files.readAllBytes(path), project, variant, relative)
                    val found = inventory(document, relative, applicationId)
                    if (found.isNotEmpty()) {
                        documents[relative] = XmlDocument(document, found)
                        references.addAll(found)
                    }
                }
            }
        }
        val roots = TreeSet<String>()
        references.map { it.resolvedIdentity }
            .filter { identity -> identity.startsWith("$applicationId.") }
            .forEach { roots.add(it) }
        protectedNames.addAll(
            SourceProtectionScanner.inferProtectedIdentities(
                consumerSourceDirectories.files,
                roots,
            ),
        )
        hatches.filter { hatch -> hatch.protects(KaleidoProtectionDimension.ORIGINAL_IDENTITY) }
            .forEach { hatch ->
                roots.filter { hatch.matches(it) }.forEach { protectedNames.add(it) }
            }
        val mapping = RewriteClassesAndManifestTask.allocateMapping(
            roots,
            protectedNames,
            roots,
            stream,
        )
        val sites = references
            .filter { reference -> mapping.containsKey(reference.resolvedIdentity) }
            .map { reference ->
                XmlSite(
                    reference.location,
                    reference.lexicalValue,
                    render(
                        reference.lexicalValue,
                        applicationId,
                        mapping.getValue(reference.resolvedIdentity),
                    ),
                )
            }
            .sortedBy { it.location }
        val byLocation = sites.associateBy { it.location }
        val outputRoot = outputResources.get().asFile.toPath()
        deleteTree(outputRoot)
        for (entry in documents.entries) {
            var changed = false
            for (reference in entry.value.references) {
                val site = byLocation[reference.location]
                if (site != null) {
                    reference.writer.set(site.target)
                    changed = true
                }
            }
            if (changed) {
                write(outputRoot.resolve(entry.key), serialize(entry.value.document))
            }
        }
        val intent = StringBuilder("schema=XmlRewriteIntent.v1\n")
        mapping.entries.sortedBy { it.key }.forEach { entry ->
            intent.append("mapping=").append(entry.key).append('|')
                .append(entry.value).append('\n')
        }
        sites.forEach { site ->
            intent.append("site=").append(site.location).append('|')
                .append(site.original).append('|').append(site.target).append('\n')
        }
        write(rewriteIntent.get().asFile.toPath(), intent.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private fun interface StringWriter {
        fun set(value: String)
    }

    @JvmRecord
    private data class XmlReference(
        val location: String,
        val lexicalValue: String,
        val resolvedIdentity: String,
        val writer: StringWriter,
    )

    @JvmRecord
    private data class XmlSite(val location: String, val original: String, val target: String)

    @JvmRecord
    private data class XmlDocument(val document: Document, val references: List<XmlReference>)

    companion object {
        private const val ANDROID = "http://schemas.android.com/apk/res/android"

        private fun inventory(
            document: Document,
            path: String,
            applicationId: String,
        ): List<XmlReference> {
            val references = ArrayList<XmlReference>()
            val elements = document.getElementsByTagName("*")
            for (index in 0 until elements.length) {
                val element = elements.item(index) as Element
                val name = element.tagName
                if (name.contains(".") && !name.startsWith("android.")) {
                    references.add(
                        reference(
                            "$path/element[$index]",
                            name,
                            { value -> document.renameNode(element, element.namespaceURI, value) },
                            applicationId,
                        ),
                    )
                }
                if (name == "view" || name == "fragment") {
                    addAttribute(references, path, index, element, null, "class", applicationId)
                }
                if (name == "fragment" || name == "activity" || name == "dialog") {
                    addAttribute(references, path, index, element, ANDROID, "name", applicationId)
                }
                if (name == "variable" || name == "import") {
                    addAttribute(references, path, index, element, null, "type", applicationId)
                }
                val attributes = element.attributes
                for (attributeIndex in 0 until attributes.length) {
                    val attribute = attributes.item(attributeIndex)
                    val local = attribute.localName ?: attribute.nodeName
                    if (local == "context" || local == "layout_behavior" ||
                        local == "layoutManager" || local == "argType"
                    ) {
                        val value = attribute.nodeValue
                        if (looksLikeClass(value)) {
                            references.add(
                                reference(
                                    "$path/element[$index]@$local",
                                    value,
                                    { replacement -> attribute.nodeValue = replacement },
                                    applicationId,
                                ),
                            )
                        }
                    }
                }
            }
            return references
        }

        private fun addAttribute(
            references: MutableList<XmlReference>,
            path: String,
            index: Int,
            element: Element,
            namespace: String?,
            attribute: String,
            applicationId: String,
        ) {
            val present = if (namespace == null) {
                element.hasAttribute(attribute)
            } else {
                element.hasAttributeNS(namespace, attribute)
            }
            if (!present) return
            val value = if (namespace == null) {
                element.getAttribute(attribute)
            } else {
                element.getAttributeNS(namespace, attribute)
            }
            if (!looksLikeClass(value)) return
            references.add(
                reference(
                    "$path/element[$index]@$attribute",
                    value,
                    { replacement ->
                        if (namespace == null) {
                            element.setAttribute(attribute, replacement)
                        } else {
                            element.setAttributeNS(namespace, "android:$attribute", replacement)
                        }
                    },
                    applicationId,
                ),
            )
        }

        private fun reference(
            location: String,
            lexical: String,
            writer: StringWriter,
            applicationId: String,
        ): XmlReference {
            val resolved = when {
                lexical.startsWith(".") -> applicationId + lexical
                lexical.contains(".") -> lexical
                else -> "$applicationId.$lexical"
            }
            return XmlReference(location, lexical, resolved, writer)
        }

        private fun looksLikeClass(value: String): Boolean =
            value.startsWith(".") ||
                value.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_\$][A-Za-z0-9_\$]*)+"))

        private fun render(lexical: String, applicationId: String, target: String): String =
            if (lexical.startsWith(".") && target.startsWith("$applicationId.")) {
                target.substring(applicationId.length)
            } else {
                target
            }

        private fun parse(bytes: ByteArray, project: String, variant: String, path: String): Document {
            try {
                val factory = DocumentBuilderFactory.newInstance()
                factory.isNamespaceAware = true
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
            } catch (failure: Exception) {
                throw RewriteClassesAndManifestTask.protectionFailure(
                    project,
                    variant,
                    path,
                    "Consumer XML is not securely parseable",
                    "Fix or remove the malformed XML resource",
                )
            }
        }

        private fun serialize(document: Document): ByteArray {
            val factory = TransformerFactory.newInstance()
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
            val transformer = factory.newTransformer()
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            transformer.setOutputProperty(OutputKeys.INDENT, "no")
            val bytes = ByteArrayOutputStream()
            transformer.transform(DOMSource(document), StreamResult(bytes))
            return bytes.toByteArray()
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

        private fun required(
            values: Map<String, String>,
            key: String,
            project: String,
            variant: String,
        ): String {
            val value = values[key]
            if (value == null) {
                throw RewriteClassesAndManifestTask.protectionFailure(
                    project,
                    variant,
                    key,
                    "Adoption Plan is incomplete",
                    "Regenerate a complete AdoptionPlan.v1",
                )
            }
            return value
        }

        private fun commaSeparated(value: String?): Set<String> =
            if (value.isNullOrBlank()) emptySet() else value.split(',').toSet()

        private fun deleteTree(root: Path) {
            if (!Files.exists(root)) return
            Files.walk(root).use { paths ->
                for (path in paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path)
            }
        }

        private fun write(path: Path, bytes: ByteArray) {
            Files.createDirectories(path.parent)
            Files.write(path, bytes)
        }
    }
}
