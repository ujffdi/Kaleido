package com.tongsr.kaleido.gradle

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeSet
import java.util.regex.Pattern

/** Early source evidence needed before semantic Manifest/XML class rewrites run. */
internal object SourceProtectionScanner {
    private val PACKAGE = Pattern.compile("(?m)\\bpackage\\s+([A-Za-z_\$][A-Za-z0-9_\$\\.]*)\\s*;?")
    private val DECLARATION =
        Pattern.compile("\\b(?:class|interface|enum|object)\\s+([A-Za-z_\$][A-Za-z0-9_\$]*)")
    private val MANAGED_NATIVE = Pattern.compile("\\bnative\\b|\\bexternal\\s+fun\\b")
    private val EXACT_REFLECTION = Pattern.compile(
        "(?:Class\\s*\\.\\s*forName|loadClass)\\s*\\(\\s*\"" +
            "([A-Za-z_\$][A-Za-z0-9_\$\\.]*[A-Za-z0-9_\$])\"",
    )

    @JvmStatic
    fun inferProtectedIdentities(
        sourceRoots: Collection<File>,
        candidateIdentities: Set<String>,
    ): Set<String> {
        val protectedNames = TreeSet<String>()
        for (root in sourceRoots.map { it.toPath() }
            .filter { Files.isDirectory(it) }
            .sortedBy { it.toString() }) {
            Files.walk(root).use { paths ->
                for (source in paths.filter { Files.isRegularFile(it) }
                    .filter { isManagedSource(it) }
                    .sorted()
                    .toList()) {
                    val text = Files.readString(source, StandardCharsets.UTF_8)
                    val commentsRemoved = removeComments(text)
                    inferExactReflection(commentsRemoved, candidateIdentities, protectedNames)
                    val code = removeStringAndCharacterLiterals(commentsRemoved)
                    if (MANAGED_NATIVE.matcher(code).find()) {
                        inferDeclaredFamilies(code, source, candidateIdentities, protectedNames)
                    }
                }
            }
        }
        return protectedNames.toSet()
    }

    private fun inferExactReflection(
        source: String,
        candidates: Set<String>,
        protectedNames: MutableSet<String>,
    ) {
        val matcher = EXACT_REFLECTION.matcher(source)
        while (matcher.find()) {
            addCandidateFamily(matcher.group(1), candidates, protectedNames)
        }
    }

    private fun inferDeclaredFamilies(
        source: String,
        path: Path,
        candidates: Set<String>,
        protectedNames: MutableSet<String>,
    ) {
        val packageMatcher = PACKAGE.matcher(source)
        val packageName = if (packageMatcher.find()) packageMatcher.group(1) else ""
        val declarationMatcher = DECLARATION.matcher(source)
        var foundDeclaration = false
        while (declarationMatcher.find()) {
            foundDeclaration = true
            val identity = if (packageName.isEmpty()) {
                declarationMatcher.group(1)
            } else {
                packageName + "." + declarationMatcher.group(1)
            }
            addCandidateFamily(identity, candidates, protectedNames)
        }
        if (!foundDeclaration) {
            val fileName = path.fileName.toString()
            val separator = fileName.lastIndexOf('.')
            val simple = if (separator < 0) fileName else fileName.substring(0, separator)
            addCandidateFamily(
                if (packageName.isEmpty()) simple else "$packageName.$simple",
                candidates,
                protectedNames,
            )
        }
    }

    private fun addCandidateFamily(
        identity: String,
        candidates: Set<String>,
        protectedNames: MutableSet<String>,
    ) {
        candidates.filter { candidate ->
            candidate == identity || candidate.startsWith(identity + "$")
        }.forEach { protectedNames.add(it) }
    }

    private fun isManagedSource(path: Path): Boolean {
        val name = path.fileName.toString()
        return name.endsWith(".java") || name.endsWith(".kt")
    }

    private fun removeComments(input: String): String {
        val output = StringBuilder(input.length)
        var state = LexicalState.CODE
        var index = 0
        while (index < input.length) {
            val current = input[index]
            val next = if (index + 1 < input.length) input[index + 1] else '\u0000'
            if (state == LexicalState.CODE && current == '/' && next == '/') {
                output.append(' ').append(' ')
                index++
                state = LexicalState.LINE_COMMENT
            } else if (state == LexicalState.CODE && current == '/' && next == '*') {
                output.append(' ').append(' ')
                index++
                state = LexicalState.BLOCK_COMMENT
            } else if (state == LexicalState.LINE_COMMENT) {
                output.append(if (current == '\n') '\n' else ' ')
                if (current == '\n') state = LexicalState.CODE
            } else if (state == LexicalState.BLOCK_COMMENT) {
                if (current == '*' && next == '/') {
                    output.append(' ').append(' ')
                    index++
                    state = LexicalState.CODE
                } else {
                    output.append(if (current == '\n') '\n' else ' ')
                }
            } else {
                output.append(current)
            }
            index++
        }
        return output.toString()
    }

    private fun removeStringAndCharacterLiterals(input: String): String {
        val output = StringBuilder(input.length)
        var quote = '\u0000'
        var escaped = false
        for (index in input.indices) {
            val current = input[index]
            if (quote == '\u0000') {
                if (current == '"' || current == '\'') {
                    quote = current
                    output.append(' ')
                } else {
                    output.append(current)
                }
            } else {
                output.append(if (current == '\n') '\n' else ' ')
                if (escaped) {
                    escaped = false
                } else if (current == '\\') {
                    escaped = true
                } else if (current == quote) {
                    quote = '\u0000'
                }
            }
        }
        return output.toString()
    }

    private enum class LexicalState { CODE, LINE_COMMENT, BLOCK_COMMENT }
}
