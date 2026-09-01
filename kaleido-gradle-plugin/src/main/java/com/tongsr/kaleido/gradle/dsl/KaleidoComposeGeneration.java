package com.tongsr.kaleido.gradle.dsl;

import org.gradle.api.provider.Property;

public abstract class KaleidoComposeGeneration {
    public abstract Property<Boolean> getEnabled();

    public abstract Property<Integer> getFileCount();

    public abstract Property<Integer> getFunctionsPerFile();
}
