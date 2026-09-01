package com.tongsr.kaleido.gradle

import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeSet
import java.util.regex.Pattern
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

internal object ApplicationResourceInventory {
    @JvmStatic
    fun scan(roots: Iterable<Path>, project: String, variant: String): Result {
        val resources = TreeSet(
            compareBy(BundleRewriteModule.ResourceKey::type)
                .thenBy(BundleRewriteModule.ResourceKey::name),
        )
        val publicNames = TreeSet<String>()
        val keepSelectors = TreeSet<String>()
        for (root in roots) {
            if (!Files.isDirectory(root)) continue
            Files.walk(root).use { paths ->
                for (file in paths.filter { Files.isRegularFile(it) }.sorted().toList()) {
                    val relative = root.relativize(file)
                    if (relative.nameCount < 2) continue
                    val directory = relative.getName(0).toString()
                    val type = directory.split("-", limit = 2)[0]
                    if (file.fileName.toString().endsWith(".xml")) {
                        scanToolsKeep(file, keepSelectors, project, variant)
                    }
                    if (type == "values" && file.fileName.toString().endsWith(".xml")) {
                        scanValues(file, resources, publicNames, project, variant)
                    } else {
                        val name = resourceFileName(file.fileName.toString())
                        if (name.isNotBlank()) {
                            resources.add(BundleRewriteModule.ResourceKey(type, name))
                        }
                    }
                }
            }
        }
        val protectedNames = TreeSet(publicNames)
        for (resource in resources) {
            val identity = resource.type + "/" + resource.name
            if (keepSelectors.any { glob(it).matcher(identity).matches() }) {
                protectedNames.add(resource.name)
            }
        }
        val warnings = keepSelectors
            .filter { selector -> selector.contains("*") || selector.contains("?") }
            .map { selector ->
                "KLD-RESOURCE-001 project=$project variant=$variant" +
                    " stage=bundle-rewrite origin=tools:keep target=$selector" +
                    " reason=Broad tools:keep pattern retained matching resources" +
                    " repair=<none>"
            }
        return Result(resources.toSet(), protectedNames.toSet(), warnings)
    }

    private fun scanValues(
        file: Path,
        resources: MutableSet<BundleRewriteModule.ResourceKey>,
        publicNames: MutableSet<String>,
        project: String,
        variant: String,
    ) {
        try {
            val factory = secureFactory()
            val document = factory.newDocumentBuilder().parse(file.toFile())
            val root = document.documentElement
            for (index in 0 until root.childNodes.length) {
                val node = root.childNodes.item(index)
                if (node !is Element) continue
                val tag = node.tagName
                val name = node.getAttribute("name")
                val type = valuesType(tag, node.getAttribute("type"))
                if (name.isNotBlank() && type != null) {
                    resources.add(BundleRewriteModule.ResourceKey(type, name))
                    if (tag == "public") publicNames.add(name)
                }
                if (tag == "declare-styleable") {
                    val attrs = node.getElementsByTagName("attr")
                    for (attrIndex in 0 until attrs.length) {
                        val attr = attrs.item(attrIndex) as Element
                        val attrName = attr.getAttribute("name")
                        if (attrName.isNotBlank() && !attrName.startsWith("android:")) {
                            resources.add(BundleRewriteModule.ResourceKey("attr", attrName))
                        }
                    }
                }
            }
        } catch (invalid: Exception) {
            throw KaleidoDiagnostic(
                "KLD-BUNDLE-001",
                project,
                variant,
                "bundle-rewrite",
                BundleRewriteArtifacts.PLAN_SCHEMA,
                file.toString(),
                "Application values XML cannot be inventoried safely",
                "Fix the malformed values resource before Release",
            ).failure()
        }
    }

    private fun scanToolsKeep(
        file: Path,
        selectors: MutableSet<String>,
        project: String,
        variant: String,
    ) {
        try {
            val document = secureFactory().newDocumentBuilder().parse(file.toFile())
            val elements = document.getElementsByTagName("*")
            for (index in 0 until elements.length) {
                val element = elements.item(index) as Element
                val keep = element.getAttributeNS("http://schemas.android.com/tools", "keep")
                if (keep.isBlank()) continue
                for (value in keep.split(",")) {
                    var normalized = value.trim()
                    if (normalized.startsWith("@")) normalized = normalized.substring(1)
                    val colon = normalized.indexOf(':')
                    if (colon >= 0) normalized = normalized.substring(colon + 1)
                    if (normalized.matches(Regex("[a-z*][a-z0-9_*?]*/[a-z0-9_*?]+"))) {
                        selectors.add(normalized)
                    }
                }
            }
        } catch (invalid: Exception) {
            throw KaleidoDiagnostic(
                "KLD-BUNDLE-001",
                project,
                variant,
                "bundle-rewrite",
                BundleRewriteArtifacts.PLAN_SCHEMA,
                file.toString(),
                "Application XML protection attributes cannot be inventoried safely",
                "Fix the malformed resource XML before Release",
            ).failure()
        }
    }

    private fun secureFactory(): DocumentBuilderFactory {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        return factory
    }

    private fun glob(selector: String): Pattern {
        val regex = StringBuilder("^")
        for (character in selector) {
            when (character) {
                '*' -> regex.append(".*")
                '?' -> regex.append('.')
                else -> regex.append(Pattern.quote(character.toString()))
            }
        }
        return Pattern.compile(regex.append('$').toString())
    }

    private fun valuesType(tag: String, declaredType: String): String? {
        if (tag == "item" || tag == "public") {
            return declaredType.ifBlank { null }
        }
        if (tag == "string-array" || tag == "integer-array") return "array"
        if (tag == "declare-styleable") return "styleable"
        if (tag in setOf("eat-comment", "skip", "overlayable")) return null
        return tag
    }

    private fun resourceFileName(fileName: String): String {
        val ninePatch = fileName.indexOf(".9.")
        if (ninePatch > 0) return fileName.substring(0, ninePatch)
        val dot = fileName.indexOf('.')
        return if (dot > 0) fileName.substring(0, dot) else fileName
    }

    class Result(
        @get:JvmName("resources") val resources: Set<BundleRewriteModule.ResourceKey>,
        @get:JvmName("protectedNames") val protectedNames: Set<String>,
        warnings: List<String>,
    ) {
        @get:JvmName("warnings")
        val warnings: List<String> = warnings.sorted()
    }
}
