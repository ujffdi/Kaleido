package com.tongsr.kaleido.release;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

public final class CompatibilityFixtureContractTest {
    private final Path root = Path.of(System.getProperty("kaleido.repository.root"));

    @Test
    public void publicFixturesResolveThePackagedMarkerWithoutImplementationShortcuts()
            throws Exception {
        for (var relative : List.of(
                "release/fixtures/java-safe",
                "release/fixtures/kotlin-safe",
                "release/fixtures/full-compose",
                "release/fixtures/native-resource",
                "samples/kaleido-sample")) {
            var fixture = root.resolve(relative);
            var settings = Files.readString(fixture.resolve("settings.gradle.kts"));
            var build = Files.readString(fixture.resolve("app/build.gradle.kts"));
            assertTrue(relative, settings.contains("matrixPluginRepository"));
            assertTrue(relative, settings.contains("com.tongsr.kaleido"));
            assertTrue(relative, build.contains("id(\"com.tongsr.kaleido\")"));
            assertFalse(relative, settings.contains("includeBuild"));
            assertFalse(relative, settings.contains("implementationClass"));
            assertFalse(relative, build.contains("implementationClass"));
        }
    }

    @Test
    public void fixturesCoverJavaBuiltInKotlinFullComposeAndSampleContracts()
            throws Exception {
        var javaFixture = root.resolve("release/fixtures/java-safe");
        assertTrue(Files.isRegularFile(javaFixture.resolve(
                "app/src/main/java/com/tongsr/kaleido/matrix/java/MainActivity.java")));
        assertFalse(Files.exists(javaFixture.resolve("app/src/main/kotlin")));

        var kotlinFixture = root.resolve("release/fixtures/kotlin-safe");
        assertTrue(Files.isRegularFile(kotlinFixture.resolve(
                "app/src/main/kotlin/com/tongsr/kaleido/matrix/kotlin/MainActivity.kt")));
        var kotlinBuild = Files.readString(kotlinFixture.resolve("app/build.gradle.kts"));
        assertFalse(kotlinBuild.contains("org.jetbrains.kotlin.android"));

        var composeBuild = Files.readString(root.resolve(
                "release/fixtures/full-compose/app/build.gradle.kts"));
        assertTrue(composeBuild.contains("KaleidoProfile.FULL"));
        assertTrue(composeBuild.contains("enabled.set(true)"));
        assertTrue(composeBuild.contains("org.jetbrains.kotlin.plugin.compose"));

        var nativeBuild = Files.readString(root.resolve(
                "release/fixtures/native-resource/app/build.gradle.kts"));
        assertTrue(nativeBuild.contains("ndkVersion = \"25.1.8937393\""));
        assertTrue(Files.isRegularFile(root.resolve(
                "release/fixtures/native-resource/app/src/main/cpp/native_probe.cpp")));

        assertTrue(Files.isRegularFile(root.resolve(
                "samples/kaleido-sample/app/src/main/java/com/tongsr/kaleido/sample/"
                        + "MainActivity.java")));
    }
}
