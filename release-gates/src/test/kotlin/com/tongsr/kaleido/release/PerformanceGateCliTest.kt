package com.tongsr.kaleido.release

import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceGateCliTest {
    @Test
    fun acceptsMeasurementsAtAllBudgets() {
        val values = passing()
        val verdict = PerformanceGateCli.evaluate(values)
        assertEquals(emptyList<String>(), verdict.failures)
        assertTrue(verdict.record.endsWith("verdict=PASS\n"))
    }

    @Test
    fun rejectsThresholdFailure() {
        val values = passing()
        values.setProperty("plugin.jarBytes", (10L * 1024 * 1024 + 1).toString())
        assertEquals(1, PerformanceGateCli.evaluate(values).failures.size)
    }

    companion object {
        private fun passing(): Properties {
            val values = Properties()
            values.setProperty("candidate.sha256", "abc")
            values.setProperty("environment.os", "Linux")
            values.setProperty("environment.arch", "x86_64")
            values.setProperty("complexity.verdict", "PASS")
            values.setProperty("safe.clean.baselineSeconds", "1,1,100,100,100,100,100")
            values.setProperty("safe.clean.candidateSeconds", "1,1,120,120,120,120,120")
            values.setProperty("full.clean.baselineSeconds", "1,1,100,100,100,100,100")
            values.setProperty("full.clean.candidateSeconds", "1,1,130,130,130,130,130")
            values.setProperty("warm.noClean.baselineSeconds", "1,1,10,10,10,10,10")
            values.setProperty("warm.noClean.candidateSeconds", "1,1,20,20,20,20,20")
            values.setProperty("memory.peakMib.baseline", "1,1,1000,1000,1000,1000,1000")
            values.setProperty("memory.peakMib.candidate", "1,1,1400,1400,1400,1400,1400")
            for (prefix in arrayOf(
                "sana.safe",
                "sana.full",
                "sana.compose512",
                "sample.safe",
                "sample.full",
            )) {
                values.setProperty("$prefix.baselineBytes", "1000000")
                values.setProperty("$prefix.candidateBytes", "1000000")
            }
            values.setProperty("plugin.jarBytes", "1000")
            values.setProperty("dependencies.newBytes", "1000")
            return values
        }
    }
}
