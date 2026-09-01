package com.tongsr.kaleido.gradle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.CacheableTask;

@CacheableTask
public abstract class GenerateSafeContentTask extends DefaultTask {
    @Input public String getKaleidoCacheSchema() { return "GenerateContentCache.v1"; }
    private static final Pattern STRING_RESOURCE = Pattern.compile(
            "<string\\s+[^>]*name\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern TOOLS_DISCARD = Pattern.compile(
            "tools:discard\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern RESOURCE_REFERENCE = Pattern.compile(
            "@[A-Za-z0-9_.-]+/([a-zA-Z0-9_]+)");
    private static final Pattern SOURCE_PACKAGE = Pattern.compile(
            "(?m)^\\s*package\\s+([A-Za-z_][A-Za-z0-9_.]*)\\s*[;]?");
    private static final Pattern SOURCE_TYPE = Pattern.compile(
            "(?m)^\\s*(?:(?:public|protected|private|internal|final|open|abstract|sealed|data|value|enum|annotation)\\s+)*(?:class|interface|object|record)\\s+([A-Za-z_][A-Za-z0-9_]*)");

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getAdoptionPlan();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getConsumerResourceDirectories();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getConsumerSourceDirectories();

    @Classpath
    public abstract ConfigurableFileCollection getCompileClasspath();

    @Input
    public abstract ListProperty<String> getCompileComponents();

    @OutputDirectory public abstract DirectoryProperty getJavaOutputDirectory();
    @OutputDirectory public abstract DirectoryProperty getKotlinOutputDirectory();
    @OutputDirectory public abstract DirectoryProperty getResourceOutputDirectory();
    @OutputFile public abstract RegularFileProperty getManifestOutputFile();
    @OutputDirectory public abstract DirectoryProperty getKeepRulesOutputDirectory();
    @OutputFile public abstract RegularFileProperty getInventoryFile();

    @TaskAction
    public void generate() throws IOException {
        var plan = readPlan(getAdoptionPlan().get().getAsFile().toPath());
        var javaRoot = getJavaOutputDirectory().get().getAsFile().toPath();
        var kotlinRoot = getKotlinOutputDirectory().get().getAsFile().toPath();
        var resourceRoot = getResourceOutputDirectory().get().getAsFile().toPath();
        var manifestFile = getManifestOutputFile().get().getAsFile().toPath();
        var keepRulesRoot = getKeepRulesOutputDirectory().get().getAsFile().toPath();
        var keepRulesFile = keepRulesRoot.resolve("generated.keep");
        var inventoryFile = getInventoryFile().get().getAsFile().toPath();
        var consumerResources = inventoryConsumerResources();
        var files = SafeGenerationEngine.plan(
                plan, consumerResources, inventoryConsumerClasses());
        var compose = ComposeGenerationEngine.plan(plan);
        var composeRuntime = compose.kotlinFiles().isEmpty()
                ? "disabled" : resolveComposeRuntime(plan);
        validateResourceEscapeHatches(plan, consumerResources, files);

        deleteTree(javaRoot);
        deleteTree(kotlinRoot);
        deleteTree(resourceRoot);
        Files.deleteIfExists(manifestFile);
        deleteTree(keepRulesRoot);
        Files.deleteIfExists(inventoryFile);
        Files.createDirectories(javaRoot);
        Files.createDirectories(kotlinRoot);
        Files.createDirectories(resourceRoot);
        writeGeneratedFiles(javaRoot, files.javaFiles());
        writeGeneratedFiles(kotlinRoot, compose.kotlinFiles());
        writeGeneratedFiles(resourceRoot, files.resourceFiles());
        write(manifestFile, files.manifest());
        write(keepRulesFile, files.keepRules());

        var inventory = new ArrayList<InventoryEntry>();
        inventory.addAll(inventory(javaRoot, "java"));
        inventory.addAll(inventory(kotlinRoot, "kotlin"));
        inventory.addAll(inventory(resourceRoot, "res"));
        inventory.add(new InventoryEntry("manifest/AndroidManifest.xml", sha256(files.manifest())));
        inventory.add(new InventoryEntry("rules/generated.keep", sha256(files.keepRules())));
        inventory.sort(Comparator.comparing(InventoryEntry::path));
        var text = new StringBuilder()
                .append("schema=GeneratedInventory.v1\n")
                .append("classes=").append(files.classCount()).append('\n')
                .append("methods=").append(files.methodCount()).append('\n')
                .append("layouts=").append(files.layoutCount()).append('\n')
                .append("drawables=").append(files.drawableCount()).append('\n')
                .append("strings=").append(files.stringCount()).append('\n')
                .append("components.schema=")
                .append(FullComponentGenerationEngine.SCHEMA).append('\n')
                .append("components.activities=").append(files.activities().size()).append('\n');
        files.activities().forEach(activity -> text.append("component=activity|")
                .append(activity).append('|').append("exported=false").append('\n'));
        text.append("compose.schema=").append(ComposeGenerationEngine.SCHEMA).append('\n')
                .append("compose.enabled=").append(!compose.kotlinFiles().isEmpty()).append('\n')
                .append("compose.runtimeArtifact=").append(composeRuntime).append('\n')
                .append("compose.facades=").append(compose.facades().size()).append('\n')
                .append("compose.functions=").append(compose.functions().size()).append('\n');
        compose.facades().forEach(facade -> text.append("composeFacade=")
                .append(facade).append('\n'));
        compose.functions().forEach(function -> text.append("composeFunction=")
                .append(function.facade()).append('#').append(function.name())
                .append('|').append(function.graphIndex()).append('\n'));
        inventory.forEach(entry -> text.append("file=")
                .append(entry.path()).append('|').append(entry.sha256()).append('\n'));
        write(inventoryFile, text.toString());
    }

