package com.tongsr.kaleido.matrix.kotlin

import android.app.Activity
import android.os.Bundle
import android.util.Log

class MainActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Log.i("KaleidoRuntimeProbe", "KALEIDO_PROBE_PASS resource=AppTheme")
    }
}
