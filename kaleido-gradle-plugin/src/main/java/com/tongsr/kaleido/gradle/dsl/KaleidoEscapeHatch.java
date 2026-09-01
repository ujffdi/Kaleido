package com.tongsr.kaleido.gradle.dsl;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.gradle.api.Named;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;

public abstract class KaleidoEscapeHatch implements Named {
    private final String name;

    protected KaleidoEscapeHatch(String name) {
        this.name = name;
    }

    @Override
    public final String getName() {
        return name;
    }

    public abstract Property<String> getReason();
    public abstract SetProperty<KaleidoProtectionDimension> getDimensions();
    public abstract Property<KaleidoProtectionSelectorKind> getSelectorKind();
    public abstract Property<String> getSelectorValue();

    public final void exact(String identity) {
        getSelectorKind().set(KaleidoProtectionSelectorKind.EXACT);
        getSelectorValue().set(identity);
    }

    public final void prefix(String boundedPrefix) {
        getSelectorKind().set(KaleidoProtectionSelectorKind.PREFIX);
        getSelectorValue().set(boundedPrefix);
    }

    public final String canonicalDeclaration() {
        var dimensions = getDimensions().get().stream().map(Enum::name).sorted().toList();
        return getName() + "|" + getSelectorKind().get().name() + "|"
                + encode(getSelectorValue().get()) + "|" + String.join("+", dimensions)
                + "|" + encode(getReason().get());
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
