package com.tongsr.kaleido.gradle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "Publication validates signing evidence and commits atomically")
public abstract class PublishKaleidoReleaseTask extends DefaultTask {
    static final String MANIFEST_SCHEMA = "ReleaseEvidenceSetManifest.v1";
    static final String REPORT_SCHEMA_URI = ArtifactReportReader.CURRENT_SCHEMA_URI;

    public PublishKaleidoReleaseTask() {
        getOutputs().upToDateWhen(ignored -> false);
    }

    @Input public abstract Property<String> getConsumerProjectPath();
    @Input public abstract Property<String> getVariantName();
    @Input public abstract Property<String> getPluginVersion();

    @Internal public abstract DirectoryProperty getConsumerProjectDirectory();
    @Internal public abstract DirectoryProperty getPublishedEvidenceDirectory();

    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInputBundle();
    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getStagedSignedBundle();
    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getSigningReceipt();
    @InputFiles @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getDeterministicEvidence();

    @OutputFile public abstract RegularFileProperty getOutputBundle();

    @TaskAction
    public void publish() throws IOException {
        var project = getConsumerProjectPath().get();
        var variant = getVariantName().get();
        var root = getConsumerProjectDirectory().get().getAsFile().toPath()
                .toAbsolutePath().normalize();
        var deterministic = collectEvidence(root, getDeterministicEvidence());
        var inputBundle = getInputBundle().get().getAsFile().toPath();
        var signedBundle = getStagedSignedBundle().get().getAsFile().toPath();
        var signingReceipt = getSigningReceipt().get().getAsFile().toPath();
        ensureInput(deterministic, root, inputBundle);

        final Publication publication;
        try {
            publication = assemble(new Context(project, variant, getPluginVersion().get()),
                    deterministic, Files.readAllBytes(signedBundle),
                    Files.readAllBytes(signingReceipt));
        } catch (IllegalArgumentException invalid) {
            throw failure(project, variant, "staging", invalid.getMessage(),
                    "Regenerate every stage artifact and publish only one complete verified set");
        }

        var output = getOutputBundle().get().getAsFile().toPath();
        var evidence = getPublishedEvidenceDirectory().get().getAsFile().toPath();
        recoverInterruptedPublication(output, evidence);
        var outputStage = output.resolveSibling(output.getFileName() + ".publication-staging");
        var evidenceStage = evidence.resolveSibling(evidence.getFileName() + ".publication-staging");
        deleteTree(outputStage);
        deleteTree(evidenceStage);
        Files.createDirectories(outputStage.getParent());
        Files.write(outputStage, publication.signedBundle());
        Files.createDirectories(evidenceStage);
        for (var entry : publication.files().entrySet()) {
            var target = evidenceStage.resolve(entry.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, entry.getValue());
        }
        validateStagedTree(evidenceStage, publication.files());
        commit(outputStage, output, evidenceStage, evidence);
    }

