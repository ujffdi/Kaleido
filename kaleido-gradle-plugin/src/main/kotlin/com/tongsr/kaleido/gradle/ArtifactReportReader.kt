package com.tongsr.kaleido.gradle

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.HexFormat
import java.util.TreeMap

object ArtifactReportReader {
    const val CURRENT_MAJOR: Int = 1
    const val CURRENT_MINOR: Int = 0

    @JvmField
    val CURRENT_SCHEMA_URI: String = schemaUri(CURRENT_MAJOR, CURRENT_MINOR)

    @JvmStatic
    fun read(source: String): Report = read(source, CURRENT_MAJOR, CURRENT_MINOR)

    @JvmStatic
    fun read(source: String?, readerMajor: Int, readerMinor: Int): Report {
        if (source == null || source.contains("\r")) {
            throw schemaFailure("Artifact Report must be canonical UTF-8/LF text")
        }
        val fields = TreeMap<String, String>()
        for (line in source.split("\n")) {
            if (line.isBlank()) continue
            val separator = line.indexOf('=')
            if (separator <= 0 ||
                fields.put(line.substring(0, separator), line.substring(separator + 1)) != null
            ) {
                throw schemaFailure("Artifact Report fields are malformed or duplicated")
            }
        }
        val dialect = SchemaDialect.parse(required(fields, "schemaVersion"))
        val expectedUri = schemaUri(dialect.major, dialect.minor)
        if (expectedUri != required(fields, "schemaUri")) {
            throw schemaFailure("Artifact Report URI and schemaVersion disagree")
        }
        if (dialect.major > readerMajor || dialect.major < readerMajor - 1) {
            throw schemaFailure(
                "Artifact Report major " + dialect.major +
                    " is outside reader support " + (readerMajor - 1) + ".." + readerMajor,
            )
        }
        required(fields, "releaseEvidenceSetId")
        required(fields, "project")
        required(fields, "variant")
        return Report(
            source.toByteArray(StandardCharsets.UTF_8).copyOf(),
            dialect,
            fields.toMap(),
            readerMajor,
            readerMinor,
        )
    }

    @JvmStatic
    fun deriveCurrent(source: Report, converterVersion: String?): DerivedView {
        if (converterVersion.isNullOrBlank()) {
            throw IllegalArgumentException(
                KaleidoVersionContract.MIGRATION_DIAGNOSTIC + " converter version is required",
            )
        }
        val sourceSha = sha256(source.sourceBytes())
        val text = StringBuilder()
            .append("schemaUri=").append(CURRENT_SCHEMA_URI).append('\n')
            .append("schemaVersion=").append(CURRENT_MAJOR).append('.')
            .append(CURRENT_MINOR).append('\n')
            .append("derivedFromSchemaUri=")
            .append(source.fields["schemaUri"]).append('\n')
            .append("derivedFromReleaseEvidenceSetId=")
            .append(source.fields["releaseEvidenceSetId"]).append('\n')
            .append("derivedFromSha256=").append(sourceSha).append('\n')
            .append("converterVersion=").append(converterVersion).append('\n')
        source.fields.entries
            .filter { entry -> entry.key != "schemaUri" && entry.key != "schemaVersion" }
            .forEach { entry ->
                text.append("source.").append(entry.key).append('=')
                    .append(entry.value).append('\n')
            }
        val identity = sha256(text.toString().toByteArray(StandardCharsets.UTF_8))
        text.append("derivedViewId=").append(identity).append('\n')
        return DerivedView(text.toString(), identity, sourceSha)
    }

    @JvmStatic
    fun schemaUri(major: Int, minor: Int): String {
        if (major < 0 || minor < 0) throw schemaFailure("Schema versions cannot be negative")
        return "https://schemas.tongsr.com/kaleido/artifact-report/$major.$minor"
    }

    private fun required(fields: Map<String, String>, key: String): String {
        val value = fields[key]
        if (value.isNullOrBlank()) {
            throw schemaFailure("Artifact Report is missing $key")
        }
        return value
    }

    private fun schemaFailure(reason: String): IllegalArgumentException =
        IllegalArgumentException(KaleidoVersionContract.SCHEMA_DIAGNOSTIC + " " + reason)

    private fun sha256(bytes: ByteArray): String = try {
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    } catch (impossible: NoSuchAlgorithmException) {
        throw IllegalStateException("SHA-256 is unavailable", impossible)
    }

    @JvmRecord
    data class SchemaDialect(val major: Int, val minor: Int) {
        companion object {
            @JvmStatic
            fun parse(value: String): SchemaDialect {
                if (!value.matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))) {
                    throw schemaFailure("schemaVersion must be canonical major.minor")
                }
                val parts = value.split(".")
                return SchemaDialect(parts[0].toInt(), parts[1].toInt())
            }
        }
    }

    class Report(
        sourceBytes: ByteArray,
        @get:JvmName("dialect") val dialect: SchemaDialect,
        @get:JvmName("fields") val fields: Map<String, String>,
        @get:JvmName("readerMajor") val readerMajor: Int,
        @get:JvmName("readerMinor") val readerMinor: Int,
    ) {
        private val storedBytes = sourceBytes.copyOf()

        @JvmName("sourceBytes")
        fun sourceBytes(): ByteArray = storedBytes.copyOf()
    }

    @JvmRecord
    data class DerivedView(
        val canonicalText: String,
        val identity: String,
        val sourceSha256: String,
    )
}
