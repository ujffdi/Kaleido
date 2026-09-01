package com.tongsr.kaleido.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Validation has no reusable output")
abstract class KaleidoValidateAdoptionTask : DefaultTask() {
    @get:Input
    abstract val consumerProjectPath: Property<String>

    @get:Input
    abstract val eligibleVariants: ListProperty<String>

    @TaskAction
    fun validateAdoption() {
        if (eligibleVariants.get().isEmpty()) {
            throw AdoptionValidator.noEligibleVariant(consumerProjectPath.get()).failure()
        }
    }
}
