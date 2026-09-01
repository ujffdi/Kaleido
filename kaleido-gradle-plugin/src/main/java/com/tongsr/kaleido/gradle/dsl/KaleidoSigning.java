package com.tongsr.kaleido.gradle.dsl;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

public abstract class KaleidoSigning {
    public abstract RegularFileProperty getKeyStoreFile();

    public abstract Property<String> getStorePassword();

    public abstract Property<String> getKeyAlias();

    public abstract Property<String> getKeyPassword();

    public abstract Property<String> getExpectedCertificateSha256();
}