    static Publication assemble(
            Context context, Map<String, byte[]> deterministicInputs, byte[] signedBundle,
            byte[] signingBytes) {
        var inputs = new TreeMap<String, byte[]>();
        deterministicInputs.forEach((path, bytes) -> {
            var normalized = path.replace('\\', '/');
            if (normalized.startsWith("/") || normalized.contains("../")) {
                throw new IllegalArgumentException("Evidence path is not project-relative: " + path);
            }
            var previous = inputs.put(normalized, bytes.clone());
            if (previous != null && !java.util.Arrays.equals(previous, bytes)) {
                throw new IllegalArgumentException("Evidence path has conflicting bytes: " + path);
            }
        });
        var adoptionBytes = unique(inputs, "adoption-plan.properties");
        var generationBytes = unique(inputs, "generated-inventory.properties");
        var rawKaleido = unique(inputs, "class-rewrite/raw-kaleido-mapping.txt");
        var rawR8 = unique(inputs, "r8/raw-r8-mapping.txt");
        var composed = unique(inputs, "r8/composed-mapping.txt");
        var resource = unique(inputs, "bundle-rewrite/resource-mapping.txt");
        var unsigned = unique(inputs, "bundle-rewrite/unsigned-candidate.aab");
        var unsignedDigestBytes = unique(inputs, "bundle-rewrite/unsigned-candidate.sha256");
        var composeReceipt = unique(inputs, "compose/final-dex-receipt.properties");

        var adoption = properties(adoptionBytes, "Adoption Plan");
        var generation = properties(generationBytes, "generation inventory");
        var signing = properties(signingBytes, "signing receipt");
        var compose = properties(composeReceipt, "Compose final DEX receipt");
        require(adoption, "schema", "AdoptionPlan.v1");
        require(generation, "schema", "GeneratedInventory.v1");
        require(signing, "schema", "SigningReceipt.v1");
        require(compose, "schema", "ComposeFinalDexReceipt.v1");
        require(signing, "signatureCoverageValidated", "true");
        require(signing, "certificateMatched", "true");
        require(signing, "bundletoolValidated", "true");
        require(compose, "mappingResolved", "true");
        require(compose, "incomingBytecodeEdges", "0");
        require(compose, "finalDexRetained", "true");
        if (!context.project().equals(adoption.get("project"))
                || !context.variant().equals(adoption.get("variant.name"))
                || !context.project().equals(signing.get("project"))
                || !context.variant().equals(signing.get("variant"))) {
            throw new IllegalArgumentException("Variant identity differs across staged evidence");
        }

        var unsignedSha = sha256(unsigned);
        var expectedUnsigned = new String(unsignedDigestBytes, StandardCharsets.UTF_8).trim();
        var signedSha = sha256(signedBundle);
        if (!unsignedSha.equals(expectedUnsigned)
                || !unsignedSha.equals(signing.get("unsignedAabSha256"))) {
            throw new IllegalArgumentException("Unsigned AAB digest closure is incomplete");
        }
        if (!signedSha.equals(signing.get("signedAabSha256"))) {
            throw new IllegalArgumentException("Signed AAB digest differs from signing evidence");
        }
        var certificate = signing.getOrDefault("certificateSha256", "");
        if (!certificate.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Signing certificate digest is not canonical");
        }

        var deterministicManifest = new StringBuilder(
                "schema=DeterministicEvidenceManifest.v1\n");
        inputs.forEach((path, bytes) -> deterministicManifest.append("file=")
                .append(path).append('|').append(sha256(bytes)).append('\n'));
        var deterministicBytes = deterministicManifest.toString()
                .getBytes(StandardCharsets.UTF_8);
        var deterministicSha = sha256(deterministicBytes);
        var rawKaleidoSha = sha256(rawKaleido);
        var rawR8Sha = sha256(rawR8);
        var composedSha = sha256(composed);
        var resourceSha = sha256(resource);
        var identity = "schema=" + MANIFEST_SCHEMA + "\n"
                + "project=" + context.project() + "\n"
                + "variant=" + context.variant() + "\n"
                + "applicationId=" + adoption.get("applicationId") + "\n"
                + "unsignedAabSha256=" + unsignedSha + "\n"
                + "signedAabSha256=" + signedSha + "\n"
                + "rawKaleidoMappingSha256=" + rawKaleidoSha + "\n"
                + "rawR8MappingSha256=" + rawR8Sha + "\n"
                + "composedMappingSha256=" + composedSha + "\n"
                + "resourceMappingSha256=" + resourceSha + "\n"
                + "deterministicEvidenceSha256=" + deterministicSha + "\n"
                + "certificateSha256=" + certificate + "\n";
        var setId = sha256(identity.getBytes(StandardCharsets.UTF_8));
        var manifest = identity
                + "pluginVersion=" + context.pluginVersion() + "\n"
                + "profile=" + adoption.get("profile") + "\n"
                + "publicationResult=PUBLISHED\n"
                + "releaseEvidenceSetId=" + setId + "\n";
        var report = report(context, adoption, generation, signing, compose, setId,
                unsignedSha, signedSha, certificate, deterministicSha,
                rawKaleidoSha, rawR8Sha, composedSha, resourceSha);
        ArtifactReportReader.read(report);

        var files = new TreeMap<String, byte[]>();
        inputs.forEach((path, bytes) -> files.put("deterministic/" + path, bytes.clone()));
        files.put("mappings/raw-kaleido-mapping.txt", rawKaleido.clone());
        files.put("mappings/raw-r8-mapping.txt", rawR8.clone());
        files.put("mappings/composed-mapping.txt", composed.clone());
        files.put("mappings/resource-mapping.txt", resource.clone());
        files.put("publication/signing-receipt.properties", signingBytes.clone());
        files.put("publication/compose-final-dex-receipt.properties", composeReceipt.clone());
        files.put("deterministic-evidence-manifest.properties", deterministicBytes);
        files.put("release-evidence-set-manifest.properties",
                manifest.getBytes(StandardCharsets.UTF_8));
        files.put("artifact-report.txt", report.getBytes(StandardCharsets.UTF_8));
        return new Publication(Map.copyOf(files), signedBundle.clone(), setId);
    }

