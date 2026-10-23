package ml.toki.walkie;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

// Toki Walkie : talkie-walkie + messages en Wi-Fi local, et SMS longue distance.
public class MainActivity extends Activity {
    static final int PORT = 5005, RATE = 8000, CHUNK = 640, H = 5;
    static final byte T_AUDIO = 0, T_TEXT = 1;
    final int myId = new Random().nextInt();

    volatile boolean talking = false, busy = false, running = true;
    volatile DatagramSocket sock;
    Button btn;
    TextView status, messages;
    ScrollView scroll;
    EditText input, phone;
    WifiManager.MulticastLock lock;
    SharedPreferences prefs;
    SharedPreferences.OnSharedPreferenceChangeListener lis;

    // Enregistre un message dans l'historique (utilisé aussi par SmsReceiver)
    static synchronized void saveMsg(Context c, String line) {
        SharedPreferences p = c.getSharedPreferences("toki", Context.MODE_PRIVATE);
        String all = p.getString("hist", "") + line + "\n\n";
        if (all.length() > 20000) all = all.substring(all.length() - 20000);
        p.edit().putString("hist", all).apply();
    }

    // Logo : un talkie-walkie dessiné en code
    class LogoView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        LogoView(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float u = Math.min(w, h) / 100f;
            float ox = (w - 100 * u) / 2, oy = (h - 100 * u) / 2;
            p.setStyle(Paint.Style.FILL);

            p.setColor(Color.rgb(76, 175, 80));
            c.drawRoundRect(new RectF(ox + 60 * u, oy, ox + 68 * u, oy + 30 * u), 4 * u, 4 * u, p);
            c.drawRoundRect(new RectF(ox + 25 * u, oy + 25 * u, ox + 75 * u, oy + 100 * u), 10 * u, 10 * u, p);

            p.setColor(Color.rgb(17, 17, 17));
            for (int i = 0; i < 4; i++) {
                c.drawRoundRect(new RectF(ox + 35 * u, oy + (35 + i * 8) * u,
                        ox + 65 * u, oy + (38 + i * 8) * u), 1.5f * u, 1.5f * u, p);
            }

            p.setColor(Color.WHITE);
            c.drawCircle(ox + 50 * u, oy + 80 * u, 8 * u, p);
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        prefs = getSharedPreferences("toki", MODE_PRIVATE);

        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (10 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.rgb(17, 17, 17));
        root.setPadding(pad, pad * 2, pad, pad);

        LogoView logo = new LogoView(this);
        int ls = (int) (60 * d);

        TextView title = new TextView(this);
        title.setText("Toki Walkie");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);

        btn = new Button(this);
        btn.setText("Maintiens pour parler");
        btn.setTextSize(16);
        btn.setTextColor(Color.WHITE);
        btn.setBackgroundColor(Color.rgb(46, 125, 50));
        int s = (int) (140 * d);

