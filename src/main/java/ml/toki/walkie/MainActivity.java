package ml.toki.walkie;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.ScrollView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView textView = new TextView(this);
        textView.setPadding(32, 32, 32, 32);
        textView.setTextSize(16);

        try {
            textView.setText("Toki Walkie démarre correctement !");
        } catch (Throwable t) {
            textView.setText("Erreur détectée :\n\n" + t.toString());
        }

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(textView);
        setContentView(scrollView);
    }
}
