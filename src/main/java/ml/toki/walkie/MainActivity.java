package ml.toki.walkie;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.AdvertisingSet;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;package ml.toki.walkie;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
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
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
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
import java.util.HashMap;
import java.util.List;
import java.util.Random;

// Toki Walkie : talkie-walkie + messages en Wi-Fi local,
// et messages texte Bluetooth (longue portée BLE Coded PHY si possible, sinon mode normal),
// sans internet ni crédit.
public class MainActivity extends Activity {
    static final int PORT = 5005, RATE = 8000, CHUNK = 640, H = 5;
    static final byte T_AUDIO = 0, T_TEXT = 1;
    static final int MFR = 0xFFFF;          // identifiant fabricant (test) pour nos messages BLE
    static final int BLE_MAX_BYTES = 160;   // taille max d'un message Bluetooth
    static final int LEG_TEXT = 16;         // octets de texte par morceau (mode normal)
    final int myId = new Random().nextInt();

    volatile boolean talking = false, busy = false, running = true;
    volatile DatagramSocket sock;
    Button btn;
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
    int legToken = 0, legMsgId = new Random().nextInt(256);
    final HashMap<String, LegacyAsm> legBuf = new HashMap<>();
    final HashMap<Integer, Integer> legDone = new HashMap<>();
    final HashMap<Integer, String> lastMsg = new HashMap<>();
    final HashMap<Integer, Long> lastTime = new HashMap<>();
    BluetoothLeScanner bleScanner;

    // Enregistre un message dans l'historique (utilisé aussi par SmsReceiver s'il existe encore)
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

        Button ble = new Button(this);
        ble.setText("Bluetooth longue portée");
        ble.setTextColor(Color.WHITE);
        ble.setBackgroundColor(Color.rgb(106, 27, 154));

        bleInfo = new TextView(this);
        bleInfo.setTextColor(Color.GRAY);
        bleInfo.setTextSize(13);
        bleInfo.setGravity(Gravity.CENTER);

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
        root.addView(ble, full);
        root.addView(bleInfo, full);
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

        send.setOnClickListener(v -> {
            String t = input.getText().toString().trim();
            if (t.isEmpty()) return;
            sendText(t);
            addMsg("Moi (Wi-Fi) : " + t);
            input.setText("");
        });

        ble.setOnClickListener(v -> {
            String t = input.getText().toString().trim();
            if (t.isEmpty()) {
                status.setText("Écris d'abord un message");
                return;
            }
            if (sendBle(t)) input.setText("");
        });

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            lock = wm.createMulticastLock("toki");
            lock.acquire();
        }

        new Thread(this::recvLoop).start();
        ensureBle();
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

    void info(String t) {
        runOnUiThread(() -> bleInfo.setText(t));
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
        public void onStartFailure(int err) {
            ui("Échec de l'envoi Bluetooth (mode normal, code " + err + ")");
        }
    };

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
            bleBase = codedSupported(ad)
                    ? "Bluetooth : prêt (longue portée + mode normal)"
                    : "Bluetooth : prêt (mode normal, portée plus courte)";
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
        long now = System.currentTimeMillis();
        if (now - lastInfo > 1000) {
            lastInfo = now;
            info(bleBase + " | vus : " + seenAll + ", Toki : " + seenToki);
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
        deliver(id & 0xFFFF, new String(d, 8, d.length - 8, StandardCharsets.UTF_8));
    }

    void handleLegacy(byte[] d) {
        int sender = ((d[2] & 0xFF) << 8) | (d[3] & 0xFF);
        if (sender == (myId & 0xFFFF)) return;
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
        addMsg("Bluetooth : " + msg);
    }

    // Retourne true si le message est parti
    boolean sendBle(String t) {
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
        byte[] txt = t.getBytes(StandardCharsets.UTF_8);
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
        addMsg("Moi (Bluetooth) : " + t);
        status.setText("Envoi Bluetooth en cours…");
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
                if (st != AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                    ui("Échec de l'envoi longue portée (code " + st + ")");
                }
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
                    ui("Envoi Bluetooth terminé");
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
        stopBleScan();
        if (sock != null) sock.close();
        if (lock != null && lock.isHeld()) lock.release();
    }
}

import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
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
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
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
import java.util.HashMap;
import java.util.List;
import java.util.Random;

// Toki Walkie : talkie-walkie + messages en Wi-Fi local,
// et messages texte Bluetooth (longue portée BLE Coded PHY si possible, sinon mode normal),
// sans internet ni crédit.
public class MainActivity extends Activity {
    static final int PORT = 5005, RATE = 8000, CHUNK = 640, H = 5;
    static final byte T_AUDIO = 0, T_TEXT = 1;
    static final int MFR = 0xFFFF;          // identifiant fabricant (test) pour nos messages BLE
    static final int BLE_MAX_BYTES = 160;   // taille max d'un message Bluetooth
    static final int LEG_TEXT = 16;         // octets de texte par morceau (mode normal)
    final int myId = new Random().nextInt();

