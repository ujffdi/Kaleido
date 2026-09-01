package com.tongsr.kaleido.gradle.dsl

import org.gradle.api.provider.Provider
import java.util.Objects

class KaleidoSeed {
    private var configuredProvider: Provider<String>? = null

    fun set(provider: Provider<String>) {
        configuredProvider = Objects.requireNonNull(provider, "provider")
    }

    fun isConfigured(): Boolean = configuredProvider != null

    fun getProvider(): Provider<String> = configuredProvider
        ?: throw IllegalStateException("No explicit Kaleido seed Provider is configured")
}
