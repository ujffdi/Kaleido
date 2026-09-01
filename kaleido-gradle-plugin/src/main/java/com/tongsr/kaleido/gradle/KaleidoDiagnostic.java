package com.tongsr.kaleido.gradle;

import org.gradle.api.GradleException;

record KaleidoDiagnostic(
        String code,
        String project,
        String variant,
        String stage,
        String origin,
        String target,
        String reason,
        String repair) {
    GradleException failure() {
        return new GradleException(render());
    }

    String render() {
        return "%s project=%s variant=%s stage=%s origin=%s target=%s reason=%s repair=%s"
                .formatted(code, project, variant, stage, origin, target, reason, repair);
    }
}
