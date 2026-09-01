package com.tongsr.kaleido.gradle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import kotlin.Metadata;
import kotlin.metadata.KmClass;
import kotlin.metadata.KmClassifier;
import kotlin.metadata.KmConstructor;
import kotlin.metadata.KmDeclarationContainer;
import kotlin.metadata.KmFunction;
import kotlin.metadata.KmPackage;
import kotlin.metadata.KmProperty;
import kotlin.metadata.KmType;
import kotlin.metadata.KmTypeAlias;
import kotlin.metadata.KmTypeParameter;
import kotlin.metadata.KmValueParameter;
import kotlin.metadata.jvm.JvmExtensionsKt;
import kotlin.metadata.jvm.JvmFieldSignature;
import kotlin.metadata.jvm.JvmMethodSignature;
import kotlin.metadata.jvm.KotlinClassHeader;
import kotlin.metadata.jvm.KotlinClassMetadata;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Remapper;

final class KotlinMetadataRewriter {
    private KotlinMetadataRewriter() {}

    static Metadata rewrite(byte[] classBytes, Map<String, String> dottedMapping, Remapper remapper) {
        var header = readHeader(classBytes);
        if (header == null) {
            return null;
        }
        var metadata = KotlinClassMetadata.readStrict(header);
        var names = new HashMap<String, String>();
        dottedMapping.forEach((original, target) -> names.put(
                original.replace('.', '/').replace('$', '.'),
                target.replace('.', '/').replace('$', '.')));
        if (metadata instanceof KotlinClassMetadata.Class classMetadata) {
            remapClass(classMetadata.getKmClass(), names, remapper);
        } else if (metadata instanceof KotlinClassMetadata.FileFacade fileFacade) {
            remapContainer(fileFacade.getKmPackage(), names, remapper);
        } else if (metadata instanceof KotlinClassMetadata.MultiFileClassPart part) {
            remapContainer(part.getKmPackage(), names, remapper);
            part.setFacadeClassName(remapJvmInternal(part.getFacadeClassName(), dottedMapping));
        } else if (metadata instanceof KotlinClassMetadata.MultiFileClassFacade facade) {
            facade.setPartClassNames(facade.getPartClassNames().stream()
                    .map(name -> remapJvmInternal(name, dottedMapping)).toList());
        } else if (metadata instanceof KotlinClassMetadata.SyntheticClass synthetic
                && synthetic.isLambda()) {
            remapFunction(synthetic.getKmLambda().getFunction(), names, remapper);
        }
        return metadata.write();
    }

    static Set<String> associatedClassNames(byte[] classBytes) {
        var header = readHeader(classBytes);
        if (header == null) return Set.of();
        var metadata = KotlinClassMetadata.readStrict(header);
        var names = new TreeSet<String>();
        var owner = new ClassReader(classBytes).getClassName().replace('/', '.');
        if (metadata instanceof KotlinClassMetadata.Class classMetadata) {
            classMetadata.getKmClass().getNestedClasses().forEach(
                    name -> names.add(owner + "$" + name));
            if (classMetadata.getKmClass().getCompanionObject() != null) {
                names.add(owner + "$" + classMetadata.getKmClass().getCompanionObject());
            }
        } else if (metadata instanceof KotlinClassMetadata.MultiFileClassPart part) {
            names.add(part.getFacadeClassName().replace('/', '.'));
        } else if (metadata instanceof KotlinClassMetadata.MultiFileClassFacade facade) {
            facade.getPartClassNames().forEach(name -> names.add(name.replace('/', '.')));
        }
        return Set.copyOf(names);
    }

    static ClassVisitor replacingVisitor(ClassVisitor delegate, Metadata metadata) {
        if (metadata == null) {
            return delegate;
        }
        return new ClassVisitor(Opcodes.ASM9, delegate) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if ("Lkotlin/Metadata;".equals(descriptor)) {
                    return null;
                }
                return super.visitAnnotation(descriptor, visible);
            }

