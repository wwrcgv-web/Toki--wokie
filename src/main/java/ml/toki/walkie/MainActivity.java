package ml.toki.walkie;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.AdvertisingSet;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.RectF;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

import org.json.JSONArray;
import org.json.JSONObject;

// Toki Walkie : talkie-walkie + messages en Wi-Fi local,
// et messages texte Bluetooth (longue portée BLE Coded PHY si possible, sinon mode normal),
// sans internet ni crédit.
public class MainActivity extends Activity {
    static final int PORT = 5005, RATE = 8000, CHUNK = 640, H = 5;
    static final byte T_AUDIO = 0, T_TEXT = 1, T_PING = 2;
    // Couleurs de l'interface
    static final int C_BG = 0xFF0B1220, C_CARD = 0xFF151F30, C_TEXT = 0xFFF8FAFC,
            C_GREEN = 0xFF22C55E, C_MUTED = 0xFF94A3B8, C_RED = 0xFFEF4444,
            C_BLUE = 0xFF3B82F6, C_AMBER = 0xFFF59E0B,
            C_STROKE = 0xFF223049, C_GRAY = 0xFF475569;
    static final String STATE_READY = "Prêt", STATE_DOWN = "Connexion interrompue";
    static final int MFR = 0xFFFF;          // identifiant fabricant (test) pour nos messages BLE
    static final int BLE_MAX_BYTES = 160;   // taille max d'un message Bluetooth
    static final String ADMIN_CODE = "toki-4821"; // <-- mets ton code administrateur ici
    static final int APP_BUILD = 0; // remplacé automatiquement par GitHub à chaque construction
    static final String REPO = "wwrcgv-web/Toki--wokie"; // <-- vérifie : compte/projet GitHub
    static final int LEG_TEXT = 16;         // octets de texte par morceau (mode normal)
    final int myId = new Random().nextInt();

    volatile boolean talking = false, busy = false, running = true;
    volatile DatagramSocket sock;
    MicButton btn;
    TextView status, messages, bleInfo;
    ScrollView scroll;
    EditText input;
    WifiManager.MulticastLock lock;
    SharedPreferences prefs;
    SharedPreferences.OnSharedPreferenceChangeListener lis;

