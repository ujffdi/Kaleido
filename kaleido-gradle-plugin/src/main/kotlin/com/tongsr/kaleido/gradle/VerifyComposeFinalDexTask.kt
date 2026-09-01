package com.tongsr.kaleido.gradle

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.TreeMap
import java.util.TreeSet
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Final DEX cache gates are established by Ticket 33")
abstract class VerifyComposeFinalDexTask : DefaultTask() {
    init {
        outputs.upToDateWhen { false }
    }

    @get:Input
    abstract val consumerProjectPath: Property<String>

    @get:Input
    abstract val variantName: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputBundle: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val compiledInventory: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val composedMapping: RegularFileProperty

    @get:OutputFile
    abstract val verificationReceipt: RegularFileProperty

    @TaskAction
    fun verify() {
        val project = consumerProjectPath.get()
        val variant = variantName.get()
        val inventory = Files.readAllLines(
            compiledInventory.get().asFile.toPath(),
            StandardCharsets.UTF_8,
        )
        if (inventory.isEmpty() || inventory[0] != "schema=${ComposeCompiledValidator.SCHEMA}") {
            throw failure(
                project,
                variant,
                "compiled-inventory",
                "Compose compiled inventory schema is missing or incompatible",
                "Regenerate compiled Compose evidence with this Kaleido version",
            )
        }
        val facades = TreeMap<String, String>()
        val functions = ArrayList<FunctionEntry>()
        for (line in inventory) {
            if (line.startsWith("facade=")) {
                val values = line.substring("facade=".length).split("|", limit = 2)
                if (values.size != 2) throw malformed(project, variant, line)
                facades[values[0]] = values[1]
            } else if (line.startsWith("method=")) {
                val values = line.substring("method=".length).split("|", limit = 3)
                val separator = if (values.size == 3) values[0].lastIndexOf('#') else -1
                if (separator <= 0) throw malformed(project, variant, line)
                functions.add(
                    FunctionEntry(
                        values[0].substring(0, separator),
                        values[0].substring(separator + 1),
                        values[1],
                    ),
                )
            }
        }
        if (facades.isEmpty() && functions.isEmpty()) {
            writeReceipt(0, 0, 0)
            return
        }
        if (facades.isEmpty() || functions.isEmpty() ||
            functions.any { it.facade !in facades }
        ) {
            throw malformed(project, variant, "inventory-closure")
        }

        val mapping = Files.readString(
            composedMapping.get().asFile.toPath(),
            StandardCharsets.UTF_8,
        )
        val finalClasses = TreeMap<String, String>()
        val finalMethods = TreeMap<String, Set<String>>()
        for (facade in facades.keys) {
            val section = mappingSection(mapping, facade, project, variant)
            finalClasses[facade] = section.finalClass
            val names = TreeSet<String>()
            for (function in functions.filter { it.facade == facade }) {
                val mapped = mappedMethod(section.lines, function.name, project, variant)
                if (mapped != null) names.add(mapped)
            }
            finalMethods[facade] = names.toSet()
        }

        val dexClasses = HashMap<String, MutableSet<String>>()
        var dexCount = 0
        ZipFile(inputBundle.get().asFile).use { bundle ->
            for (entry in bundle.stream().filter { item ->
                !item.isDirectory && item.name.matches(DEX_ENTRY)
            }.toList()) {
                dexCount++
                val parsed = try {
                    DexDeclarations.parse(bundle.getInputStream(entry).readAllBytes())
                } catch (_: RuntimeException) {
                    throw failure(
                        project,
                        variant,
                        entry.name,
                        "Final DEX declarations are not structurally readable",
                        "Discard the malformed Bundle and rebuild before signing",
                    )
                }
                parsed.forEach { type, methods ->
                    dexClasses.getOrPut(type) { mutableSetOf() }.addAll(methods)
                }
            }
        }
        if (dexCount == 0) {
            throw failure(
                project,
                variant,
                "base/dex",
                "Final Bundle contains no base classes DEX",
                "Build the minified Release application before Compose retention verification",
            )
        }
        for (facade in facades.keys) {
            val finalClass = finalClasses.getValue(facade)
            val descriptor = "L${finalClass.replace('.', '/')};"
            val declaredMethods = dexClasses[descriptor]
                ?: throw failure(
                    project,
                    variant,
                    facade,
                    "Mapped generated Compose facade is absent from final DEX",
                    "Keep the exact compiled facade without allowing shrinking",
                )
            val expectedMemberCount = functions.count { it.facade == facade }
            if (declaredMethods.size != expectedMemberCount) {
                throw failure(
                    project,
                    variant,
                    facade,
                    "Final DEX member count differs from the compiled Compose inventory",
                    "Retain every and only inventoried generated facade member",
                )
            }
            for (method in finalMethods.getValue(facade)) {
                if (method !in declaredMethods) {
                    throw failure(
                        project,
                        variant,
                        "$facade#$method",
                        "Mapped generated Compose function is absent from final DEX",
                        "Keep every inventoried facade member without allowing shrinking",
                    )
                }
            }
        }
        writeReceipt(facades.size, functions.size, dexCount)
    }

