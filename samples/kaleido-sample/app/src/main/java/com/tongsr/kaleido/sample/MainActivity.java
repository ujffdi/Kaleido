package com.tongsr.kaleido.sample;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        var label = new TextView(this);
        label.setText(R.string.sample_message);
        setContentView(label);
        Log.i("KaleidoRuntimeProbe", "KALEIDO_PROBE_PASS resource="
                + getString(R.string.sample_message));
    }
}
