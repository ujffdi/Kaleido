package com.tongsr.kaleido.gradle.dsl;

import javax.inject.Inject;
import org.gradle.api.Action;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

public abstract class KaleidoGeneration {
    private final KaleidoComposeGeneration compose;

    @Inject
    public KaleidoGeneration(ObjectFactory objects) {
        compose = objects.newInstance(KaleidoComposeGeneration.class);
    }

    public abstract Property<String> getPackageBase();

    public abstract Property<Integer> getPackageCount();

    public abstract Property<Integer> getClassesPerPackage();

    public abstract Property<Integer> getMethodsPerClass();

    public abstract Property<Integer> getLayoutCount();

    public abstract Property<Integer> getDrawableCount();

    public abstract Property<Integer> getStringCount();

    public abstract Property<Integer> getActivityCount();

    public KaleidoComposeGeneration getCompose() {
        return compose;
    }

    public void compose(Action<? super KaleidoComposeGeneration> action) {
        action.execute(compose);
    }
}
