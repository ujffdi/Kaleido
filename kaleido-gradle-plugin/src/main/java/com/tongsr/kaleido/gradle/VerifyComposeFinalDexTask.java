package com.tongsr.kaleido.gradle;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipFile;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "Final DEX cache gates are established by Ticket 33")
public abstract class VerifyComposeFinalDexTask extends DefaultTask {
    public VerifyComposeFinalDexTask() {
        getOutputs().upToDateWhen(ignored -> false);
    }

    @Input public abstract Property<String> getConsumerProjectPath();
    @Input public abstract Property<String> getVariantName();
    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInputBundle();
    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getCompiledInventory();
    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getComposedMapping();
    @OutputFile public abstract RegularFileProperty getVerificationReceipt();

    @TaskAction
    public void verify() throws IOException {
        var project = getConsumerProjectPath().get();
        var variant = getVariantName().get();
        var inventory = Files.readAllLines(
                getCompiledInventory().get().getAsFile().toPath(), StandardCharsets.UTF_8);
        if (inventory.isEmpty()
                || !inventory.get(0).equals("schema=" + ComposeCompiledValidator.SCHEMA)) {
            throw failure(project, variant, "compiled-inventory",
                    "Compose compiled inventory schema is missing or incompatible",
                    "Regenerate compiled Compose evidence with this Kaleido version");
        }
        var facades = new TreeMap<String, String>();
        var functions = new ArrayList<FunctionEntry>();
        for (var line : inventory) {
            if (line.startsWith("facade=")) {
                var values = line.substring("facade=".length()).split("\\|", 2);
                if (values.length != 2) throw malformed(project, variant, line);
                facades.put(values[0], values[1]);
            } else if (line.startsWith("method=")) {
                var values = line.substring("method=".length()).split("\\|", 3);
                var separator = values.length == 3 ? values[0].lastIndexOf('#') : -1;
                if (separator <= 0) throw malformed(project, variant, line);
                functions.add(new FunctionEntry(values[0].substring(0, separator),
                        values[0].substring(separator + 1), values[1]));
            }
        }
        if (facades.isEmpty() && functions.isEmpty()) {
            writeReceipt(0, 0, 0);
            return;
        }
        if (facades.isEmpty() || functions.isEmpty()
                || functions.stream().anyMatch(function -> !facades.containsKey(function.facade()))) {
            throw malformed(project, variant, "inventory-closure");
        }

        var mapping = Files.readString(
                getComposedMapping().get().getAsFile().toPath(), StandardCharsets.UTF_8);
        var finalClasses = new TreeMap<String, String>();
        var finalMethods = new TreeMap<String, Set<String>>();
        for (var facade : facades.keySet()) {
            var section = mappingSection(mapping, facade, project, variant);
            finalClasses.put(facade, section.finalClass());
            var names = new TreeSet<String>();
            for (var function : functions.stream()
                    .filter(item -> item.facade().equals(facade)).toList()) {
                var mapped = mappedMethod(section.lines(), function.name(), project, variant);
                if (mapped != null) names.add(mapped);
            }
            finalMethods.put(facade, Set.copyOf(names));
        }

        var dexClasses = new HashMap<String, Set<String>>();
        var dexCount = 0;
        try (var bundle = new ZipFile(getInputBundle().get().getAsFile())) {
            for (var entry : bundle.stream().filter(item -> !item.isDirectory()
                    && item.getName().matches("base/dex/classes[0-9]*\\.dex")).toList()) {
                dexCount++;
                final Map<String, Set<String>> parsed;
                try {
                    parsed = DexDeclarations.parse(bundle.getInputStream(entry).readAllBytes());
                } catch (RuntimeException invalid) {
                    throw failure(project, variant, entry.getName(),
                            "Final DEX declarations are not structurally readable",
                            "Discard the malformed Bundle and rebuild before signing");
                }
                parsed.forEach((type, methods) -> dexClasses
                        .computeIfAbsent(type, ignored -> new HashSet<>()).addAll(methods));
            }
        }
        if (dexCount == 0) {
            throw failure(project, variant, "base/dex",
                    "Final Bundle contains no base classes DEX",
                    "Build the minified Release application before Compose retention verification");
        }
        for (var facade : facades.keySet()) {
            var finalClass = finalClasses.get(facade);
            var descriptor = "L" + finalClass.replace('.', '/') + ";";
            var declaredMethods = dexClasses.get(descriptor);
            if (declaredMethods == null) {
                throw failure(project, variant, facade,
                        "Mapped generated Compose facade is absent from final DEX",
                        "Keep the exact compiled facade without allowing shrinking");
            }
            var expectedMemberCount = functions.stream()
                    .filter(function -> function.facade().equals(facade)).count();
            if (declaredMethods.size() != expectedMemberCount) {
                throw failure(project, variant, facade,
                        "Final DEX member count differs from the compiled Compose inventory",
                        "Retain every and only inventoried generated facade member");
            }
            for (var method : finalMethods.get(facade)) {
                if (!declaredMethods.contains(method)) {
                    throw failure(project, variant, facade + "#" + method,
                            "Mapped generated Compose function is absent from final DEX",
                            "Keep every inventoried facade member without allowing shrinking");
                }
            }
        }
        writeReceipt(facades.size(), functions.size(), dexCount);
    }