    private String resolveComposeRuntime(Map<String, String> plan) throws IOException {
        var matches = new TreeSet<String>();
        for (var file : getCompileClasspath().getFiles().stream()
                .sorted(Comparator.comparing(java.io.File::getName)).toList()) {
            if (!file.isFile()) continue;
            if (containsClass(file.toPath(), "androidx/compose/runtime/Composer.class")) {
                matches.add(file.getName());
            }
        }
        var runtimeComponents = getCompileComponents().get().stream()
                .filter(component -> component.matches(
                        "androidx\\.compose\\.runtime:runtime(-android)?:[^:]+"))
                .sorted().toList();
        if (matches.size() != 1 || runtimeComponents.size() != 1) {
            throw new KaleidoDiagnostic("KLD-COMPOSE-001",
                    plan.getOrDefault("project", "<consumer>"),
                    plan.getOrDefault("variant.name", "<variant>"),
                    "compose-generation", ComposeGenerationEngine.SCHEMA,
                    "compileClasspath",
                    matches.isEmpty() || runtimeComponents.isEmpty()
                            ? "Compose Runtime is not resolvable on the Release compile classpath"
                            : "Compose Runtime resolves to more than one class-bearing artifact",
                    "Provide exactly one compatible androidx.compose.runtime:runtime dependency")
                    .failure();
        }
        return runtimeComponents.get(0);
    }

    private static boolean containsClass(Path artifact, String classEntry) throws IOException {
        try (var archive = new ZipFile(artifact.toFile())) {
            if (archive.getEntry(classEntry) != null) return true;
            var classesJar = archive.getEntry("classes.jar");
            if (classesJar == null) return false;
            try (var nested = new ZipInputStream(archive.getInputStream(classesJar))) {
                for (var entry = nested.getNextEntry(); entry != null;
                        entry = nested.getNextEntry()) {
                    if (entry.getName().equals(classEntry)) return true;
                }
            }
            return false;
        }
    }

    private Set<ResourceIdentity> inventoryConsumerResources() throws IOException {
        var identities = new HashSet<ResourceIdentity>();
        var roots = getConsumerResourceDirectories().getFiles().stream()
                .map(java.io.File::toPath)
                .filter(Files::isDirectory)
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        for (var root : roots) {
            try (var paths = Files.walk(root)) {
                for (var path : paths.filter(Files::isRegularFile).sorted().toList()) {
                    var relative = root.relativize(path);
                    if (relative.getNameCount() < 2) {
                        continue;
                    }
                    var type = relative.getName(0).toString().split("-", 2)[0];
                    if (type.equals("values") && path.getFileName().toString().endsWith(".xml")) {
                        var matcher = STRING_RESOURCE.matcher(Files.readString(path));
                        while (matcher.find()) {
                            identities.add(new ResourceIdentity("string", matcher.group(1)));
                        }
                    } else if (type.equals("layout") || type.equals("drawable")) {
                        var fileName = path.getFileName().toString();
                        var dot = fileName.indexOf('.');
                        if (dot > 0) {
                            identities.add(new ResourceIdentity(type, fileName.substring(0, dot)));
                        }
                    }
                }
            }
        }
        return Set.copyOf(identities);
    }

