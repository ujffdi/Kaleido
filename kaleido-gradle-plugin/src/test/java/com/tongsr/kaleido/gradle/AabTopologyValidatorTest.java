package com.tongsr.kaleido.gradle;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.gradle.api.GradleException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class AabTopologyValidatorTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static final AabTopologyValidator.Context CONTEXT =
            new AabTopologyValidator.Context(":app", "release");

    @Test
    public void acceptsOnlyRegularBaseFeatureModule() {
        AabTopologyValidator.validateModel(
                new AabTopologyValidator.TopologyModel(
                        "REGULAR",
                        List.of(new AabTopologyValidator.ModuleFact(
                                "base", "FEATURE_MODULE", false)),
                        0),
                CONTEXT);
    }

    @Test
    public void rejectsUnknownModuleType() {
        var failure = assertThrows(GradleException.class, () ->
                AabTopologyValidator.validateModel(
                        new AabTopologyValidator.TopologyModel(
                                "REGULAR",
                                List.of(new AabTopologyValidator.ModuleFact(
                                        "base", "UNKNOWN_MODULE_TYPE", false)),
                                0),
                        CONTEXT));

        assertTrue(failure.getMessage().contains("KLD-TOPOLOGY-009"));
    }

    @Test
    public void rejectsFeatureAssetMlSdkAndInstantTopologies() {
        for (var module : List.of(
                new AabTopologyValidator.ModuleFact("feature", "FEATURE_MODULE", false),
                new AabTopologyValidator.ModuleFact("assets", "ASSET_MODULE", false),
                new AabTopologyValidator.ModuleFact("model", "ML_MODULE", false),
                new AabTopologyValidator.ModuleFact("sdk", "SDK_DEPENDENCY_MODULE", false),
                new AabTopologyValidator.ModuleFact("base", "FEATURE_MODULE", true))) {
            assertThrows(GradleException.class, () -> AabTopologyValidator.validateModel(
                    new AabTopologyValidator.TopologyModel(
                            "REGULAR", List.of(module), 0),
                    CONTEXT));
        }
        assertThrows(GradleException.class, () -> AabTopologyValidator.validateModel(
                new AabTopologyValidator.TopologyModel(
                        "REGULAR",
                        List.of(new AabTopologyValidator.ModuleFact(
                                "base", "FEATURE_MODULE", false)),
                        1),
                CONTEXT));
    }

    @Test
    public void rejectsNonRegularBundle() {
        var failure = assertThrows(GradleException.class, () ->
                AabTopologyValidator.validateModel(
                        new AabTopologyValidator.TopologyModel("ASSET_ONLY", List.of(), 0),
                        CONTEXT));

        assertTrue(failure.getMessage().contains("KLD-TOPOLOGY-008"));
    }

    @Test
    public void rejectsRepresentativeDynamicCodeAndHotfixSignals() throws Exception {
        var signals = List.of(
                new String[] {"SplitInstall", "Lcom/google/android/play/core/splitinstall/"},
                new String[] {"Tinker", "Lcom/tencent/tinker/"},
                new String[] {"RePlugin", "Lcom/qihoo360/replugin/"},
                new String[] {"VirtualAPK", "Lcom/didi/virtualapk/"});
        for (var signal : signals) {
            var bundle = temporaryFolder.newFile(signal[0] + ".aab").toPath();
            try (var output = new ZipOutputStream(Files.newOutputStream(bundle))) {
                output.putNextEntry(new ZipEntry("base/dex/classes.dex"));
                output.write(("prefix" + signal[1] + "suffix").getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
            try (var zip = new ZipFile(bundle.toFile())) {
                var failure = assertThrows(GradleException.class, () ->
                        AabTopologyValidator.validateDynamicSignals(zip, CONTEXT));
                assertTrue(failure.getMessage().contains("KLD-TOPOLOGY-007"));
                assertTrue(failure.getMessage().contains("target=" + signal[0]));
            }
        }
    }
}