        status = new TextView(this);
        status.setText("Connexion…");
        status.setTextColor(Color.LTGRAY);
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, pad, 0, pad);

        messages = new TextView(this);
        messages.setTextColor(Color.WHITE);
        messages.setTextSize(15);
        messages.setPadding(pad, pad, pad, pad);

        scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(34, 34, 34));
        scroll.addView(messages);

        input = new EditText(this);
        input.setHint("Écris un message…");
        input.setHintTextColor(Color.GRAY);
        input.setTextColor(Color.WHITE);
        input.setMaxLines(3);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(300)});

        Button send = new Button(this);
        send.setText("Wi-Fi");
        send.setTextColor(Color.WHITE);
        send.setBackgroundColor(Color.rgb(25, 118, 210));

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        row1.addView(input, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row1.addView(send, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        phone = new EditText(this);
        phone.setHint("Numéro (+223…) pour SMS longue distance");
        phone.setHintTextColor(Color.GRAY);
        phone.setTextColor(Color.WHITE);
        phone.setInputType(InputType.TYPE_CLASS_PHONE);
        phone.setSingleLine(true);
        phone.setText(prefs.getString("num", ""));

        Button sms = new Button(this);
        sms.setText("SMS");
        sms.setTextColor(Color.WHITE);
        sms.setBackgroundColor(Color.rgb(230, 81, 0));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        row2.addView(phone, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row2.addView(sms, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams full = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollLp.bottomMargin = pad;

        root.addView(logo, new LinearLayout.LayoutParams(ls, ls));
        root.addView(title, full);
        root.addView(btn, new LinearLayout.LayoutParams(s, s));
        root.addView(status, full);
        root.addView(scroll, scrollLp);
        root.addView(row1, full);
        root.addView(row2, full);
        setContentView(root);

        // Met à jour l'écran quand l'historique change (Wi-Fi ou SMS reçu)
        lis = (p, key) -> {
            if ("hist".equals(key)) refresh();
        };

        btn.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) startTalk();
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) stopTalk();
            return true;
        });

        send.setOnClickListener(v -> {
            String t = input.getText().toString().trim();
            if (t.isEmpty()) return;
            sendText(t);
            addMsg("Moi (Wi-Fi) : " + t);
            input.setText("");
        });

        sms.setOnClickListener(v -> {
            String t = input.getText().toString().trim();
            String num = phone.getText().toString().trim();
            if (t.isEmpty()) {
                status.setText("Écris d'abord un message");
                return;
            }
            if (num.length() < 6) {
                status.setText("Entre un numéro, ex. +223…");
                return;
            }
            sendSms(num, t);
        });

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            lock = wm.createMulticastLock("toki");
            lock.acquire();
        }

        new Thread(this::recvLoop).start();
    }

    void refresh() {
        runOnUiThread(() -> {
            messages.setText(prefs.getString("hist", ""));
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    void ui(String t) {
        runOnUiThread(() -> status.setText(t));
    }

    void addMsg(String line) {
        saveMsg(this, line);
    }

    void sendSms(String num, String t) {
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.SEND_SMS,
                    Manifest.permission.RECEIVE_SMS}, 2);
            status.setText("Autorise les SMS, puis réessaie");
            return;
        }
        try {
            SmsManager sm = Build.VERSION.SDK_INT >= 31
                    ? getSystemService(SmsManager.class)
                    : SmsManager.getDefault();
            ArrayList<String> parts = sm.divideMessage("TW:" + t);
            sm.sendMultipartTextMessage(num, null, parts, null, null);
            prefs.edit().putString("num", num).apply();
            addMsg("Moi (SMS à " + num + ") : " + t);
            input.setText("");
            status.setText("SMS envoyé");
        } catch (Exception e) {
            status.setText("Échec de l'envoi du SMS");
        }
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

    // Message texte en Wi-Fi local (sur un thread : réseau interdit sur le thread principal)
    void sendText(final String t) {
        new Thread(() -> {
            if (sock == null) {
                ui("Pas encore connecté");
                return;
            }
            byte[] txt = t.getBytes(StandardCharsets.UTF_8);
            byte[] buf = new byte[H + txt.length];
            ByteBuffer.wrap(buf).putInt(myId);
            buf[4] = T_TEXT;
            System.arraycopy(txt, 0, buf, H, txt.length);
            for (InetAddress a : broadcasts()) {
                try {
                    sock.send(new DatagramPacket(buf, buf.length, a, PORT));
                } catch (Exception e) {
                    // ignoré
                }
            }
        }).start();
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
            byte[] buf = new byte[H + CHUNK];
            ByteBuffer.wrap(buf).putInt(myId);
            buf[4] = T_AUDIO;
            List<InetAddress> targets = broadcasts();
            rec.startRecording();
            while (talking && running) {
                int n = rec.read(buf, H, CHUNK);
                if (n <= 0) continue;
                for (InetAddress a : targets) {
                    try {
                        sock.send(new DatagramPacket(buf, H + n, a, PORT));
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
        track.play();

        byte[] buf = new byte[2048];
        boolean receiving = false;
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                sock.receive(p);
                int len = p.getLength();
                if (len <= H || ByteBuffer.wrap(buf).getInt() == myId) continue;
                byte type = buf[4];
                if (type == T_TEXT) {
                    String msg = new String(buf, H, len - H, StandardCharsets.UTF_8);
                    addMsg("Wi-Fi : " + msg);
                } else {
                    track.write(buf, H, len - H);
                    if (!receiving) {
                        receiving = true;
                        ui("Quelqu’un parle…");
                    }
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
        track.release();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        prefs.registerOnSharedPreferenceChangeListener(lis);
    }

    @Override
    protected void onPause() {
        super.onPause();
        talking = false;
        prefs.unregisterOnSharedPreferenceChangeListener(lis);
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
