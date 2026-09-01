package com.tongsr.kaleido.gradle;

import com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.CacheableTask;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

@CacheableTask
public abstract class RewriteSemanticXmlTask extends DefaultTask {
    @Input public String getKaleidoCacheSchema() { return "SemanticXmlRewriteCache.v2"; }
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    @Input public abstract Property<String> getConsumerProjectPath();
    @Input public abstract Property<String> getVariantName();
    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getAdoptionPlan();
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getConsumerResourceDirectories();
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getConsumerSourceDirectories();
    @OutputDirectory public abstract DirectoryProperty getOutputResources();
    @OutputFile public abstract RegularFileProperty getRewriteIntent();

    @TaskAction
    public void rewrite() throws Exception {
        var project = getConsumerProjectPath().get();
        var variant = getVariantName().get();
        var adoption = readProperties(Files.readAllBytes(
                getAdoptionPlan().get().getAsFile().toPath()));
        var applicationId = required(adoption, "applicationId", project, variant);
        var stream = required(adoption, "seed.domain.class-rewrite", project, variant);
        var protectedNames = new TreeSet<>(commaSeparated(
                adoption.get("protection.originalClassNames")));
        var hatches = RewriteClassesAndManifestTask.parseEscapeHatches(
                adoption.get("protection.classEscapeHatches"),
                EscapeHatchDeclaration.Kind.CLASS, project, variant);

        var documents = new TreeMap<String, XmlDocument>();
        var references = new ArrayList<XmlReference>();
        var seenPaths = new HashSet<String>();
        for (var root : getConsumerResourceDirectories().getFiles().stream()
                .map(java.io.File::toPath).filter(Files::isDirectory)
                .sorted(Comparator.comparing(Path::toString)).toList()) {
            try (var paths = Files.walk(root)) {
                for (var path : paths.filter(Files::isRegularFile)
                        .filter(file -> file.getFileName().toString().endsWith(".xml"))
                        .sorted().toList()) {
                    var relative = root.relativize(path).toString()
                            .replace(java.io.File.separatorChar, '/');
                    if (!seenPaths.add(relative)) {
                        throw RewriteClassesAndManifestTask.protectionFailure(
                                project, variant, relative,
                                "Semantic XML path is ambiguous across source roots",
                                "Resolve the source overlay before Kaleido rewriting");
                    }
                    var document = parse(Files.readAllBytes(path), project, variant, relative);
                    var found = inventory(document, relative, applicationId);
                    if (!found.isEmpty()) {
                        documents.put(relative, new XmlDocument(document, found));
                        references.addAll(found);
                    }
                }
            }
        }
        var roots = new TreeSet<String>();
        references.stream().map(XmlReference::resolvedIdentity)
                .filter(identity -> identity.startsWith(applicationId + "."))
                .forEach(roots::add);
        protectedNames.addAll(SourceProtectionScanner.inferProtectedIdentities(
                getConsumerSourceDirectories().getFiles(), roots));
        hatches.stream().filter(hatch ->
                        hatch.protects(KaleidoProtectionDimension.ORIGINAL_IDENTITY))
                .forEach(hatch -> roots.stream().filter(hatch::matches)
                        .forEach(protectedNames::add));
        var mapping = RewriteClassesAndManifestTask.allocateMapping(
                roots, protectedNames, roots, stream);
        var sites = references.stream()
                .filter(reference -> mapping.containsKey(reference.resolvedIdentity()))
                .map(reference -> new XmlSite(reference.location(), reference.lexicalValue(),
                        render(reference.lexicalValue(), applicationId,
                                mapping.get(reference.resolvedIdentity()))))
                .sorted(Comparator.comparing(XmlSite::location)).toList();
        var byLocation = sites.stream().collect(java.util.stream.Collectors.toMap(
                XmlSite::location, java.util.function.Function.identity()));
        var outputRoot = getOutputResources().get().getAsFile().toPath();
        deleteTree(outputRoot);
        for (var entry : documents.entrySet()) {
            var changed = false;
            for (var reference : entry.getValue().references()) {
                var site = byLocation.get(reference.location());
                if (site != null) {
                    reference.writer().set(site.target());
                    changed = true;
                }
            }
            if (changed) write(outputRoot.resolve(entry.getKey()), serialize(entry.getValue().document()));
        }
        var intent = new StringBuilder("schema=XmlRewriteIntent.v1\n");
        mapping.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                intent.append("mapping=").append(entry.getKey()).append('|')
                        .append(entry.getValue()).append('\n'));
        sites.forEach(site -> intent.append("site=").append(site.location()).append('|')
                .append(site.original()).append('|').append(site.target()).append('\n'));
        write(getRewriteIntent().get().getAsFile().toPath(),
                intent.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static List<XmlReference> inventory(
            Document document, String path, String applicationId) {
        var references = new ArrayList<XmlReference>();
        var elements = document.getElementsByTagName("*");
        for (var index = 0; index < elements.getLength(); index++) {
            var element = (Element) elements.item(index);
            var name = element.getTagName();
            if (name.contains(".") && !name.startsWith("android.")) {
                references.add(reference(path + "/element[" + index + "]", name,
                        value -> document.renameNode(element, element.getNamespaceURI(), value),
                        applicationId));
            }
            if (name.equals("view") || name.equals("fragment")) {
                addAttribute(references, path, index, element, null, "class", applicationId);
            }
            if (name.equals("fragment") || name.equals("activity")
                    || name.equals("dialog")) {
                addAttribute(references, path, index, element, ANDROID, "name", applicationId);
            }
            if (name.equals("variable") || name.equals("import")) {
                addAttribute(references, path, index, element, null, "type", applicationId);
            }
            var attributes = element.getAttributes();
            for (var attributeIndex = 0; attributeIndex < attributes.getLength(); attributeIndex++) {
                var attribute = attributes.item(attributeIndex);
                var local = attribute.getLocalName() == null
                        ? attribute.getNodeName() : attribute.getLocalName();
                if (local.equals("context") || local.equals("layout_behavior")
                        || local.equals("layoutManager") || local.equals("argType")) {
                    var value = attribute.getNodeValue();
                    if (looksLikeClass(value)) {
                        references.add(reference(path + "/element[" + index + "]@" + local,
                                value, attribute::setNodeValue, applicationId));
                    }
                }
            }
        }
        return references;
    }

    private static void addAttribute(
            List<XmlReference> references, String path, int index, Element element,
            String namespace, String attribute, String applicationId) {
        var present = namespace == null ? element.hasAttribute(attribute)
                : element.hasAttributeNS(namespace, attribute);
        if (!present) return;
        var value = namespace == null ? element.getAttribute(attribute)
                : element.getAttributeNS(namespace, attribute);
        if (!looksLikeClass(value)) return;
        references.add(reference(path + "/element[" + index + "]@" + attribute, value,
                replacement -> {
                    if (namespace == null) element.setAttribute(attribute, replacement);
                    else element.setAttributeNS(namespace, "android:" + attribute, replacement);
                }, applicationId));
    }

    private static XmlReference reference(
            String location, String lexical, StringWriter writer, String applicationId) {
        var resolved = lexical.startsWith(".") ? applicationId + lexical
                : lexical.contains(".") ? lexical : applicationId + "." + lexical;
        return new XmlReference(location, lexical, resolved, writer);
    }

    private static boolean looksLikeClass(String value) {
        return value.startsWith(".") || value.matches(
                "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
    }

    private static String render(String lexical, String applicationId, String target) {
        return lexical.startsWith(".") && target.startsWith(applicationId + ".")
                ? target.substring(applicationId.length()) : target;
    }

    private static Document parse(byte[] bytes, String project, String variant, String path) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(bytes));
        } catch (Exception failure) {
            throw RewriteClassesAndManifestTask.protectionFailure(project, variant, path,
                    "Consumer XML is not securely parseable",
                    "Fix or remove the malformed XML resource");
        }
    }

    private static byte[] serialize(Document document) throws Exception {
        var factory = TransformerFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        var transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "no");
        var bytes = new java.io.ByteArrayOutputStream();
        transformer.transform(new DOMSource(document), new StreamResult(bytes));
        return bytes.toByteArray();
    }

    private static Map<String, String> readProperties(byte[] bytes) {
        var values = new HashMap<String, String>();
        for (var line : new String(bytes, StandardCharsets.UTF_8).split("\\n")) {
            var separator = line.indexOf('=');
            if (separator > 0) values.put(line.substring(0, separator), line.substring(separator + 1));
        }
        return Map.copyOf(values);
    }

    private static String required(
            Map<String, String> values, String key, String project, String variant) {
        var value = values.get(key);
        if (value == null) throw RewriteClassesAndManifestTask.protectionFailure(
                project, variant, key, "Adoption Plan is incomplete",
                "Regenerate a complete AdoptionPlan.v1");
        return value;
    }

    private static Set<String> commaSeparated(String value) {
        return value == null || value.isBlank() ? Set.of() : Set.of(value.split(","));
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private static void write(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
    }

    @FunctionalInterface private interface StringWriter { void set(String value); }
    private record XmlReference(String location, String lexicalValue,
                                String resolvedIdentity, StringWriter writer) {}
    private record XmlSite(String location, String original, String target) {}
    private record XmlDocument(Document document, List<XmlReference> references) {}
}
