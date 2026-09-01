package com.tongsr.kaleido.gradle

import com.android.tools.build.bundletool.commands.ValidateBundleCommand
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.cert.Certificate
import java.security.cert.CertificateEncodingException
import java.util.HexFormat
import java.util.Locale
import java.util.TreeMap
import java.util.TreeSet
import java.util.jar.JarFile
import java.util.zip.ZipFile
import jdk.security.jarsigner.JarSigner
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Signing resolves secret providers and always revalidates")
abstract class KaleidoSignAndVerifyBundleTask : DefaultTask() {
    init {
        outputs.upToDateWhen { false }
    }

    @get:Internal
    abstract val consumerProjectPath: Property<String>

    @get:Internal
    abstract val variantName: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputBundle: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val expectedUnsignedDigest: RegularFileProperty

    @get:OutputFile
    abstract val outputBundle: RegularFileProperty

    @get:OutputFile
    abstract val signingReceipt: RegularFileProperty

    @get:Internal
    abstract val exactKeyStore: Property<String>

    @get:Internal
    abstract val exactStorePassword: Property<String>

    @get:Internal
    abstract val exactKeyAlias: Property<String>

    @get:Internal
    abstract val exactKeyPassword: Property<String>

    @get:Internal
    abstract val exactCertificateSha256: Property<String>

    @get:Internal
    abstract val topKeyStore: Property<String>

    @get:Internal
    abstract val topStorePassword: Property<String>

    @get:Internal
    abstract val topKeyAlias: Property<String>

    @get:Internal
    abstract val topKeyPassword: Property<String>

    @get:Internal
    abstract val topCertificateSha256: Property<String>

    @get:Internal
    abstract val environmentKeyStore: Property<String>

    @get:Internal
    abstract val environmentStorePassword: Property<String>

    @get:Internal
    abstract val environmentKeyAlias: Property<String>

    @get:Internal
    abstract val environmentKeyPassword: Property<String>

    @get:Internal
    abstract val environmentCertificateSha256: Property<String>

    @get:Internal
    abstract val propertyKeyStore: Property<String>

    @get:Internal
    abstract val propertyStorePassword: Property<String>

    @get:Internal
    abstract val propertyKeyAlias: Property<String>

    @get:Internal
    abstract val propertyKeyPassword: Property<String>

    @get:Internal
    abstract val propertyCertificateSha256: Property<String>

