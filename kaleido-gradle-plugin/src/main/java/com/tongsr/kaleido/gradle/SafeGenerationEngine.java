package com.tongsr.kaleido.gradle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class SafeGenerationEngine {
    private SafeGenerationEngine() {}

    static GeneratedContent plan(
            Map<String, String> adoptionPlan,
            Set<GenerateSafeContentTask.ResourceIdentity> consumerResources) {
        return plan(adoptionPlan, consumerResources, Set.of());
    }

    static GeneratedContent plan(
            Map<String, String> adoptionPlan,
            Set<GenerateSafeContentTask.ResourceIdentity> consumerResources,
            Set<String> consumerClasses) {
        var packageBase = required(adoptionPlan, "generation.packageBase");
        var packageCount = integer(adoptionPlan, "generation.packageCount");
        var classesPerPackage = integer(adoptionPlan, "generation.classesPerPackage");
        var methodsPerClass = integer(adoptionPlan, "generation.methodsPerClass");
        var layoutCount = integer(adoptionPlan, "generation.layoutCount");
        var drawableCount = integer(adoptionPlan, "generation.drawableCount");
        var stringCount = integer(adoptionPlan, "generation.stringCount");
        var prefix = required(adoptionPlan, "resources.prefix");
        var stream = required(adoptionPlan, "seed.domain.generation-ordinary");

        var javaFiles = new LinkedHashMap<String, String>();
        var generatedPackages = new TreeSet<String>();
        var generatedClasses = new TreeSet<String>();
        var methodTotal = 0;
        for (var packageIndex = 0; packageIndex < packageCount; packageIndex++) {
            var packageName = packageBase + ".p_" + token(stream, "package", packageIndex, 8);
            generatedPackages.add(packageName);
            for (var classIndex = 0; classIndex < classesPerPackage; classIndex++) {
                var identity = packageIndex + ":" + classIndex;
                var className = "C_" + token(stream, "class", identity, 10);
                generatedClasses.add(packageName + "." + className);
                var source = new StringBuilder()
                        .append("package ").append(packageName).append(";\n\n")
                        .append("final class ").append(className).append(" {\n")
                        .append("    private ").append(className).append("() {}\n\n");
                for (var methodIndex = 0; methodIndex < methodsPerClass; methodIndex++) {
                    var methodName = "m_" + token(stream, "method",
                            identity + ":" + methodIndex, 10);
                    var constant = token(stream, "constant",
                            identity + ":" + methodIndex, 8);
                    source.append("    static int ").append(methodName)
                            .append("(int value) {\n")
                            .append("        return Integer.rotateLeft(value ^ 0x")
                            .append(constant).append(", ")
                            .append((methodIndex % 31) + 1).append(");\n")
                            .append("    }\n\n");
                    methodTotal++;
                }
                source.append("}\n");
                javaFiles.put(packageName.replace('.', '/') + "/" + className + ".java",
                        source.toString());
            }
        }

        var resourceFiles = new LinkedHashMap<String, String>();
        var generatedResources = new TreeSet<GenerateSafeContentTask.ResourceIdentity>(
                java.util.Comparator.comparing(GenerateSafeContentTask.ResourceIdentity::type)
                        .thenComparing(GenerateSafeContentTask.ResourceIdentity::name));
        for (var index = 0; index < layoutCount; index++) {
            var name = prefix + "layout_" + token(stream, "layout", index, 8);
            addResource(adoptionPlan, consumerResources, generatedResources, "layout", name);
            resourceFiles.put("layout/" + name + ".xml", """
                    <?xml version="1.0" encoding="utf-8"?>
                    <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
                        android:layout_width="match_parent"
                        android:layout_height="match_parent" />
                    """);
        }
        for (var index = 0; index < drawableCount; index++) {
            var name = prefix + "drawable_" + token(stream, "drawable", index, 8);
            addResource(adoptionPlan, consumerResources, generatedResources, "drawable", name);
            var color = token(stream, "color", index, 6).toUpperCase(java.util.Locale.ROOT);
            resourceFiles.put("drawable/" + name + ".xml", """
                    <?xml version="1.0" encoding="utf-8"?>
                    <shape xmlns:android="http://schemas.android.com/apk/res/android"
                        android:shape="rectangle">
                        <solid android:color="#%s" />
                    </shape>
                    """.formatted(color));
        }
        var strings = new StringBuilder("<resources>\n");
        for (var index = 0; index < stringCount; index++) {
            var name = prefix + "string_" + token(stream, "string-name", index, 8);
            addResource(adoptionPlan, consumerResources, generatedResources, "string", name);
            strings.append("    <string name=\"").append(name).append("\">kld_")
                    .append(token(stream, "string-value", index, 16)).append("</string>\n");
        }
        strings.append("</resources>\n");
        resourceFiles.put("values/strings.xml", strings.toString());

        var components = FullComponentGenerationEngine.plan(
                adoptionPlan, consumerClasses, generatedClasses);
        javaFiles.putAll(components.javaFiles());
        var keepRules = "-keep,allowoptimization,allowobfuscation class "
                + packageBase + ".** { *; }\n";
        return new GeneratedContent(
                Map.copyOf(javaFiles),
                Map.copyOf(resourceFiles),
                components.manifest(),
                keepRules,
                generatedClasses.size() + components.activities().size(),
                methodTotal,
                layoutCount,
                drawableCount,
                stringCount,
                components.activities());
    }

    private static void addResource(
            Map<String, String> plan,
            Set<GenerateSafeContentTask.ResourceIdentity> consumerResources,
            Set<GenerateSafeContentTask.ResourceIdentity> generatedResources,
            String type,
            String name) {
        var identity = new GenerateSafeContentTask.ResourceIdentity(type, name);
        if (consumerResources.contains(identity) || !generatedResources.add(identity)) {
            throw generationFailure(plan, type + "/" + name,
                    "Generated resource identity collides with an existing identity",
                    "Change the seed or generation package and rebuild");
        }
    }

    private static int integer(Map<String, String> plan, String key) {
        return Integer.parseInt(required(plan, key));
    }

    private static String required(Map<String, String> plan, String key) {
        var value = plan.get(key);
        if (value == null) {
            throw generationFailure(plan, key,
                    "Adoption Plan is missing a required generation value",
                    "Regenerate a complete AdoptionPlan.v1");
        }
        return value;
    }

    private static String token(String stream, String domain, Object identity, int length) {
        return SeedDerivation.derive(stream, domain, identity.toString()).substring(0, length);
    }

    private static org.gradle.api.GradleException generationFailure(
            Map<String, String> plan, String target, String reason, String repair) {
        return new KaleidoDiagnostic(
                "KLD-GENERATION-001",
                plan.getOrDefault("project", "<consumer>"),
                plan.getOrDefault("variant.name", "<variant>"),
                "generation",
                "AdoptionPlan.v1",
                target,
                reason,
                repair).failure();
    }

    record GeneratedContent(
            Map<String, String> javaFiles,
            Map<String, String> resourceFiles,
            String manifest,
            String keepRules,
            int classCount,
            int methodCount,
            int layoutCount,
            int drawableCount,
            int stringCount,
            java.util.List<String> activities) {}
}