    private static String report(
            Context context, Map<String, String> adoption, Map<String, String> generation,
            Map<String, String> signing, Map<String, String> compose, String setId,
            String unsignedSha, String signedSha, String certificate,
            String deterministicSha, String rawKaleidoSha, String rawR8Sha,
            String composedSha, String resourceSha) {
        var stages = List.of(
                "adoption-validation", "immutable-adoption-plan", "bounded-generation",
                "class-manifest-protection", "r8-configuration-mapping",
                "resource-plan-rewrite", "unsigned-canonicalization",
                "compose-final-dex-verification", "signing-bundle-verification",
                "atomic-publication");
        var text = new StringBuilder()
                .append("schemaUri=").append(REPORT_SCHEMA_URI).append('\n')
                .append("schemaVersion=1.0\n")
                .append("releaseEvidenceSetId=").append(setId).append('\n')
                .append("project=").append(context.project()).append('\n')
                .append("variant=").append(context.variant()).append('\n')
                .append("applicationId=").append(adoption.get("applicationId")).append('\n')
                .append("profile=").append(adoption.get("profile")).append('\n')
                .append("pluginVersion=").append(context.pluginVersion()).append('\n');
        for (var index = 0; index < stages.size(); index++) {
            text.append("stage.").append(String.format(java.util.Locale.ROOT, "%02d", index + 1))
                    .append('=').append(stages.get(index)).append("|PASS\n");
        }
        return text.append("generationClasses=").append(generation.get("classes")).append('\n')
                .append("generationActivities=")
                .append(generation.get("components.activities")).append('\n')
                .append("composeFacades=").append(compose.get("facades")).append('\n')
                .append("unsignedAabSha256=").append(unsignedSha).append('\n')
                .append("signedAabSha256=").append(signedSha).append('\n')
                .append("certificateSha256=").append(certificate).append('\n')
                .append("signatureCoverageValidated=")
                .append(signing.get("signatureCoverageValidated")).append('\n')
                .append("bundletoolValidated=").append(signing.get("bundletoolValidated"))
                .append('\n')
                .append("codeTransparencyEntries=")
                .append(signing.get("codeTransparencyEntries")).append('\n')
                .append("rawKaleidoMappingSha256=").append(rawKaleidoSha).append('\n')
                .append("rawR8MappingSha256=").append(rawR8Sha).append('\n')
                .append("composedMappingSha256=").append(composedSha).append('\n')
                .append("resourceMappingSha256=").append(resourceSha).append('\n')
                .append("deterministicEvidenceSha256=").append(deterministicSha).append('\n')
                .append("diagnostics.count=0\n")
                .append("publicationResult=PUBLISHED\n")
                .append("proofLimitations=Static and controlled-fixture evidence does not prove all runtime paths, devices, store review, or absolute unreachability.\n")
                .toString();
    }

    private static TreeMap<String, byte[]> collectEvidence(
            Path root, ConfigurableFileCollection collection) throws IOException {
        var values = new TreeMap<String, byte[]>();
        for (var file : collection.getFiles().stream()
                .sorted(Comparator.comparing(java.io.File::getPath)).toList()) {
            var path = file.toPath().toAbsolutePath().normalize();
            if (Files.isDirectory(path)) {
                try (var paths = Files.walk(path)) {
                    for (var child : paths.filter(Files::isRegularFile).sorted().toList()) {
                        putEvidence(values, root, child);
                    }
                }
            } else if (Files.isRegularFile(path)) {
                putEvidence(values, root, path);
            }
        }
        return values;
    }

    private static void ensureInput(Map<String, byte[]> values, Path root, Path input)
            throws IOException {
        putEvidence(values, root, input.toAbsolutePath().normalize());
    }

