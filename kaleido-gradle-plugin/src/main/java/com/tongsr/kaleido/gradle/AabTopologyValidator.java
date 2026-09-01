package com.tongsr.kaleido.gradle;

import com.android.bundle.Config.BundleConfig.BundleType;
import com.android.tools.build.bundletool.model.AppBundle;
import com.android.tools.build.bundletool.model.BundleModule.ModuleType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class AabTopologyValidator {
    private static final List<DynamicSignal> DYNAMIC_SIGNALS = List.of(
            new DynamicSignal("DexClassLoader", "Ldalvik/system/DexClassLoader;"),
            new DynamicSignal("InMemoryDexClassLoader", "Ldalvik/system/InMemoryDexClassLoader;"),
            new DynamicSignal("SplitInstall", "Lcom/google/android/play/core/splitinstall/"),
            new DynamicSignal("Tinker", "Lcom/tencent/tinker/"),
            new DynamicSignal("RePlugin", "Lcom/qihoo360/replugin/"),
            new DynamicSignal("VirtualAPK", "Lcom/didi/virtualapk/"));

    private AabTopologyValidator() {}

    static void validate(Path bundlePath, Context context) {
        try (var zipFile = new ZipFile(bundlePath.toFile())) {
            var bundle = AppBundle.buildFromZip(zipFile);
            var modules = bundle.getModules().values().stream()
                    .map(module -> new ModuleFact(
                            module.getName().getName(),
                            module.getModuleType().name(),
                            module.isInstantModule()))
                    .sorted(Comparator.comparing(ModuleFact::name))
                    .toList();
            validateModel(new TopologyModel(
                    bundle.getBundleConfig().getType().name(),
                    modules,
                    bundle.getRuntimeEnabledSdkDependencies().size()), context);
            validateDynamicSignals(zipFile, context);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof org.gradle.api.GradleException gradleException) {
                throw gradleException;
            }
            throw diagnostic(
                    "KLD-TOPOLOGY-008",
                    context,
                    "final-aab",
                    "Final AAB could not be parsed as an Android App Bundle",
                    "Rebuild a structurally valid regular Android App Bundle").failure();
        }
    }

    static void validateModel(TopologyModel model, Context context) {
        if (!BundleType.REGULAR.name().equals(model.bundleType())) {
            throw diagnostic(
                    "KLD-TOPOLOGY-008",
                    context,
                    model.bundleType(),
                    "Only a REGULAR App Bundle is supported",
                    "Produce a regular base-only application bundle").failure();
        }
        var supportedBase = model.modules().size() == 1
                && "base".equals(model.modules().get(0).name())
                && ModuleType.FEATURE_MODULE.name().equals(model.modules().get(0).type())
                && !model.modules().get(0).instant();
        if (!supportedBase || model.runtimeEnabledSdkCount() != 0) {
            throw diagnostic(
                    "KLD-TOPOLOGY-009",
                    context,
                    model.modules().toString(),
                    "Final AAB topology is not regular base-only",
                    "Remove dynamic, asset, instant, ML, SDK, or unknown modules").failure();
        }
    }

    static void validateDynamicSignals(ZipFile zipFile, Context context) throws IOException {
        var dexEntries = new ArrayList<ZipEntry>();
        zipFile.stream()
                .filter(entry -> !entry.isDirectory())
                .filter(entry -> entry.getName().startsWith("base/dex/"))
                .filter(entry -> entry.getName().endsWith(".dex"))
                .sorted(Comparator.comparing(ZipEntry::getName))
                .forEach(dexEntries::add);
        for (var signal : DYNAMIC_SIGNALS) {
            var needle = signal.descriptor().getBytes(StandardCharsets.UTF_8);
            for (var entry : dexEntries) {
                try (var input = zipFile.getInputStream(entry)) {
                    if (contains(input, needle)) {
                        throw diagnostic(
                                "KLD-TOPOLOGY-007",
                                context,
                                signal.name(),
                                "Confirmed unsupported dynamic-code signal was found",
                                "Remove the dynamic loading, SplitInstall, plugin, or hotfix mechanism")
                                .failure();
                    }
                }
            }
        }
    }

    private static boolean contains(InputStream input, byte[] pattern) throws IOException {
        var prefix = new int[pattern.length];
        for (int index = 1, matched = 0; index < pattern.length; index++) {
            while (matched > 0 && pattern[index] != pattern[matched]) {
                matched = prefix[matched - 1];
            }
            if (pattern[index] == pattern[matched]) {
                matched++;
            }
            prefix[index] = matched;
        }
        for (int value, matched = 0; (value = input.read()) != -1; ) {
            var current = (byte) value;
            while (matched > 0 && current != pattern[matched]) {
                matched = prefix[matched - 1];
            }
            if (current == pattern[matched] && ++matched == pattern.length) {
                return true;
            }
        }
        return false;
    }

    private static KaleidoDiagnostic diagnostic(
            String code, Context context, String target, String reason, String repair) {
        return new KaleidoDiagnostic(
                code,
                context.projectPath(),
                context.variant(),
                "adoption",
                "final-aab",
                target,
                reason,
                repair);
    }

    record Context(String projectPath, String variant) {}

    record ModuleFact(String name, String type, boolean instant) {}

    record TopologyModel(String bundleType, List<ModuleFact> modules, int runtimeEnabledSdkCount) {
        TopologyModel {
            modules = modules.stream().sorted(Comparator.comparing(ModuleFact::name)).toList();
        }
    }

    private record DynamicSignal(String name, String descriptor) {}
}
