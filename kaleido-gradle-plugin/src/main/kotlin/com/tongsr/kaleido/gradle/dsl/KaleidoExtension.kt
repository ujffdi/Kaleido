package com.tongsr.kaleido.gradle.dsl

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import java.util.LinkedHashMap
import java.util.Collections
import javax.inject.Inject

abstract class KaleidoExtension @Inject constructor(
    private val objects: ObjectFactory,
) {
    abstract val profile: Property<KaleidoProfile>

    val seed = KaleidoSeed()
    val generation: KaleidoGeneration = objects.newInstance(KaleidoGeneration::class.java)
    val resources: KaleidoResources = objects.newInstance(KaleidoResources::class.java)
    val protection: KaleidoProtection = objects.newInstance(KaleidoProtection::class.java)
    val signing: KaleidoSigning = objects.newInstance(KaleidoSigning::class.java)
    private val mutableVariantSigning = LinkedHashMap<String, KaleidoSigning>()

    fun generation(action: Action<in KaleidoGeneration>) = action.execute(generation)
    fun resources(action: Action<in KaleidoResources>) = action.execute(resources)
    fun protection(action: Action<in KaleidoProtection>) = action.execute(protection)
    fun signing(action: Action<in KaleidoSigning>) = action.execute(signing)

    fun signing(exactVariantName: String?, action: Action<in KaleidoSigning>) {
        require(exactVariantName != null && exactVariantName.matches(Regex("[A-Za-z][A-Za-z0-9]*"))) {
            "Exact signing variant name is invalid"
        }
        val value = mutableVariantSigning.computeIfAbsent(exactVariantName) {
            objects.newInstance(KaleidoSigning::class.java)
        }
        action.execute(value)
    }

    val variantSigning: Map<String, KaleidoSigning>
        get() = Collections.unmodifiableMap(LinkedHashMap(mutableVariantSigning))
}
