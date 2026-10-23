package ml.toki.walkie;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.View;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;

public class MainActivity extends Activity {
    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Demande de permission micro au démarrage
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO_PERMISSION);
            }
        }

        // Création de l'interface visuelle
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);

        TextView statusText = new TextView(this);
        statusText.setText("Statut : Prêt à écouter");
        statusText.setTextSize(18);
        statusText.setPadding(0, 0, 0, 32);

        Button talkButton = new Button(this);
        talkButton.setText("APPUYER POUR PARLER");
        talkButton.setTextSize(18);

        // Action quand on appuie sur le bouton
        talkButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                statusText.setText("Statut : Transmission en cours...");
            }
        });

        layout.addView(statusText);
        layout.addView(talkButton);

        setContentView(layout);
    }
}
