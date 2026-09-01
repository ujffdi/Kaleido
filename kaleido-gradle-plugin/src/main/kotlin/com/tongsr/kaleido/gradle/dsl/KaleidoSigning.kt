package com.tongsr.kaleido.gradle.dsl

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property

abstract class KaleidoSigning {
    abstract val keyStoreFile: RegularFileProperty
    abstract val storePassword: Property<String>
    abstract val keyAlias: Property<String>
    abstract val keyPassword: Property<String>
    abstract val expectedCertificateSha256: Property<String>
}
