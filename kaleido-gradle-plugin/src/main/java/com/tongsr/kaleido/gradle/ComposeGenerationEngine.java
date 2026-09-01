package com.tongsr.kaleido.gradle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ComposeGenerationEngine {
    static final String SCHEMA = "ComposeGeneration.v1";

    private ComposeGenerationEngine() {}

    static GeneratedCompose plan(Map<String, String> adoption) {
        if (!Boolean.parseBoolean(required(adoption, "generation.compose.enabled"))) {
            return new GeneratedCompose(Map.of(), List.of(), List.of());
        }
        var packageName = required(adoption, "generation.packageBase") + ".compose";
        var fileCount = integer(adoption, "generation.compose.fileCount");
        var functionsPerFile = integer(adoption, "generation.compose.functionsPerFile");
        var stream = required(adoption, "seed.domain.generation-compose");
        var total = Math.multiplyExact(fileCount, functionsPerFile);
        var functions = new ArrayList<FunctionIdentity>(total);
        var facades = new ArrayList<String>(fileCount);
        for (var fileIndex = 0; fileIndex < fileCount; fileIndex++) {
            var facade = "KldCompose_" + token(stream, "facade", fileIndex, 12);
            facades.add(packageName + "." + facade);
            for (var functionIndex = 0; functionIndex < functionsPerFile; functionIndex++) {
                var globalIndex = fileIndex * functionsPerFile + functionIndex;
                functions.add(new FunctionIdentity(
                        packageName + "." + facade,
                        "kld_" + token(stream, "function", globalIndex, 12),
                        globalIndex));
            }
        }

        var files = new LinkedHashMap<String, String>();
        for (var fileIndex = 0; fileIndex < fileCount; fileIndex++) {
            var facadeSimple = facades.get(fileIndex).substring(packageName.length() + 1);
            var source = new StringBuilder()
                    .append("@file:JvmName(\"").append(facadeSimple).append("\")\n\n")
                    .append("package ").append(packageName).append("\n\n")
                    .append("import androidx.compose.runtime.Composable\n\n");
            for (var functionIndex = 0; functionIndex < functionsPerFile; functionIndex++) {
                var globalIndex = fileIndex * functionsPerFile + functionIndex;
                var function = functions.get(globalIndex);
                var constant = token(stream, "constant", globalIndex, 8);
                source.append("@Composable\n")
                        .append("internal fun ").append(function.name())
                        .append("(value: Int): Int {\n")
                        .append("    var mixed = value xor 0x").append(constant).append(".toInt()\n");
                if (globalIndex + 1 < total) {
                    source.append("    if ((mixed and 1) == 0) mixed = ")
                            .append(functions.get(globalIndex + 1).name())
                            .append("(mixed)\n");
                }
                source.append("    return mixed\n")
                        .append("}\n\n");
            }
            files.put(packageName.replace('.', '/') + "/" + facadeSimple + ".kt",
                    source.toString());
        }
        return new GeneratedCompose(Map.copyOf(files), List.copyOf(facades),
                List.copyOf(functions));
    }

    private static int integer(Map<String, String> values, String key) {
        return Integer.parseInt(required(values, key));
    }

    private static String required(Map<String, String> values, String key) {
        var value = values.get(key);
        if (value == null) throw new IllegalArgumentException("Missing " + key);
        return value;
    }

    private static String token(String stream, String domain, Object identity, int length) {
        return SeedDerivation.derive(stream, domain, identity.toString()).substring(0, length);
    }

    record FunctionIdentity(String facade, String name, int graphIndex) {}

    record GeneratedCompose(
            Map<String, String> kotlinFiles,
            List<String> facades,
            List<FunctionIdentity> functions) {}
}
