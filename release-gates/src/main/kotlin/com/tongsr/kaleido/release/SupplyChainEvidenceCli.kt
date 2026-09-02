package com.tongsr.kaleido.release

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/** Generates the candidate dependency inventory and CycloneDX 1.7 SBOM. */
object SupplyChainEvidenceCli {
    private const val DIAGNOSTIC = "KLD-PROVENANCE-001 "
    private val LICENSES: Map<String, String> = licenses()

    @JvmStatic
    fun main(arguments: Array<String>) {
        val options = parse(arguments)
        val output = Path.of(required(options, "output"))
        val version = required(options, "version")
        val candidate = existing(options, "candidate")
        val sources = existing(options, "sources")
        val marker = existing(options, "marker")
        val license = existing(options, "license")
        val notice = existing(options, "notice")
        val thirdParty = existing(options, "third-party")
        val provenance = existing(options, "provenance")
        val verification = existing(options, "verification")
        val components = parseComponents(options.getOrDefault("component", emptyList()))
        if (components.isEmpty()) throw failure("resolved dependency inventory is empty")
        Files.createDirectories(output)

        val candidateDigest = digest(candidate)
        val inventory = inventory(
            version,
            candidateDigest,
            candidate,
            sources,
            marker,
            license,
            notice,
            thirdParty,
            provenance,
            verification,
            components,
        )
        val sbom = sbom(version, candidateDigest, sources, marker, components)
        writeAtomically(output.resolve("source-dependency-inventory.properties"), inventory)
        writeAtomically(output.resolve("kaleido-$version.cdx.json"), sbom)
        val manifest = StringBuilder("schema=KaleidoSupplyChainManifest.v1\n")
            .append("candidate.sha256=").append(candidateDigest).append('\n')
            .append("sources.sha256=").append(digest(sources)).append('\n')
            .append("marker.sha256=").append(digest(marker)).append('\n')
            .append("inventory.sha256=").append(sha256(inventory)).append('\n')
            .append("sbom.sha256=").append(sha256(sbom)).append('\n')
            .append("license.sha256=").append(digest(license)).append('\n')
            .append("notice.sha256=").append(digest(notice)).append('\n')
            .append("thirdParty.sha256=").append(digest(thirdParty)).append('\n')
            .append("provenance.sha256=").append(digest(provenance)).append('\n')
            .append("verificationMetadata.sha256=").append(digest(verification)).append('\n')
            .append("claims.slsa=false\nclaims.upstreamPermission=false\nverdict=PASS\n")
            .toString().toByteArray(StandardCharsets.UTF_8)
        writeAtomically(output.resolve("supply-chain-manifest.properties"), manifest)
    }

