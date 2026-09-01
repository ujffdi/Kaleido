package com.tongsr.kaleido.gradle;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class FullComponentGenerationEngine {
    static final String SCHEMA = "FullComponentGeneration.v1";

    private FullComponentGenerationEngine() {}

    static Result plan(
            Map<String, String> adoptionPlan,
            Set<String> consumerClasses,
            Set<String> ordinaryGeneratedClasses) {
        var count = integer(adoptionPlan, "generation.activityCount");
        if (count == 0) {
            return new Result(Map.of(), List.of(), emptyManifest());
        }
        if (!"FULL".equals(required(adoptionPlan, "profile"))) {
            throw failure(adoptionPlan, "generation.activityCount",
                    "Android component generation is available only in Full Profile",
                    "Select FULL explicitly or keep activityCount at zero");
        }

        var packageName = required(adoptionPlan, "generation.packageBase") + ".components";
        var stream = required(adoptionPlan, "seed.domain.generation-ordinary");
        var files = new LinkedHashMap<String, String>();
        var activities = new TreeSet<String>();
        for (var index = 0; index < count; index++) {
            var className = "A_" + token(stream, "activity", index, 12);
            var identity = packageName + "." + className;
            if (consumerClasses.contains(identity)
                    || ordinaryGeneratedClasses.contains(identity)
                    || !activities.add(identity)) {
                throw failure(adoptionPlan, identity,
                        "Generated Activity identity collides with an existing class",
                        "Change the seed or generation package and rebuild");
            }
            files.put(packageName.replace('.', '/') + "/" + className + ".java", """
                    package %s;

                    public final class %s extends android.app.Activity {
                        public %s() {}
                    }
                    """.formatted(packageName, className, className));
        }
        var result = new Result(Map.copyOf(files), List.copyOf(activities),
                manifest(activities));
        validateContract(adoptionPlan, result);
        return result;
    }

    static void validateContract(Map<String, String> adoptionPlan, Result result) {
        var expected = integer(adoptionPlan, "generation.activityCount");
        if (result.activities().size() != expected
                || result.javaFiles().size() != expected
                || result.activities().stream().distinct().count() != expected) {
            throw failure(adoptionPlan, "generation.activityCount",
                    "Generated component inventory is incomplete or duplicated",
                    "Regenerate the complete deterministic component inventory");
        }
        for (var activity : result.activities()) {
            var path = activity.replace('.', '/') + ".java";
            var source = result.javaFiles().get(path);
            var simpleName = activity.substring(activity.lastIndexOf('.') + 1);
            if (source == null
                    || !source.contains("public final class " + simpleName
                            + " extends android.app.Activity")
                    || source.contains("android.content.Intent")
                    || source.contains("android.net.")
                    || source.contains("java.net.")
                    || source.contains("android.util.Log")) {
                throw failure(adoptionPlan, activity,
                        "Generated Activity source violates the inert component contract",
                        "Generate only an empty public Activity with no Consumer or I/O references");
            }
            var declaration = "<activity android:name=\"" + activity
                    + "\" android:exported=\"false\" />";
            if (!result.manifest().contains(declaration)) {
                throw failure(adoptionPlan, activity,
                        "Generated Activity is missing its exact inert Manifest declaration",
                        "Regenerate matching class and Manifest inventories");
            }
        }
        var forbidden = List.of("<uses-permission", "<intent-filter", "<service", "<receiver",
                "<provider", "android:exported=\"true\"", "android:permission=",
                "android:process=", "android:enabled=\"true\"", "android:authorities=");
        var violation = forbidden.stream().filter(result.manifest()::contains).findFirst();
        if (violation.isPresent()) {
            throw failure(adoptionPlan, violation.get(),
                    "Generated Manifest violates the inert component contract",
                    "Remove permissions, entry points, startup hooks, and unsupported attributes");
        }
        var declarations = result.manifest().split("<activity ", -1).length - 1;
        if (declarations != expected) {
            throw failure(adoptionPlan, "AndroidManifest.xml",
                    "Generated Manifest component declarations do not match the inventory",
                    "Regenerate the complete deterministic component Manifest");
        }
    }

    private static String manifest(Set<String> activities) {
        var manifest = new StringBuilder()
                .append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
                .append("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n")
                .append("    <application>\n");
        activities.forEach(activity -> manifest.append("        <activity android:name=\"")
                .append(activity).append("\" android:exported=\"false\" />\n"));
        return manifest.append("    </application>\n")
                .append("</manifest>\n")
                .toString();
    }

    private static String emptyManifest() {
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application />
                </manifest>
                """;
    }

    private static int integer(Map<String, String> plan, String key) {
        return Integer.parseInt(required(plan, key));
    }

    private static String required(Map<String, String> plan, String key) {
        var value = plan.get(key);
        if (value == null || value.isBlank()) {
            throw failure(plan, key,
                    "Adoption Plan is missing a required component-generation value",
                    "Regenerate a complete AdoptionPlan.v1");
        }
        return value;
    }

    private static String token(String stream, String domain, Object identity, int length) {
        return SeedDerivation.derive(stream, domain, identity.toString()).substring(0, length);
    }

    private static org.gradle.api.GradleException failure(
            Map<String, String> plan, String target, String reason, String repair) {
        return new KaleidoDiagnostic(
                "KLD-COMPONENT-001",
                plan.getOrDefault("project", "<consumer>"),
                plan.getOrDefault("variant.name", "<variant>"),
                "component-generation",
                SCHEMA,
                target,
                reason,
                repair).failure();
    }

    record Result(Map<String, String> javaFiles, List<String> activities, String manifest) {
        Result {
            javaFiles = Map.copyOf(javaFiles);
            activities = List.copyOf(activities);
        }
    }
}
