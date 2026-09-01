package com.tongsr.kaleido.gradle.dsl

import org.gradle.api.Named
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import java.nio.charset.StandardCharsets
import java.util.Base64

abstract class KaleidoEscapeHatch protected constructor(
    private val name: String,
) : Named {
    final override fun getName(): String = name

    abstract val reason: Property<String>
    abstract val dimensions: SetProperty<KaleidoProtectionDimension>
    abstract val selectorKind: Property<KaleidoProtectionSelectorKind>
    abstract val selectorValue: Property<String>

    fun exact(identity: String) {
        selectorKind.set(KaleidoProtectionSelectorKind.EXACT)
        selectorValue.set(identity)
    }

    fun prefix(boundedPrefix: String) {
        selectorKind.set(KaleidoProtectionSelectorKind.PREFIX)
        selectorValue.set(boundedPrefix)
    }

    fun canonicalDeclaration(): String {
        val sortedDimensions = dimensions.get().map(Enum<*>::name).sorted()
        return name + "|" + selectorKind.get().name + "|" +
            encode(selectorValue.get()) + "|" + sortedDimensions.joinToString("+") +
            "|" + encode(reason.get())
    }

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
}