            @Override
            public void visitEnd() {
                writeMetadata(super.visitAnnotation("Lkotlin/Metadata;", true), metadata);
                super.visitEnd();
            }
        };
    }

    static void validate(byte[] classBytes) {
        var header = readHeader(classBytes);
        if (header != null) {
            KotlinClassMetadata.readStrict(header);
        }
    }

    private static void remapClass(
            KmClass kmClass, Map<String, String> names, Remapper remapper) {
        var originalName = kmClass.getName();
        var mappedName = names.getOrDefault(originalName, originalName);
        kmClass.setName(mappedName);
        if (kmClass.getCompanionObject() != null) {
            kmClass.setCompanionObject(remapNestedShortName(
                    originalName, mappedName, kmClass.getCompanionObject(), names));
        }
        replace(kmClass.getNestedClasses(), name ->
                remapNestedShortName(originalName, mappedName, name, names));
        replace(kmClass.getSealedSubclasses(), name -> names.getOrDefault(name, name));
        remapTypes(kmClass.getSupertypes(), names);
        remapTypeParameters(kmClass.getTypeParameters(), names);
        kmClass.getConstructors().forEach(constructor -> remapConstructor(constructor, names, remapper));
        remapContainer(kmClass, names, remapper);
        if (kmClass.getInlineClassUnderlyingType() != null) {
            remapType(kmClass.getInlineClassUnderlyingType(), names);
        }
        remapTypes(kmClass.getContextReceiverTypes(), names);
    }

    private static void remapContainer(
            KmDeclarationContainer container, Map<String, String> names, Remapper remapper) {
        container.getFunctions().forEach(function -> remapFunction(function, names, remapper));
        container.getProperties().forEach(property -> remapProperty(property, names, remapper));
        container.getTypeAliases().forEach(alias -> remapTypeAlias(alias, names));
    }

    private static void remapFunction(
            KmFunction function, Map<String, String> names, Remapper remapper) {
        remapType(function.getReturnType(), names);
        remapNullableType(function.getReceiverParameterType(), names);
        remapTypes(function.getContextReceiverTypes(), names);
        remapTypeParameters(function.getTypeParameters(), names);
        function.getValueParameters().forEach(parameter -> remapValue(parameter, names));
        function.getContextParameters().forEach(parameter -> remapValue(parameter, names));
        var signature = JvmExtensionsKt.getSignature(function);
        if (signature != null) {
            JvmExtensionsKt.setSignature(function, new JvmMethodSignature(
                    signature.getName(), remapper.mapMethodDesc(signature.getDescriptor())));
        }
    }

    private static void remapProperty(
            KmProperty property, Map<String, String> names, Remapper remapper) {
        remapType(property.getReturnType(), names);
        remapNullableType(property.getReceiverParameterType(), names);
        remapTypes(property.getContextReceiverTypes(), names);
        remapTypeParameters(property.getTypeParameters(), names);
        property.getContextParameters().forEach(parameter -> remapValue(parameter, names));
        if (property.getSetterParameter() != null) {
            remapValue(property.getSetterParameter(), names);
        }
        var field = JvmExtensionsKt.getFieldSignature(property);
        if (field != null) {
            JvmExtensionsKt.setFieldSignature(property, new JvmFieldSignature(
                    field.getName(), remapper.mapDesc(field.getDescriptor())));
        }
        remapMethodSignature(property, JvmExtensionsKt.getGetterSignature(property),
                signature -> JvmExtensionsKt.setGetterSignature(property, signature), remapper);
        remapMethodSignature(property, JvmExtensionsKt.getSetterSignature(property),
                signature -> JvmExtensionsKt.setSetterSignature(property, signature), remapper);
        remapMethodSignature(property, JvmExtensionsKt.getSyntheticMethodForAnnotations(property),
                signature -> JvmExtensionsKt.setSyntheticMethodForAnnotations(property, signature),
                remapper);
        remapMethodSignature(property, JvmExtensionsKt.getSyntheticMethodForDelegate(property),
                signature -> JvmExtensionsKt.setSyntheticMethodForDelegate(property, signature),
                remapper);
    }

    private static void remapMethodSignature(
            KmProperty property,
            JvmMethodSignature signature,
            java.util.function.Consumer<JvmMethodSignature> setter,
            Remapper remapper) {
        if (signature != null) {
            setter.accept(new JvmMethodSignature(
                    signature.getName(), remapper.mapMethodDesc(signature.getDescriptor())));
        }
    }

    private static void remapConstructor(
            KmConstructor constructor, Map<String, String> names, Remapper remapper) {
        constructor.getValueParameters().forEach(parameter -> remapValue(parameter, names));
        var signature = JvmExtensionsKt.getSignature(constructor);
        if (signature != null) {
            JvmExtensionsKt.setSignature(constructor, new JvmMethodSignature(
                    signature.getName(), remapper.mapMethodDesc(signature.getDescriptor())));
        }
    }

    private static void remapTypeAlias(KmTypeAlias alias, Map<String, String> names) {
        remapType(alias.getUnderlyingType(), names);
        remapType(alias.getExpandedType(), names);
        remapTypeParameters(alias.getTypeParameters(), names);
    }

    private static void remapValue(KmValueParameter parameter, Map<String, String> names) {
        remapType(parameter.getType(), names);
        remapNullableType(parameter.getVarargElementType(), names);
    }

    private static void remapTypeParameters(
            List<KmTypeParameter> parameters, Map<String, String> names) {
        parameters.forEach(parameter -> remapTypes(parameter.getUpperBounds(), names));
    }

    private static void remapTypes(List<KmType> types, Map<String, String> names) {
        types.forEach(type -> remapType(type, names));
    }

    private static void remapNullableType(KmType type, Map<String, String> names) {
        if (type != null) {
            remapType(type, names);
        }
    }

    private static void remapType(KmType type, Map<String, String> names) {
        var classifier = type.getClassifier();
        if (classifier instanceof KmClassifier.Class classClassifier) {
            var name = classClassifier.getName();
            type.setClassifier(new KmClassifier.Class(names.getOrDefault(name, name)));
        } else if (classifier instanceof KmClassifier.TypeAlias aliasClassifier) {
            var name = aliasClassifier.getName();
            type.setClassifier(new KmClassifier.TypeAlias(names.getOrDefault(name, name)));
        }
        type.getArguments().forEach(argument -> remapNullableType(argument.getType(), names));
        remapNullableType(type.getAbbreviatedType(), names);
        remapNullableType(type.getOuterType(), names);
        if (type.getFlexibleTypeUpperBound() != null) {
            remapType(type.getFlexibleTypeUpperBound().getType(), names);
        }
    }

    private static String remapNestedShortName(
            String originalOwner, String mappedOwner, String shortName,
            Map<String, String> names) {
        var mapped = names.getOrDefault(originalOwner + "." + shortName,
                originalOwner + "." + shortName);
        return mapped.startsWith(mappedOwner + ".")
                ? mapped.substring(mappedOwner.length() + 1)
                : shortName;
    }

    private static String remapJvmInternal(String name, Map<String, String> mapping) {
        var dotted = name.replace('/', '.');
        return mapping.getOrDefault(dotted, dotted).replace('.', '/');
    }

    private static <T> void replace(List<T> values, java.util.function.Function<T, T> mapper) {
        for (var index = 0; index < values.size(); index++) {
            values.set(index, mapper.apply(values.get(index)));
        }
    }

    private static Metadata readHeader(byte[] classBytes) {
        var reader = new MetadataAnnotationReader();
        new ClassReader(classBytes).accept(reader,
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return reader.header();
    }

    private static void writeMetadata(AnnotationVisitor visitor, Metadata metadata) {
        visitor.visit("k", metadata.k());
        visitor.visit("mv", metadata.mv());
        writeArray(visitor, "d1", metadata.d1());
        writeArray(visitor, "d2", metadata.d2());
        if (!metadata.xs().isEmpty()) visitor.visit("xs", metadata.xs());
        if (!metadata.pn().isEmpty()) visitor.visit("pn", metadata.pn());
        visitor.visit("xi", metadata.xi());
        visitor.visitEnd();
    }

    private static void writeArray(AnnotationVisitor visitor, String name, String[] values) {
        var array = visitor.visitArray(name);
        for (var value : values) array.visit(null, value);
        array.visitEnd();
    }

    private static final class MetadataAnnotationReader extends ClassVisitor {
        private int kind;
        private int[] metadataVersion = new int[0];
        private final List<String> data1 = new ArrayList<>();
        private final List<String> data2 = new ArrayList<>();
        private String extraString = "";
        private String packageName = "";
        private int extraInt;
        private boolean found;

        private MetadataAnnotationReader() {
            super(Opcodes.ASM9);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (!"Lkotlin/Metadata;".equals(descriptor)) return null;
            found = true;
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override public void visit(String name, Object value) {
                    switch (name) {
                        case "k" -> kind = (Integer) value;
                        case "mv" -> metadataVersion = (int[]) value;
                        case "xs" -> extraString = (String) value;
                        case "pn" -> packageName = (String) value;
                        case "xi" -> extraInt = (Integer) value;
                        default -> { }
                    }
                }

                @Override public AnnotationVisitor visitArray(String name) {
                    var destination = name.equals("d1") ? data1 : name.equals("d2") ? data2 : null;
                    return destination == null ? null : new AnnotationVisitor(Opcodes.ASM9) {
                        @Override public void visit(String ignored, Object value) {
                            destination.add((String) value);
                        }
                    };
                }
            };
        }

        private Metadata header() {
            return found ? new KotlinClassHeader(kind, metadataVersion,
                    data1.toArray(String[]::new), data2.toArray(String[]::new),
                    extraString, packageName, extraInt) : null;
        }
    }
}
