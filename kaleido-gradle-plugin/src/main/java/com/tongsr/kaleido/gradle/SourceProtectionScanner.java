package com.tongsr.kaleido.gradle;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Early source evidence needed before semantic Manifest/XML class rewrites run. */
final class SourceProtectionScanner {
    private static final Pattern PACKAGE = Pattern.compile(
            "(?m)\\bpackage\\s+([A-Za-z_$][A-Za-z0-9_$.]*)\\s*;?");
    private static final Pattern DECLARATION = Pattern.compile(
            "\\b(?:class|interface|enum|object)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
    private static final Pattern MANAGED_NATIVE = Pattern.compile(
            "\\bnative\\b|\\bexternal\\s+fun\\b");
    private static final Pattern EXACT_REFLECTION = Pattern.compile(
            "(?:Class\\s*\\.\\s*forName|loadClass)\\s*\\(\\s*\""
                    + "([A-Za-z_$][A-Za-z0-9_$.]*[A-Za-z0-9_$])\"");

    private SourceProtectionScanner() {}

    static Set<String> inferProtectedIdentities(
            Collection<File> sourceRoots, Set<String> candidateIdentities) throws IOException {
        var protectedNames = new TreeSet<String>();
        for (var root : sourceRoots.stream().map(File::toPath)
                .filter(Files::isDirectory).sorted(Comparator.comparing(Path::toString)).toList()) {
            try (var paths = Files.walk(root)) {
                for (var source : paths.filter(Files::isRegularFile)
                        .filter(SourceProtectionScanner::isManagedSource).sorted().toList()) {
                    var text = Files.readString(source, StandardCharsets.UTF_8);
                    var commentsRemoved = removeComments(text);
                    inferExactReflection(commentsRemoved, candidateIdentities, protectedNames);
                    var code = removeStringAndCharacterLiterals(commentsRemoved);
                    if (MANAGED_NATIVE.matcher(code).find()) {
                        inferDeclaredFamilies(code, source, candidateIdentities, protectedNames);
                    }
                }
            }
        }
        return Set.copyOf(protectedNames);
    }

    private static void inferExactReflection(
            String source, Set<String> candidates, Set<String> protectedNames) {
        var matcher = EXACT_REFLECTION.matcher(source);
        while (matcher.find()) {
            addCandidateFamily(matcher.group(1), candidates, protectedNames);
        }
    }

    private static void inferDeclaredFamilies(
            String source, Path path, Set<String> candidates, Set<String> protectedNames) {
        var packageMatcher = PACKAGE.matcher(source);
        var packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
        var declarationMatcher = DECLARATION.matcher(source);
        var foundDeclaration = false;
        while (declarationMatcher.find()) {
            foundDeclaration = true;
            var identity = packageName.isEmpty() ? declarationMatcher.group(1)
                    : packageName + "." + declarationMatcher.group(1);
            addCandidateFamily(identity, candidates, protectedNames);
        }
        if (!foundDeclaration) {
            var fileName = path.getFileName().toString();
            var separator = fileName.lastIndexOf('.');
            var simple = separator < 0 ? fileName : fileName.substring(0, separator);
            addCandidateFamily(packageName.isEmpty() ? simple : packageName + "." + simple,
                    candidates, protectedNames);
        }
    }

    private static void addCandidateFamily(
            String identity, Set<String> candidates, Set<String> protectedNames) {
        candidates.stream()
                .filter(candidate -> candidate.equals(identity)
                        || candidate.startsWith(identity + "$"))
                .forEach(protectedNames::add);
    }

    private static boolean isManagedSource(Path path) {
        var name = path.getFileName().toString();
        return name.endsWith(".java") || name.endsWith(".kt");
    }

    private static String removeComments(String input) {
        var output = new StringBuilder(input.length());
        var state = LexicalState.CODE;
        for (int index = 0; index < input.length(); index++) {
            var current = input.charAt(index);
            var next = index + 1 < input.length() ? input.charAt(index + 1) : '\0';
            if (state == LexicalState.CODE && current == '/' && next == '/') {
                output.append(' ').append(' ');
                index++;
                state = LexicalState.LINE_COMMENT;
            } else if (state == LexicalState.CODE && current == '/' && next == '*') {
                output.append(' ').append(' ');
                index++;
                state = LexicalState.BLOCK_COMMENT;
            } else if (state == LexicalState.LINE_COMMENT) {
                output.append(current == '\n' ? '\n' : ' ');
                if (current == '\n') state = LexicalState.CODE;
            } else if (state == LexicalState.BLOCK_COMMENT) {
                if (current == '*' && next == '/') {
                    output.append(' ').append(' ');
                    index++;
                    state = LexicalState.CODE;
                } else {
                    output.append(current == '\n' ? '\n' : ' ');
                }
            } else {
                output.append(current);
            }
        }
        return output.toString();
    }

    private static String removeStringAndCharacterLiterals(String input) {
        var output = new StringBuilder(input.length());
        var quote = '\0';
        var escaped = false;
        for (int index = 0; index < input.length(); index++) {
            var current = input.charAt(index);
            if (quote == '\0') {
                if (current == '\"' || current == '\'') {
                    quote = current;
                    output.append(' ');
                } else {
                    output.append(current);
                }
            } else {
                output.append(current == '\n' ? '\n' : ' ');
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    quote = '\0';
                }
            }
        }
        return output.toString();
    }

    private enum LexicalState { CODE, LINE_COMMENT, BLOCK_COMMENT }
}
