package com.tongsr.kaleido.gradle.dsl

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class KaleidoGeneration @Inject constructor(objects: ObjectFactory) {
    abstract val packageBase: Property<String>
    abstract val packageCount: Property<Int>
    abstract val classesPerPackage: Property<Int>
    abstract val methodsPerClass: Property<Int>
    abstract val layoutCount: Property<Int>
    abstract val drawableCount: Property<Int>
    abstract val stringCount: Property<Int>
    abstract val activityCount: Property<Int>

    val compose: KaleidoComposeGeneration = objects.newInstance(KaleidoComposeGeneration::class.java)

    fun compose(action: Action<in KaleidoComposeGeneration>) {
        action.execute(compose)
    }
}
