package com.tongsr.kaleido.release;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Generates the candidate dependency inventory and CycloneDX 1.7 SBOM. */
public final class SupplyChainEvidenceCli {
    private static final String DIAGNOSTIC = "KLD-PROVENANCE-001 ";
    private static final Map<String, String> LICENSES = licenses();

    private SupplyChainEvidenceCli() {}

    public static void main(String[] arguments) throws Exception {
        var options = parse(arguments);
        var output = Path.of(required(options, "output"));
        var version = required(options, "version");
        var candidate = existing(options, "candidate");
        var sources = existing(options, "sources");
        var marker = existing(options, "marker");
        var license = existing(options, "license");
        var notice = existing(options, "notice");
        var thirdParty = existing(options, "third-party");
        var provenance = existing(options, "provenance");
        var verification = existing(options, "verification");
        var components = parseComponents(options.getOrDefault("component", List.of()));
        if (components.isEmpty()) throw failure("resolved dependency inventory is empty");
        Files.createDirectories(output);

        var candidateDigest = digest(candidate);
        var inventory = inventory(version, candidateDigest, candidate, sources, marker,
                license, notice, thirdParty, provenance, verification, components);
        var sbom = sbom(version, candidateDigest, sources, marker, components);
        writeAtomically(output.resolve("source-dependency-inventory.properties"), inventory);
        writeAtomically(output.resolve("kaleido-" + version + ".cdx.json"), sbom);
        var manifest = new StringBuilder("schema=KaleidoSupplyChainManifest.v1\n")
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
                .toString().getBytes(StandardCharsets.UTF_8);
        writeAtomically(output.resolve("supply-chain-manifest.properties"), manifest);
    }

