package com.tongsr.kaleido.gradle.dsl;

import javax.inject.Inject;

public abstract class KaleidoClassEscapeHatch extends KaleidoEscapeHatch {
    @Inject
    public KaleidoClassEscapeHatch(String name) {
        super(name);
    }
}
