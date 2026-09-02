package com.tongsr.kaleido.matrix.nativeprobe

import android.app.Activity
import android.os.Bundle
import android.util.Log

class MainActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val resource = getString(R.string.probe_value)
        val answer = nativeAnswer()
        if (resource != "resource-ok" || answer != 42) {
            throw IllegalStateException("runtime probe failed")
        }
        Log.i(
            "KaleidoRuntimeProbe",
            "KALEIDO_PROBE_PASS resource=$resource native=$answer",
        )
    }

    companion object {
        init {
            System.loadLibrary("kaleido_probe")
        }

        @JvmStatic
        private external fun nativeAnswer(): Int
    }
}
