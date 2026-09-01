package com.tongsr.kaleido.gradle.dsl

import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.SetProperty
import javax.inject.Inject

abstract class KaleidoProtection @Inject constructor(objects: ObjectFactory) {
    abstract val originalClassNames: SetProperty<String>
    abstract val resourceNames: SetProperty<String>
    abstract val packagedPaths: SetProperty<String>

    val classEscapeHatches: NamedDomainObjectContainer<KaleidoClassEscapeHatch> =
        objects.domainObjectContainer(KaleidoClassEscapeHatch::class.java) { name ->
            objects.newInstance(KaleidoClassEscapeHatch::class.java, name)
        }
    val resourceEscapeHatches: NamedDomainObjectContainer<KaleidoResourceEscapeHatch> =
        objects.domainObjectContainer(KaleidoResourceEscapeHatch::class.java) { name ->
            objects.newInstance(KaleidoResourceEscapeHatch::class.java, name)
        }

    fun classes(stableId: String, configure: Action<in KaleidoClassEscapeHatch>) {
        classEscapeHatches.create(stableId, configure)
    }

    fun resources(stableId: String, configure: Action<in KaleidoResourceEscapeHatch>) {
        resourceEscapeHatches.create(stableId, configure)
    }
}
