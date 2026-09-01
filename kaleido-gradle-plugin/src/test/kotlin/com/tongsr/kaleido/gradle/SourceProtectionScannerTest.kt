package com.tongsr.kaleido.gradle

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceProtectionScannerTest {
    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun managedNativeAndExactReflectionProtectOnlyCandidateIdentities() {
        val root = temporaryFolder.newFolder("sources").toPath()
        val java = root.resolve("example/MainActivity.java")
        Files.createDirectories(java.parent)
        Files.writeString(
            java,
            """
                package example;
                // native must not count from comments.
                public final class MainActivity {
                    public native int answer();
                    static Class<?> target() throws Exception {
                        return Class.forName("example.ReflectiveTarget");
                    }
                }
            """.trimIndent(),
        )
        Files.writeString(
            root.resolve("example/Other.kt"),
            """
                package example
                class Other { external fun call(): Int }
            """.trimIndent(),
        )

        assertEquals(
            setOf("example.MainActivity", "example.ReflectiveTarget", "example.Other"),
            SourceProtectionScanner.inferProtectedIdentities(
                setOf(root.toFile()),
                setOf(
                    "example.MainActivity",
                    "example.ReflectiveTarget",
                    "example.Other",
                    "example.Unrelated",
                ),
            ),
        )
    }

    @Test
    fun commentAndStringKeywordsDoNotCreateNativeProtection() {
        val root = temporaryFolder.newFolder("non-native").toPath()
        val source = root.resolve("example/Plain.java")
        Files.createDirectories(source.parent)
        Files.writeString(
            source,
            """
                package example;
                public final class Plain {
                    String text = "native external fun";
                    /* native int ignored(); */
                }
            """.trimIndent(),
        )
        assertEquals(
            setOf<String>(),
            SourceProtectionScanner.inferProtectedIdentities(
                setOf(root.toFile()),
                setOf("example.Plain"),
            ),
        )
    }
}
