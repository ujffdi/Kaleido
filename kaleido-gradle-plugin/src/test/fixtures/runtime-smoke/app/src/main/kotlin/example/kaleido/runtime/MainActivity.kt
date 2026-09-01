package example.kaleido.runtime

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val view = TextView(this)
        view.text = "Kaleido runtime smoke"
        setContentView(view)
    }
}
