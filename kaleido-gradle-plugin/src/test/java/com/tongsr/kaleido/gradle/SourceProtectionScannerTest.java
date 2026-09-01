package com.tongsr.kaleido.gradle;

import static org.junit.Assert.assertEquals;

import java.nio.file.Files;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class SourceProtectionScannerTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void managedNativeAndExactReflectionProtectOnlyCandidateIdentities()
            throws Exception {
        var root = temporaryFolder.newFolder("sources").toPath();
        var java = root.resolve("example/MainActivity.java");
        Files.createDirectories(java.getParent());
        Files.writeString(java, """
                package example;
                // native must not count from comments.
                public final class MainActivity {
                    public native int answer();
                    static Class<?> target() throws Exception {
                        return Class.forName("example.ReflectiveTarget");
                    }
                }
                """);
        Files.writeString(root.resolve("example/Other.kt"), """
                package example
                class Other { external fun call(): Int }
                """);

        assertEquals(Set.of(
                        "example.MainActivity", "example.ReflectiveTarget", "example.Other"),
                SourceProtectionScanner.inferProtectedIdentities(
                        Set.of(root.toFile()), Set.of(
                                "example.MainActivity", "example.ReflectiveTarget",
                                "example.Other", "example.Unrelated")));
    }

    @Test
    public void commentAndStringKeywordsDoNotCreateNativeProtection() throws Exception {
        var root = temporaryFolder.newFolder("non-native").toPath();
        var source = root.resolve("example/Plain.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package example;
                public final class Plain {
                    String text = "native external fun";
                    /* native int ignored(); */
                }
                """);
        assertEquals(Set.of(), SourceProtectionScanner.inferProtectedIdentities(
                Set.of(root.toFile()), Set.of("example.Plain")));
    }
}