    private static void putEvidence(Map<String, byte[]> values, Path root, Path path)
            throws IOException {
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Evidence input is outside the Consumer Project");
        }
        var logical = root.relativize(path).toString().replace(java.io.File.separatorChar, '/');
        var bytes = Files.readAllBytes(path);
        var previous = values.putIfAbsent(logical, bytes);
        if (previous != null && !java.util.Arrays.equals(previous, bytes)) {
            throw new IllegalArgumentException("Evidence path has conflicting bytes: " + logical);
        }
    }

    private static byte[] unique(Map<String, byte[]> values, String suffix) {
        var matches = values.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith(suffix)).toList();
        if (matches.size() != 1) {
            throw new IllegalArgumentException("Expected exactly one staged " + suffix);
        }
        return matches.get(0).getValue();
    }

    private static Map<String, String> properties(byte[] bytes, String label) {
        var values = new TreeMap<String, String>();
        for (var line : new String(bytes, StandardCharsets.UTF_8).split("\\n")) {
            if (line.isBlank()) continue;
            var separator = line.indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException(label + " is not canonical key/value evidence");
            }
            var key = line.substring(0, separator);
            var previous = values.putIfAbsent(key, line.substring(separator + 1));
            if (previous != null && !List.of(
                    "file", "component", "composeFacade", "composeFunction").contains(key)) {
                throw new IllegalArgumentException(label
                        + " is not canonical key/value evidence");
            }
        }
        return Map.copyOf(values);
    }

    private static void require(Map<String, String> values, String key, String expected) {
        if (!expected.equals(values.get(key))) {
            throw new IllegalArgumentException("Evidence field " + key + " must equal " + expected);
        }
    }

    private static void validateStagedTree(Path stage, Map<String, byte[]> expected)
            throws IOException {
        var actual = new TreeMap<String, String>();
        try (var paths = Files.walk(stage)) {
            for (var file : paths.filter(Files::isRegularFile).sorted().toList()) {
                actual.put(stage.relativize(file).toString()
                        .replace(java.io.File.separatorChar, '/'), sha256(Files.readAllBytes(file)));
            }
        }
        var planned = new TreeMap<String, String>();
        expected.forEach((path, bytes) -> planned.put(path, sha256(bytes)));
        if (!actual.equals(planned)) {
            throw new IOException("Staged Release Evidence Set differs from its validated plan");
        }
    }

    private static void recoverInterruptedPublication(Path output, Path evidence)
            throws IOException {
        var outputBackup = backup(output);
        var evidenceBackup = backup(evidence);
        if (!Files.exists(output) && Files.exists(outputBackup)) move(outputBackup, output);
        if (!Files.exists(evidence) && Files.exists(evidenceBackup)) move(evidenceBackup, evidence);
        deleteTree(output.resolveSibling(output.getFileName() + ".publication-staging"));
        deleteTree(evidence.resolveSibling(evidence.getFileName() + ".publication-staging"));
    }

    private static void commit(
            Path outputStage, Path output, Path evidenceStage, Path evidence) throws IOException {
        var outputBackup = backup(output);
        var evidenceBackup = backup(evidence);
        deleteTree(outputBackup);
        deleteTree(evidenceBackup);
        if (Files.exists(output)) move(output, outputBackup);
        if (Files.exists(evidence)) move(evidence, evidenceBackup);
        try {
            move(evidenceStage, evidence);
            move(outputStage, output);
            deleteTree(outputBackup);
            deleteTree(evidenceBackup);
        } catch (IOException failure) {
            deleteTree(output);
            deleteTree(evidence);
            if (Files.exists(outputBackup)) move(outputBackup, output);
            if (Files.exists(evidenceBackup)) move(evidenceBackup, evidence);
            throw failure;
        } finally {
            deleteTree(outputStage);
            deleteTree(evidenceStage);
        }
    }

    private static Path backup(Path path) {
        return path.resolveSibling(path.getFileName() + ".publication-previous");
    }

    private static void move(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        if (Files.isDirectory(root)) {
            try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        } else {
            Files.deleteIfExists(root);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static org.gradle.api.GradleException failure(
            String project, String variant, String target, String reason, String repair) {
        return new KaleidoDiagnostic("KLD-PUBLICATION-001", project, variant,
                "atomic-publication", MANIFEST_SCHEMA, target, reason, repair).failure();
    }

    record Context(String project, String variant, String pluginVersion) {}
    record Publication(Map<String, byte[]> files, byte[] signedBundle, String setId) {}

}
