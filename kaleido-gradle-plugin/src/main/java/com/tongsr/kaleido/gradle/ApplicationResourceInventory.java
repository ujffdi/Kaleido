package com.tongsr.kaleido.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

final class ApplicationResourceInventory {
    private ApplicationResourceInventory() {}

    static Result scan(Iterable<Path> roots, String project, String variant) throws IOException {
        var resources = new TreeSet<BundleRewriteModule.ResourceKey>(Comparator
                .comparing(BundleRewriteModule.ResourceKey::type)
                .thenComparing(BundleRewriteModule.ResourceKey::name));
        var publicNames = new TreeSet<String>();
        var keepSelectors = new TreeSet<String>();
        for (var root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (var paths = Files.walk(root)) {
                for (var file : paths.filter(Files::isRegularFile).sorted().toList()) {
                    var relative = root.relativize(file);
                    if (relative.getNameCount() < 2) continue;
                    var directory = relative.getName(0).toString();
                    var type = directory.split("-", 2)[0];
                    if (file.getFileName().toString().endsWith(".xml")) {
                        scanToolsKeep(file, keepSelectors, project, variant);
                    }
                    if ("values".equals(type) && file.getFileName().toString().endsWith(".xml")) {
                        scanValues(file, resources, publicNames, project, variant);
                    } else {
                        var name = resourceFileName(file.getFileName().toString());
                        if (!name.isBlank()) resources.add(
                                new BundleRewriteModule.ResourceKey(type, name));
                    }
                }
            }
        }
        var protectedNames = new TreeSet<>(publicNames);
        for (var resource : resources) {
            var identity = resource.type() + "/" + resource.name();
            if (keepSelectors.stream().anyMatch(
                    selector -> glob(selector).matcher(identity).matches())) {
                protectedNames.add(resource.name());
            }
        }
        var warnings = keepSelectors.stream()
                .filter(selector -> selector.contains("*") || selector.contains("?"))
                .map(selector -> "KLD-RESOURCE-001 project=" + project + " variant=" + variant
                        + " stage=bundle-rewrite origin=tools:keep target=" + selector
                        + " reason=Broad tools:keep pattern retained matching resources"
                        + " repair=<none>")
                .toList();
        return new Result(Set.copyOf(resources), Set.copyOf(protectedNames), warnings);
    }

    private static void scanValues(
            Path file,
            Set<BundleRewriteModule.ResourceKey> resources,
            Set<String> publicNames,
            String project,
            String variant) {
        try {
            var factory = secureFactory();
            var document = factory.newDocumentBuilder().parse(file.toFile());
            var root = document.getDocumentElement();
            for (var index = 0; index < root.getChildNodes().getLength(); index++) {
                if (!(root.getChildNodes().item(index) instanceof Element element)) continue;
                var tag = element.getTagName();
                var name = element.getAttribute("name");
                var type = valuesType(tag, element.getAttribute("type"));
                if (!name.isBlank() && type != null) {
                    resources.add(new BundleRewriteModule.ResourceKey(type, name));
                    if ("public".equals(tag)) publicNames.add(name);
                }
                if ("declare-styleable".equals(tag)) {
                    var attrs = element.getElementsByTagName("attr");
                    for (var attrIndex = 0; attrIndex < attrs.getLength(); attrIndex++) {
                        var attr = (Element) attrs.item(attrIndex);
                        var attrName = attr.getAttribute("name");
                        if (!attrName.isBlank() && !attrName.startsWith("android:")) {
                            resources.add(new BundleRewriteModule.ResourceKey("attr", attrName));
                        }
                    }
                }
            }
        } catch (Exception invalid) {
            throw new KaleidoDiagnostic("KLD-BUNDLE-001", project, variant,
                    "bundle-rewrite", BundleRewriteArtifacts.PLAN_SCHEMA, file.toString(),
                    "Application values XML cannot be inventoried safely",
                    "Fix the malformed values resource before Release").failure();
        }
    }

    private static void scanToolsKeep(
            Path file, Set<String> selectors, String project, String variant) {
        try {
            var document = secureFactory().newDocumentBuilder().parse(file.toFile());
            var elements = document.getElementsByTagName("*");
            for (var index = 0; index < elements.getLength(); index++) {
                var element = (Element) elements.item(index);
                var keep = element.getAttributeNS("http://schemas.android.com/tools", "keep");
                if (keep.isBlank()) continue;
                for (var value : keep.split(",")) {
                    var normalized = value.trim();
                    if (normalized.startsWith("@")) normalized = normalized.substring(1);
                    var colon = normalized.indexOf(':');
                    if (colon >= 0) normalized = normalized.substring(colon + 1);
                    if (normalized.matches("[a-z*][a-z0-9_*?]*/[a-z0-9_*?]+")) {
                        selectors.add(normalized);
                    }
                }
            }
        } catch (Exception invalid) {
            throw new KaleidoDiagnostic("KLD-BUNDLE-001", project, variant,
                    "bundle-rewrite", BundleRewriteArtifacts.PLAN_SCHEMA, file.toString(),
                    "Application XML protection attributes cannot be inventoried safely",
                    "Fix the malformed resource XML before Release").failure();
        }
    }

    private static DocumentBuilderFactory secureFactory() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    private static Pattern glob(String selector) {
        var regex = new StringBuilder("^");
        for (var character : selector.toCharArray()) {
            if (character == '*') regex.append(".*");
            else if (character == '?') regex.append('.');
            else regex.append(Pattern.quote(Character.toString(character)));
        }
        return Pattern.compile(regex.append('$').toString());
    }

    private static String valuesType(String tag, String declaredType) {
        if ("item".equals(tag) || "public".equals(tag)) {
            return declaredType.isBlank() ? null : declaredType;
        }
        if ("string-array".equals(tag) || "integer-array".equals(tag)) return "array";
        if ("declare-styleable".equals(tag)) return "styleable";
        if (Set.of("eat-comment", "skip", "overlayable").contains(tag)) return null;
        return tag;
    }

    private static String resourceFileName(String fileName) {
        var ninePatch = fileName.indexOf(".9.");
        if (ninePatch > 0) return fileName.substring(0, ninePatch);
        var dot = fileName.indexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    record Result(Set<BundleRewriteModule.ResourceKey> resources,
                  Set<String> protectedNames, List<String> warnings) {
        Result { warnings = warnings.stream().sorted().toList(); }
    }
}
