package com.tongsr.kaleido.gradle.dsl;

import javax.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import org.gradle.api.Action;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

public abstract class KaleidoExtension {
    private final KaleidoSeed seed = new KaleidoSeed();
    private final KaleidoGeneration generation;
    private final KaleidoResources resources;
    private final KaleidoProtection protection;
    private final KaleidoSigning signing;
    private final ObjectFactory objects;
    private final Map<String, KaleidoSigning> variantSigning = new LinkedHashMap<>();

    @Inject
    public KaleidoExtension(ObjectFactory objects) {
        this.objects = objects;
        generation = objects.newInstance(KaleidoGeneration.class);
        resources = objects.newInstance(KaleidoResources.class);
        protection = objects.newInstance(KaleidoProtection.class);
        signing = objects.newInstance(KaleidoSigning.class);
    }

    public abstract Property<KaleidoProfile> getProfile();

    public KaleidoSeed getSeed() {
        return seed;
    }

    public KaleidoGeneration getGeneration() {
        return generation;
    }

    public KaleidoResources getResources() {
        return resources;
    }

    public KaleidoProtection getProtection() {
        return protection;
    }

    public KaleidoSigning getSigning() {
        return signing;
    }

    public void generation(Action<? super KaleidoGeneration> action) {
        action.execute(generation);
    }

    public void resources(Action<? super KaleidoResources> action) {
        action.execute(resources);
    }

    public void protection(Action<? super KaleidoProtection> action) {
        action.execute(protection);
    }

    public void signing(Action<? super KaleidoSigning> action) {
        action.execute(signing);
    }

    public void signing(String exactVariantName, Action<? super KaleidoSigning> action) {
        if (exactVariantName == null || !exactVariantName.matches("[A-Za-z][A-Za-z0-9]*")) {
            throw new IllegalArgumentException("Exact signing variant name is invalid");
        }
        var value = variantSigning.computeIfAbsent(exactVariantName,
                ignored -> objects.newInstance(KaleidoSigning.class));
        action.execute(value);
    }

    public Map<String, KaleidoSigning> getVariantSigning() {
        return Map.copyOf(variantSigning);
    }
}
