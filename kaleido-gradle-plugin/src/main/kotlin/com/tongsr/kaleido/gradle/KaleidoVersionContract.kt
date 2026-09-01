package com.tongsr.kaleido.gradle

import java.time.LocalDate
import java.time.temporal.ChronoUnit

object KaleidoVersionContract {
    const val PUBLIC_BASELINE_VERSION: String = "1.0.0"
    const val COMPATIBILITY_DIAGNOSTIC: String = "KLD-COMPAT-001"
    const val DEPRECATION_DIAGNOSTIC: String = "KLD-DEPRECATION-001"
    const val SCHEMA_DIAGNOSTIC: String = "KLD-SCHEMA-001"
    const val MIGRATION_DIAGNOSTIC: String = "KLD-MIGRATION-001"
    @JvmField
    val SEMVER_PUBLIC_SURFACES: Set<String> = setOf(
        "plugin-behavior",
        "adoption-dsl",
        "safe-defaults",
        "compatibility-matrix",
        "diagnostics",
        "artifact-report",
        "release-evidence-set",
        "mapping-formats",
    )

    @JvmStatic
    fun validateTransition(currentVersion: String, nextVersion: String, change: ChangeKind) {
        val current = Version.parse(currentVersion)
        val next = Version.parse(nextVersion)
        require(next > current) {
            "$COMPATIBILITY_DIAGNOSTIC next version must be greater than the current version"
        }
        if (change == ChangeKind.BREAKING) {
            require(next.major > current.major) {
                "$COMPATIBILITY_DIAGNOSTIC breaking public behavior requires a new major version"
            }
        }
        if (change == ChangeKind.ADDITIVE) {
            require(next.major == current.major) {
                "$COMPATIBILITY_DIAGNOSTIC additive public behavior belongs to the current major family"
            }
            require(next.minor > current.minor) {
                "$COMPATIBILITY_DIAGNOSTIC additive public behavior requires a minor version increment"
            }
        }
        if (change == ChangeKind.FIX) {
            require(next.major == current.major && next.minor == current.minor) {
                "$COMPATIBILITY_DIAGNOSTIC a compatible fix must remain in the current minor line"
            }
        }
    }

    @JvmStatic
    fun validateDeprecationWindow(window: DeprecationWindow) {
        val introduced = Version.parse(window.firstDeprecatedVersion)
        val bridge = Version.parse(window.bridgeVersion)
        val removal = Version.parse(window.removalVersion)
        require(bridge.major == introduced.major && bridge.minor > introduced.minor) {
            "$DEPRECATION_DIAGNOSTIC deprecation must survive the next non-preview minor Bridge Release"
        }
        require(removal.major > introduced.major) {
            "$DEPRECATION_DIAGNOSTIC deprecated public behavior may be removed only in a later major"
        }
        require(
            ChronoUnit.DAYS.between(window.firstDeprecatedDate, window.removalDate) >= 90,
        ) {
            "$DEPRECATION_DIAGNOSTIC deprecation window must retain behavior for at least 90 days"
        }
    }

    @JvmStatic
    fun bridgeDiagnostic(
        deprecatedSurface: String,
        replacement: String,
        window: DeprecationWindow,
        executableMigration: String,
    ): String {
        validateDeprecationWindow(window)
        require(
            deprecatedSurface.isNotBlank() &&
                replacement.isNotBlank() &&
                executableMigration.isNotBlank(),
        ) {
            "$DEPRECATION_DIAGNOSTIC Bridge Release guidance is incomplete"
        }
        return DEPRECATION_DIAGNOSTIC +
            " surface=" + deprecatedSurface +
            " replacement=" + replacement +
            " firstDeprecated=" + window.firstDeprecatedVersion +
            " bridge=" + window.bridgeVersion +
            " removal=" + window.removalVersion +
            " deadline=" + window.removalDate +
            " migration=" + executableMigration
    }

    @JvmStatic
    fun migrationDiagnostic(
        sourceVersion: String,
        bridgeVersion: String,
        targetVersion: String,
        action: String,
    ): String {
        require(action.isNotBlank()) { "$MIGRATION_DIAGNOSTIC migration action is required" }
        return "$MIGRATION_DIAGNOSTIC source=$sourceVersion" +
            " bridge=$bridgeVersion target=$targetVersion" +
            " action=$action"
    }

    enum class ChangeKind {
        FIX,
        ADDITIVE,
        BREAKING,
    }

    @JvmRecord
    data class DeprecationWindow(
        val firstDeprecatedVersion: String,
        val firstDeprecatedDate: LocalDate,
        val bridgeVersion: String,
        val removalVersion: String,
        val removalDate: LocalDate,
    )

    @JvmRecord
    internal data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
        override fun compareTo(other: Version): Int =
            compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

        companion object {
            fun parse(value: String): Version {
                require(value.matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))) {
                    "$COMPATIBILITY_DIAGNOSTIC version must be canonical major.minor.patch"
                }
                val parts = value.split('.')
                return Version(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
            }
        }
    }
}