    @TaskAction
    @Throws(IOException::class)
    fun signAndVerify() {
        val project = consumerProjectPath.get()
        val variant = variantName.get()
        val input = inputBundle.get().asFile.toPath()
        val output = outputBundle.get().asFile.toPath()
        val unsignedSha256 = verifyUnsignedCandidate(
            input,
            expectedUnsignedDigest.get().asFile.toPath(),
            project,
            variant,
        )
        val source = selectSource(project, variant)
            ?: throw failure(
                project,
                variant,
                "source",
                "No complete upload-signing source is configured",
                "Configure one complete exact-variant, top-level, environment," +
                    " or Gradle-property source",
            )
        val expectedCertificate = normalizeCertificateDigest(
            source.expectedCertificateSha256!!,
            project,
            variant,
        )
        val entry = loadPrivateKeyEntry(source, project, variant)
        val actualCertificate = certificateSha256(entry.certificate)
        if (actualCertificate != expectedCertificate) {
            throw failure(
                project,
                variant,
                "certificate",
                "Selected signing certificate differs from the expected digest",
                "Use the expected upload key or correct expectedCertificateSha256",
            )
        }
        val beforeTransparency = codeTransparencyDigests(input)
        Files.createDirectories(output.parent)
        val temporary = output.resolveSibling(output.fileName.toString() + ".signing.tmp")
        Files.deleteIfExists(temporary)
        try {
            ZipFile(input.toFile()).use { unsigned ->
                Files.newOutputStream(temporary).use { signed ->
                    JarSigner.Builder(entry).digestAlgorithm("SHA-256")
                        .signerName("KALEIDO").build().sign(unsigned, signed)
                }
            }
        } catch (invalid: Exception) {
            Files.deleteIfExists(temporary)
            throw failure(
                project,
                variant,
                "credentials",
                "Signing failed for the selected complete source",
                "Verify the keystore, alias, and passwords",
            )
        }
        verifySignedBundle(temporary, expectedCertificate, beforeTransparency, project, variant)
        Files.move(
            temporary,
            output,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
        writeReceipt(
            source.category,
            unsignedSha256,
            sha256(output),
            actualCertificate,
            true,
            true,
            true,
            beforeTransparency,
            output,
        )
    }

    private fun selectSource(project: String, variant: String): SigningSource? {
        val sources = listOf(
            source(
                "EXACT_VARIANT_DSL",
                exactKeyStore,
                exactStorePassword,
                exactKeyAlias,
                exactKeyPassword,
                exactCertificateSha256,
            ),
            source(
                "TOP_LEVEL_DSL",
                topKeyStore,
                topStorePassword,
                topKeyAlias,
                topKeyPassword,
                topCertificateSha256,
            ),
            source(
                "ENVIRONMENT",
                environmentKeyStore,
                environmentStorePassword,
                environmentKeyAlias,
                environmentKeyPassword,
                environmentCertificateSha256,
            ),
            source(
                "GRADLE_PROPERTY",
                propertyKeyStore,
                propertyStorePassword,
                propertyKeyAlias,
                propertyKeyPassword,
                propertyCertificateSha256,
            ),
        )
        for (candidate in sources) {
            val present = candidate.values().count { it != null }
            if (present == 0) continue
            if (present != candidate.values().size) {
                throw failure(
                    project,
                    variant,
                    candidate.category,
                    "Higher-precedence signing source is partial",
                    "Provide every keystore, password, alias, and certificate field" +
                        " in this one source",
                )
            }
            return candidate
        }
        return null
    }

    @Throws(IOException::class)
    private fun writeReceipt(
        source: String,
        unsignedSha: String,
        signedSha: String,
        certificateSha: String,
        coverage: Boolean,
        bundletool: Boolean,
        certificateMatched: Boolean,
        transparency: Map<String, String>,
        output: Path,
    ) {
        val text = StringBuilder("schema=SigningReceipt.v1\n")
            .append("project=").append(consumerProjectPath.get()).append('\n')
            .append("variant=").append(variantName.get()).append('\n')
            .append("source=").append(source).append('\n')
            .append("unsignedAabSha256=").append(unsignedSha).append('\n')
            .append("signedAabSha256=").append(signedSha).append('\n')
            .append("certificateSha256=").append(certificateSha).append('\n')
            .append("signatureCoverageValidated=").append(coverage).append('\n')
            .append("certificateMatched=").append(certificateMatched).append('\n')
            .append("bundletoolValidated=").append(bundletool).append('\n')
            .append("codeTransparencyEntries=").append(transparency.size).append('\n')
        val receipt = signingReceipt.get().asFile.toPath()
        Files.createDirectories(receipt.parent)
        Files.writeString(receipt, text, StandardCharsets.UTF_8)
    }

    companion object {
        @JvmStatic
        @Throws(IOException::class)
        fun verifyUnsignedCandidate(
            candidate: Path,
            expectedDigest: Path,
            project: String,
            variant: String,
        ): String {
            val actual = sha256(candidate)
            val expected = Files.readString(expectedDigest, StandardCharsets.UTF_8).trim()
            if (actual != expected) {
                throw failure(
                    project,
                    variant,
                    "unsigned-candidate",
                    "Unsigned candidate digest differs from canonicalization evidence",
                    "Discard the mutated candidate and rerun canonical Bundle rewriting",
                )
            }
            return actual
        }

        @JvmStatic
        @Throws(IOException::class)
        fun verifySignedBundle(
            bundle: Path,
            expectedCertificate: String,
            beforeTransparency: Map<String, String>,
            project: String,
            variant: String,
        ) {
            val certificateDigests = TreeSet<String>()
            try {
                JarFile(bundle.toFile(), true).use { zip ->
                    for (entry in zip.stream().filter { item -> !item.isDirectory }.toList()) {
                        zip.getInputStream(entry).use { input ->
                            input.transferTo(OutputStream.nullOutputStream())
                        }
                        if (entry.name.startsWith("META-INF/")) continue
                        val certificates = entry.certificates
                        if (certificates == null || certificates.isEmpty()) {
                            throw failure(
                                project,
                                variant,
                                entry.name,
                                "Signed AAB contains an entry without signature coverage",
                                "Sign every non-signature Bundle entry atomically",
                            )
                        }
                        for (certificate in certificates) {
                            certificateDigests.add(certificateSha256(certificate))
                        }
                    }
                }
            } catch (invalid: SecurityException) {
                throw failure(
                    project,
                    variant,
                    "signature",
                    "Signed AAB signature verification failed",
                    "Discard the corrupted candidate and sign the exact unsigned digest again",
                )
            }
            if (certificateDigests != setOf(expectedCertificate)) {
                throw failure(
                    project,
                    variant,
                    "certificate",
                    "Signature coverage uses an unexpected certificate identity",
                    "Sign every entry with only the expected upload certificate",
                )
            }
            if (beforeTransparency != codeTransparencyDigests(bundle)) {
                throw failure(
                    project,
                    variant,
                    "code-transparency",
                    "Code-transparency material changed during signing",
                    "Preserve every code-transparency path and byte unchanged",
                )
            }
            try {
                ValidateBundleCommand.builder().setBundlePath(bundle).setPrintOutput(false)
                    .build().execute()
            } catch (invalid: RuntimeException) {
                throw failure(
                    project,
                    variant,
                    "bundletool",
                    "bundletool rejected the signed candidate",
                    "Discard the candidate and inspect signing-stage structural integrity",
                )
            }
        }

        private fun source(
            category: String,
            keyStore: Property<String>,
            storePassword: Property<String>,
            alias: Property<String>,
            keyPassword: Property<String>,
            certificate: Property<String>,
        ): SigningSource = SigningSource(
            category,
            value(keyStore),
            value(storePassword),
            value(alias),
            value(keyPassword),
            value(certificate),
        )

        private fun value(property: Property<String>): String? {
            val value = property.orNull
            return if (value.isNullOrBlank()) null else value
        }

        private fun loadPrivateKeyEntry(
            source: SigningSource,
            project: String,
            variant: String,
        ): KeyStore.PrivateKeyEntry {
            try {
                val path = Path.of(source.keyStore)
                if (!Files.isRegularFile(path)) throw IOException("missing")
                var last: Exception? = null
                for (type in listOf("PKCS12", "JKS")) {
                    try {
                        Files.newInputStream(path).use { stream ->
                            val keyStore = KeyStore.getInstance(type)
                            keyStore.load(stream, source.storePassword!!.toCharArray())
                            val entry = keyStore.getEntry(
                                source.alias,
                                KeyStore.PasswordProtection(source.keyPassword!!.toCharArray()),
                            )
                            if (entry is KeyStore.PrivateKeyEntry) return entry
                        }
                    } catch (invalid: Exception) {
                        last = invalid
                    }
                }
                throw last ?: IOException("unavailable")
            } catch (invalid: Exception) {
                throw failure(
                    project,
                    variant,
                    "credentials",
                    "Selected signing source cannot resolve one private-key entry",
                    "Verify the keystore format, alias, and passwords",
                )
            }
        }

        @Throws(IOException::class)
        private fun codeTransparencyDigests(bundle: Path): Map<String, String> {
            val values = TreeMap<String, String>()
            ZipFile(bundle.toFile()).use { zip ->
                for (entry in zip.stream().filter { item ->
                    val path = item.name.lowercase(Locale.ROOT)
                    !item.isDirectory && (
                        path.contains("code-transparency") ||
                            path.contains("code_transparency")
                        )
                }.toList()) {
                    values[entry.name] = sha256(zip.getInputStream(entry).readAllBytes())
                }
            }
            return values.toMap()
        }

        private fun normalizeCertificateDigest(
            value: String,
            project: String,
            variant: String,
        ): String {
            val normalized = value.replace(":", "").lowercase(Locale.ROOT)
            if (!normalized.matches(Regex("[0-9a-f]{64}"))) {
                throw failure(
                    project,
                    variant,
                    "certificate",
                    "Expected certificate digest is not canonical SHA-256",
                    "Provide 64 hexadecimal SHA-256 characters",
                )
            }
            return normalized
        }

        private fun certificateSha256(certificate: Certificate): String = try {
            sha256(certificate.encoded)
        } catch (impossible: CertificateEncodingException) {
            throw IllegalStateException("Certificate encoding is unavailable", impossible)
        }

        @Throws(IOException::class)
        private fun sha256(path: Path): String = sha256(Files.readAllBytes(path))

        private fun sha256(bytes: ByteArray): String = try {
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        } catch (impossible: NoSuchAlgorithmException) {
            throw IllegalStateException("SHA-256 is unavailable", impossible)
        }

        private fun failure(
            project: String,
            variant: String,
            target: String,
            reason: String,
            repair: String,
        ): GradleException = KaleidoDiagnostic(
            "KLD-SIGNING-001",
            project,
            variant,
            "signing",
            "SigningReceipt.v1",
            target,
            reason,
            repair,
        ).failure()
    }

    @JvmRecord
    private data class SigningSource(
        val category: String,
        val keyStore: String?,
        val storePassword: String?,
        val alias: String?,
        val keyPassword: String?,
        val expectedCertificateSha256: String?,
    ) {
        fun values(): List<String?> =
            listOf(keyStore, storePassword, alias, keyPassword, expectedCertificateSha256)
    }
}
