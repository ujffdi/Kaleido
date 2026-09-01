package com.tongsr.kaleido.gradle;

import com.android.tools.build.bundletool.commands.ValidateBundleCommand;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;
import jdk.security.jarsigner.JarSigner;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "Signing resolves secret providers and always revalidates")
public abstract class KaleidoSignAndVerifyBundleTask extends DefaultTask {
    public KaleidoSignAndVerifyBundleTask() {
        getOutputs().upToDateWhen(ignored -> false);
    }

    @Internal public abstract Property<String> getConsumerProjectPath();
    @Internal public abstract Property<String> getVariantName();

    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInputBundle();
    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getExpectedUnsignedDigest();
    @OutputFile public abstract RegularFileProperty getOutputBundle();
    @OutputFile public abstract RegularFileProperty getSigningReceipt();

    @Internal public abstract Property<String> getExactKeyStore();
    @Internal public abstract Property<String> getExactStorePassword();
    @Internal public abstract Property<String> getExactKeyAlias();
    @Internal public abstract Property<String> getExactKeyPassword();
    @Internal public abstract Property<String> getExactCertificateSha256();
    @Internal public abstract Property<String> getTopKeyStore();
    @Internal public abstract Property<String> getTopStorePassword();
    @Internal public abstract Property<String> getTopKeyAlias();
    @Internal public abstract Property<String> getTopKeyPassword();
    @Internal public abstract Property<String> getTopCertificateSha256();
    @Internal public abstract Property<String> getEnvironmentKeyStore();
    @Internal public abstract Property<String> getEnvironmentStorePassword();
    @Internal public abstract Property<String> getEnvironmentKeyAlias();
    @Internal public abstract Property<String> getEnvironmentKeyPassword();
    @Internal public abstract Property<String> getEnvironmentCertificateSha256();
    @Internal public abstract Property<String> getPropertyKeyStore();
    @Internal public abstract Property<String> getPropertyStorePassword();
    @Internal public abstract Property<String> getPropertyKeyAlias();
    @Internal public abstract Property<String> getPropertyKeyPassword();
    @Internal public abstract Property<String> getPropertyCertificateSha256();

    @TaskAction
    public void signAndVerify() throws IOException {
        var project = getConsumerProjectPath().get();
        var variant = getVariantName().get();
        var input = getInputBundle().get().getAsFile().toPath();
        var output = getOutputBundle().get().getAsFile().toPath();
        var unsignedSha256 = verifyUnsignedCandidate(input,
                getExpectedUnsignedDigest().get().getAsFile().toPath(), project, variant);
        var source = selectSource(project, variant);
        if (source == null) {
            throw failure(project, variant, "source",
                    "No complete upload-signing source is configured",
                    "Configure one complete exact-variant, top-level, environment,"
                            + " or Gradle-property source");
        }
        var expectedCertificate = normalizeCertificateDigest(
                source.expectedCertificateSha256(), project, variant);
        var entry = loadPrivateKeyEntry(source, project, variant);
        var actualCertificate = certificateSha256(entry.getCertificate());
        if (!actualCertificate.equals(expectedCertificate)) {
            throw failure(project, variant, "certificate",
                    "Selected signing certificate differs from the expected digest",
                    "Use the expected upload key or correct expectedCertificateSha256");
        }
        var beforeTransparency = codeTransparencyDigests(input);
        Files.createDirectories(output.getParent());
        var temporary = output.resolveSibling(output.getFileName() + ".signing.tmp");
        Files.deleteIfExists(temporary);
        try (var unsigned = new ZipFile(input.toFile());
             OutputStream signed = Files.newOutputStream(temporary)) {
            new JarSigner.Builder(entry).digestAlgorithm("SHA-256")
                    .signerName("KALEIDO").build().sign(unsigned, signed);
        } catch (Exception invalid) {
            Files.deleteIfExists(temporary);
            throw failure(project, variant, "credentials",
                    "Signing failed for the selected complete source",
                    "Verify the keystore, alias, and passwords");
        }
        verifySignedBundle(temporary, expectedCertificate, beforeTransparency, project, variant);
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        writeReceipt(source.category(), unsignedSha256, sha256(output), actualCertificate,
                true, true, true, beforeTransparency, output);
    }

