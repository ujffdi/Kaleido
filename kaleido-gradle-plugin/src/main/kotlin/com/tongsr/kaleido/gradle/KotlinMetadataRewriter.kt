package com.tongsr.kaleido.gradle

import java.util.TreeSet
import kotlin.ExperimentalContextParameters
import kotlin.Metadata
import kotlin.metadata.ExperimentalContextReceivers
import kotlin.metadata.KmClass
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmConstructor
import kotlin.metadata.KmDeclarationContainer
import kotlin.metadata.KmFunction
import kotlin.metadata.KmProperty
import kotlin.metadata.KmType
import kotlin.metadata.KmTypeAlias
import kotlin.metadata.KmTypeParameter
import kotlin.metadata.KmValueParameter
import kotlin.metadata.jvm.JvmFieldSignature
import kotlin.metadata.jvm.JvmMethodSignature
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.fieldSignature
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.setterSignature
import kotlin.metadata.jvm.signature
import kotlin.metadata.jvm.syntheticMethodForAnnotations
import kotlin.metadata.jvm.syntheticMethodForDelegate
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.Remapper

@OptIn(ExperimentalContextReceivers::class, ExperimentalContextParameters::class)
@Suppress("DEPRECATION", "DEPRECATION_ERROR")
internal object KotlinMetadataRewriter {
    @JvmStatic
    fun rewrite(
        classBytes: ByteArray,
        dottedMapping: Map<String, String>,
        remapper: Remapper,
    ): Metadata? {
        val header = readHeader(classBytes) ?: return null
        val metadata = KotlinClassMetadata.readStrict(header)
        val names = HashMap<String, String>()
        dottedMapping.forEach { (original, target) ->
            names[original.replace('.', '/').replace('$', '.')] =
                target.replace('.', '/').replace('$', '.')
        }
        when (metadata) {
            is KotlinClassMetadata.Class -> remapClass(metadata.kmClass, names, remapper)
            is KotlinClassMetadata.FileFacade -> remapContainer(metadata.kmPackage, names, remapper)
            is KotlinClassMetadata.MultiFileClassPart -> {
                remapContainer(metadata.kmPackage, names, remapper)
                metadata.facadeClassName = remapJvmInternal(metadata.facadeClassName, dottedMapping)
            }
            is KotlinClassMetadata.MultiFileClassFacade -> {
                metadata.partClassNames = metadata.partClassNames.map { name ->
                    remapJvmInternal(name, dottedMapping)
                }
            }
            is KotlinClassMetadata.SyntheticClass -> if (metadata.isLambda) {
                remapFunction(metadata.kmLambda!!.function, names, remapper)
            }
            else -> {}
        }
        return metadata.write()
    }

    @JvmStatic
    fun associatedClassNames(classBytes: ByteArray): Set<String> {
        val header = readHeader(classBytes) ?: return emptySet()
        val metadata = KotlinClassMetadata.readStrict(header)
        val names = TreeSet<String>()
        val owner = ClassReader(classBytes).className.replace('/', '.')
        when (metadata) {
            is KotlinClassMetadata.Class -> {
                metadata.kmClass.nestedClasses.forEach { name ->
                    names.add(owner + "$" + name)
                }
                val companion = metadata.kmClass.companionObject
                if (companion != null) {
                    names.add(owner + "$" + companion)
                }
            }
            is KotlinClassMetadata.MultiFileClassPart -> {
                names.add(metadata.facadeClassName.replace('/', '.'))
            }
            is KotlinClassMetadata.MultiFileClassFacade -> {
                metadata.partClassNames.forEach { name -> names.add(name.replace('/', '.')) }
            }
            else -> {}
        }
        return names.toSet()
    }

