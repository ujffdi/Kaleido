package com.tongsr.kaleido.gradle.dsl;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;

public abstract class KaleidoResources {
    public abstract SetProperty<String> getNativeLibrariesToDelete();

    public abstract SetProperty<String> getMetadataToDelete();

    public abstract Property<Boolean> getReplaceUnusedStrings();

    public abstract RegularFileProperty getConfirmedUnusedStringsFile();

    public abstract SetProperty<String> getRetainedLanguages();
}
