package com.tongsr.kaleido.release

import com.android.tools.build.bundletool.commands.InstallApksCommand
import com.android.tools.build.bundletool.device.DdmlibAdbServer
import java.nio.file.Path
import java.time.Duration

/** Installs one prepared APK set through the pinned bundletool implementation. */
object RuntimeGateInstallCli {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 3) {
            "usage: RuntimeGateInstallCli <apks> <adb> <device-serial>"
        }
        val adbPath = Path.of(arguments[1])
        val server = DdmlibAdbServer.getInstance()
        server.init(adbPath)
        try {
            InstallApksCommand.builder()
                .setApksArchivePath(Path.of(arguments[0]))
                .setAdbPath(adbPath)
                .setAdbServer(server)
                .setDeviceId(arguments[2])
                .setAllowDowngrade(true)
                .setTimeout(Duration.ofMinutes(2))
                .build()
                .execute()
        } finally {
            server.close()
        }
    }
}
