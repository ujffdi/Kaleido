package com.tongsr.kaleido.gradle.dsl;

import javax.inject.Inject;

public abstract class KaleidoResourceEscapeHatch extends KaleidoEscapeHatch {
    @Inject
    public KaleidoResourceEscapeHatch(String name) {
        super(name);
    }
}