    private Set<String> inventoryConsumerClasses() throws IOException {
        var identities = new TreeSet<String>();
        var roots = getConsumerSourceDirectories().getFiles().stream()
                .map(java.io.File::toPath)
                .filter(Files::isDirectory)
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        for (var root : roots) {
            try (var paths = Files.walk(root)) {
                for (var path : paths.filter(Files::isRegularFile)
                        .filter(file -> file.toString().endsWith(".java")
                                || file.toString().endsWith(".kt"))
                        .sorted().toList()) {
                    var source = Files.readString(path);
                    var packageMatcher = SOURCE_PACKAGE.matcher(source);
                    if (!packageMatcher.find()) continue;
                    var packageName = packageMatcher.group(1);
                    var typeMatcher = SOURCE_TYPE.matcher(source);
                    while (typeMatcher.find()) {
                        identities.add(packageName + "." + typeMatcher.group(1));
                    }
                }
            }
        }
        return Set.copyOf(identities);
    }

    private void validateResourceEscapeHatches(
            Map<String, String> plan,
            Set<ResourceIdentity> consumerResources,
            SafeGenerationEngine.GeneratedContent generated) throws IOException {
        var identities = new TreeSet<String>();
        consumerResources.stream().map(ResourceIdentity::name).forEach(identities::add);
        generated.resourceFiles().keySet().stream()
                .filter(path -> path.startsWith("layout/") || path.startsWith("drawable/"))
                .map(path -> path.substring(path.indexOf('/') + 1, path.length() - 4))
                .forEach(identities::add);
        var matcher = STRING_RESOURCE.matcher(
                generated.resourceFiles().getOrDefault("values/strings.xml", ""));
        while (matcher.find()) identities.add(matcher.group(1));
        var project = plan.getOrDefault("project", "<consumer>");
        var variant = plan.getOrDefault("variant.name", "<variant>");
        var discarded = discardedResourceNames();
        for (var hatch : RewriteClassesAndManifestTask.parseEscapeHatches(
                plan.get("protection.resourceEscapeHatches"),
                EscapeHatchDeclaration.Kind.RESOURCE, project, variant)) {
            if (identities.stream().noneMatch(hatch::matches)) {
                throw RewriteClassesAndManifestTask.protectionFailure(
                        project, variant, hatch.id(),
                        "Escape Hatch resolves to zero Consumer or generated resources",
                        "Remove the stale declaration or select an existing bounded resource target");
            }
            var conflict = discarded.stream().filter(hatch::matches).findFirst();
            if (conflict.isPresent()) {
                throw RewriteClassesAndManifestTask.protectionFailure(
                        project, variant, hatch.id(),
                        "tools:discard conflicts with protected resource " + conflict.get(),
                        "Remove tools:discard or the stale Protection Requirement");
            }
        }
    }

    private Set<String> discardedResourceNames() throws IOException {
        var names = new TreeSet<String>();
        for (var root : getConsumerResourceDirectories().getFiles().stream()
                .map(java.io.File::toPath).filter(Files::isDirectory)
                .sorted(Comparator.comparing(Path::toString)).toList()) {
            try (var paths = Files.walk(root)) {
                for (var path : paths.filter(Files::isRegularFile)
                        .filter(file -> file.getFileName().toString().endsWith(".xml"))
                        .sorted().toList()) {
                    var discard = TOOLS_DISCARD.matcher(Files.readString(path));
                    while (discard.find()) {
                        var reference = RESOURCE_REFERENCE.matcher(discard.group(1));
                        while (reference.find()) names.add(reference.group(1));
                    }
                }
            }
        }
        return Set.copyOf(names);
    }

    private static Map<String, String> readPlan(Path path) throws IOException {
        var values = new HashMap<String, String>();
        for (var line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            var separator = line.indexOf('=');
            if (separator > 0) {
                values.put(line.substring(0, separator), line.substring(separator + 1));
            }
        }
        return Map.copyOf(values);
    }

    private static void writeGeneratedFiles(Path root, Map<String, String> files)
            throws IOException {
        for (var entry : files.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            write(root.resolve(entry.getKey()), entry.getValue());
        }
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static List<InventoryEntry> inventory(Path root, String prefix) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .sorted()
                    .map(path -> {
                        try {
                            return new InventoryEntry(
                                    prefix + "/" + root.relativize(path).toString()
                                            .replace(java.io.File.separatorChar, '/'),
                                    sha256(Files.readString(path, StandardCharsets.UTF_8)));
                        } catch (IOException exception) {
                            throw new java.io.UncheckedIOException(exception);
                        }
                    })
                    .toList();
        } catch (java.io.UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    record ResourceIdentity(String type, String name) {}
    private record InventoryEntry(String path, String sha256) {}
}
