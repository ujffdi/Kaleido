package com.tongsr.kaleido.gradle.dsl

import org.gradle.api.provider.Property

abstract class KaleidoComposeGeneration {
    abstract val enabled: Property<Boolean>
    abstract val fileCount: Property<Int>
    abstract val functionsPerFile: Property<Int>
}