    // Bluetooth
    final Handler handler = new Handler(Looper.getMainLooper());
    final HashMap<Integer, Integer> seen = new HashMap<>();
    boolean scanning = false;
    int bleCounter = new Random().nextInt(60000);
    AdvertisingSetCallback advCb;
    volatile int seenAll = 0, seenToki = 0;
    volatile long lastInfo = 0;
    String bleBase = "";
    String myName = "";
    TextView who, banner, updateBar, connWifi, connBt, modeHint, chipWifi, chipBt;
    LinearLayout msgBox;
    int mode = 0;                 // 0 = messages par Wi-Fi, 1 = messages par Bluetooth
    float density = 1f;
    String bleState = "Bluetooth : démarrage…";
    final HashMap<Integer, Long> wifiPeers = new HashMap<>();
    final HashMap<Integer, Long> btPeers = new HashMap<>();
    int tickN = 0;
    long wifiSince = 0, lastSend = 0;
    boolean micDenied = false;
    int lastCount = -1;
    Button sendBtn;
    String pendShown = null;
    final Object pendLock = new Object();
    final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateConn();
            if (tickN++ % 3 == 0) sendPing();
            handler.postDelayed(this, 2000);
        }
    };
    int legToken = 0, legMsgId = new Random().nextInt(256);
    final HashMap<String, LegacyAsm> legBuf = new HashMap<>();
    final HashMap<Integer, Integer> legDone = new HashMap<>();
    final HashMap<Integer, String> lastMsg = new HashMap<>();
    final HashMap<Integer, Long> lastTime = new HashMap<>();
    BluetoothLeScanner bleScanner;

    // Enregistre un message dans l'historique (utilisé aussi par SmsReceiver s'il existe encore)
    static synchronized void saveMsg(Context c, String line) {
        SharedPreferences p = c.getSharedPreferences("toki", Context.MODE_PRIVATE);
        String hm = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        String all = p.getString("hist", "") + hm + "\u0003" + line + "\n\n";
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

    // Bouton micro : rond. mode 0 = prêt, 1 = je parle (micro réellement en marche), 2 = j'écoute
    class MicButton extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int mode = 0;

        MicButton(Context c) {
            super(c);
        }

        void setMode(int m) {
            mode = m;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float u = Math.min(w, h) / 100f;
            float cx = w / 2, cy = h / 2;
            int col = mode == 1 ? C_RED : (mode == 2 ? C_BLUE : (mode == 3 ? C_GRAY : C_GREEN));
            if (mode == 1 || mode == 2) {
                float t = (System.currentTimeMillis() % 1400) / 1400f;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(3 * u);
                p.setColor(Color.argb((int) (150 * (1 - t)), Color.red(col), Color.green(col), Color.blue(col)));
                c.drawCircle(cx, cy, (38 + 11 * t) * u, p);
                postInvalidateOnAnimation();
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(col);
            c.drawCircle(cx, cy, 36 * u, p);

            float q = u * 0.75f;
            p.setColor(Color.WHITE);
            c.drawRoundRect(new RectF(cx - 9 * q, cy - 28 * q, cx + 9 * q, cy + 6 * q), 9 * q, 9 * q, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(4 * q);
            p.setStrokeCap(Paint.Cap.ROUND);
            c.drawArc(new RectF(cx - 17 * q, cy - 16 * q, cx + 17 * q, cy + 18 * q), 0, 180, false, p);
            c.drawLine(cx, cy + 18 * q, cx, cy + 28 * q, p);
            c.drawLine(cx - 9 * q, cy + 28 * q, cx + 9 * q, cy + 28 * q, p);
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        prefs = getSharedPreferences("toki", MODE_PRIVATE);
        myName = prefs.getString("name", "");
        mode = prefs.getInt("mode", 0);
        density = getResources().getDisplayMetrics().density;
        int pad = dp(14);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(pad, dp(26), pad, pad);

        LinearLayout.LayoutParams full = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        LinearLayout.LayoutParams fullTop = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        fullTop.topMargin = dp(10);

        // En-tête : logo, nom, paramètres
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LogoView logo = new LogoView(this);

        TextView title = new TextView(this);
        title.setText("Toki Walkie");
        title.setTextColor(C_TEXT);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        title.setOnLongClickListener(v -> {
            askAdmin();
            return true;
        });

        who = new TextView(this);
        who.setTextColor(C_MUTED);
        who.setTextSize(13);
        who.setText(whoText());
        who.setOnClickListener(v -> askName(false));

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(10), 0, 0, 0);
        names.addView(title);
        names.addView(who);

        TextView gear = new TextView(this);
        gear.setText("\u2699");
        gear.setTextSize(26);
        gear.setTextColor(C_MUTED);
        gear.setPadding(dp(12), dp(4), dp(4), dp(4));
        gear.setOnClickListener(v -> openSettings());

        header.addView(logo, new LinearLayout.LayoutParams(dp(36), dp(36)));
        header.addView(names, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(gear, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // Bandeaux : mise à jour et annonce
        updateBar = new TextView(this);
        updateBar.setBackground(rounded(0xFF166534, 12));
        updateBar.setTextColor(C_TEXT);
        updateBar.setTextSize(14);
        updateBar.setPadding(dp(12), dp(10), dp(12), dp(10));
        updateBar.setVisibility(View.GONE);

        banner = new TextView(this);
        banner.setBackground(rounded(0xFF78350F, 12));
        banner.setTextColor(C_TEXT);
        banner.setTextSize(14);
        banner.setPadding(dp(12), dp(10), dp(12), dp(10));
        String savedAd = prefs.getString("annonce", "");
        banner.setText(savedAd);
        banner.setVisibility(savedAd.isEmpty() ? View.GONE : View.VISIBLE);
        banner.setOnClickListener(v -> {
            banner.setVisibility(View.GONE);
            prefs.edit().remove("annonce").apply();
        });

        // Carte « Connexion »
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(C_CARD, 16, C_STROKE));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView cardTitle = new TextView(this);
        cardTitle.setText("CONNEXION");
        cardTitle.setTextColor(C_MUTED);
        cardTitle.setTextSize(11);
        cardTitle.setTypeface(null, Typeface.BOLD);

        chipWifi = chip("Wi-Fi");
        chipBt = chip("Bluetooth");
        chipWifi.setOnClickListener(v -> setMode(0));
        chipBt.setOnClickListener(v -> setMode(1));
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        cl.setMargins(0, dp(8), dp(4), dp(8));
        LinearLayout.LayoutParams cr = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        cr.setMargins(dp(4), dp(8), 0, dp(8));
        chips.addView(chipWifi, cl);
        chips.addView(chipBt, cr);

        connWifi = new TextView(this);
        connWifi.setTextSize(13);
        connWifi.setTextColor(C_TEXT);
        connBt = new TextView(this);
        connBt.setTextSize(13);
        connBt.setTextColor(C_TEXT);
        connBt.setPadding(0, dp(2), 0, 0);
        modeHint = new TextView(this);
        modeHint.setTextSize(12);
        modeHint.setTextColor(C_MUTED);
        modeHint.setPadding(0, dp(6), 0, 0);

        card.addView(cardTitle);
        card.addView(chips);
        card.addView(connWifi);
        card.addView(connBt);
        card.addView(modeHint);

        // Zone centrale : micro et état
        btn = new MicButton(this);
        status = new TextView(this);
        status.setText("Connexion…");
        status.setTextColor(C_MUTED);
        status.setTextSize(16);
        status.setTypeface(null, Typeface.BOLD);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(4), 0, dp(4));

        TextView hint = new TextView(this);
        hint.setText("Maintiens le micro pour parler");
        hint.setTextColor(C_MUTED);
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);

        LinearLayout mic = new LinearLayout(this);
        mic.setOrientation(LinearLayout.VERTICAL);
        mic.setGravity(Gravity.CENTER_HORIZONTAL);
        mic.addView(btn, new LinearLayout.LayoutParams(dp(120), dp(120)));
        mic.addView(status, full);
        mic.addView(hint, full);

        // Messages
        msgBox = new LinearLayout(this);
        msgBox.setOrientation(LinearLayout.VERTICAL);
        msgBox.setPadding(0, dp(4), 0, dp(4));
        scroll = new ScrollView(this);
        scroll.addView(msgBox, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollLp.topMargin = dp(8);
        scrollLp.bottomMargin = dp(8);

        // Saisie
        input = new EditText(this);
        input.setHint("Écris un message…");
        input.setHintTextColor(C_MUTED);
        input.setTextColor(C_TEXT);
        input.setTextSize(15);
        input.setMaxLines(4);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setGravity(Gravity.CENTER_VERTICAL);
        input.setBackground(rounded(C_CARD, 22, C_STROKE));
        input.setPadding(dp(16), dp(10), dp(16), dp(10));
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(300)});

        Button send = new Button(this);
        send.setText("Envoyer");
        send.setAllCaps(false);
        send.setTextColor(C_BG);
        send.setTypeface(null, Typeface.BOLD);
        send.setBackground(rounded(C_GREEN, 22));
        sendBtn = send;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(input, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(44));
        sendLp.leftMargin = dp(8);
        row.addView(send, sendLp);

        root.addView(header, full);
        root.addView(updateBar, fullTop);
        root.addView(banner, fullTop);
        root.addView(card, fullTop);
        root.addView(mic, fullTop);
        root.addView(scroll, scrollLp);
        root.addView(row, full);
        setContentView(root);

        // Met à jour l'écran quand l'historique change
        lis = (p, key) -> {
            if ("hist".equals(key)) refresh();
        };

        btn.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) startTalk();
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) stopTalk();
            return true;
        });

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence cs, int st, int bf, int ct) {
            }

            @Override
            public void onTextChanged(CharSequence cs, int st, int bf, int ct) {
                updateSendEnabled();
            }

            @Override
            public void afterTextChanged(Editable ed) {
            }
        });
        updateSendEnabled();

        send.setOnClickListener(v -> {
            String t = input.getText().toString().trim();
            if (t.isEmpty()) return;
            long now = System.currentTimeMillis();
            if (now - lastSend < 800) return; // évite les doubles envois
            lastSend = now;
            if (mode == 0) {
                sendTextMsg(t);
                input.setText("");
            } else if (sendBle(t)) {
                input.setText("");
            }
        });

        // Petit écran / clavier ouvert : on masque la carte et le micro pour garder la place aux messages
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            Rect r = new Rect();
            root.getWindowVisibleDisplayFrame(r);
            int total = root.getRootView().getHeight();
            int vis = (total - r.bottom > total * 0.15) ? View.GONE : View.VISIBLE;
            if (mic.getVisibility() != vis) {
                mic.setVisibility(vis);
                card.setVisibility(vis);
            }
        });

        updateConn();

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            lock = wm.createMulticastLock("toki");
            lock.acquire();
        }

        new Thread(this::recvLoop).start();
        handler.postDelayed(tick, 1000);
        ensureBle();
        if (myName.isEmpty()) askName(true);
        checkUpdate(false);
    }

    // ---------------- Profil local (nom choisi sur le téléphone) ----------------

    void askName(final boolean first) {
        final EditText e = new EditText(this);
        e.setText(myName);
        e.setHint("Ton nom");
        e.setSingleLine(true);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(15)});
        new AlertDialog.Builder(this)
                .setTitle(first ? "Bienvenue ! Choisis ton nom" : "Changer mon nom")
                .setView(e)
                .setCancelable(!first)
                .setPositiveButton("OK", (dlg, w) -> {
                    String n = e.getText().toString().replace("\u001F", "").trim();
                    if (n.isEmpty()) n = "Anonyme";
                    myName = n;
                    prefs.edit().putString("name", n).apply();
                    who.setText(whoText());
                })
                .show();
    }

    // Texte envoyé = nom + séparateur + message
    String fullText(String t) {
        return myName.isEmpty() ? t : myName + "\u001F" + t;
    }

    // Affichage d'un message reçu : « Awa (Wi-Fi) : salut »
    String fmt(String raw, String channel) {
        int i = raw.indexOf('\u001F');
        if (i > 0) return raw.substring(0, i) + " (" + channel + ") : " + raw.substring(i + 1);
        return channel + " : " + raw;
    }

    // ---------------- Annonce de l'administrateur (locale, Wi-Fi + Bluetooth) ----------------

    void onIncoming(String raw, String channel) {
        if (raw.startsWith("\u001E")) {
            String body = raw.substring(1);
            int i = body.indexOf('\u001F');
            String from = i > 0 ? body.substring(0, i) : "Admin";
            String msg = i > 0 ? body.substring(i + 1) : body;
            final String text = "ANNONCE de " + from + " : " + msg;
            addMsg("ANNONCE de " + from + " (" + channel + ") : " + msg);
            prefs.edit().putString("annonce", text).apply();
            runOnUiThread(() -> {
                banner.setText(text);
                banner.setVisibility(View.VISIBLE);
            });
        } else {
            addMsg(fmt(raw, channel));
        }
    }

    void askAdmin() {
        final EditText c = new EditText(this);
        c.setHint("Code administrateur");
        c.setSingleLine(true);
        c.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this)
                .setTitle("Administrateur")
                .setView(c)
                .setPositiveButton("OK", (dlg, w) -> {
                    if (ADMIN_CODE.equals(c.getText().toString().trim())) askAnnounce();
                    else status.setText("Code incorrect");
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    void askAnnounce() {
        final EditText a = new EditText(this);
        a.setHint("Ton annonce (100 caractères max)");
        a.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});
        new AlertDialog.Builder(this)
                .setTitle("Envoyer une annonce")
                .setView(a)
                .setPositiveButton("Envoyer", (dlg, w) -> {
                    String m = a.getText().toString().trim();
                    if (m.isEmpty()) return;
                    String raw = "\u001E" + fullText(m);
                    sendWifiRaw(raw);
                    if (!sendBleRaw(raw, "Moi (annonce) : " + m)) {
                        addMsg("Moi (annonce Wi-Fi seulement) : " + m);
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    // ---------------- Mise à jour proposée (via les Releases GitHub) ----------------

    void checkUpdate(final boolean manual) {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(
                        "https://api.github.com/repos/" + REPO + "/releases/latest").openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "TokiWalkie");
                if (c.getResponseCode() != 200) {
                    if (manual) ui("Aucune version publiée pour le moment");
                    return;
                }
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                InputStream in = c.getInputStream();
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                in.close();
                JSONObject j = new JSONObject(new String(bo.toByteArray(), StandardCharsets.UTF_8));
                int latest = Integer.parseInt(j.optString("tag_name", "").replaceAll("[^0-9]", ""));
                if (latest <= APP_BUILD) {
                    if (manual) ui("Tu as déjà la dernière version");
                    return;
                }
                String url = j.optString("html_url", "");
                JSONArray as = j.optJSONArray("assets");
                if (as != null && as.length() > 0) {
                    url = as.getJSONObject(0).optString("browser_download_url", url);
                }
                final String link = url;
                final int ver = latest;
                runOnUiThread(() -> {
                    updateBar.setText("Nouvelle version disponible (" + ver + "). Touche pour télécharger.");
                    updateBar.setOnClickListener(x ->
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link))));
                    updateBar.setVisibility(View.VISIBLE);
                });
            } catch (Exception e) {
                if (manual) ui("Impossible de vérifier (pas d'internet ?)");
            }
        }).start();
    }

    // ---------------- Paramètres ----------------

    TextView sectionTitle(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(C_GREEN);
        v.setTextSize(11);
        v.setTypeface(null, Typeface.BOLD);
        v.setPadding(0, dp(14), 0, dp(4));
        return v;
    }

    View settingRow(String title, String desc, final Runnable act, final AlertDialog[] dlg) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(12), dp(10), dp(12), dp(10));
        r.setBackground(rounded(C_CARD, 12, C_STROKE));
        TextView a = new TextView(this);
        a.setText(title);
        a.setTextColor(C_TEXT);
        a.setTextSize(15);
        TextView b = new TextView(this);
        b.setText(desc);
        b.setTextColor(C_MUTED);
        b.setTextSize(12);
        r.addView(a);
        r.addView(b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        r.setLayoutParams(lp);
        r.setOnClickListener(v -> {
            if (dlg[0] != null) dlg[0].dismiss();
            act.run();
        });
        return r;
    }

    void openSettings() {
        final AlertDialog[] dlg = new AlertDialog[1];
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(4), dp(16), dp(8));

        box.addView(sectionTitle("PROFIL"));
        box.addView(settingRow("Mon nom", "Affiché devant tes messages.", () -> askName(false), dlg));

        box.addView(sectionTitle("CONNEXION"));
        box.addView(settingRow("Messages envoyés par : " + (mode == 0 ? "Wi-Fi" : "Bluetooth"),
                "Touche pour changer. La voix utilise toujours le Wi-Fi.", () -> setMode(1 - mode), dlg));
        box.addView(settingRow("Relancer le Bluetooth",
                "À faire après avoir allumé le Bluetooth.", () -> {
                    stopBleScan();
                    ensureBle();
                }, dlg));

        box.addView(sectionTitle("ADMINISTRATION"));
        box.addView(settingRow("Envoyer une annonce",
                "Réservé à l'administrateur, avec son code.", () -> askAdmin(), dlg));

        box.addView(sectionTitle("APPLICATION"));
        box.addView(settingRow("Rechercher une mise à jour",
                "Demande internet. Les messages, eux, n'en ont pas besoin.", () -> checkUpdate(true), dlg));
        box.addView(settingRow("Effacer l'historique",
                "Supprime les messages affichés sur ce téléphone.", () -> confirmClear(), dlg));
        box.addView(settingRow("À propos", "Version et fonctionnement.", () -> showAbout(), dlg));

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        TextView ttl = new TextView(this);
        ttl.setText("Paramètres");
        ttl.setTextColor(C_TEXT);
        ttl.setTextSize(20);
        ttl.setTypeface(null, Typeface.BOLD);
        ttl.setPadding(dp(20), dp(18), dp(20), dp(4));

        AlertDialog d = new AlertDialog.Builder(this)
                .setCustomTitle(ttl)
                .setView(sv)
                .setNegativeButton("Retour", null)
                .create();
        dlg[0] = d;
        d.show();
        d.getWindow().setBackgroundDrawable(rounded(C_BG, 16, C_STROKE));
        d.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(C_GREEN);
    }

    void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("Effacer l'historique ?")
                .setMessage("Tous les messages affichés seront supprimés de ce téléphone.")
                .setPositiveButton("Effacer", (dlg, w) -> {
                    prefs.edit().remove("hist").apply();
                    refresh();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("Toki Walkie")
                .setMessage("Version " + APP_BUILD
                        + "\n\nVoix et messages en Wi-Fi local, messages texte en Bluetooth."
                        + "\nFonctionne sans internet ni crédit."
                        + "\n\nNom : " + (myName.isEmpty() ? "non choisi" : myName))
                .setPositiveButton("OK", null)
                .show();
    }

    void refresh() {
        runOnUiThread(() -> {
            msgBox.removeAllViews();
            String hist = prefs.getString("hist", "");
            String[] lines = hist.isEmpty() ? new String[0] : hist.split("\n\n");
            int shown = 0;
            for (int i = Math.max(0, lines.length - 60); i < lines.length; i++) {
                if (lines[i].trim().isEmpty()) continue;
                msgBox.addView(bubble(lines[i]));
                shown++;
            }
            if (shown == 0) {
                TextView w = new TextView(this);
                w.setText("Bienvenue sur Toki Walkie.\n\nMaintiens le micro pour parler aux appareils à portée de ton Wi-Fi, ou envoie un message texte par Wi-Fi ou par Bluetooth.");
                w.setTextColor(C_MUTED);
                w.setTextSize(14);
                w.setGravity(Gravity.CENTER);
                w.setPadding(dp(24), dp(24), dp(24), dp(24));
                msgBox.addView(w);
            }
            if (shown > lastCount && lastCount >= 0 && msgBox.getChildCount() > 0) {
                View lastView = msgBox.getChildAt(msgBox.getChildCount() - 1);
                lastView.setAlpha(0f);
                lastView.animate().alpha(1f).setDuration(180).start();
            }
            lastCount = shown;
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    void ui(String t) {
        runOnUiThread(() -> setState(t));
    }

    void info(String t) {
        bleState = t;
        runOnUiThread(this::updateConn);
    }

    // ---------------- Aides d'affichage ----------------

    int dp(float v) {
        return (int) (v * density + 0.5f);
    }

    GradientDrawable rounded(int color, float radiusDp) {
        return rounded(color, radiusDp, 0);
    }

    GradientDrawable rounded(int color, float radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp * density);
        if (stroke != 0) g.setStroke(Math.max(1, dp(1)), stroke);
        return g;
    }

    TextView chip(String label) {
        TextView c = new TextView(this);
        c.setText(label);
        c.setGravity(Gravity.CENTER);
        c.setTextSize(14);
        c.setTypeface(null, Typeface.BOLD);
        c.setPadding(dp(8), dp(9), dp(8), dp(9));
        return c;
    }

    void styleChip(TextView c, boolean on) {
        c.setBackground(rounded(on ? C_GREEN : 0xFF1E293B, 12));
        c.setTextColor(on ? C_BG : C_MUTED);
    }

    String whoText() {
        return myName.isEmpty() ? "Choisis ton nom" : myName + "  ·  modifier";
    }

    int colorFor(String t) {
        if (t.startsWith("Prêt") || t.startsWith("Message") || t.startsWith("Diffusion")) return C_GREEN;
        if (t.startsWith("Échec")) return C_RED;
        if (t.startsWith("Connexion interrompue") || t.startsWith("Microphone")) return C_AMBER;
        if (t.startsWith("Transmission")) return C_RED;
        if (t.startsWith("Quelqu")) return C_BLUE;
        return C_TEXT;
    }

    void setState(String t) {
        status.setText(t);
        status.setTextColor(colorFor(t));
    }

    void setMode(int m) {
        mode = m;
        prefs.edit().putInt("mode", m).apply();
        updateConn();
    }

    int countPeers(HashMap<Integer, Long> map, long now, long window) {
        int n = 0;
        synchronized (map) {
            for (Long t : map.values()) {
                if (now - t < window) n++;
            }
        }
        return n;
    }

    // Carte « Connexion » : états et nombres réels
    void updateConn() {
        if (connWifi == null || status == null) return;
        boolean wifi = !broadcasts().isEmpty();
        long now = System.currentTimeMillis();
        if (wifi && wifiSince == 0) wifiSince = now;
        if (!wifi) wifiSince = 0;
        int wp = countPeers(wifiPeers, now, 20000);
        int bp = countPeers(btPeers, now, 120000);
        boolean searching = wifi && wp == 0 && now - wifiSince < 15000;

        String ws;
        int wc;
        if (!wifi) {
            ws = "Wi-Fi : non connecté";
            wc = C_AMBER;
        } else if (wp > 0) {
            ws = "Wi-Fi : connecté  ·  " + wp + (wp > 1 ? " appareils" : " appareil") + " à portée";
            wc = C_GREEN;
        } else if (searching) {
            ws = "Wi-Fi : recherche d'appareils…";
            wc = C_TEXT;
        } else {
            ws = "Wi-Fi : aucun appareil trouvé";
            wc = C_AMBER;
        }
        connWifi.setText(ws);
        connWifi.setTextColor(wc);

        boolean bleOk = bleState.contains("prêt");
        connBt.setText(bleOk
                ? bleState + (bp > 0 ? "  ·  " + bp + (bp > 1 ? " appareils entendus" : " appareil entendu")
                : "  ·  en écoute, aucun appareil entendu")
                : bleState);
        connBt.setTextColor(!bleOk ? C_AMBER : (bp > 0 ? C_GREEN : C_TEXT));

        styleChip(chipWifi, mode == 0);
        styleChip(chipBt, mode == 1);

        String help;
        if (mode == 0) {
            if (!wifi) help = "Connecte-toi à un Wi-Fi, ou active le partage de connexion sur l'un des deux téléphones.";
            else if (wp == 0 && !searching) help = "Vérifie que l'autre téléphone a Toki Walkie ouvert et qu'il est sur le même Wi-Fi.";
            else help = "Wi-Fi : voix et messages entre appareils sur le même réseau.";
        } else {
            if (!bleOk) help = "Allume le Bluetooth, puis Paramètres > Relancer le Bluetooth.";
            else if (bp == 0) help = "Rapproche les téléphones et vérifie le Bluetooth des deux côtés. Un appareil n'est compté qu'après un message reçu.";
            else help = "Bluetooth : messages courts (160 octets max), sans réseau. La voix reste sur le Wi-Fi.";
        }
        modeHint.setText(help);

        if (btn.mode == 0 || btn.mode == 3) btn.setMode(!wifi || micDenied ? 3 : 0);

        if (!talking && !busy) {
            String cur = status.getText().toString();
            if (wifi) {
                if (cur.equals(STATE_DOWN) || cur.equals("Connexion…")) setState(STATE_READY);
            } else if (cur.equals(STATE_READY) || cur.equals("Connexion…")) {
                setState(STATE_DOWN);
            }
        }
    }

    void updateSendEnabled() {
        if (sendBtn == null) return;
        boolean on = !input.getText().toString().trim().isEmpty();
        sendBtn.setEnabled(on);
        sendBtn.setBackground(rounded(on ? C_GREEN : 0xFF1E293B, 22));
        sendBtn.setTextColor(on ? C_BG : C_MUTED);
    }

    // Affiche un état quelques secondes, puis revient à « Prêt »
    void flash(final String t) {
        runOnUiThread(() -> {
            setState(t);
            handler.postDelayed(() -> {
                if (status.getText().toString().equals(t)) setState(STATE_READY);
            }, 2500);
        });
    }

    void showMicHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Microphone non autorisé")
                .setMessage("Toki Walkie a besoin du micro pour envoyer ta voix. Ouvre les réglages du téléphone, touche Autorisations, puis autorise le Microphone.")
                .setPositiveButton("Ouvrir les réglages", (dlg, w) -> startActivity(new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()))))
                .setNegativeButton("Plus tard", null)
                .show();
    }

    // Petit signal de présence en Wi-Fi (5 octets) pour compter les appareils à portée
    void sendPing() {
        new Thread(() -> {
            if (sock == null) return;
            byte[] buf = new byte[H];
            ByteBuffer.wrap(buf).putInt(myId);
            buf[4] = T_PING;
            for (InetAddress a : broadcasts()) {
                try {
                    sock.send(new DatagramPacket(buf, buf.length, a, PORT));
                } catch (Exception e) {
                    // ignoré
                }
            }
        }).start();
    }

    // Une ligne de l'historique -> une bulle
    View bubble(String line) {
        String time = "";
        int tp = line.indexOf('\u0003');
        if (tp >= 0) {
            time = line.substring(0, tp);
            line = line.substring(tp + 1);
        }
        String state = "";
        int sp = line.lastIndexOf('\u0005');
        if (sp >= 0) {
            state = line.substring(sp + 1);
            line = line.substring(0, sp);
        }
        boolean mine = line.startsWith("Moi (");
        boolean ad = line.startsWith("ANNONCE de ") || line.startsWith("Moi (annonce");
        int sep = line.indexOf(" : ");
        String head = sep >= 0 ? line.substring(0, sep) : "";
        String text = sep >= 0 ? line.substring(sep + 3) : line;
        String who2 = head, chan = "";
        int po = head.lastIndexOf(" (");
        if (po > 0 && head.endsWith(")")) {
            who2 = head.substring(0, po);
            chan = head.substring(po + 2, head.length() - 1);
        }
        if (who2.equals("Wi-Fi") || who2.equals("Bluetooth")) {
            chan = who2;
            who2 = "Quelqu'un";
        }
        if (who2.isEmpty()) who2 = "Message";
        boolean voice = chan.startsWith("voix");
        String type = chan.isEmpty() ? "" : chan.substring(0, 1).toUpperCase() + chan.substring(1);
        if (ad && mine) type = "Annonce";
        boolean fail = state.equals("Échec");
        String cap = (mine ? "Moi" : who2)
                + (type.isEmpty() ? "" : "  ·  " + type)
                + (time.isEmpty() ? "" : "  ·  " + time)
                + (state.isEmpty() ? "" : "  ·  " + state);

        int bg = ad ? 0xFF78350F : (voice ? 0xFF1E3A5F : (mine ? 0xFF166534 : C_CARD));
        LinearLayout bx = new LinearLayout(this);
        bx.setOrientation(LinearLayout.VERTICAL);
        bx.setPadding(dp(12), dp(8), dp(12), dp(8));
        bx.setBackground(rounded(bg, 14, (!ad && !voice && !mine) ? C_STROKE : 0));
        TextView c = new TextView(this);
        c.setText(cap);
        c.setTextSize(11);
        c.setTextColor(fail ? 0xFFFCA5A5 : (ad ? 0xFFFCD34D : (mine ? 0xFFBBF7D0 : C_MUTED)));
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(15);
        t.setTextColor(C_TEXT);
        bx.addView(c);
        bx.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = mine ? Gravity.END : Gravity.START;
        lp.setMargins(mine ? dp(40) : 0, dp(5), mine ? 0 : dp(40), dp(5));
        bx.setLayoutParams(lp);
        return bx;
    }

    void addMsg(String line) {
        saveMsg(this, line);
    }

    // ---------------- Bluetooth : longue portée si possible, sinon mode normal ----------------

    String[] blePerms() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{"android.permission.BLUETOOTH_SCAN",
                    "android.permission.BLUETOOTH_ADVERTISE",
                    "android.permission.BLUETOOTH_CONNECT"};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    boolean hasBlePerms() {
        for (String p : blePerms()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    void ensureBle() {
        if (!hasBlePerms()) {
            requestPermissions(blePerms(), 3);
            return;
        }
        startBleScan();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 1) {
            boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
            micDenied = !ok;
            setState(ok ? STATE_READY : "Microphone non autorisé");
            if (!ok) showMicHelp();
        }
        if (code == 3) {
            if (hasBlePerms()) startBleScan();
            else info("Autorise le Bluetooth pour les messages Bluetooth");
        }
    }

    final ScanCallback scanCb = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult r) {
            handleBle(r);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> list) {
            for (ScanResult r : list) handleBle(r);
        }

        @Override
        public void onScanFailed(int code) {
            scanning = false;
            info("Échec du scan Bluetooth (code " + code + ")");
        }
    };

    final AdvertiseCallback legacyCb = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings st) {
            finishBle(true);
        }

        @Override
        public void onStartFailure(int err) {
            finishBle(false);
        }
    };

    // Fin du démarrage de l'envoi Bluetooth : Envoyé (diffusion lancée) ou Échec. La réception n'est pas confirmée.
    void finishBle(boolean ok) {
        String sh;
        synchronized (pendLock) {
            if (pendShown == null) return;
            sh = pendShown;
            pendShown = null;
        }
        addMsg(sh + "\u0005" + (ok ? "Envoyé" : "Échec"));
        ui(ok ? "Diffusion Bluetooth lancée" : "Échec de l'envoi Bluetooth");
    }

    @TargetApi(26)
    boolean codedSupported(BluetoothAdapter ad) {
        return Build.VERSION.SDK_INT >= 26 && ad.isLeCodedPhySupported()
                && ad.isLeExtendedAdvertisingSupported();
    }

    @TargetApi(26)
    boolean extScan(BluetoothAdapter ad) {
        return Build.VERSION.SDK_INT >= 26 && ad.isLeExtendedAdvertisingSupported();
    }

    @TargetApi(26)
    void extendSettings(ScanSettings.Builder sb) {
        sb.setLegacy(false);
        sb.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED);
    }

    ScanFilter bleFilter(char c) {
        return new ScanFilter.Builder()
                .setManufacturerData(MFR, new byte[]{'T', (byte) c},
                        new byte[]{(byte) 0xFF, (byte) 0xFF})
                .build();
    }

    void startBleScan() {
        if (scanning) return;
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null || !ad.isEnabled()) {
            info("Bluetooth éteint : active-le puis relance l'appli");
            return;
        }
        BluetoothLeScanner sc = ad.getBluetoothLeScanner();
        if (sc == null) {
            info("Scanner Bluetooth indisponible");
            return;
        }
        try {
            ScanSettings.Builder sb = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY);
            if (extScan(ad)) extendSettings(sb);
            sc.startScan(null, sb.build(), scanCb); // pas de filtre matériel : on trie dans le code
            bleScanner = sc;
            scanning = true;
            bleBase = codedSupported(ad) ? "Bluetooth : prêt (mode étendu)" : "Bluetooth : prêt";
            info(bleBase);
        } catch (Exception e) {
            info("Erreur Bluetooth (scan)");
        }
    }

    void stopBleScan() {
        if (!scanning) return;
        try {
            if (bleScanner != null) bleScanner.stopScan(scanCb);
        } catch (Exception e) {
            // ignoré
        }
        scanning = false;
    }

    // Reçoit un message découpé en morceaux (mode normal)
    static class LegacyAsm {
        byte[][] parts;
        int count, total;
    }

    void handleBle(ScanResult r) {
        seenAll++;
        ScanRecord sr = r.getScanRecord();
        byte[] d = sr == null ? null : sr.getManufacturerSpecificData(MFR);
        if (d != null && d.length >= 7 && d[0] == 'T' && (d[1] == 'W' || d[1] == 'L')) {
            seenToki++;
            if (d[1] == 'W') handleCoded(d);
            else handleLegacy(d);
        }
    }

    void handleCoded(byte[] d) {
        if (d.length < 9) return;
        int id = ByteBuffer.wrap(d, 2, 4).getInt();
        int cnt = ((d[6] & 0xFF) << 8) | (d[7] & 0xFF);
        if (id == myId) return;
        synchronized (seen) {
            Integer last = seen.get(id);
            if (last != null && last == cnt) return; // même message, déjà reçu
            seen.put(id, cnt);
        }
        synchronized (btPeers) {
            btPeers.put(id & 0xFFFF, System.currentTimeMillis());
        }
        deliver(id & 0xFFFF, new String(d, 8, d.length - 8, StandardCharsets.UTF_8));
    }

    void handleLegacy(byte[] d) {
        int sender = ((d[2] & 0xFF) << 8) | (d[3] & 0xFF);
        if (sender == (myId & 0xFFFF)) return;
        synchronized (btPeers) {
            btPeers.put(sender, System.currentTimeMillis());
        }
        int msgId = d[4] & 0xFF, idx = d[5] & 0xFF, total = d[6] & 0xFF;
        if (total == 0 || total > 40 || idx >= total) return;
        byte[] txt = new byte[d.length - 7];
        System.arraycopy(d, 7, txt, 0, txt.length);
        byte[] whole = null;
        synchronized (legBuf) {
            Integer done = legDone.get(sender);
            if (done != null && done == msgId) return; // déjà reçu
            String key = sender + "/" + msgId;
            LegacyAsm a = legBuf.get(key);
            if (a == null || a.total != total) {
                if (legBuf.size() > 20) legBuf.clear();
                a = new LegacyAsm();
                a.total = total;
                a.parts = new byte[total][];
                legBuf.put(key, a);
            }
            if (a.parts[idx] == null) {
                a.parts[idx] = txt;
                a.count++;
            }
            if (a.count == total) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                for (byte[] p : a.parts) bo.write(p, 0, p.length);
                whole = bo.toByteArray();
                legDone.put(sender, msgId);
                legBuf.remove(key);
            }
        }
        if (whole != null) deliver(sender, new String(whole, StandardCharsets.UTF_8));
    }

    // Affiche un message reçu (évite les doublons entre les deux modes)
    void deliver(int sender, String msg) {
        long now = System.currentTimeMillis();
        synchronized (lastMsg) {
            Long t = lastTime.get(sender);
            if (msg.equals(lastMsg.get(sender)) && t != null && now - t < 20000) return;
            lastMsg.put(sender, msg);
            lastTime.put(sender, now);
        }
        onIncoming(msg, "Bluetooth");
    }

    // Retourne true si le message est parti
    boolean sendBle(String t) {
        return sendBleRaw(fullText(t), "Moi (Bluetooth) : " + t);
    }

    boolean sendBleRaw(String raw, String shown) {
        if (!hasBlePerms()) {
            ensureBle();
            status.setText("Autorise le Bluetooth, puis réessaie");
            return false;
        }
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null || !ad.isEnabled()) {
            status.setText("Active le Bluetooth, puis réessaie");
            return false;
        }
        BluetoothLeAdvertiser adv = ad.getBluetoothLeAdvertiser();
        if (adv == null) {
            status.setText("Bluetooth indisponible sur ce téléphone");
            return false;
        }
        byte[] txt = raw.getBytes(StandardCharsets.UTF_8);
        if (txt.length > BLE_MAX_BYTES) {
            status.setText("Message trop long pour le Bluetooth (max " + BLE_MAX_BYTES + " octets)");
            return false;
        }
        try {
            if (codedSupported(ad)) sendCoded(ad, adv, txt);
            sendLegacy(adv, txt);
        } catch (Exception e) {
            status.setText("Erreur d'envoi Bluetooth");
            return false;
        }
        synchronized (pendLock) {
            pendShown = shown;
        }
        setState("Envoi Bluetooth en cours…");
        handler.postDelayed(() -> finishBle(false), 3000);
        return true;
    }

    // Mode longue portée (BLE Coded PHY), message d'un seul bloc
    @TargetApi(26)
    void sendCoded(BluetoothAdapter ad, final BluetoothLeAdvertiser adv, byte[] txt) {
        if (txt.length + 12 > ad.getLeMaximumAdvertisingDataLength()) return;
        if (advCb != null) {
            try {
                adv.stopAdvertisingSet(advCb);
            } catch (Exception e) {
                // ignoré
            }
        }
        bleCounter = (bleCounter + 1) & 0xFFFF;
        byte[] pl = new byte[8 + txt.length];
        pl[0] = 'T';
        pl[1] = 'W';
        ByteBuffer.wrap(pl).putInt(2, myId);
        pl[6] = (byte) (bleCounter >> 8);
        pl[7] = (byte) bleCounter;
        System.arraycopy(txt, 0, pl, 8, txt.length);

        AdvertisingSetParameters params = new AdvertisingSetParameters.Builder()
                .setLegacyMode(false)
                .setConnectable(false)
                .setScannable(false)
                .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                .setPrimaryPhy(BluetoothDevice.PHY_LE_CODED)
                .setSecondaryPhy(BluetoothDevice.PHY_LE_CODED)
                .build();
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addManufacturerData(MFR, pl)
                .build();

        final AdvertisingSetCallback cb = new AdvertisingSetCallback() {
            @Override
            public void onAdvertisingSetStarted(AdvertisingSet set, int txPower, int st) {
                if (st == AdvertisingSetCallback.ADVERTISE_SUCCESS) finishBle(true);
            }
        };
        advCb = cb;
        adv.startAdvertisingSet(params, data, null, null, null, cb);

        // diffuse pendant 8 secondes puis s'arrête
        handler.postDelayed(() -> {
            try {
                adv.stopAdvertisingSet(cb);
            } catch (Exception e) {
                // ignoré
            }
        }, 8000);
    }

    // Mode normal : message découpé en petits morceaux de LEG_TEXT octets,
    // diffusés à tour de rôle (marche sur tous les téléphones Bluetooth LE)
    void sendLegacy(final BluetoothLeAdvertiser adv, byte[] txt) {
        final int n = Math.max(1, (txt.length + LEG_TEXT - 1) / LEG_TEXT);
        final byte[][] pk = new byte[n][];
        legMsgId = (legMsgId + 1) & 0xFF;
        int sender = myId & 0xFFFF;
        for (int i = 0; i < n; i++) {
            int from = i * LEG_TEXT, to = Math.min(txt.length, from + LEG_TEXT);
            byte[] p = new byte[7 + to - from];
            p[0] = 'T';
            p[1] = 'L';
            p[2] = (byte) (sender >> 8);
            p[3] = (byte) sender;
            p[4] = (byte) legMsgId;
            p[5] = (byte) i;
            p[6] = (byte) n;
            System.arraycopy(txt, from, p, 7, to - from);
            pk[i] = p;
        }
        final int token = ++legToken;
        final int steps = Math.max(n * 4, 8);
        final AdvertiseSettings st = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .setTimeout(0)
                .build();
        final int[] k = {0};
        Runnable r = new Runnable() {
            @Override
            public void run() {
                if (token != legToken) return; // un message plus récent a pris le relais
                try {
                    adv.stopAdvertising(legacyCb);
                } catch (Exception e) {
                    // ignoré
                }
                if (k[0] >= steps) {
                    runOnUiThread(() -> {
                        String cur = status.getText().toString();
                        if (cur.startsWith("Diffusion Bluetooth") || cur.startsWith("Envoi Bluetooth")) {
                            flash("Diffusion Bluetooth terminée");
                        }
                    });
                    return;
                }
                AdvertiseData data = new AdvertiseData.Builder()
                        .setIncludeDeviceName(false)
                        .setIncludeTxPowerLevel(false)
                        .addManufacturerData(MFR, pk[k[0] % n])
                        .build();
                try {
                    adv.startAdvertising(st, data, legacyCb);
                } catch (Exception e) {
                    ui("Erreur Bluetooth (mode normal)");
                    return;
                }
                k[0]++;
                handler.postDelayed(this, 500);
            }
        };
        handler.post(r);
    }

    // ---------------- Voix et texte Wi-Fi (inchangé) ----------------

    void startTalk() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (prefs.getBoolean("micAsked", false)) {
                micDenied = true;
                setState("Microphone non autorisé");
                showMicHelp();
            } else {
                prefs.edit().putBoolean("micAsked", true).apply();
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            }
            return;
        }
        micDenied = false;
        if (busy || sock == null) return;
        if (broadcasts().isEmpty()) {
            setState(STATE_DOWN);
            return;
        }
        talking = true;
        busy = true;
        setState("Préparation…");
        new Thread(this::sendLoop).start();
    }

    void stopTalk() {
        talking = false;
        if (busy) setState(STATE_READY);
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
    void sendText(String t) {
        sendWifiRaw(fullText(t));
    }

    // Message texte Wi-Fi avec retour réel : Envoyé si au moins un paquet est parti, sinon Échec
    void sendTextMsg(final String t) {
        final String raw = fullText(t);
        setState("Envoi en cours…");
        new Thread(() -> {
            boolean ok = sendWifiPackets(raw);
            addMsg("Moi (Wi-Fi) : " + t + "\u0005" + (ok ? "Envoyé" : "Échec"));
            flash(ok ? "Message envoyé par Wi-Fi" : "Échec : aucun réseau Wi-Fi disponible");
        }).start();
    }

    boolean sendWifiPackets(String raw) {
        if (sock == null) return false;
        byte[] txt = raw.getBytes(StandardCharsets.UTF_8);
        byte[] buf = new byte[H + txt.length];
        ByteBuffer.wrap(buf).putInt(myId);
        buf[4] = T_TEXT;
        System.arraycopy(txt, 0, buf, H, txt.length);
        boolean ok = false;
        for (InetAddress a : broadcasts()) {
            try {
                sock.send(new DatagramPacket(buf, buf.length, a, PORT));
                ok = true;
            } catch (Exception e) {
                // ignoré
            }
        }
        return ok;
    }

    void sendWifiRaw(final String raw) {
        new Thread(() -> sendWifiPackets(raw)).start();
    }

    void sendLoop() {
        AudioRecord rec = null;
        boolean err = false, sentOk = false;
        long t0 = 0;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, CHUNK * 4));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                err = true;
                ui("Micro indisponible");
                return;
            }
            byte[] buf = new byte[H + CHUNK];
            ByteBuffer.wrap(buf).putInt(myId);
            buf[4] = T_AUDIO;
            List<InetAddress> targets = broadcasts();
            rec.startRecording();
            t0 = System.currentTimeMillis();
            if (talking) {
                // le micro enregistre réellement : on l'affiche
                runOnUiThread(() -> btn.setMode(1));
                ui("Transmission en cours…");
            }
            while (talking && running) {
                int n = rec.read(buf, H, CHUNK);
                if (n <= 0) continue;
                for (InetAddress a : targets) {
                    try {
                        sock.send(new DatagramPacket(buf, H + n, a, PORT));
                        sentOk = true;
                    } catch (Exception e) {
                        // ignoré
                    }
                }
            }
            rec.stop();
        } catch (Exception e) {
            err = true;
            ui("Erreur micro");
        } finally {
            if (rec != null) rec.release();
            busy = false;
            runOnUiThread(() -> btn.setMode(0));
        }
        if (!err) {
            long ms = t0 == 0 ? 0 : System.currentTimeMillis() - t0;
            if (ms >= 400) {
                long secs = Math.max(1, (ms + 500) / 1000);
                addMsg("Moi (voix Wi-Fi) : Message vocal · " + secs + " s\u0005" + (sentOk ? "Envoyé" : "Échec"));
                flash(sentOk ? "Message vocal envoyé" : "Échec de l'envoi vocal");
            } else {
                ui(STATE_READY);
            }
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
        ui(STATE_READY);

        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack track = new AudioTrack(AudioManager.STREAM_MUSIC, RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(min, CHUNK * 8), AudioTrack.MODE_STREAM);
        track.play();

        byte[] buf = new byte[2048];
        boolean receiving = false;
        long recvStart = 0;
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                sock.receive(p);
                int len = p.getLength();
                if (len < H) continue;
                int sid = ByteBuffer.wrap(buf).getInt();
                if (sid == myId) continue;
                synchronized (wifiPeers) {
                    wifiPeers.put(sid, System.currentTimeMillis());
                }
                if (len == H) continue; // simple signal de présence
                byte type = buf[4];
                if (type == T_TEXT) {
                    String msg = new String(buf, H, len - H, StandardCharsets.UTF_8);
                    onIncoming(msg, "Wi-Fi");
                } else if (type == T_AUDIO) {
                    track.write(buf, H, len - H);
                    if (!receiving) {
                        receiving = true;
                        recvStart = System.currentTimeMillis();
                        ui("Quelqu’un parle…");
                        runOnUiThread(() -> {
                            if (!talking) btn.setMode(2);
                        });
                    }
                }
            } catch (SocketTimeoutException e) {
                if (receiving) {
                    receiving = false;
                    long secs = Math.max(1, (System.currentTimeMillis() - recvStart + 500) / 1000);
                    addMsg("Quelqu'un (voix Wi-Fi) : Message vocal reçu · " + secs + " s");
                    ui(talking ? "Transmission en cours…" : STATE_READY);
                    runOnUiThread(() -> btn.setMode(talking ? 1 : 0));
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
        if (Build.VERSION.SDK_INT >= 26 && hasBlePerms()) startBleScan();
    }

    @Override
    protected void onPause() {
        super.onPause();
        talking = false;
        prefs.unregisterOnSharedPreferenceChangeListener(lis);
        stopBleScan();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        running = false;
        talking = false;
        handler.removeCallbacks(tick);
        stopBleScan();
        if (sock != null) sock.close();
        if (lock != null && lock.isHeld()) lock.release();
    }
}
