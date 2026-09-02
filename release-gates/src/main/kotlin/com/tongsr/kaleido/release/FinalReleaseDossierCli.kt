package com.tongsr.kaleido.release

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Properties

/** Closes the approved candidate with its public-Portal resolution and device smoke. */
object FinalReleaseDossierCli {
    private const val DIAGNOSTIC = "KLD-PUBLICATION-001 "

    @JvmStatic
    fun main(arguments: Array<String>) {
        val options = parse(arguments)
        val output = Path.of(required(options, "output"))
        val prePublicationPath = existing(options, "pre-publication-dossier")
        val postPublicationPath = existing(options, "post-publication-record")
        val manifestPath = existing(options, "manifest")
        val version = required(options, "version")
        val signedTag = required(options, "signed-tag")

        val prePublication = load(prePublicationPath)
        val postPublication = load(postPublicationPath)
        val manifest = load(manifestPath)
        exact(prePublication, "schema", "KaleidoPrePublicationDossier.v1")
        exact(prePublication, "verdict", "PASS")
        exact(postPublication, "schema", "KaleidoPostPublication.v1")
        exact(postPublication, "verdict", "PASS")
        exact(manifest, "schema", "KaleidoImmutableReleaseManifest.v1")
        exact(manifest, "verdict", "PASS")
        exact(manifest, "version", version)
        exact(manifest, "source.tag", signedTag)

        val candidate = requireProperty(manifest, "asset.0.sha256")
        exact(prePublication, "candidate.sha256", candidate)
        exact(postPublication, "candidate.sha256", candidate)
        exact(postPublication, "coordinates", "io.github.ujffdi.kaleido:$version")
        for (property in arrayOf(
            "publicPluginDigest",
            "publicMarkerDigest",
            "cleanMarkerResolution",
            "consumerReleaseEvidence",
            "bundletoolAndDeviceSmoke",
        )) {
            exact(postPublication, property, "PASS")
        }

        val text = "schema=KaleidoReleaseDossier.v1\n" +
            "version=$version\n" +
            "coordinates=io.github.ujffdi.kaleido:$version\n" +
            "source.tag=$signedTag\n" +
            "source.revision=${requireProperty(manifest, "source.revision")}\n" +
            "candidate.sha256=$candidate\n" +
            "releaseManifest.sha256=${digest(manifestPath)}\n" +
            "prePublicationDossier.sha256=${digest(prePublicationPath)}\n" +
            "postPublicationRecord.sha256=${digest(postPublicationPath)}\n" +
            "publicPluginDigest=PASS\n" +
            "publicMarkerDigest=PASS\n" +
            "cleanMarkerResolution=PASS\n" +
            "consumerReleaseEvidence=PASS\n" +
            "bundletoolAndDeviceSmoke=PASS\n" +
            "publication.mutableReplacement=false\n" +
            "publication.waiver=false\n" +
            "verdict=PASS\n"
        write(output, text)
    }

    private fun parse(arguments: Array<String>): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        var index = 0
        while (index < arguments.size) {
            if (!arguments[index].startsWith("--") || index + 1 >= arguments.size ||
                values.put(arguments[index].substring(2), arguments[index + 1]) != null
            ) {
                throw failure("arguments must be unique --name value pairs")
            }
            index += 2
        }
        return values
    }

    private fun existing(options: Map<String, String>, name: String): Path {
        val path = Path.of(required(options, name))
        if (!Files.isRegularFile(path)) throw failure("$name is missing")
        return path
    }

    private fun required(options: Map<String, String>, name: String): String {
        val value = options[name]
        if (value.isNullOrBlank()) throw failure("--$name is required")
        return value
    }

    private fun load(path: Path): Properties {
        val values = Properties()
        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
            values.load(reader)
        }
        return values
    }

    private fun exact(values: Properties, name: String, expected: String) {
        if (expected != requireProperty(values, name)) throw failure("$name mismatch")
    }

    private fun requireProperty(values: Properties, name: String): String {
        val value = values.getProperty(name)
        if (value.isNullOrBlank()) throw failure("missing property: $name")
        return value.trim()
    }

    private fun digest(path: Path): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))

    private fun write(output: Path, text: String) {
        Files.createDirectories(output.toAbsolutePath().parent)
        val staged = Files.createTempFile(
            output.toAbsolutePath().parent,
            output.fileName.toString(),
            ".tmp",
        )
        try {
            Files.writeString(staged, text, StandardCharsets.UTF_8)
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
}
