package com.tongsr.kaleido.sample

import android.content.res.Configuration
import android.content.res.XmlResourceParser
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<ComposeView>(R.id.compose_probe).setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.compose_probe_title))
                        Text(stringResource(R.string.sample_status))
                        Text(stringResource(R.string.compose_probe_message))
                    }
                }
            }
        }

        val passed = mutableListOf<String>()
        val failed = mutableListOf<String>()
        runCheck("layout", passed, failed) {
            check(findViewById<android.view.View>(R.id.icon_grid) != null) {
                "icon grid is missing"
            }
            check(findViewById<android.view.View>(R.id.compose_probe) != null) {
                "Compose probe is missing"
            }
        }
        runCheck("direct-values", passed, failed) {
            check(getString(R.string.public_label) == "public-ok") { "public string changed" }
            check(getString(R.string.kept_label) == "kept-ok") { "tools:keep string changed" }
            check(resources.getDimensionPixelSize(R.dimen.probe_icon_size) > 0) {
                "dimension is unavailable"
            }
        }
        runCheck("getIdentifier", passed, failed) {
            val identifier = resources.getIdentifier("runtime_label", "string", packageName)
            check(identifier != 0) { "runtime_label name was not retained" }
            check(getString(identifier) == "runtime-ok") { "runtime_label value changed" }
        }
        runCheck("protected-class", passed, failed) {
            val type = Class.forName("com.tongsr.kaleido.sample.RuntimeProtectedEntry")
            check(type.getDeclaredConstructor().newInstance() != null) {
                "protected class could not be constructed"
            }
        }
        runCheck("protected-layout", passed, failed) {
            check(layoutInflater.inflate(R.layout.protected_probe_layout, null) != null) {
                "protected layout could not be inflated"
            }
        }
        runCheck("raw", passed, failed) {
            check(
                resources.openRawResource(R.raw.probe_payload).bufferedReader().use {
                    it.readText()
                }.contains("\"probe\":\"raw-ok\""),
            ) { "raw payload changed" }
        }
        runCheck("assets", passed, failed) {
            check(
                assets.open("probe_control.json").bufferedReader().use {
                    it.readText()
                }.contains("\"probe\":\"asset-ok\""),
            ) { "asset payload changed" }
        }
        runCheck("xml", passed, failed) {
            check(readProbeXml() == "xml-ok") { "XML resource changed" }
        }
        runCheck("zh-rCN", passed, failed) {
            val configuration = Configuration(resources.configuration)
            configuration.setLocale(Locale.forLanguageTag("zh-CN"))
            val localized = createConfigurationContext(configuration)
                .getString(R.string.locale_probe)
            check(localized == "locale-zh-cn") { "Chinese configuration is unavailable" }
        }
        runCheck("night", passed, failed) {
            val dayConfiguration = Configuration(resources.configuration)
            dayConfiguration.uiMode = dayConfiguration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK.inv() or Configuration.UI_MODE_NIGHT_NO
            val dayColor = createConfigurationContext(dayConfiguration)
                .getColor(R.color.probe_mode_marker)
            val nightConfiguration = Configuration(resources.configuration)
            nightConfiguration.uiMode = nightConfiguration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK.inv() or Configuration.UI_MODE_NIGHT_YES
            val nightColor = createConfigurationContext(nightConfiguration)
                .getColor(R.color.probe_mode_marker)
            check(dayColor != nightColor) { "night color did not override the default" }
        }
        runCheck("vectors", passed, failed) {
            val vectors = intArrayOf(
                R.drawable.ic_arrow_left_long,
                R.drawable.ic_arrow_left_long_duplicate,
                R.drawable.ic_arrow_up_right_long,
                R.drawable.ic_arrow_up_right_long_duplicate,
                R.drawable.ic_arrow_up_long,
                R.drawable.ic_arrow_up_long_compact,
                R.drawable.ic_chevron_up,
                R.drawable.ic_arrow_right_circle,
                R.drawable.ic_arrow_next_bar,
                R.drawable.ic_chevron_left,
                R.drawable.ic_arrow_down_right,
            )
            vectors.forEach { vector ->
                val drawable: Drawable? = getDrawable(vector)
                check(drawable != null) { "vector failed to load: $vector" }
            }
            check(R.drawable.ic_arrow_left_long != R.drawable.ic_arrow_left_long_duplicate) {
                "left-arrow duplicate IDs merged"
            }
            check(
                R.drawable.ic_arrow_up_right_long !=
                    R.drawable.ic_arrow_up_right_long_duplicate,
            ) { "up-right duplicate IDs merged" }
        }

        renderStatus(passed, failed)
    }

    private fun renderStatus(passed: List<String>, failed: List<String>) {
        val status = findViewById<TextView>(R.id.probe_status)
        if (failed.isEmpty()) {
            status.text = getString(
                R.string.status_pass,
                passed.size,
                TextUtils.join("\n", passed),
            )
            status.setTextColor(getColor(R.color.probe_status_pass))
            Log.i(LOG_TAG, "KALEIDO_RESOURCE_PROBE_PASS checks=${passed.size}")
        } else {
            status.text = getString(
                R.string.status_fail,
                failed.size,
                TextUtils.join("\n", failed),
            )
            status.setTextColor(getColor(R.color.probe_status_fail))
            Log.e(LOG_TAG, "KALEIDO_RESOURCE_PROBE_FAIL checks=${failed.joinToString(",")}")
        }
    }

    private fun readProbeXml(): String? {
        resources.getXml(R.xml.probe_config).use { parser ->
            while (
                parser.eventType != XmlResourceParser.START_TAG &&
                parser.eventType != XmlResourceParser.END_DOCUMENT
            ) {
                parser.next()
            }
            return parser.getAttributeValue(null, "value")
        }
    }

    private fun runCheck(
        name: String,
        passed: MutableList<String>,
        failed: MutableList<String>,
        probe: () -> Unit,
    ) {
        try {
            probe()
            passed += "PASS $name"
        } catch (exception: Exception) {
            failed += "FAIL $name: ${exception.message}"
        }
    }

    private companion object {
        const val LOG_TAG = "KaleidoResourceProbe"
    }
}
