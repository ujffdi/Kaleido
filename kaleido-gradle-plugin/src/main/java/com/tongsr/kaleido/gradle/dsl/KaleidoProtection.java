package com.tongsr.kaleido.gradle.dsl;

import javax.inject.Inject;
import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.SetProperty;

public abstract class KaleidoProtection {
    private final NamedDomainObjectContainer<KaleidoClassEscapeHatch> classEscapeHatches;
    private final NamedDomainObjectContainer<KaleidoResourceEscapeHatch> resourceEscapeHatches;

    @Inject
    public KaleidoProtection(ObjectFactory objects) {
        classEscapeHatches = objects.domainObjectContainer(
                KaleidoClassEscapeHatch.class,
                name -> objects.newInstance(KaleidoClassEscapeHatch.class, name));
        resourceEscapeHatches = objects.domainObjectContainer(
                KaleidoResourceEscapeHatch.class,
                name -> objects.newInstance(KaleidoResourceEscapeHatch.class, name));
    }

    public abstract SetProperty<String> getOriginalClassNames();

    public abstract SetProperty<String> getResourceNames();

    public abstract SetProperty<String> getPackagedPaths();

    public final NamedDomainObjectContainer<KaleidoClassEscapeHatch> getClassEscapeHatches() {
        return classEscapeHatches;
    }

    public final NamedDomainObjectContainer<KaleidoResourceEscapeHatch> getResourceEscapeHatches() {
        return resourceEscapeHatches;
    }

    public final void classes(
            String stableId, Action<? super KaleidoClassEscapeHatch> configure) {
        classEscapeHatches.create(stableId, configure);
    }

    public final void resources(
            String stableId, Action<? super KaleidoResourceEscapeHatch> configure) {
        resourceEscapeHatches.create(stableId, configure);
    }
}