    static String verifyUnsignedCandidate(
            Path candidate, Path expectedDigest, String project, String variant)
            throws IOException {
        var actual = sha256(candidate);
        var expected = Files.readString(expectedDigest, StandardCharsets.UTF_8).trim();
        if (!actual.equals(expected)) {
            throw failure(project, variant, "unsigned-candidate",
                    "Unsigned candidate digest differs from canonicalization evidence",
                    "Discard the mutated candidate and rerun canonical Bundle rewriting");
        }
        return actual;
    }

    private SigningSource selectSource(String project, String variant) {
        var sources = List.of(
                source("EXACT_VARIANT_DSL", getExactKeyStore(), getExactStorePassword(),
                        getExactKeyAlias(), getExactKeyPassword(), getExactCertificateSha256()),
                source("TOP_LEVEL_DSL", getTopKeyStore(), getTopStorePassword(),
                        getTopKeyAlias(), getTopKeyPassword(), getTopCertificateSha256()),
                source("ENVIRONMENT", getEnvironmentKeyStore(), getEnvironmentStorePassword(),
                        getEnvironmentKeyAlias(), getEnvironmentKeyPassword(),
                        getEnvironmentCertificateSha256()),
                source("GRADLE_PROPERTY", getPropertyKeyStore(), getPropertyStorePassword(),
                        getPropertyKeyAlias(), getPropertyKeyPassword(),
                        getPropertyCertificateSha256()));
        for (var source : sources) {
            var present = source.values().stream().filter(value -> value != null).count();
            if (present == 0) continue;
            if (present != source.values().size()) {
                throw failure(project, variant, source.category(),
                        "Higher-precedence signing source is partial",
                        "Provide every keystore, password, alias, and certificate field"
                                + " in this one source");
            }
            return source;
        }
        return null;
    }

    private static SigningSource source(
            String category, Property<String> keyStore, Property<String> storePassword,
            Property<String> alias, Property<String> keyPassword,
            Property<String> certificate) {
        return new SigningSource(category, value(keyStore), value(storePassword), value(alias),
                value(keyPassword), value(certificate));
    }

    private static String value(Property<String> property) {
        var value = property.getOrNull();
        return value == null || value.isBlank() ? null : value;
    }

    private static KeyStore.PrivateKeyEntry loadPrivateKeyEntry(
            SigningSource source, String project, String variant) {
        try {
            var path = Path.of(source.keyStore());
            if (!Files.isRegularFile(path)) throw new IOException("missing");
            Exception last = null;
            for (var type : List.of("PKCS12", "JKS")) {
                try (var stream = Files.newInputStream(path)) {
                    var keyStore = KeyStore.getInstance(type);
                    keyStore.load(stream, source.storePassword().toCharArray());
                    var entry = keyStore.getEntry(source.alias(),
                            new KeyStore.PasswordProtection(source.keyPassword().toCharArray()));
                    if (entry instanceof KeyStore.PrivateKeyEntry privateKey) return privateKey;
                } catch (Exception invalid) {
                    last = invalid;
                }
            }
            throw last == null ? new IOException("unavailable") : last;
        } catch (Exception invalid) {
            throw failure(project, variant, "credentials",
                    "Selected signing source cannot resolve one private-key entry",
                    "Verify the keystore format, alias, and passwords");
        }
    }

