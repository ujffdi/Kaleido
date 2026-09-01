package example.kaleido.runtime;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        var view = new TextView(this);
        view.setText("Kaleido runtime smoke");
        setContentView(view);
    }
}
