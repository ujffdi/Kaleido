package com.tongsr.kaleido.gradle

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Properties

internal object KaleidoPluginVersion {
    const val RESOURCE_PATH: String = "META-INF/kaleido/version.properties"
    private const val VERSION_KEY: String = "pluginVersion"
    private const val PUBLICATION_ERROR_CODE: String = "KLD-PUBLICATION-001"

    fun current(): String = read(
        KaleidoPluginVersion::class.java.classLoader.getResourceAsStream(RESOURCE_PATH),
    )

    internal fun read(resource: InputStream?): String {
        if (resource == null) return requireValid(null)
        val properties = Properties()
        resource.reader(StandardCharsets.UTF_8).use(properties::load)
        return requireValid(properties.getProperty(VERSION_KEY))
    }

    internal fun requireValid(rawVersion: String?): String {
        val version = rawVersion?.trim()
        if (version.isNullOrEmpty() || version.equals("unspecified", ignoreCase = true)) {
            throw IllegalArgumentException(
                "$PUBLICATION_ERROR_CODE Plugin version resource $RESOURCE_PATH " +
                    "is missing, blank, or unspecified",
            )
        }
        return version
    }
}