    volatile boolean talking = false, busy = false, running = true;
    volatile DatagramSocket sock;
    Button btn;
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
    int legToken = 0, legMsgId = new Random().nextInt(256);
    final HashMap<String, LegacyAsm> legBuf = new HashMap<>();
    final HashMap<Integer, Integer> legDone = new HashMap<>();
    final HashMap<Integer, String> lastMsg = new HashMap<>();
    final HashMap<Integer, Long> lastTime = new HashMap<>();
    BluetoothLeScanner bleScanner;

    // Enregistre un message dans l'historique (utilisé aussi par SmsReceiver s'il existe encore)
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

        Button ble = new Button(this);
        ble.setText("Bluetooth longue portée");
        ble.setTextColor(Color.WHITE);
        ble.setBackgroundColor(Color.rgb(106, 27, 154));

        bleInfo = new TextView(this);
        bleInfo.setTextColor(Color.GRAY);
        bleInfo.setTextSize(13);
        bleInfo.setGravity(Gravity.CENTER);

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
        root.addView(ble, full);
        root.addView(bleInfo, full);
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

        send.setOnClickListener(v -> {
            String t = input.getText().toString().trim();
            if (t.isEmpty()) return;
            sendText(t);
            addMsg("Moi (Wi-Fi) : " + t);
            input.setText("");
        });

        ble.setOnClickListener(v -> {
            String t = input.getText().toString().trim();
            if (t.isEmpty()) {
                status.setText("Écris d'abord un message");
                return;
            }
            if (sendBle(t)) input.setText("");
        });

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            lock = wm.createMulticastLock("toki");
            lock.acquire();
        }

        new Thread(this::recvLoop).start();
        ensureBle();
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

    void info(String t) {
        runOnUiThread(() -> bleInfo.setText(t));
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
        public void onStartFailure(int err) {
            ui("Échec de l'envoi Bluetooth (mode normal, code " + err + ")");
        }
    };

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
            bleBase = codedSupported(ad)
                    ? "Bluetooth : prêt (longue portée + mode normal)"
                    : "Bluetooth : prêt (mode normal, portée plus courte)";
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
        long now = System.currentTimeMillis();
        if (now - lastInfo > 1000) {
            lastInfo = now;
            info(bleBase + " | vus : " + seenAll + ", Toki : " + seenToki);
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
        deliver(id & 0xFFFF, new String(d, 8, d.length - 8, StandardCharsets.UTF_8));
    }

    void handleLegacy(byte[] d) {
        int sender = ((d[2] & 0xFF) << 8) | (d[3] & 0xFF);
        if (sender == (myId & 0xFFFF)) return;
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
        addMsg("Bluetooth : " + msg);
    }

    // Retourne true si le message est parti
    boolean sendBle(String t) {
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
        byte[] txt = t.getBytes(StandardCharsets.UTF_8);
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
        addMsg("Moi (Bluetooth) : " + t);
        status.setText("Envoi Bluetooth en cours…");
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
                if (st != AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                    ui("Échec de l'envoi longue portée (code " + st + ")");
                }
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
                    ui("Envoi Bluetooth terminé");
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
        stopBleScan();
        if (sock != null) sock.close();
        if (lock != null && lock.isHeld()) lock.release();
    }
}
package ml.toki.walkie;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.AdvertiseData;
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
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
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
import java.util.HashMap;
import java.util.List;
import java.util.Random;

// Toki Walkie : talkie-walkie + messages en Wi-Fi local,
// et messages texte Bluetooth longue portée (BLE Coded PHY), sans internet ni crédit.
public class MainActivity extends Activity {
    static final int PORT = 5005, RATE = 8000, CHUNK = 640, H = 5;
    static final byte T_AUDIO = 0, T_TEXT = 1;
    static final int MFR = 0xFFFF;          // identifiant fabricant (test) pour nos messages BLE
    static final int BLE_MAX_BYTES = 160;   // taille max d'un message Bluetooth
    final int myId = new Random().nextInt();

    volatile boolean talking = false, busy = false, running = true;
    volatile DatagramSocket sock;
    Button btn;
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
    BluetoothLeScanner bleScanner;

    // Enregistre un message dans l'historique (utilisé aussi par SmsReceiver s'il existe encore)
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

        Button ble = new Button(this);
        ble.setText("Bluetooth longue portée");
        ble.setTextColor(Color.WHITE);
        ble.setBackgroundColor(Color.rgb(106, 27, 154));

        bleInfo = new TextView(this);
        bleInfo.setTextColor(Color.GRAY);
        bleInfo.setTextSize(13);
        bleInfo.setGravity(Gravity.CENTER);

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
        root.addView(ble, full);
        root.addView(bleInfo, full);
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

