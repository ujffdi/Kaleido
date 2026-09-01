package com.tongsr.kaleido.gradle

import com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension
import com.tongsr.kaleido.gradle.dsl.KaleidoProtectionSelectorKind
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.EnumSet
import org.gradle.api.GradleException

@JvmRecord
internal data class EscapeHatchDeclaration(
    val id: String,
    val selectorKind: KaleidoProtectionSelectorKind,
    val selectorValue: String,
    val dimensions: Set<KaleidoProtectionDimension>,
    val reason: String,
    val kind: Kind,
) {
    enum class Kind {
        CLASS,
        RESOURCE,
    }

    fun matches(identity: String): Boolean =
        if (selectorKind == KaleidoProtectionSelectorKind.EXACT) {
            identity == selectorValue
        } else {
            identity.startsWith(if (kind == Kind.CLASS) normalizedPrefix() else selectorValue)
        }

    fun protects(dimension: KaleidoProtectionDimension): Boolean = dimension in dimensions

    private fun normalizedPrefix(): String =
        if (selectorValue.endsWith(".")) selectorValue else "$selectorValue."

    private fun validate(project: String, variant: String) {
        if (!ID.matcher(id).matches()) {
            throw diagnostic(
                project,
                variant,
                id,
                "Escape Hatch ID is not stable lowercase identifier text",
                "Use 3..64 lowercase letters, digits, dot, underscore, or hyphen",
            )
        }
        if (reason.isBlank() || reason.length > 512) {
            throw diagnostic(
                project,
                variant,
                id,
                "Escape Hatch reason is blank or exceeds 512 characters",
                "Provide one finite human review reason",
            )
        }
        if (dimensions.isEmpty()) {
            throw diagnostic(
                project,
                variant,
                id,
                "Escape Hatch selects no Protection Requirement dimensions",
                "Select the minimum required typed dimensions",
            )
        }
        val allowed = if (kind == Kind.CLASS) {
            EnumSet.of(
                KaleidoProtectionDimension.REACHABILITY,
                KaleidoProtectionDimension.ORIGINAL_IDENTITY,
                KaleidoProtectionDimension.DESCRIPTOR_CLOSURE,
                KaleidoProtectionDimension.RUNTIME_ATTRIBUTES,
            )
        } else {
            EnumSet.of(
                KaleidoProtectionDimension.REACHABILITY,
                KaleidoProtectionDimension.RESOURCE_NAME,
                KaleidoProtectionDimension.PACKAGED_PATH,
            )
        }
        if (!allowed.containsAll(dimensions)) {
            throw diagnostic(
                project,
                variant,
                id,
                "Escape Hatch mixes class and resource protection dimensions",
                "Move the declaration to the matching typed block",
            )
        }
        val legalSelector = if (kind == Kind.CLASS) {
            if (selectorKind == KaleidoProtectionSelectorKind.EXACT) {
                CLASS_EXACT.matcher(selectorValue).matches()
            } else {
                CLASS_PREFIX.matcher(selectorValue).matches()
            }
        } else {
            RESOURCE.matcher(selectorValue).matches()
        }
        if (!legalSelector || selectorValue == "*" || selectorValue.isBlank()) {
            throw diagnostic(
                project,
                variant,
                id,
                "Escape Hatch selector is global, raw, or invalid",
                "Use one legal exact identity or bounded package/resource prefix",
            )
        }
    }

    companion object {
        private val ID = Regex("[a-z][a-z0-9._-]{2,63}").toPattern()
        private val CLASS_EXACT =
            Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+").toPattern()
        private val CLASS_PREFIX =
            Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+\\.?").toPattern()
        private val RESOURCE = Regex("[a-z][a-z0-9_]{1,126}").toPattern()

        fun parse(
            encoded: String,
            kind: Kind,
            project: String,
            variant: String,
        ): EscapeHatchDeclaration {
            try {
                val values = encoded.split("|")
                if (values.size != 5) error("field count")
                val dimensions = EnumSet.noneOf(KaleidoProtectionDimension::class.java)
                if (values[3].isNotEmpty()) {
                    for (dimension in values[3].split("+")) {
                        dimensions.add(KaleidoProtectionDimension.valueOf(dimension))
                    }
                }
                val declaration = EscapeHatchDeclaration(
                    values[0],
                    KaleidoProtectionSelectorKind.valueOf(values[1]),
                    decode(values[2]),
                    dimensions,
                    decode(values[4]),
                    kind,
                )
                declaration.validate(project, variant)
                return declaration
            } catch (failure: RuntimeException) {
                if (failure is GradleException) throw failure
                throw diagnostic(
                    project,
                    variant,
                    "declaration",
                    "Escape Hatch declaration is malformed",
                    "Use a typed exact or bounded prefix declaration",
                )
            }
        }

        private fun decode(value: String): String =
            String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)

        private fun diagnostic(
            project: String,
            variant: String,
            target: String,
            reason: String,
            repair: String,
        ): Nothing {
            throw KaleidoDiagnostic(
                "KLD-PROTECTION-001",
                project,
                variant,
                "protection",
                "kaleido.protection",
                target,
                reason,
                repair,
            ).failure()
        }
    }
}