    static byte[] sbom(
            String version, String candidateDigest, Path sources, Path marker,
            List<Component> components) throws Exception {
        var rootRef = "pkg:maven/io.github.ujffdi/kaleido-gradle-plugin@" + version;
        var serial = UUID.nameUUIDFromBytes(
                ("kaleido-sbom:" + candidateDigest).getBytes(StandardCharsets.UTF_8));
        var json = new StringBuilder()
                .append("{\n")
                .append("  \"$schema\": \"https://cyclonedx.org/schema/bom-1.7.schema.json\",\n")
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
                .append("  \"components\": [\n");
        for (int index = 0; index < components.size(); index++) {
            var component = components.get(index);
            var purl = component.purl();
            json.append("    {\"type\": \"library\", \"bom-ref\": \"").append(purl)
                    .append("\", \"group\": \"").append(escape(component.group()))
                    .append("\", \"name\": \"").append(escape(component.name()))
                    .append("\", \"version\": \"").append(escape(component.version()))
                    .append("\", \"purl\": \"").append(purl)
                    .append("\", \"hashes\": [{\"alg\": \"SHA-256\", \"content\": \"")
                    .append(component.digest()).append("\"}], \"licenses\": [{\"license\": ")
                    .append("{\"id\": \"").append(component.license()).append("\"}}]}")
                    .append(index + 1 == components.size() ? "\n" : ",\n");
        }
        json.append("  ],\n  \"dependencies\": [\n    {\"ref\": \"").append(rootRef)
                .append("\", \"dependsOn\": [");
        for (int index = 0; index < components.size(); index++) {
            if (index > 0) json.append(", ");
            json.append('"').append(components.get(index).purl()).append('"');
        }
        json.append("]}\n  ],\n  \"compositions\": [{\"aggregate\": \"complete\", ")
                .append("\"assemblies\": [\"").append(rootRef).append("\"]}]\n}\n");
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] inventory(
            String version, String candidateDigest, Path candidate, Path sources, Path marker,
            Path license, Path notice, Path thirdParty, Path provenance, Path verification,
            List<Component> components) throws Exception {
        var text = new StringBuilder("schema=KaleidoSourceDependencyInventory.v1\n")
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
                .append("components=").append(components.size()).append('\n');
        for (int index = 0; index < components.size(); index++) {
            var component = components.get(index);
            var prefix = "component." + index + ".";
            text.append(prefix).append("coordinate=").append(component.coordinate()).append('\n')
                    .append(prefix).append("sha256=").append(component.digest()).append('\n')
                    .append(prefix).append("license=").append(component.license()).append('\n');
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static List<Component> parseComponents(List<String> encoded) throws Exception {
        var components = new ArrayList<Component>();
        for (var value : encoded) {
            var separator = value.indexOf('|');
            var coordinate = separator < 0 ? "" : value.substring(0, separator);
            var parts = coordinate.split(":", -1);
            if (parts.length != 3) throw failure("invalid component coordinate");
            var artifact = Path.of(value.substring(separator + 1));
            if (!Files.isRegularFile(artifact)) throw failure("component artifact is missing");
            var license = LICENSES.get(parts[0] + ":" + parts[1]);
            if (license == null) throw failure("unreviewed dependency license: " + coordinate);
            components.add(new Component(
                    parts[0], parts[1], parts[2], digest(artifact), license));
        }
        return components.stream().sorted(Comparator.comparing(Component::coordinate)).toList();
    }

    private static Map<String, String> licenses() {
        var values = new LinkedHashMap<String, String>();
        for (var coordinate : List.of(
                "com.android.tools.build:bundletool", "com.android.tools.build:aapt2-proto",
                "com.google.auto.value:auto-value-annotations",
                "com.google.errorprone:error_prone_annotations", "com.google.guava:guava",
                "com.google.guava:failureaccess", "com.google.guava:listenablefuture",
                "com.google.j2objc:j2objc-annotations", "com.google.code.gson:gson",
                "com.google.dagger:dagger", "javax.inject:javax.inject",
                "org.bitbucket.b_c:jose4j", "org.jetbrains.kotlin:kotlin-metadata-jvm",
                "org.jetbrains.kotlin:kotlin-stdlib", "org.jetbrains:annotations")) {
            values.put(coordinate, "Apache-2.0");
        }
        for (var coordinate : List.of(
                "org.ow2.asm:asm", "org.ow2.asm:asm-tree", "org.ow2.asm:asm-commons",
                "com.google.protobuf:protobuf-java", "com.google.protobuf:protobuf-java-util",
                "com.google.code.findbugs:jsr305")) {
            values.put(coordinate, "BSD-3-Clause");
        }
        values.put("org.checkerframework:checker-qual", "MIT");
        values.put("org.slf4j:slf4j-api", "MIT");
        return Map.copyOf(values);
    }

    private static Map<String, List<String>> parse(String[] arguments) {
        var values = new LinkedHashMap<String, List<String>>();
        for (int index = 0; index < arguments.length; index += 2) {
            if (!arguments[index].startsWith("--") || index + 1 >= arguments.length) {
                throw failure("arguments must be --name value pairs");
            }
            values.computeIfAbsent(arguments[index].substring(2), ignored -> new ArrayList<>())
                    .add(arguments[index + 1]);
        }
        return values;
    }

    private static String required(Map<String, List<String>> options, String name) {
        var values = options.get(name);
        if (values == null || values.size() != 1 || values.get(0).isBlank()) {
            throw failure("exactly one --" + name + " is required");
        }
        return values.get(0);
    }

    private static Path existing(Map<String, List<String>> options, String name) {
        var path = Path.of(required(options, name));
        if (!Files.isRegularFile(path)) throw failure(name + " file is missing");
        return path;
    }

    private static String digest(Path path) throws Exception {
        return sha256(Files.readAllBytes(path));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void writeAtomically(Path output, byte[] bytes) throws Exception {
        var staged = Files.createTempFile(output.getParent(), output.getFileName().toString(), ".tmp");
        try {
            Files.write(staged, bytes);
            try {
                Files.move(staged, output, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(staged, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static IllegalArgumentException failure(String message) {
        return new IllegalArgumentException(DIAGNOSTIC + message);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    record Component(
            String group, String name, String version, String digest, String license) {
        String coordinate() { return group + ":" + name + ":" + version; }
        String purl() { return "pkg:maven/" + group + "/" + name + "@" + version; }
    }
}