        send.setOnClickListener(v -> {
            String t = input.getText().toString().trim();
            if (t.isEmpty()) return;
            sendText(t);
            addMsg("Moi (Wi-Fi) : " + t);
            input.setText("");
        });

        ble.setOnClickListener(v -> {
            String t = input.getText().toString().trim();
            if (t.isEmpty()) {
                status.setText("Écris d'abord un message");
                return;
            }
            if (sendBle(t)) input.setText("");
        });

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            lock = wm.createMulticastLock("toki");
            lock.acquire();
        }

        new Thread(this::recvLoop).start();
        ensureBle();
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

    void info(String t) {
        runOnUiThread(() -> bleInfo.setText(t));
    }

    void addMsg(String line) {
        saveMsg(this, line);
    }

    // ---------------- Bluetooth longue portée (BLE Coded PHY) ----------------

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
        if (Build.VERSION.SDK_INT < 26) {
            info("Bluetooth longue portée : Android trop ancien");
            return;
        }
        if (!hasBlePerms()) {
            requestPermissions(blePerms(), 3);
            return;
        }
        startBleScan();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 3) {
            if (hasBlePerms()) startBleScan();
            else info("Autorise le Bluetooth pour les messages longue portée");
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

    @TargetApi(26)
    void startBleScan() {
        if (scanning) return;
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null || !ad.isEnabled()) {
            info("Bluetooth éteint : active-le puis relance l'appli");
            return;
        }
        if (!ad.isLeCodedPhySupported() || !ad.isLeExtendedAdvertisingSupported()) {
            info("Ce téléphone ne supporte pas le Bluetooth longue portée");
            return;
        }
        BluetoothLeScanner sc = ad.getBluetoothLeScanner();
        if (sc == null) {
            info("Scanner Bluetooth indisponible");
            return;
        }
        try {
            ScanFilter f = new ScanFilter.Builder()
                    .setManufacturerData(MFR, new byte[]{'T', 'W'},
                            new byte[]{(byte) 0xFF, (byte) 0xFF})
                    .build();
            ScanSettings st = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setLegacy(false)
                    .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                    .build();
            sc.startScan(Collections.singletonList(f), st, scanCb);
            bleScanner = sc;
            scanning = true;
            info("Bluetooth longue portée : prêt (texte seulement)");
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

    void handleBle(ScanResult r) {
        ScanRecord sr = r.getScanRecord();
        if (sr == null) return;
        byte[] d = sr.getManufacturerSpecificData(MFR);
        if (d == null || d.length < 9 || d[0] != 'T' || d[1] != 'W') return;
        int id = ByteBuffer.wrap(d, 2, 4).getInt();
        int cnt = ((d[6] & 0xFF) << 8) | (d[7] & 0xFF);
        if (id == myId) return;
        synchronized (seen) {
            Integer last = seen.get(id);
            if (last != null && last == cnt) return; // même message, déjà reçu
            seen.put(id, cnt);
        }
        String msg = new String(d, 8, d.length - 8, StandardCharsets.UTF_8);
        addMsg("Bluetooth : " + msg);
    }

    // Retourne true si le message est parti
    @TargetApi(26)
    boolean sendBle(String t) {
        if (Build.VERSION.SDK_INT < 26) {
            status.setText("Bluetooth longue portée : Android trop ancien");
            return false;
        }
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
        if (!ad.isLeCodedPhySupported() || !ad.isLeExtendedAdvertisingSupported()) {
            status.setText("Téléphone non compatible Bluetooth longue portée");
            return false;
        }
        final BluetoothLeAdvertiser adv = ad.getBluetoothLeAdvertiser();
        if (adv == null) {
            status.setText("Bluetooth indisponible");
            return false;
        }
        byte[] txt = t.getBytes(StandardCharsets.UTF_8);
        if (txt.length > BLE_MAX_BYTES || txt.length + 12 > ad.getLeMaximumAdvertisingDataLength()) {
            status.setText("Message trop long pour le Bluetooth (max " + BLE_MAX_BYTES + " octets)");
            return false;
        }
        try {
            // arrête l'envoi précédent
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
                    if (st == AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                        ui("Message envoyé en Bluetooth");
                    } else {
                        ui("Échec de l'envoi Bluetooth (code " + st + ")");
                    }
                }
            };
            advCb = cb;
            adv.startAdvertisingSet(params, data, null, null, null, cb);
            addMsg("Moi (Bluetooth) : " + t);

            // diffuse pendant 8 secondes puis s'arrête
            handler.postDelayed(() -> {
                try {
                    adv.stopAdvertisingSet(cb);
                } catch (Exception e) {
                    // ignoré
                }
            }, 8000);
            return true;
        } catch (Exception e) {
            status.setText("Erreur d'envoi Bluetooth");
            return false;
        }
    }

    // ---------------- Voix et texte Wi-Fi (inchangé) ----------------

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
        stopBleScan();
        if (sock != null) sock.close();
        if (lock != null && lock.isHeld()) lock.release();
    }
}
