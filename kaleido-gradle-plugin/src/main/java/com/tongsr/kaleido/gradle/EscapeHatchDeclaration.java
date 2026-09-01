package com.tongsr.kaleido.gradle;

import com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension;
import com.tongsr.kaleido.gradle.dsl.KaleidoProtectionSelectorKind;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

record EscapeHatchDeclaration(
        String id,
        KaleidoProtectionSelectorKind selectorKind,
        String selectorValue,
        Set<KaleidoProtectionDimension> dimensions,
        String reason,
        Kind kind) {
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9._-]{2,63}");
    private static final Pattern CLASS_EXACT = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
    private static final Pattern CLASS_PREFIX = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+\\.?");
    private static final Pattern RESOURCE = Pattern.compile("[a-z][a-z0-9_]{1,126}");

    enum Kind { CLASS, RESOURCE }

    EscapeHatchDeclaration {
        dimensions = Set.copyOf(dimensions);
    }

    static EscapeHatchDeclaration parse(
            String encoded, Kind kind, String project, String variant) {
        try {
            var values = encoded.split("\\|", -1);
            if (values.length != 5) throw new IllegalArgumentException("field count");
            var dimensions = EnumSet.noneOf(KaleidoProtectionDimension.class);
            if (!values[3].isEmpty()) {
                for (var dimension : values[3].split("\\+")) {
                    dimensions.add(KaleidoProtectionDimension.valueOf(dimension));
                }
            }
            var declaration = new EscapeHatchDeclaration(
                    values[0],
                    KaleidoProtectionSelectorKind.valueOf(values[1]),
                    decode(values[2]),
                    dimensions,
                    decode(values[4]),
                    kind);
            declaration.validate(project, variant);
            return declaration;
        } catch (RuntimeException failure) {
            if (failure instanceof org.gradle.api.GradleException gradleFailure) {
                throw gradleFailure;
            }
            throw diagnostic(project, variant, "declaration",
                    "Escape Hatch declaration is malformed",
                    "Use a typed exact or bounded prefix declaration");
        }
    }

    boolean matches(String identity) {
        return selectorKind == KaleidoProtectionSelectorKind.EXACT
                ? identity.equals(selectorValue)
                : identity.startsWith(kind == Kind.CLASS ? normalizedPrefix() : selectorValue);
    }

    boolean protects(KaleidoProtectionDimension dimension) {
        return dimensions.contains(dimension);
    }

    private String normalizedPrefix() {
        return selectorValue.endsWith(".") ? selectorValue : selectorValue + ".";
    }

    private void validate(String project, String variant) {
        if (!ID.matcher(id).matches()) {
            throw diagnostic(project, variant, id,
                    "Escape Hatch ID is not stable lowercase identifier text",
                    "Use 3..64 lowercase letters, digits, dot, underscore, or hyphen");
        }
        if (reason.isBlank() || reason.length() > 512) {
            throw diagnostic(project, variant, id,
                    "Escape Hatch reason is blank or exceeds 512 characters",
                    "Provide one finite human review reason");
        }
        if (dimensions.isEmpty()) {
            throw diagnostic(project, variant, id,
                    "Escape Hatch selects no Protection Requirement dimensions",
                    "Select the minimum required typed dimensions");
        }
        var allowed = kind == Kind.CLASS
                ? EnumSet.of(KaleidoProtectionDimension.REACHABILITY,
                        KaleidoProtectionDimension.ORIGINAL_IDENTITY,
                        KaleidoProtectionDimension.DESCRIPTOR_CLOSURE,
                        KaleidoProtectionDimension.RUNTIME_ATTRIBUTES)
                : EnumSet.of(KaleidoProtectionDimension.REACHABILITY,
                        KaleidoProtectionDimension.RESOURCE_NAME,
                        KaleidoProtectionDimension.PACKAGED_PATH);
        if (!allowed.containsAll(dimensions)) {
            throw diagnostic(project, variant, id,
                    "Escape Hatch mixes class and resource protection dimensions",
                    "Move the declaration to the matching typed block");
        }
        var legalSelector = kind == Kind.CLASS
                ? (selectorKind == KaleidoProtectionSelectorKind.EXACT
                        ? CLASS_EXACT.matcher(selectorValue).matches()
                        : CLASS_PREFIX.matcher(selectorValue).matches())
                : RESOURCE.matcher(selectorValue).matches();
        if (!legalSelector || selectorValue.equals("*") || selectorValue.isBlank()) {
            throw diagnostic(project, variant, id,
                    "Escape Hatch selector is global, raw, or invalid",
                    "Use one legal exact identity or bounded package/resource prefix");
        }
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static org.gradle.api.GradleException diagnostic(
            String project, String variant, String target, String reason, String repair) {
        return new KaleidoDiagnostic("KLD-PROTECTION-001", project, variant,
                "protection", "kaleido.protection", target, reason, repair).failure();
    }
}