    static void verifySignedBundle(
            Path bundle, String expectedCertificate, Map<String, String> beforeTransparency,
            String project, String variant) throws IOException {
        var certificateDigests = new TreeSet<String>();
        try (var zip = new JarFile(bundle.toFile(), true)) {
            for (var entry : zip.stream().filter(item -> !item.isDirectory()).toList()) {
                try (var input = zip.getInputStream(entry)) { input.transferTo(OutputStream.nullOutputStream()); }
                if (entry.getName().startsWith("META-INF/")) continue;
                var certificates = entry.getCertificates();
                if (certificates == null || certificates.length == 0) {
                    throw failure(project, variant, entry.getName(),
                            "Signed AAB contains an entry without signature coverage",
                            "Sign every non-signature Bundle entry atomically");
                }
                for (var certificate : certificates) {
                    certificateDigests.add(certificateSha256(certificate));
                }
            }
        } catch (SecurityException invalid) {
            throw failure(project, variant, "signature",
                    "Signed AAB signature verification failed",
                    "Discard the corrupted candidate and sign the exact unsigned digest again");
        }
        if (!certificateDigests.equals(java.util.Set.of(expectedCertificate))) {
            throw failure(project, variant, "certificate",
                    "Signature coverage uses an unexpected certificate identity",
                    "Sign every entry with only the expected upload certificate");
        }
        if (!beforeTransparency.equals(codeTransparencyDigests(bundle))) {
            throw failure(project, variant, "code-transparency",
                    "Code-transparency material changed during signing",
                    "Preserve every code-transparency path and byte unchanged");
        }
        try {
            ValidateBundleCommand.builder().setBundlePath(bundle).setPrintOutput(false)
                    .build().execute();
        } catch (RuntimeException invalid) {
            throw failure(project, variant, "bundletool",
                    "bundletool rejected the signed candidate",
                    "Discard the candidate and inspect signing-stage structural integrity");
        }
    }

    private void writeReceipt(
            String source, String unsignedSha, String signedSha, String certificateSha,
            boolean coverage, boolean bundletool, boolean certificateMatched,
            Map<String, String> transparency, Path output) throws IOException {
        var text = new StringBuilder("schema=SigningReceipt.v1\n")
                .append("project=").append(getConsumerProjectPath().get()).append('\n')
                .append("variant=").append(getVariantName().get()).append('\n')
                .append("source=").append(source).append('\n')
                .append("unsignedAabSha256=").append(unsignedSha).append('\n')
                .append("signedAabSha256=").append(signedSha).append('\n')
                .append("certificateSha256=").append(certificateSha).append('\n')
                .append("signatureCoverageValidated=").append(coverage).append('\n')
                .append("certificateMatched=").append(certificateMatched).append('\n')
                .append("bundletoolValidated=").append(bundletool).append('\n')
                .append("codeTransparencyEntries=").append(transparency.size()).append('\n');
        var receipt = getSigningReceipt().get().getAsFile().toPath();
        Files.createDirectories(receipt.getParent());
        Files.writeString(receipt, text, StandardCharsets.UTF_8);
    }

    private static Map<String, String> codeTransparencyDigests(Path bundle) throws IOException {
        var values = new TreeMap<String, String>();
        try (var zip = new ZipFile(bundle.toFile())) {
            for (var entry : zip.stream().filter(item -> {
                var path = item.getName().toLowerCase(Locale.ROOT);
                return !item.isDirectory() && (path.contains("code-transparency")
                        || path.contains("code_transparency"));
            }).toList()) {
                values.put(entry.getName(), sha256(zip.getInputStream(entry).readAllBytes()));
            }
        }
        return Map.copyOf(values);
    }

    private static String normalizeCertificateDigest(
            String value, String project, String variant) {
        var normalized = value.replace(":", "").toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw failure(project, variant, "certificate",
                    "Expected certificate digest is not canonical SHA-256",
                    "Provide 64 hexadecimal SHA-256 characters");
        }
        return normalized;
    }

    private static String certificateSha256(Certificate certificate) {
        try { return sha256(certificate.getEncoded()); }
        catch (java.security.cert.CertificateEncodingException impossible) {
            throw new IllegalStateException("Certificate encoding is unavailable", impossible);
        }
    }

    private static String sha256(Path path) throws IOException {
        return sha256(Files.readAllBytes(path));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static org.gradle.api.GradleException failure(
            String project, String variant, String target, String reason, String repair) {
        return new KaleidoDiagnostic("KLD-SIGNING-001", project, variant, "signing",
                "SigningReceipt.v1", target, reason, repair).failure();
    }

    private record SigningSource(String category, String keyStore, String storePassword,
                                 String alias, String keyPassword,
                                 String expectedCertificateSha256) {
        List<String> values() {
            var values = new ArrayList<String>();
            values.add(keyStore); values.add(storePassword); values.add(alias);
            values.add(keyPassword); values.add(expectedCertificateSha256);
            return values;
        }
    }
}