    private void writeReceipt(int facades, int functions, int dexFiles) throws IOException {
        var text = "schema=ComposeFinalDexReceipt.v1\n"
                + "project=" + getConsumerProjectPath().get() + "\n"
                + "variant=" + getVariantName().get() + "\n"
                + "facades=" + facades + "\n"
                + "functions=" + functions + "\n"
                + "dexFiles=" + dexFiles + "\n"
                + "mappingResolved=true\n"
                + "incomingBytecodeEdges=0\n"
                + "finalDexRetained=true\n";
        var output = getVerificationReceipt().get().getAsFile().toPath();
        Files.createDirectories(output.getParent());
        Files.writeString(output, text, StandardCharsets.UTF_8);
    }

    private static MappingSection mappingSection(
            String mapping, String original, String project, String variant) {
        var lines = mapping.lines().toList();
        for (var index = 0; index < lines.size(); index++) {
            var prefix = original + " -> ";
            if (!lines.get(index).startsWith(prefix) || !lines.get(index).endsWith(":")) continue;
            var finalClass = lines.get(index).substring(
                    prefix.length(), lines.get(index).length() - 1);
            var members = new ArrayList<String>();
            for (var member = index + 1; member < lines.size()
                    && !lines.get(member).isEmpty()
                    && Character.isWhitespace(lines.get(member).charAt(0)); member++) {
                members.add(lines.get(member));
            }
            return new MappingSection(finalClass, List.copyOf(members));
        }
        throw failure(project, variant, original,
                "Generated Compose facade is absent from the composed mapping",
                "Regenerate raw Kaleido, raw R8, and composed mappings together");
    }

    private static String mappedMethod(
            List<String> lines, String originalName, String project, String variant) {
        var marker = originalName + "(";
        var values = lines.stream().filter(line -> line.contains(marker) && line.contains(" -> "))
                .map(line -> line.substring(line.lastIndexOf(" -> ") + 4)).distinct().toList();
        if (values.isEmpty()) return null;
        if (values.size() != 1) {
            throw failure(project, variant, originalName,
                    "Generated Compose function does not resolve uniquely through composed mapping",
                    "Retain and map every compiled generated function exactly once");
        }
        return values.get(0);
    }

    private static org.gradle.api.GradleException malformed(
            String project, String variant, String target) {
        return failure(project, variant, target,
                "Compose compiled inventory is malformed or incomplete",
                "Regenerate the inventory from compiled PROJECT classes");
    }

    private static org.gradle.api.GradleException failure(
            String project, String variant, String target, String reason, String repair) {
        return new KaleidoDiagnostic("KLD-COMPOSE-001", project, variant,
                "compose-final-dex", "ComposeFinalDexReceipt.v1", target, reason, repair).failure();
    }

