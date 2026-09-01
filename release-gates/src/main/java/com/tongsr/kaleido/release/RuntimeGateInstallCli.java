package com.tongsr.kaleido.release;

import com.android.tools.build.bundletool.commands.InstallApksCommand;
import com.android.tools.build.bundletool.device.DdmlibAdbServer;
import java.nio.file.Path;
import java.time.Duration;

/** Installs one prepared APK set through the pinned bundletool implementation. */
public final class RuntimeGateInstallCli {
    private RuntimeGateInstallCli() {}

    public static void main(String[] arguments) {
        if (arguments.length != 3) {
            throw new IllegalArgumentException(
                    "usage: RuntimeGateInstallCli <apks> <adb> <device-serial>");
        }
        var adbPath = Path.of(arguments[1]);
        var server = DdmlibAdbServer.getInstance();
        server.init(adbPath);
        try {
            InstallApksCommand.builder()
                    .setApksArchivePath(Path.of(arguments[0]))
                    .setAdbPath(adbPath)
                    .setAdbServer(server)
                    .setDeviceId(arguments[2])
                    .setAllowDowngrade(true)
                    .setTimeout(Duration.ofMinutes(2))
                    .build()
                    .execute();
        } finally {
            server.close();
        }
    }
}
