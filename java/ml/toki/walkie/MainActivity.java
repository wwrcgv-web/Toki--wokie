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
            // Test d'affichage simple
            textView.setText("Toki Walkie démarre correctement !");
        } catch (Throwable t) {
            // Affiche l'erreur exacte sur l'écran si ça plante
            textView.setText("Erreur détectée :\n\n" + t.toString());
        }

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(textView);
        setContentView(scrollView);
    }
}