    private record FunctionEntry(String facade, String name, String descriptor) {}
    private record MappingSection(String finalClass, List<String> lines) {}

    private static final class DexDeclarations {
        private DexDeclarations() {}

        static Map<String, Set<String>> parse(byte[] bytes) {
            if (bytes.length < 112 || bytes[0] != 'd' || bytes[1] != 'e' || bytes[2] != 'x') {
                throw new IllegalArgumentException("Invalid DEX header");
            }
            var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            var strings = strings(bytes, buffer.getInt(56), buffer.getInt(60));
            var types = types(buffer, strings, buffer.getInt(64), buffer.getInt(68));
            var methodClasses = new int[buffer.getInt(88)];
            var methodNames = new String[methodClasses.length];
            var methodOffset = buffer.getInt(92);
            for (var index = 0; index < methodClasses.length; index++) {
                var offset = methodOffset + index * 8;
                methodClasses[index] = Short.toUnsignedInt(buffer.getShort(offset));
                methodNames[index] = strings.get(buffer.getInt(offset + 4));
            }
            var result = new TreeMap<String, Set<String>>();
            var classCount = buffer.getInt(96);
            var classOffset = buffer.getInt(100);
            for (var classIndex = 0; classIndex < classCount; classIndex++) {
                var offset = classOffset + classIndex * 32;
                var type = types.get(buffer.getInt(offset));
                var classDataOffset = buffer.getInt(offset + 24);
                var declared = new TreeSet<String>();
                if (classDataOffset != 0) readClassData(
                        bytes, classDataOffset, methodNames, declared);
                result.put(type, Set.copyOf(declared));
            }
            return Map.copyOf(result);
        }

        private static List<String> strings(
                byte[] bytes, int count, int offsetsStart) {
            var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            var values = new ArrayList<String>(count);
            for (var index = 0; index < count; index++) {
                var cursor = new Cursor(buffer.getInt(offsetsStart + index * 4));
                uleb(bytes, cursor);
                var start = cursor.value;
                while (bytes[cursor.value] != 0) cursor.value++;
                values.add(new String(bytes, start, cursor.value - start,
                        StandardCharsets.UTF_8));
            }
            return List.copyOf(values);
        }

        private static List<String> types(
                ByteBuffer buffer, List<String> strings, int count, int start) {
            var values = new ArrayList<String>(count);
            for (var index = 0; index < count; index++) {
                values.add(strings.get(buffer.getInt(start + index * 4)));
            }
            return List.copyOf(values);
        }

        private static void readClassData(
                byte[] bytes, int offset, String[] methodNames, Set<String> declared) {
            var cursor = new Cursor(offset);
            var staticFields = uleb(bytes, cursor);
            var instanceFields = uleb(bytes, cursor);
            var directMethods = uleb(bytes, cursor);
            var virtualMethods = uleb(bytes, cursor);
            for (var index = 0; index < staticFields + instanceFields; index++) {
                uleb(bytes, cursor); uleb(bytes, cursor);
            }
            var methodIndex = 0;
            for (var index = 0; index < directMethods; index++) {
                methodIndex += uleb(bytes, cursor);
                uleb(bytes, cursor); uleb(bytes, cursor);
                declared.add(methodNames[methodIndex]);
            }
            methodIndex = 0;
            for (var index = 0; index < virtualMethods; index++) {
                methodIndex += uleb(bytes, cursor);
                uleb(bytes, cursor); uleb(bytes, cursor);
                declared.add(methodNames[methodIndex]);
            }
        }

        private static int uleb(byte[] bytes, Cursor cursor) {
            var result = 0;
            var shift = 0;
            int value;
            do {
                value = Byte.toUnsignedInt(bytes[cursor.value++]);
                result |= (value & 0x7f) << shift;
                shift += 7;
            } while ((value & 0x80) != 0);
            return result;
        }

        private static final class Cursor {
            int value;
            Cursor(int value) { this.value = value; }
        }
    }
}
