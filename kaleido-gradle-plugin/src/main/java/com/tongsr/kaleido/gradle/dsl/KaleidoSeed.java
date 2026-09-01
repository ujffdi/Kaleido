package com.tongsr.kaleido.gradle.dsl;

import java.util.Objects;
import org.gradle.api.provider.Provider;

public final class KaleidoSeed {
    private Provider<String> provider;

    public void set(Provider<String> provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    public boolean isConfigured() {
        return provider != null;
    }

    public Provider<String> getProvider() {
        if (provider == null) {
            throw new IllegalStateException("No explicit Kaleido seed Provider is configured");
        }
        return provider;
    }
}