    private fun writeReceipt(facades: Int, functions: Int, dexFiles: Int) {
        val text = "schema=ComposeFinalDexReceipt.v1\n" +
            "project=" + consumerProjectPath.get() + "\n" +
            "variant=" + variantName.get() + "\n" +
            "facades=" + facades + "\n" +
            "functions=" + functions + "\n" +
            "dexFiles=" + dexFiles + "\n" +
            "mappingResolved=true\n" +
            "incomingBytecodeEdges=0\n" +
            "finalDexRetained=true\n"
        val output = verificationReceipt.get().asFile.toPath()
        Files.createDirectories(output.parent)
        Files.writeString(output, text, StandardCharsets.UTF_8)
    }

    @JvmRecord
    private data class FunctionEntry(
        val facade: String,
        val name: String,
        val descriptor: String,
    )

    @JvmRecord
    private data class MappingSection(val finalClass: String, val lines: List<String>)

    private object DexDeclarations {
        fun parse(bytes: ByteArray): Map<String, Set<String>> {
            if (bytes.size < 112 ||
                bytes[0] != 'd'.code.toByte() ||
                bytes[1] != 'e'.code.toByte() ||
                bytes[2] != 'x'.code.toByte()
            ) {
                throw IllegalArgumentException("Invalid DEX header")
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val strings = strings(bytes, buffer.getInt(56), buffer.getInt(60))
            val types = types(buffer, strings, buffer.getInt(64), buffer.getInt(68))
            val methodClasses = IntArray(buffer.getInt(88))
            val methodNames = arrayOfNulls<String>(methodClasses.size)
            val methodOffset = buffer.getInt(92)
            for (index in methodClasses.indices) {
                val offset = methodOffset + index * 8
                methodClasses[index] = buffer.getShort(offset).toInt() and 0xFFFF
                methodNames[index] = strings[buffer.getInt(offset + 4)]
            }
            val result = TreeMap<String, Set<String>>()
            val classCount = buffer.getInt(96)
            val classOffset = buffer.getInt(100)
            for (classIndex in 0 until classCount) {
                val offset = classOffset + classIndex * 32
                val type = types[buffer.getInt(offset)]
                val classDataOffset = buffer.getInt(offset + 24)
                val declared = TreeSet<String>()
                if (classDataOffset != 0) {
                    readClassData(bytes, classDataOffset, methodNames, declared)
                }
                result[type] = declared.toSet()
            }
            return result.toMap()
        }

        private fun strings(bytes: ByteArray, count: Int, offsetsStart: Int): List<String> {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val values = ArrayList<String>(count)
            for (index in 0 until count) {
                val cursor = Cursor(buffer.getInt(offsetsStart + index * 4))
                uleb(bytes, cursor)
                val start = cursor.value
                while (bytes[cursor.value].toInt() != 0) cursor.value++
                values.add(
                    String(bytes, start, cursor.value - start, StandardCharsets.UTF_8),
                )
            }
            return values.toList()
        }

        private fun types(
            buffer: ByteBuffer,
            strings: List<String>,
            count: Int,
            start: Int,
        ): List<String> {
            val values = ArrayList<String>(count)
            for (index in 0 until count) {
                values.add(strings[buffer.getInt(start + index * 4)])
            }
            return values.toList()
        }

        private fun readClassData(
            bytes: ByteArray,
            offset: Int,
            methodNames: Array<String?>,
            declared: MutableSet<String>,
        ) {
            val cursor = Cursor(offset)
            val staticFields = uleb(bytes, cursor)
            val instanceFields = uleb(bytes, cursor)
            val directMethods = uleb(bytes, cursor)
            val virtualMethods = uleb(bytes, cursor)
            repeat(staticFields + instanceFields) {
                uleb(bytes, cursor)
                uleb(bytes, cursor)
            }
            var methodIndex = 0
            repeat(directMethods) {
                methodIndex += uleb(bytes, cursor)
                uleb(bytes, cursor)
                uleb(bytes, cursor)
                declared.add(checkNotNull(methodNames[methodIndex]))
            }
            methodIndex = 0
            repeat(virtualMethods) {
                methodIndex += uleb(bytes, cursor)
                uleb(bytes, cursor)
                uleb(bytes, cursor)
                declared.add(checkNotNull(methodNames[methodIndex]))
            }
        }

        private fun uleb(bytes: ByteArray, cursor: Cursor): Int {
            var result = 0
            var shift = 0
            var value: Int
            do {
                value = bytes[cursor.value++].toInt() and 0xFF
                result = result or ((value and 0x7f) shl shift)
                shift += 7
            } while ((value and 0x80) != 0)
            return result
        }

        private class Cursor(var value: Int)
    }

