package ml.toki.walkie;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class MainActivity extends Activity {
    static final int PORT = 5005, RATE = 8000, CHUNK = 640;
    final int myId = new Random().nextInt();

    volatile boolean talking = false, busy = false, running = true;
    volatile DatagramSocket sock;
    Button btn;
    TextView status;
    WifiManager.MulticastLock lock;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.rgb(17, 17, 17));

        TextView title = new TextView(this);
        title.setText("Toki Walkie");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setPadding(0, 0, 0, 48);

        btn = new Button(this);
        btn.setText("Maintiens pour parler");
        btn.setTextSize(20);
        btn.setTextColor(Color.WHITE);
        btn.setBackgroundColor(Color.rgb(46, 125, 50));
        int s = (int) (240 * getResources().getDisplayMetrics().density);

        status = new TextView(this);
        status.setText("Connexion…");
        status.setTextColor(Color.LTGRAY);
        status.setTextSize(18);
        status.setPadding(0, 48, 0, 0);

        root.addView(title);
        root.addView(btn, new LinearLayout.LayoutParams(s, s));
        root.addView(status);
        setContentView(root);

        btn.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) startTalk();
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) stopTalk();
            return true;
        });

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            lock = wm.createMulticastLock("toki");
            lock.acquire();
        }

        // Demande de la permission micro dès le lancement si elle manque
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }

        new Thread(this::recvLoop).start();
    }

    void ui(String t) {
        runOnUiThread(() -> status.setText(t));
    }

    void startTalk() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            status.setText("Autorise le micro, puis réessaie");
            return;
        }
        if (busy || sock == null) return;
        talking = true;
        busy = true;
        btn.setBackgroundColor(Color.rgb(198, 40, 40));
        status.setText("Tu parles…");
        new Thread(this::sendLoop).start();
    }

    void stopTalk() {
        talking = false;
        btn.setBackgroundColor(Color.rgb(46, 125, 50));
        status.setText("Prêt");
    }

    List<InetAddress> broadcasts() {
        List<InetAddress> l = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getBroadcast() != null) l.add(ia.getBroadcast());
                }
            }
        } catch (Exception e) {
            // ignoré
        }
        return l;
    }

    void sendLoop() {
        AudioRecord rec = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, CHUNK * 4));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                ui("Micro indisponible");
                return;
            }
            byte[] buf = new byte[4 + CHUNK];
            ByteBuffer.wrap(buf).putInt(myId);
            List<InetAddress> targets = broadcasts();
            rec.startRecording();
            while (talking && running) {
                int n = rec.read(buf, 4, CHUNK);
                if (n <= 0) continue;
                for (InetAddress a : targets) {
                    try {
                        if (sock != null) {
                            sock.send(new DatagramPacket(buf, 4 + n, a, PORT));
                        }
                    } catch (Exception e) {
                        // ignoré
                    }
                }
            }
            rec.stop();
        } catch (Exception e) {
            ui("Erreur micro");
        } finally {
            if (rec != null) rec.release();
            busy = false;
        }
    }

    void recvLoop() {
        try {
            DatagramSocket s = new DatagramSocket(null);
            s.setReuseAddress(true);
            s.setBroadcast(true);
            s.setSoTimeout(700);
            s.bind(new InetSocketAddress(PORT));
            sock = s;
        } catch (Exception e) {
            ui("Erreur réseau");
            return;
        }
        ui("Prêt");

        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack track = new AudioTrack(AudioManager.STREAM_MUSIC, RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(min, CHUNK * 8), AudioTrack.MODE_STREAM);
        
        try {
            track.play();
        } catch (Exception e) {
            ui("Erreur audio");
            return;
        }

        byte[] buf = new byte[2048];
        boolean receiving = false;
        while (running) {
            try {
                if (sock == null) break;
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                sock.receive(p);
                int len = p.getLength();
                if (len <= 4 || ByteBuffer.wrap(buf).getInt() == myId) continue;
                track.write(buf, 4, len - 4);
                if (!receiving) {
                    receiving = true;
                    ui("Quelqu’un parle…");
                }
            } catch (SocketTimeoutException e) {
                if (receiving) {
                    receiving = false;
                    ui(talking ? "Tu parles…" : "Prêt");
                }
            } catch (Exception e) {
                break;
            }
        }
        try {
            track.release();
        } catch (Exception e) {
            // ignoré
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        talking = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        running = false;
        talking = false;
        if (sock != null) sock.close();
        if (lock != null && lock.isHeld()) lock.release();
    }
}
