package com.tongsr.kaleido.matrix.java;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Log.i("KaleidoRuntimeProbe", "KALEIDO_PROBE_PASS resource=AppTheme");
    }
}
