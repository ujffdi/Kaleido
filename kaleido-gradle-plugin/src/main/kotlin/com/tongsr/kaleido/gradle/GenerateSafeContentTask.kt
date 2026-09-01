package com.tongsr.kaleido.gradle

import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Comparator
import java.util.HexFormat
import java.util.TreeSet
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class GenerateSafeContentTask : DefaultTask() {
    @get:Input
    val kaleidoCacheSchema: String
        get() = "GenerateContentCache.v2"

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val adoptionPlan: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val consumerResourceDirectories: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val consumerSourceDirectories: ConfigurableFileCollection

    @get:Classpath
    abstract val compileClasspath: ConfigurableFileCollection

    @get:Input
    abstract val compileComponents: ListProperty<String>

    @get:OutputDirectory
    abstract val kotlinOutputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val resourceOutputDirectory: DirectoryProperty

    @get:OutputFile
    abstract val manifestOutputFile: RegularFileProperty

    @get:OutputDirectory
    abstract val keepRulesOutputDirectory: DirectoryProperty

    @get:OutputFile
    abstract val inventoryFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val plan = readPlan(adoptionPlan.get().asFile.toPath())
        val kotlinRoot = kotlinOutputDirectory.get().asFile.toPath()
        val resourceRoot = resourceOutputDirectory.get().asFile.toPath()
        val manifestFile = manifestOutputFile.get().asFile.toPath()
        val keepRulesRoot = keepRulesOutputDirectory.get().asFile.toPath()
        val keepRulesFile = keepRulesRoot.resolve("generated.keep")
        val inventoryPath = inventoryFile.get().asFile.toPath()
        val consumerResources = inventoryConsumerResources()
        val files = SafeGenerationEngine.plan(
            plan,
            consumerResources,
            inventoryConsumerClasses(),
        )
        val compose = ComposeGenerationEngine.plan(plan)
        val composeRuntime = if (compose.kotlinFiles.isEmpty()) {
            "disabled"
        } else {
            resolveComposeRuntime(plan)
        }
        validateResourceEscapeHatches(plan, consumerResources, files)

        deleteTree(kotlinRoot)
        deleteTree(resourceRoot)
        Files.deleteIfExists(manifestFile)
        deleteTree(keepRulesRoot)
        Files.deleteIfExists(inventoryPath)
        Files.createDirectories(kotlinRoot)
        Files.createDirectories(resourceRoot)
        writeGeneratedFiles(kotlinRoot, files.kotlinFiles)
        writeGeneratedFiles(kotlinRoot, compose.kotlinFiles)
        writeGeneratedFiles(resourceRoot, files.resourceFiles)
        write(manifestFile, files.manifest)
        write(keepRulesFile, files.keepRules)

        val inventory = ArrayList<InventoryEntry>()
        inventory.addAll(inventory(kotlinRoot, "kotlin"))
        inventory.addAll(inventory(resourceRoot, "res"))
        inventory.add(InventoryEntry("manifest/AndroidManifest.xml", sha256(files.manifest)))
        inventory.add(InventoryEntry("rules/generated.keep", sha256(files.keepRules)))
        inventory.sortBy { it.path }
        val text = StringBuilder()
            .append("schema=GeneratedInventory.v1\n")
            .append("classes=").append(files.classCount).append('\n')
            .append("methods=").append(files.methodCount).append('\n')
            .append("layouts=").append(files.layoutCount).append('\n')
            .append("drawables=").append(files.drawableCount).append('\n')
            .append("strings=").append(files.stringCount).append('\n')
            .append("components.schema=")
            .append(FullComponentGenerationEngine.SCHEMA).append('\n')
            .append("components.activities=").append(files.activities.size).append('\n')
        files.activities.forEach { activity ->
            text.append("component=activity|")
                .append(activity).append('|').append("exported=false").append('\n')
        }
        text.append("compose.schema=").append(ComposeGenerationEngine.SCHEMA).append('\n')
            .append("compose.enabled=").append(compose.kotlinFiles.isNotEmpty()).append('\n')
            .append("compose.runtimeArtifact=").append(composeRuntime).append('\n')
            .append("compose.facades=").append(compose.facades.size).append('\n')
            .append("compose.functions=").append(compose.functions.size).append('\n')
        compose.facades.forEach { facade ->
            text.append("composeFacade=").append(facade).append('\n')
        }
        compose.functions.forEach { function ->
            text.append("composeFunction=")
                .append(function.facade).append('#').append(function.name)
                .append('|').append(function.graphIndex).append('\n')
        }
        inventory.forEach { entry ->
            text.append("file=").append(entry.path).append('|').append(entry.sha256).append('\n')
        }
        write(inventoryPath, text.toString())
    }

    private fun resolveComposeRuntime(plan: Map<String, String>): String {
        val matches = TreeSet<String>()
        for (file in compileClasspath.files.sortedBy { it.name }) {
            if (!file.isFile) continue
            if (containsClass(file.toPath(), "androidx/compose/runtime/Composer.class")) {
                matches.add(file.name)
            }
        }
        val runtimeComponents = compileComponents.get()
            .filter { it.matches(Regex("androidx\\.compose\\.runtime:runtime(-android)?:[^:]+")) }
            .sorted()
        if (matches.size != 1 || runtimeComponents.size != 1) {
            throw KaleidoDiagnostic(
                "KLD-COMPOSE-001",
                plan.getOrDefault("project", "<consumer>"),
                plan.getOrDefault("variant.name", "<variant>"),
                "compose-generation",
                ComposeGenerationEngine.SCHEMA,
                "compileClasspath",
                if (matches.isEmpty() || runtimeComponents.isEmpty()) {
                    "Compose Runtime is not resolvable on the Release compile classpath"
                } else {
                    "Compose Runtime resolves to more than one class-bearing artifact"
                },
                "Provide exactly one compatible androidx.compose.runtime:runtime dependency",
            ).failure()
        }
        return runtimeComponents[0]
    }

    private fun inventoryConsumerResources(): Set<ResourceIdentity> {
        val identities = HashSet<ResourceIdentity>()
        val roots = consumerResourceDirectories.files
            .map { it.toPath() }
            .filter { Files.isDirectory(it) }
            .sortedBy { it.toString() }
        for (root in roots) {
            Files.walk(root).use { paths ->
                for (path in paths.filter { Files.isRegularFile(it) }.sorted()) {
                    val relative = root.relativize(path)
                    if (relative.nameCount < 2) continue
                    val type = relative.getName(0).toString().split("-", limit = 2)[0]
                    if (type == "values" && path.fileName.toString().endsWith(".xml")) {
                        val matcher = STRING_RESOURCE.matcher(Files.readString(path))
                        while (matcher.find()) {
                            identities.add(ResourceIdentity("string", matcher.group(1)))
                        }
                    } else if (type == "layout" || type == "drawable") {
                        val fileName = path.fileName.toString()
                        val dot = fileName.indexOf('.')
                        if (dot > 0) {
                            identities.add(ResourceIdentity(type, fileName.substring(0, dot)))
                        }
                    }
                }
            }
        }
        return identities.toSet()
    }

    private fun inventoryConsumerClasses(): Set<String> {
        val identities = TreeSet<String>()
        val roots = consumerSourceDirectories.files
            .map { it.toPath() }
            .filter { Files.isDirectory(it) }
            .sortedBy { it.toString() }
        for (root in roots) {
            Files.walk(root).use { paths ->
                for (path in paths.filter { Files.isRegularFile(it) }
                    .filter { it.toString().endsWith(".java") || it.toString().endsWith(".kt") }
                    .sorted()) {
                    val source = Files.readString(path)
                    val packageMatcher = SOURCE_PACKAGE.matcher(source)
                    if (!packageMatcher.find()) continue
                    val packageName = packageMatcher.group(1)
                    val typeMatcher = SOURCE_TYPE.matcher(source)
                    while (typeMatcher.find()) {
                        identities.add("$packageName.${typeMatcher.group(1)}")
                    }
                }
            }
        }
        return identities.toSet()
    }

    private fun validateResourceEscapeHatches(
        plan: Map<String, String>,
        consumerResources: Set<ResourceIdentity>,
        generated: SafeGenerationEngine.GeneratedContent,
    ) {
        val identities = TreeSet<String>()
        consumerResources.map { it.name }.forEach { identities.add(it) }
        generated.resourceFiles.keys
            .filter { it.startsWith("layout/") || it.startsWith("drawable/") }
            .map { it.substring(it.indexOf('/') + 1, it.length - 4) }
            .forEach { identities.add(it) }
        val matcher = STRING_RESOURCE.matcher(
            generated.resourceFiles.getOrDefault("values/strings.xml", ""),
        )
        while (matcher.find()) identities.add(matcher.group(1))
        val project = plan.getOrDefault("project", "<consumer>")
        val variant = plan.getOrDefault("variant.name", "<variant>")
        val discarded = discardedResourceNames()
        for (hatch in RewriteClassesAndManifestTask.parseEscapeHatches(
            plan["protection.resourceEscapeHatches"],
            EscapeHatchDeclaration.Kind.RESOURCE,
            project,
            variant,
        )) {
            if (identities.none { hatch.matches(it) }) {
                throw RewriteClassesAndManifestTask.protectionFailure(
                    project,
                    variant,
                    hatch.id,
                    "Escape Hatch resolves to zero Consumer or generated resources",
                    "Remove the stale declaration or select an existing bounded resource target",
                )
            }
            val conflict = discarded.firstOrNull { hatch.matches(it) }
            if (conflict != null) {
                throw RewriteClassesAndManifestTask.protectionFailure(
                    project,
                    variant,
                    hatch.id,
                    "tools:discard conflicts with protected resource $conflict",
                    "Remove tools:discard or the stale Protection Requirement",
                )
            }
        }
    }

    private fun discardedResourceNames(): Set<String> {
        val names = TreeSet<String>()
        for (root in consumerResourceDirectories.files
            .map { it.toPath() }
            .filter { Files.isDirectory(it) }
            .sortedBy { it.toString() }) {
            Files.walk(root).use { paths ->
                for (path in paths.filter { Files.isRegularFile(it) }
                    .filter { it.fileName.toString().endsWith(".xml") }
                    .sorted()) {
                    val discard = TOOLS_DISCARD.matcher(Files.readString(path))
                    while (discard.find()) {
                        val reference = RESOURCE_REFERENCE.matcher(discard.group(1))
                        while (reference.find()) names.add(reference.group(1))
                    }
                }
            }
        }
        return names.toSet()
    }

    @JvmRecord
    data class ResourceIdentity(val type: String, val name: String)

    @JvmRecord
    private data class InventoryEntry(val path: String, val sha256: String)

    companion object {
        private val STRING_RESOURCE = Regex("<string\\s+[^>]*name\\s*=\\s*\"([^\"]+)\"")
            .toPattern()
        private val TOOLS_DISCARD = Regex("tools:discard\\s*=\\s*\"([^\"]+)\"").toPattern()
        private val RESOURCE_REFERENCE = Regex("@[A-Za-z0-9_.-]+/([a-zA-Z0-9_]+)").toPattern()
        private val SOURCE_PACKAGE =
            Regex("(?m)^\\s*package\\s+([A-Za-z_][A-Za-z0-9_.]*)\\s*[;]?").toPattern()
        private val SOURCE_TYPE = Regex(
            "(?m)^\\s*(?:(?:public|protected|private|internal|final|open|abstract|sealed|data|value|enum|annotation)\\s+)*(?:class|interface|object|record)\\s+([A-Za-z_][A-Za-z0-9_]*)",
        ).toPattern()

        private fun containsClass(artifact: Path, classEntry: String): Boolean {
            ZipFile(artifact.toFile()).use { archive ->
                if (archive.getEntry(classEntry) != null) return true
                val classesJar = archive.getEntry("classes.jar") ?: return false
                ZipInputStream(archive.getInputStream(classesJar)).use { nested ->
                    var entry = nested.nextEntry
                    while (entry != null) {
                        if (entry.name == classEntry) return true
                        entry = nested.nextEntry
                    }
                }
                return false
            }
        }

        private fun readPlan(path: Path): Map<String, String> {
            val values = HashMap<String, String>()
            for (line in Files.readAllLines(path, StandardCharsets.UTF_8)) {
                val separator = line.indexOf('=')
                if (separator > 0) {
                    values[line.substring(0, separator)] = line.substring(separator + 1)
                }
            }
            return values.toMap()
        }

        private fun writeGeneratedFiles(root: Path, files: Map<String, String>) {
            for ((key, value) in files.entries.sortedBy { it.key }) {
                write(root.resolve(key), value)
            }
        }

        private fun write(path: Path, content: String) {
            Files.createDirectories(path.parent)
            Files.writeString(path, content, StandardCharsets.UTF_8)
        }

        private fun inventory(root: Path, prefix: String): List<InventoryEntry> {
            if (!Files.exists(root)) return emptyList()
            try {
                Files.walk(root).use { paths ->
                    return paths.filter { Files.isRegularFile(it) }
                        .sorted()
                        .map { path ->
                            InventoryEntry(
                                prefix + "/" + root.relativize(path).toString()
                                    .replace(File.separatorChar, '/'),
                                sha256(Files.readString(path, StandardCharsets.UTF_8)),
                            )
                        }
                        .toList()
                }
            } catch (exception: UncheckedIOException) {
                throw exception.cause ?: exception
            }
        }

        private fun sha256(value: String): String = try {
            HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.toByteArray(StandardCharsets.UTF_8)),
            )
        } catch (impossible: NoSuchAlgorithmException) {
            throw IllegalStateException("SHA-256 is unavailable", impossible)
        }

        private fun deleteTree(root: Path) {
            if (!Files.exists(root)) return
            Files.walk(root).use { paths ->
                for (path in paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path)
                }
            }
        }
    }
}
