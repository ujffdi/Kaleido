package com.tongsr.kaleido.gradle

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.gradle.api.GradleException
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AabTopologyValidatorTest {
    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun acceptsOnlyRegularBaseFeatureModule() {
        AabTopologyValidator.validateModel(
            AabTopologyValidator.TopologyModel(
                "REGULAR",
                listOf(AabTopologyValidator.ModuleFact("base", "FEATURE_MODULE", false)),
                0,
            ),
            CONTEXT,
        )
    }

    @Test
    fun rejectsUnknownModuleType() {
        val failure = assertThrows(GradleException::class.java) {
            AabTopologyValidator.validateModel(
                AabTopologyValidator.TopologyModel(
                    "REGULAR",
                    listOf(
                        AabTopologyValidator.ModuleFact(
                            "base",
                            "UNKNOWN_MODULE_TYPE",
                            false,
                        ),
                    ),
                    0,
                ),
                CONTEXT,
            )
        }

        assertTrue(failure.message!!.contains("KLD-TOPOLOGY-009"))
    }

    @Test
    fun rejectsFeatureAssetMlSdkAndInstantTopologies() {
        for (module in listOf(
            AabTopologyValidator.ModuleFact("feature", "FEATURE_MODULE", false),
            AabTopologyValidator.ModuleFact("assets", "ASSET_MODULE", false),
            AabTopologyValidator.ModuleFact("model", "ML_MODULE", false),
            AabTopologyValidator.ModuleFact("sdk", "SDK_DEPENDENCY_MODULE", false),
            AabTopologyValidator.ModuleFact("base", "FEATURE_MODULE", true),
        )) {
            assertThrows(GradleException::class.java) {
                AabTopologyValidator.validateModel(
                    AabTopologyValidator.TopologyModel("REGULAR", listOf(module), 0),
                    CONTEXT,
                )
            }
        }
        assertThrows(GradleException::class.java) {
            AabTopologyValidator.validateModel(
                AabTopologyValidator.TopologyModel(
                    "REGULAR",
                    listOf(
                        AabTopologyValidator.ModuleFact(
                            "base",
                            "FEATURE_MODULE",
                            false,
                        ),
                    ),
                    1,
                ),
                CONTEXT,
            )
        }
    }

    @Test
    fun rejectsNonRegularBundle() {
        val failure = assertThrows(GradleException::class.java) {
            AabTopologyValidator.validateModel(
                AabTopologyValidator.TopologyModel("ASSET_ONLY", listOf(), 0),
                CONTEXT,
            )
        }

        assertTrue(failure.message!!.contains("KLD-TOPOLOGY-008"))
    }

    @Test
    fun rejectsRepresentativeDynamicCodeAndHotfixSignals() {
        val signals = listOf(
            arrayOf("SplitInstall", "Lcom/google/android/play/core/splitinstall/"),
            arrayOf("Tinker", "Lcom/tencent/tinker/"),
            arrayOf("RePlugin", "Lcom/qihoo360/replugin/"),
            arrayOf("VirtualAPK", "Lcom/didi/virtualapk/"),
        )
        for (signal in signals) {
            val bundle = temporaryFolder.newFile(signal[0] + ".aab").toPath()
            ZipOutputStream(Files.newOutputStream(bundle)).use { output ->
                output.putNextEntry(ZipEntry("base/dex/classes.dex"))
                output.write(("prefix" + signal[1] + "suffix").toByteArray(StandardCharsets.UTF_8))
                output.closeEntry()
            }
            ZipFile(bundle.toFile()).use { zip ->
                val failure = assertThrows(GradleException::class.java) {
                    AabTopologyValidator.validateDynamicSignals(zip, CONTEXT)
                }
                assertTrue(failure.message!!.contains("KLD-TOPOLOGY-007"))
                assertTrue(failure.message!!.contains("target=" + signal[0]))
            }
        }
    }

    companion object {
        private val CONTEXT = AabTopologyValidator.Context(":app", "release")
    }
}
