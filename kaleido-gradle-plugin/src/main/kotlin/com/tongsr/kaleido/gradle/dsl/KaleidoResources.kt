package com.tongsr.kaleido.gradle.dsl

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

abstract class KaleidoResources {
    abstract val nativeLibrariesToDelete: SetProperty<String>
    abstract val metadataToDelete: SetProperty<String>
    abstract val replaceUnusedStrings: Property<Boolean>
    abstract val confirmedUnusedStringsFile: RegularFileProperty
    abstract val retainedLanguages: SetProperty<String>
}
