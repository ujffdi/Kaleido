package com.tongsr.kaleido.gradle

import org.gradle.api.GradleException

internal data class KaleidoDiagnostic(
    val code: String,
    val project: String,
    val variant: String,
    val stage: String,
    val origin: String,
    val target: String,
    val reason: String,
    val repair: String,
) {
    fun failure(): GradleException = GradleException(render())

    fun render(): String =
        "%s project=%s variant=%s stage=%s origin=%s target=%s reason=%s repair=%s"
            .format(code, project, variant, stage, origin, target, reason, repair)
}
