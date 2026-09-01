package com.tongsr.kaleido.release;

import com.android.tools.build.bundletool.commands.BuildApksCommand;
import com.android.tools.build.bundletool.commands.ValidateBundleCommand;
import com.android.tools.build.bundletool.androidtools.Aapt2Command;
import com.android.tools.build.bundletool.model.Password;
import com.android.tools.build.bundletool.model.SigningConfiguration;
import java.nio.file.Path;
import java.util.Optional;

/** Validates one AAB and builds its device-targeted APK set with bundletool 1.18.1. */
public final class RuntimeGateCli {
    private RuntimeGateCli() {}

    public static void main(String[] arguments) {
        if (arguments.length != 7) {
            throw new IllegalArgumentException(
                    "usage: RuntimeGateCli <aab> <device-spec> <apks> <keystore> <alias> "
                            + "<password-env> <aapt2>");
        }
        var bundle = Path.of(arguments[0]);
        var deviceSpec = Path.of(arguments[1]);
        var output = Path.of(arguments[2]);
        var keyStore = Path.of(arguments[3]);
        var alias = arguments[4];
        var password = System.getenv(arguments[5]);
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("KLD-RUNTIME-001 missing test signing password");
        }
        ValidateBundleCommand.builder()
                .setBundlePath(bundle)
                .setPrintOutput(false)
                .build()
                .execute();
        var secret = Optional.of(Password.createFromStringValue("pass:" + password));
        var signing = SigningConfiguration.extractFromKeystore(
                keyStore, alias, secret, secret);
        BuildApksCommand.builder()
                .setBundlePath(bundle)
                .setDeviceSpec(deviceSpec)
                .setOutputFile(output)
                .setOverwriteOutput(true)
                .setSigningConfiguration(signing)
                .setAapt2Command(Aapt2Command.createFromExecutablePath(Path.of(arguments[6])))
                .build()
                .execute();
    }
}