    @JvmStatic
    fun replacingVisitor(delegate: ClassVisitor, metadata: Metadata?): ClassVisitor {
        if (metadata == null) return delegate
        return object : ClassVisitor(Opcodes.ASM9, delegate) {
            override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
                if (descriptor == "Lkotlin/Metadata;") return null
                return super.visitAnnotation(descriptor, visible)
            }

            override fun visitEnd() {
                writeMetadata(super.visitAnnotation("Lkotlin/Metadata;", true), metadata)
                super.visitEnd()
            }
        }
    }

    @JvmStatic
    fun validate(classBytes: ByteArray) {
        val header = readHeader(classBytes)
        if (header != null) {
            KotlinClassMetadata.readStrict(header)
        }
    }

    private fun remapClass(kmClass: KmClass, names: Map<String, String>, remapper: Remapper) {
        val originalName = kmClass.name
        val mappedName = names.getOrDefault(originalName, originalName)
        kmClass.name = mappedName
        val companion = kmClass.companionObject
        if (companion != null) {
            kmClass.companionObject = remapNestedShortName(originalName, mappedName, companion, names)
        }
        replace(kmClass.nestedClasses) { name ->
            remapNestedShortName(originalName, mappedName, name, names)
        }
        replace(kmClass.sealedSubclasses) { name -> names.getOrDefault(name, name) }
        remapTypes(kmClass.supertypes, names)
        remapTypeParameters(kmClass.typeParameters, names)
        kmClass.constructors.forEach { constructor -> remapConstructor(constructor, names, remapper) }
        remapContainer(kmClass, names, remapper)
        val underlying = kmClass.inlineClassUnderlyingType
        if (underlying != null) {
            remapType(underlying, names)
        }
        remapTypes(kmClass.contextReceiverTypes, names)
    }

    private fun remapContainer(
        container: KmDeclarationContainer,
        names: Map<String, String>,
        remapper: Remapper,
    ) {
        container.functions.forEach { function -> remapFunction(function, names, remapper) }
        container.properties.forEach { property -> remapProperty(property, names, remapper) }
        container.typeAliases.forEach { alias -> remapTypeAlias(alias, names) }
    }

    private fun remapFunction(
        function: KmFunction,
        names: Map<String, String>,
        remapper: Remapper,
    ) {
        remapType(function.returnType, names)
        remapNullableType(function.receiverParameterType, names)
        remapTypes(function.contextReceiverTypes, names)
        remapTypeParameters(function.typeParameters, names)
        function.valueParameters.forEach { parameter -> remapValue(parameter, names) }
        function.contextParameters.forEach { parameter -> remapValue(parameter, names) }
        val signature = function.signature
        if (signature != null) {
            function.signature = JvmMethodSignature(
                signature.name,
                remapper.mapMethodDesc(signature.descriptor),
            )
        }
    }

    private fun remapProperty(
        property: KmProperty,
        names: Map<String, String>,
        remapper: Remapper,
    ) {
        remapType(property.returnType, names)
        remapNullableType(property.receiverParameterType, names)
        remapTypes(property.contextReceiverTypes, names)
        remapTypeParameters(property.typeParameters, names)
        property.contextParameters.forEach { parameter -> remapValue(parameter, names) }
        val setterParameter = property.setterParameter
        if (setterParameter != null) {
            remapValue(setterParameter, names)
        }
        val field = property.fieldSignature
        if (field != null) {
            property.fieldSignature = JvmFieldSignature(
                field.name,
                remapper.mapDesc(field.descriptor),
            )
        }
        remapMethodSignature(property.getterSignature, { property.getterSignature = it }, remapper)
        remapMethodSignature(property.setterSignature, { property.setterSignature = it }, remapper)
        remapMethodSignature(
            property.syntheticMethodForAnnotations,
            { property.syntheticMethodForAnnotations = it },
            remapper,
        )
        remapMethodSignature(
            property.syntheticMethodForDelegate,
            { property.syntheticMethodForDelegate = it },
            remapper,
        )
    }

    private fun remapMethodSignature(
        signature: JvmMethodSignature?,
        setter: (JvmMethodSignature) -> Unit,
        remapper: Remapper,
    ) {
        if (signature != null) {
            setter(JvmMethodSignature(signature.name, remapper.mapMethodDesc(signature.descriptor)))
        }
    }

    private fun remapConstructor(
        constructor: KmConstructor,
        names: Map<String, String>,
        remapper: Remapper,
    ) {
        constructor.valueParameters.forEach { parameter -> remapValue(parameter, names) }
        val signature = constructor.signature
        if (signature != null) {
            constructor.signature = JvmMethodSignature(
                signature.name,
                remapper.mapMethodDesc(signature.descriptor),
            )
        }
    }

    private fun remapTypeAlias(alias: KmTypeAlias, names: Map<String, String>) {
        remapType(alias.underlyingType, names)
        remapType(alias.expandedType, names)
        remapTypeParameters(alias.typeParameters, names)
    }

    private fun remapValue(parameter: KmValueParameter, names: Map<String, String>) {
        remapType(parameter.type, names)
        remapNullableType(parameter.varargElementType, names)
    }

    private fun remapTypeParameters(
        parameters: List<KmTypeParameter>,
        names: Map<String, String>,
    ) {
        parameters.forEach { parameter -> remapTypes(parameter.upperBounds, names) }
    }

    private fun remapTypes(types: List<KmType>, names: Map<String, String>) {
        types.forEach { type -> remapType(type, names) }
    }

    private fun remapNullableType(type: KmType?, names: Map<String, String>) {
        if (type != null) {
            remapType(type, names)
        }
    }

    private fun remapType(type: KmType, names: Map<String, String>) {
        when (val classifier = type.classifier) {
            is KmClassifier.Class -> {
                val name = classifier.name
                type.classifier = KmClassifier.Class(names.getOrDefault(name, name))
            }
            is KmClassifier.TypeAlias -> {
                val name = classifier.name
                type.classifier = KmClassifier.TypeAlias(names.getOrDefault(name, name))
            }
            else -> {}
        }
        type.arguments.forEach { argument -> remapNullableType(argument.type, names) }
        remapNullableType(type.abbreviatedType, names)
        remapNullableType(type.outerType, names)
        val flexible = type.flexibleTypeUpperBound
        if (flexible != null) {
            remapType(flexible.type, names)
        }
    }

    private fun remapNestedShortName(
        originalOwner: String,
        mappedOwner: String,
        shortName: String,
        names: Map<String, String>,
    ): String {
        val mapped = names.getOrDefault(originalOwner + "." + shortName, originalOwner + "." + shortName)
        return if (mapped.startsWith(mappedOwner + ".")) {
            mapped.substring(mappedOwner.length + 1)
        } else {
            shortName
        }
    }

    private fun remapJvmInternal(name: String, mapping: Map<String, String>): String {
        val dotted = name.replace('/', '.')
        return mapping.getOrDefault(dotted, dotted).replace('.', '/')
    }

    private fun <T> replace(values: MutableList<T>, mapper: (T) -> T) {
        for (index in values.indices) {
            values[index] = mapper(values[index])
        }
    }

    private fun readHeader(classBytes: ByteArray): Metadata? {
        val reader = MetadataAnnotationReader()
        ClassReader(classBytes).accept(
            reader,
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        return reader.header()
    }

    private fun writeMetadata(visitor: AnnotationVisitor, metadata: Metadata) {
        visitor.visit("k", metadata.kind)
        visitor.visit("mv", metadata.metadataVersion)
        writeArray(visitor, "d1", metadata.data1)
        writeArray(visitor, "d2", metadata.data2)
        if (metadata.extraString.isNotEmpty()) visitor.visit("xs", metadata.extraString)
        if (metadata.packageName.isNotEmpty()) visitor.visit("pn", metadata.packageName)
        visitor.visit("xi", metadata.extraInt)
        visitor.visitEnd()
    }

    private fun writeArray(visitor: AnnotationVisitor, name: String, values: Array<String>) {
        val array = visitor.visitArray(name)
        for (value in values) array.visit(null, value)
        array.visitEnd()
    }

    private class MetadataAnnotationReader : ClassVisitor(Opcodes.ASM9) {
        private var kind = 0
        private var metadataVersion = IntArray(0)
        private val data1 = ArrayList<String>()
        private val data2 = ArrayList<String>()
        private var extraString = ""
        private var packageName = ""
        private var extraInt = 0
        private var found = false

        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
            if (descriptor != "Lkotlin/Metadata;") return null
            found = true
            return object : AnnotationVisitor(Opcodes.ASM9) {
                override fun visit(name: String, value: Any?) {
                    when (name) {
                        "k" -> kind = value as Int
                        "mv" -> metadataVersion = value as IntArray
                        "xs" -> extraString = value as String
                        "pn" -> packageName = value as String
                        "xi" -> extraInt = value as Int
                        else -> {}
                    }
                }

                override fun visitArray(name: String): AnnotationVisitor? {
                    val destination = when (name) {
                        "d1" -> data1
                        "d2" -> data2
                        else -> null
                    }
                    return if (destination == null) {
                        null
                    } else {
                        object : AnnotationVisitor(Opcodes.ASM9) {
                            override fun visit(ignored: String?, value: Any?) {
                                destination.add(value as String)
                            }
                        }
                    }
                }
            }
        }

        fun header(): Metadata? = if (found) {
            kotlin.metadata.jvm.Metadata(
                kind,
                metadataVersion,
                data1.toTypedArray(),
                data2.toTypedArray(),
                extraString,
                packageName,
                extraInt,
            )
        } else {
            null
        }
    }
}
