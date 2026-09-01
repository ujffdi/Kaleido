package com.tongsr.kaleido.matrix.nativeprobe;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

public final class MainActivity extends Activity {
    static {
        System.loadLibrary("kaleido_probe");
    }

    private static native int nativeAnswer();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        var resource = getString(R.string.probe_value);
        var answer = nativeAnswer();
        if (!resource.equals("resource-ok") || answer != 42) {
            throw new IllegalStateException("runtime probe failed");
        }
        Log.i("KaleidoRuntimeProbe",
                "KALEIDO_PROBE_PASS resource=" + resource + " native=" + answer);
    }
}