    companion object {
        private val DEX_ENTRY = Regex("base/dex/classes[0-9]*\\.dex")

        private fun mappingSection(
            mapping: String,
            original: String,
            project: String,
            variant: String,
        ): MappingSection {
            val lines = mapping.lines()
            for (index in lines.indices) {
                val prefix = "$original -> "
                if (!lines[index].startsWith(prefix) || !lines[index].endsWith(":")) continue
                val finalClass = lines[index].substring(prefix.length, lines[index].length - 1)
                val members = ArrayList<String>()
                var member = index + 1
                while (member < lines.size &&
                    lines[member].isNotEmpty() &&
                    lines[member][0].isWhitespace()
                ) {
                    members.add(lines[member])
                    member++
                }
                return MappingSection(finalClass, members.toList())
            }
            throw failure(
                project,
                variant,
                original,
                "Generated Compose facade is absent from the composed mapping",
                "Regenerate raw Kaleido, raw R8, and composed mappings together",
            )
        }

        private fun mappedMethod(
            lines: List<String>,
            originalName: String,
            project: String,
            variant: String,
        ): String? {
            val marker = "$originalName("
            val values = lines.filter { it.contains(marker) && it.contains(" -> ") }
                .map { it.substring(it.lastIndexOf(" -> ") + 4) }
                .distinct()
            if (values.isEmpty()) return null
            if (values.size != 1) {
                throw failure(
                    project,
                    variant,
                    originalName,
                    "Generated Compose function does not resolve uniquely through composed mapping",
                    "Retain and map every compiled generated function exactly once",
                )
            }
            return values[0]
        }

        private fun malformed(project: String, variant: String, target: String): GradleException =
            failure(
                project,
                variant,
                target,
                "Compose compiled inventory is malformed or incomplete",
                "Regenerate the inventory from compiled PROJECT classes",
            )

        private fun failure(
            project: String,
            variant: String,
            target: String,
            reason: String,
            repair: String,
        ): GradleException =
            KaleidoDiagnostic(
                "KLD-COMPOSE-001",
                project,
                variant,
                "compose-final-dex",
                "ComposeFinalDexReceipt.v1",
                target,
                reason,
                repair,
            ).failure()
    }
}
