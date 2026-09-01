package com.tongsr.kaleido.gradle;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Set;

public final class KaleidoVersionContract {
    public static final String PUBLIC_BASELINE_VERSION = "1.0.0";
    public static final String COMPATIBILITY_DIAGNOSTIC = "KLD-COMPAT-001";
    public static final String DEPRECATION_DIAGNOSTIC = "KLD-DEPRECATION-001";
    public static final String SCHEMA_DIAGNOSTIC = "KLD-SCHEMA-001";
    public static final String MIGRATION_DIAGNOSTIC = "KLD-MIGRATION-001";
    public static final Set<String> SEMVER_PUBLIC_SURFACES = Set.of(
            "plugin-behavior",
            "adoption-dsl",
            "safe-defaults",
            "compatibility-matrix",
            "diagnostics",
            "artifact-report",
            "release-evidence-set",
            "mapping-formats");

    private KaleidoVersionContract() {}

    public static void validateTransition(
            String currentVersion, String nextVersion, ChangeKind change) {
        var current = Version.parse(currentVersion);
        var next = Version.parse(nextVersion);
        if (next.compareTo(current) <= 0) {
            throw new IllegalArgumentException(COMPATIBILITY_DIAGNOSTIC
                    + " next version must be greater than the current version");
        }
        if (change == ChangeKind.BREAKING && next.major() <= current.major()) {
            throw new IllegalArgumentException(COMPATIBILITY_DIAGNOSTIC
                    + " breaking public behavior requires a new major version");
        }
        if (change == ChangeKind.ADDITIVE && next.major() != current.major()) {
            throw new IllegalArgumentException(COMPATIBILITY_DIAGNOSTIC
                    + " additive public behavior belongs to the current major family");
        }
        if (change == ChangeKind.ADDITIVE && next.minor() <= current.minor()) {
            throw new IllegalArgumentException(COMPATIBILITY_DIAGNOSTIC
                    + " additive public behavior requires a minor version increment");
        }
        if (change == ChangeKind.FIX && (next.major() != current.major()
                || next.minor() != current.minor())) {
            throw new IllegalArgumentException(COMPATIBILITY_DIAGNOSTIC
                    + " a compatible fix must remain in the current minor line");
        }
    }

    public static void validateDeprecationWindow(DeprecationWindow window) {
        var introduced = Version.parse(window.firstDeprecatedVersion());
        var bridge = Version.parse(window.bridgeVersion());
        var removal = Version.parse(window.removalVersion());
        if (bridge.major() != introduced.major() || bridge.minor() <= introduced.minor()) {
            throw new IllegalArgumentException(DEPRECATION_DIAGNOSTIC
                    + " deprecation must survive the next non-preview minor Bridge Release");
        }
        if (removal.major() <= introduced.major()) {
            throw new IllegalArgumentException(DEPRECATION_DIAGNOSTIC
                    + " deprecated public behavior may be removed only in a later major");
        }
        if (ChronoUnit.DAYS.between(window.firstDeprecatedDate(), window.removalDate()) < 90) {
            throw new IllegalArgumentException(DEPRECATION_DIAGNOSTIC
                    + " deprecation window must retain behavior for at least 90 days");
        }
    }

    public static String bridgeDiagnostic(
            String deprecatedSurface, String replacement, DeprecationWindow window,
            String executableMigration) {
        validateDeprecationWindow(window);
        if (deprecatedSurface.isBlank() || replacement.isBlank()
                || executableMigration.isBlank()) {
            throw new IllegalArgumentException(DEPRECATION_DIAGNOSTIC
                    + " Bridge Release guidance is incomplete");
        }
        return DEPRECATION_DIAGNOSTIC
                + " surface=" + deprecatedSurface
                + " replacement=" + replacement
                + " firstDeprecated=" + window.firstDeprecatedVersion()
                + " bridge=" + window.bridgeVersion()
                + " removal=" + window.removalVersion()
                + " deadline=" + window.removalDate()
                + " migration=" + executableMigration;
    }

    public static String migrationDiagnostic(
            String sourceVersion, String bridgeVersion, String targetVersion, String action) {
        if (action.isBlank()) {
            throw new IllegalArgumentException(MIGRATION_DIAGNOSTIC
                    + " migration action is required");
        }
        return MIGRATION_DIAGNOSTIC + " source=" + sourceVersion
                + " bridge=" + bridgeVersion + " target=" + targetVersion
                + " action=" + action;
    }

    public enum ChangeKind { FIX, ADDITIVE, BREAKING }

    public record DeprecationWindow(
            String firstDeprecatedVersion,
            LocalDate firstDeprecatedDate,
            String bridgeVersion,
            String removalVersion,
            LocalDate removalDate) {}

    record Version(int major, int minor, int patch) implements Comparable<Version> {
        static Version parse(String value) {
            if (value == null || !value.matches(
                    "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")) {
                throw new IllegalArgumentException(COMPATIBILITY_DIAGNOSTIC
                        + " version must be canonical major.minor.patch");
            }
            var parts = value.split("\\.");
            return new Version(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]));
        }

        @Override
        public int compareTo(Version other) {
            var majorOrder = Integer.compare(major, other.major);
            if (majorOrder != 0) return majorOrder;
            var minorOrder = Integer.compare(minor, other.minor);
            return minorOrder != 0 ? minorOrder : Integer.compare(patch, other.patch);
        }
    }
}
