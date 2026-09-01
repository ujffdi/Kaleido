package com.tongsr.kaleido.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "Validation has no reusable output")
public abstract class KaleidoValidateAdoptionTask extends DefaultTask {
    @Input
    public abstract Property<String> getConsumerProjectPath();

    @Input
    public abstract ListProperty<String> getEligibleVariants();

    @TaskAction
    public void validateAdoption() {
        if (getEligibleVariants().get().isEmpty()) {
            throw AdoptionValidator.noEligibleVariant(getConsumerProjectPath().get()).failure();
        }
    }
}