    @JvmStatic
    fun sbom(
        version: String,
        candidateDigest: String,
        sources: Path,
        marker: Path,
        components: List<Component>,
    ): ByteArray {
        val rootRef = "pkg:maven/io.github.ujffdi/kaleido-gradle-plugin@$version"
        val serial = UUID.nameUUIDFromBytes(
            ("kaleido-sbom:$candidateDigest").toByteArray(StandardCharsets.UTF_8),
        )
        val json = StringBuilder()
            .append("{\n")
            .append("  \"\$schema\": \"https://cyclonedx.org/schema/bom-1.7.schema.json\",\n")
            .append("  \"bomFormat\": \"CycloneDX\",\n")
            .append("  \"specVersion\": \"1.7\",\n")
            .append("  \"serialNumber\": \"urn:uuid:").append(serial).append("\",\n")
            .append("  \"version\": 1,\n")
            .append("  \"metadata\": {\"component\": {")
            .append("\"type\": \"library\", \"bom-ref\": \"").append(rootRef)
            .append("\", \"group\": \"io.github.ujffdi\", ")
            .append("\"name\": \"kaleido-gradle-plugin\", \"version\": \"")
            .append(escape(version)).append("\", \"purl\": \"").append(rootRef)
            .append("\", \"hashes\": [{\"alg\": \"SHA-256\", \"content\": \"")
            .append(candidateDigest).append("\"}], \"licenses\": [{\"license\": ")
            .append("{\"id\": \"Apache-2.0\"}}], \"properties\": [")
            .append("{\"name\": \"kaleido:sources:sha256\", \"value\": \"")
            .append(digest(sources)).append("\"}, ")
            .append("{\"name\": \"kaleido:marker:sha256\", \"value\": \"")
            .append(digest(marker)).append("\"}]}} ,\n")
            .append("  \"components\": [\n")
        for (index in components.indices) {
            val component = components[index]
            val purl = component.purl()
            json.append("    {\"type\": \"library\", \"bom-ref\": \"").append(purl)
                .append("\", \"group\": \"").append(escape(component.group))
                .append("\", \"name\": \"").append(escape(component.name))
                .append("\", \"version\": \"").append(escape(component.version))
                .append("\", \"purl\": \"").append(purl)
                .append("\", \"hashes\": [{\"alg\": \"SHA-256\", \"content\": \"")
                .append(component.digest).append("\"}], \"licenses\": [{\"license\": ")
                .append("{\"id\": \"").append(component.license).append("\"}}]}")
                .append(if (index + 1 == components.size) "\n" else ",\n")
        }
        json.append("  ],\n  \"dependencies\": [\n    {\"ref\": \"").append(rootRef)
            .append("\", \"dependsOn\": [")
        for (index in components.indices) {
            if (index > 0) json.append(", ")
            json.append('"').append(components[index].purl()).append('"')
        }
        json.append("]}\n  ],\n  \"compositions\": [{\"aggregate\": \"complete\", ")
            .append("\"assemblies\": [\"").append(rootRef).append("\"]}]\n}\n")
        return json.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun inventory(
        version: String,
        candidateDigest: String,
        @Suppress("UNUSED_PARAMETER") candidate: Path,
        sources: Path,
        marker: Path,
        license: Path,
        notice: Path,
        thirdParty: Path,
        provenance: Path,
        verification: Path,
        components: List<Component>,
    ): ByteArray {
        val text = StringBuilder("schema=KaleidoSourceDependencyInventory.v1\n")
            .append("version=").append(version).append('\n')
            .append("candidate.path=kaleido-gradle-plugin-").append(version).append(".jar\n")
            .append("candidate.sha256=").append(candidateDigest).append('\n')
            .append("sources.sha256=").append(digest(sources)).append('\n')
            .append("marker.sha256=").append(digest(marker)).append('\n')
            .append("license.sha256=").append(digest(license)).append('\n')
            .append("notice.sha256=").append(digest(notice)).append('\n')
            .append("thirdParty.sha256=").append(digest(thirdParty)).append('\n')
            .append("provenance.sha256=").append(digest(provenance)).append('\n')
            .append("verificationMetadata.sha256=").append(digest(verification)).append('\n')
            .append("components=").append(components.size).append('\n')
        for (index in components.indices) {
            val component = components[index]
            val prefix = "component.$index."
            text.append(prefix).append("coordinate=").append(component.coordinate()).append('\n')
                .append(prefix).append("sha256=").append(component.digest).append('\n')
                .append(prefix).append("license=").append(component.license).append('\n')
        }
        return text.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun parseComponents(encoded: List<String>): List<Component> {
        val components = ArrayList<Component>()
        for (value in encoded) {
            val separator = value.indexOf('|')
            val coordinate = if (separator < 0) "" else value.substring(0, separator)
            val parts = coordinate.split(":", limit = 0)
            if (parts.size != 3) throw failure("invalid component coordinate")
            val artifact = Path.of(value.substring(separator + 1))
            if (!Files.isRegularFile(artifact)) throw failure("component artifact is missing")
            val license = LICENSES["${parts[0]}:${parts[1]}"]
                ?: throw failure("unreviewed dependency license: $coordinate")
            components.add(Component(parts[0], parts[1], parts[2], digest(artifact), license))
        }
        return components.sortedBy { it.coordinate() }
    }

    private fun licenses(): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        for (coordinate in listOf(
            "com.android.tools.build:bundletool", "com.android.tools.build:aapt2-proto",
            "com.google.auto.value:auto-value-annotations",
            "com.google.errorprone:error_prone_annotations", "com.google.guava:guava",
            "com.google.guava:failureaccess", "com.google.guava:listenablefuture",
            "com.google.j2objc:j2objc-annotations", "com.google.code.gson:gson",
            "com.google.dagger:dagger", "javax.inject:javax.inject",
            "org.bitbucket.b_c:jose4j", "org.jetbrains.kotlin:kotlin-metadata-jvm",
            "org.jetbrains.kotlin:kotlin-stdlib", "org.jetbrains:annotations",
        )) {
            values[coordinate] = "Apache-2.0"
        }
        for (coordinate in listOf(
            "org.ow2.asm:asm", "org.ow2.asm:asm-tree", "org.ow2.asm:asm-commons",
            "com.google.protobuf:protobuf-java", "com.google.protobuf:protobuf-java-util",
            "com.google.code.findbugs:jsr305",
        )) {
            values[coordinate] = "BSD-3-Clause"
        }
        values["org.checkerframework:checker-qual"] = "MIT"
        values["org.slf4j:slf4j-api"] = "MIT"
        return values.toMap()
    }

    private fun parse(arguments: Array<String>): Map<String, List<String>> {
        val values = LinkedHashMap<String, MutableList<String>>()
        var index = 0
        while (index < arguments.size) {
            if (!arguments[index].startsWith("--") || index + 1 >= arguments.size) {
                throw failure("arguments must be --name value pairs")
            }
            values.getOrPut(arguments[index].substring(2)) { ArrayList() }
                .add(arguments[index + 1])
            index += 2
        }
        return values
    }

    private fun required(options: Map<String, List<String>>, name: String): String {
        val values = options[name]
        if (values == null || values.size != 1 || values[0].isBlank()) {
            throw failure("exactly one --$name is required")
        }
        return values[0]
    }

    private fun existing(options: Map<String, List<String>>, name: String): Path {
        val path = Path.of(required(options, name))
        if (!Files.isRegularFile(path)) throw failure("$name file is missing")
        return path
    }

    private fun digest(path: Path): String = sha256(Files.readAllBytes(path))

    private fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun writeAtomically(output: Path, bytes: ByteArray) {
        val staged = Files.createTempFile(output.parent, output.fileName.toString(), ".tmp")
        try {
            Files.write(staged, bytes)
            try {
                Files.move(
                    staged,
                    output,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staged, output, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(staged)
        }
    }

    private fun failure(message: String): IllegalArgumentException =
        IllegalArgumentException(DIAGNOSTIC + message)

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    data class Component(
        val group: String,
        val name: String,
        val version: String,
        val digest: String,
        val license: String,
    ) {
        fun coordinate(): String = "$group:$name:$version"

        fun purl(): String = "pkg:maven/$group/$name@$version"
    }
}
