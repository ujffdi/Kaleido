package com.tongsr.kaleido.release

import com.android.tools.build.bundletool.androidtools.Aapt2Command
import com.android.tools.build.bundletool.commands.BuildApksCommand
import com.android.tools.build.bundletool.commands.ValidateBundleCommand
import com.android.tools.build.bundletool.model.Password
import com.android.tools.build.bundletool.model.SigningConfiguration
import java.nio.file.Path
import java.util.Optional

/** Validates one AAB and builds its device-targeted APK set with bundletool 1.18.1. */
object RuntimeGateCli {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 7) {
            "usage: RuntimeGateCli <aab> <device-spec> <apks> <keystore> <alias> " +
                "<password-env> <aapt2>"
        }
        val bundle = Path.of(arguments[0])
        val deviceSpec = Path.of(arguments[1])
        val output = Path.of(arguments[2])
        val keyStore = Path.of(arguments[3])
        val alias = arguments[4]
        val password = System.getenv(arguments[5])
        require(!password.isNullOrBlank()) { "KLD-RUNTIME-001 missing test signing password" }
        ValidateBundleCommand.builder()
            .setBundlePath(bundle)
            .setPrintOutput(false)
            .build()
            .execute()
        val secret = Optional.of(Password.createFromStringValue("pass:$password"))
        val signing = SigningConfiguration.extractFromKeystore(keyStore, alias, secret, secret)
        BuildApksCommand.builder()
            .setBundlePath(bundle)
            .setDeviceSpec(deviceSpec)
            .setOutputFile(output)
            .setOverwriteOutput(true)
            .setSigningConfiguration(signing)
            .setAapt2Command(Aapt2Command.createFromExecutablePath(Path.of(arguments[6])))
            .build()
            .execute()
    }
}
