package com.tongsr.kaleido.gradle

import com.android.bundle.Config.BundleConfig.BundleType
import com.android.tools.build.bundletool.model.AppBundle
import com.android.tools.build.bundletool.model.BundleModule.ModuleType
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.gradle.api.GradleException

internal object AabTopologyValidator {
    private val DYNAMIC_SIGNALS = listOf(
        DynamicSignal("DexClassLoader", "Ldalvik/system/DexClassLoader;"),
        DynamicSignal("InMemoryDexClassLoader", "Ldalvik/system/InMemoryDexClassLoader;"),
        DynamicSignal("SplitInstall", "Lcom/google/android/play/core/splitinstall/"),
        DynamicSignal("Tinker", "Lcom/tencent/tinker/"),
        DynamicSignal("RePlugin", "Lcom/qihoo360/replugin/"),
        DynamicSignal("VirtualAPK", "Lcom/didi/virtualapk/"),
    )

    @JvmStatic
    fun validate(bundlePath: Path, context: Context) {
        try {
            ZipFile(bundlePath.toFile()).use { zipFile ->
                val bundle = AppBundle.buildFromZip(zipFile)
                val modules = bundle.modules.values
                    .map { module ->
                        ModuleFact(
                            module.name.name,
                            module.moduleType.name,
                            module.isInstantModule,
                        )
                    }
                    .sortedBy { it.name }
                validateModel(
                    TopologyModel(
                        bundle.bundleConfig.type.name,
                        modules,
                        bundle.runtimeEnabledSdkDependencies.size,
                    ),
                    context,
                )
                validateDynamicSignals(zipFile, context)
            }
        } catch (exception: Exception) {
            if (exception is GradleException) throw exception
            throw diagnostic(
                "KLD-TOPOLOGY-008",
                context,
                "final-aab",
                "Final AAB could not be parsed as an Android App Bundle",
                "Rebuild a structurally valid regular Android App Bundle",
            ).failure()
        }
    }

    @JvmStatic
    fun validateModel(model: TopologyModel, context: Context) {
        if (model.bundleType != BundleType.REGULAR.name) {
            throw diagnostic(
                "KLD-TOPOLOGY-008",
                context,
                model.bundleType,
                "Only a REGULAR App Bundle is supported",
                "Produce a regular base-only application bundle",
            ).failure()
        }
        val supportedBase = model.modules.size == 1 &&
            model.modules[0].name == "base" &&
            model.modules[0].type == ModuleType.FEATURE_MODULE.name &&
            !model.modules[0].instant
        if (!supportedBase || model.runtimeEnabledSdkCount != 0) {
            throw diagnostic(
                "KLD-TOPOLOGY-009",
                context,
                model.modules.toString(),
                "Final AAB topology is not regular base-only",
                "Remove dynamic, asset, instant, ML, SDK, or unknown modules",
            ).failure()
        }
    }

    @JvmStatic
    fun validateDynamicSignals(zipFile: ZipFile, context: Context) {
        val dexEntries = ArrayList<ZipEntry>()
        zipFile.stream()
            .filter { entry -> !entry.isDirectory }
            .filter { entry -> entry.name.startsWith("base/dex/") }
            .filter { entry -> entry.name.endsWith(".dex") }
            .sorted(compareBy { it.name })
            .forEach { dexEntries.add(it) }
        for (signal in DYNAMIC_SIGNALS) {
            val needle = signal.descriptor.toByteArray(StandardCharsets.UTF_8)
            for (entry in dexEntries) {
                zipFile.getInputStream(entry).use { input ->
                    if (contains(input, needle)) {
                        throw diagnostic(
                            "KLD-TOPOLOGY-007",
                            context,
                            signal.name,
                            "Confirmed unsupported dynamic-code signal was found",
                            "Remove the dynamic loading, SplitInstall, plugin, or hotfix mechanism",
                        ).failure()
                    }
                }
            }
        }
    }

    private fun contains(input: InputStream, pattern: ByteArray): Boolean {
        val prefix = IntArray(pattern.size)
        var index = 1
        var matched = 0
        while (index < pattern.size) {
            while (matched > 0 && pattern[index] != pattern[matched]) {
                matched = prefix[matched - 1]
            }
            if (pattern[index] == pattern[matched]) {
                matched++
            }
            prefix[index] = matched
            index++
        }
        matched = 0
        var value = input.read()
        while (value != -1) {
            val current = value.toByte()
            while (matched > 0 && current != pattern[matched]) {
                matched = prefix[matched - 1]
            }
            if (current == pattern[matched] && ++matched == pattern.size) {
                return true
            }
            value = input.read()
        }
        return false
    }

    private fun diagnostic(
        code: String,
        context: Context,
        target: String,
        reason: String,
        repair: String,
    ): KaleidoDiagnostic = KaleidoDiagnostic(
        code,
        context.projectPath,
        context.variant,
        "adoption",
        "final-aab",
        target,
        reason,
        repair,
    )

    @JvmRecord
    data class Context(val projectPath: String, val variant: String)

    @JvmRecord
    data class ModuleFact(val name: String, val type: String, val instant: Boolean)

    class TopologyModel(
        @get:JvmName("bundleType") val bundleType: String,
        modules: List<ModuleFact>,
        @get:JvmName("runtimeEnabledSdkCount") val runtimeEnabledSdkCount: Int,
    ) {
        @get:JvmName("modules")
        val modules: List<ModuleFact> = modules.sortedBy { it.name }
    }

    @JvmRecord
    private data class DynamicSignal(val name: String, val descriptor: String)
}
