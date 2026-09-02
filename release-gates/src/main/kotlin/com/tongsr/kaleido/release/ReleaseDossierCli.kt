package com.tongsr.kaleido.release

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.LinkedHashSet
import java.util.Locale
import java.util.Properties
import java.util.TreeSet

/** Closes mandatory automated records and two independent approvals into one dossier. */
object ReleaseDossierCli {
    private const val DIAGNOSTIC = "KLD-PUBLICATION-001 "
    private val REQUIRED: Set<String> = setOf(
        "cache", "matrix.A3", "performance", "provenance", "documentation", "portal-dry-run",
    )

    @JvmStatic
    fun main(arguments: Array<String>) {
        val options = parse(arguments)
        val output = Path.of(single(options, "output"))
        val manifest = Path.of(single(options, "manifest"))
        val manifestSignature = Path.of(single(options, "manifest-signature"))
        if (!Files.isRegularFile(manifest)) throw failure("release manifest is missing")
        if (!Files.isRegularFile(manifestSignature)) {
            throw failure("release manifest signature is missing")
        }
        if (single(options, "manifest-signature-verified") != "true") {
            throw failure("release manifest signature is not verified")
        }
        val manifestValues = load(manifest)
        exact(manifestValues, "schema", "KaleidoImmutableReleaseManifest.v1")
        exact(manifestValues, "verdict", "PASS")
        val candidate = requireProperty(manifestValues, "asset.0.sha256")
        val records = namedFiles(options["record"])
        if (records.keys != REQUIRED) {
            val missing = TreeSet(REQUIRED)
            missing.removeAll(records.keys)
            throw failure("mandatory records mismatch; missing=$missing")
        }

        val text = StringBuilder("schema=KaleidoPrePublicationDossier.v1\n")
            .append("candidate.sha256=").append(candidate).append('\n')
            .append("releaseManifest.sha256=").append(digest(manifest)).append('\n')
            .append("releaseManifest.signature.sha256=")
            .append(digest(manifestSignature)).append('\n')
            .append("releaseManifest.signatureVerified=true\n")
            .append("failurePolicy.product=new-versioned-candidate\n")
            .append("failurePolicy.infrastructure=same-bytes-classified-rerun\n")
            .append("publication.mutableReplacement=false\n")
            .append("publication.waiver=false\n")
        val ordered = records.entries.sortedBy { it.key }
        for (index in ordered.indices) {
            val entry = ordered[index]
            val values = load(entry.value)
            exact(values, "verdict", "PASS")
            exact(values, "candidate.sha256", candidate)
            text.append("record.").append(index).append(".name=").append(entry.key).append('\n')
                .append("record.").append(index).append(".sha256=")
                .append(digest(entry.value)).append('\n')
        }

        val approvals = files(options["approval"], "approval")
        val approvalSignatures = files(options["approval-signature"], "approval signature")
        if (approvals.size != 2) throw failure("exactly two approvals are required")
        if (approvalSignatures.size != approvals.size) {
            throw failure("each approval requires one verified detached signature")
        }
        val reviewers = LinkedHashSet<String>()
        val signerFingerprints = LinkedHashSet<String>()
        val roles = LinkedHashSet<String>()
        for (index in approvals.indices) {
            val approval = load(approvals[index])
            exact(approval, "candidate.sha256", candidate)
            exact(approval, "decision", "APPROVE")
            exact(approval, "signatureVerified", "true")
            val reviewer = requireProperty(approval, "reviewer.id")
            val role = requireProperty(approval, "role")
            val fingerprint = requireProperty(approval, "signer.fingerprint").uppercase(Locale.ROOT)
            if (!fingerprint.matches(Regex("[0-9A-F]{40}|[0-9A-F]{64}"))) {
                throw failure("approval signer fingerprint is invalid")
            }
            if (!reviewers.add(reviewer)) throw failure("approval reviewers must be independent")
            if (!signerFingerprints.add(fingerprint)) {
                throw failure("approval signing keys must be independent")
            }
            roles.add(role)
            text.append("approval.").append(index).append(".reviewer=").append(reviewer).append('\n')
                .append("approval.").append(index).append(".role=").append(role).append('\n')
                .append("approval.").append(index).append(".signerFingerprint=")
                .append(fingerprint).append('\n')
                .append("approval.").append(index).append(".sha256=")
                .append(digest(approvals[index])).append('\n')
                .append("approval.").append(index).append(".signature.sha256=")
                .append(digest(approvalSignatures[index])).append('\n')
        }
        if (!roles.containsAll(setOf("release-owner", "provenance-security-reviewer"))) {
            throw failure("release-owner and provenance-security-reviewer approvals are required")
        }
        text.append("verdict=PASS\n")
        write(output, text.toString())
    }

    private fun namedFiles(encoded: List<String>?): Map<String, Path> {
        if (encoded == null) throw failure("mandatory records are missing")
        val values = LinkedHashMap<String, Path>()
        for (value in encoded) {
            val separator = value.indexOf('|')
            if (separator <= 0) throw failure("record must be name|path")
            val path = Path.of(value.substring(separator + 1))
            if (!Files.isRegularFile(path) || values.put(value.substring(0, separator), path) != null) {
                throw failure("record is missing or duplicated")
            }
        }
        return values
    }

    private fun files(encoded: List<String>?, name: String): List<Path> {
        if (encoded == null) return emptyList()
        return encoded.map { Path.of(it) }.onEach { path ->
            if (!Files.isRegularFile(path)) throw failure("$name file is missing")
        }
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

    private fun single(options: Map<String, List<String>>, name: String): String {
        val values = options[name]
        if (values == null || values.size != 1 || values[0].isBlank()) {
            throw failure("exactly one --$name is required")
        }
        return values[0]
    }

    private fun failure(message: String): IllegalArgumentException =
        IllegalArgumentException(DIAGNOSTIC + message)
}
