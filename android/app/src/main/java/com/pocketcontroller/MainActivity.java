package com.pocketcontroller;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class MainActivity extends Activity implements SensorEventListener, ControllerPad.Listener {
    private static final int BG_COLOR = 0xff07050b;
    private static final int PORT = 8766;
    private static final int DISCOVERY_PORT = 8767;

    private ControllerPad controllerPad;
    private SensorManager sensorManager;
    private Sensor rotationSensor;

    private volatile boolean discovering;
    private volatile long discoveryGeneration;
    private Thread discoveryThread;
    private volatile DatagramSocket discoverySocket;
    private WifiManager.MulticastLock discoveryMulticastLock;
    private WifiManager.WifiLock activeWifiLock;

    private volatile Socket socket;
    private volatile BufferedWriter writer;
    private volatile DatagramSocket udpSocket;
    private volatile boolean connected;
    private volatile boolean connecting;
    private volatile boolean desiredConnected;
    private volatile boolean reconnecting;
    private volatile boolean udpAcked;
    private volatile boolean tcpAlive;
    private volatile long connectionToken;
    private volatile long lastServerRxMs;
    private volatile long udpSeq;
    private volatile Thread connectionThread;
    private volatile String activeHost = "";
    private volatile String activePin = "";
    private volatile String sessionToken = "";
    private volatile int udpPort = 0;
    private final Object tcpWriteLock = new Object();

    private static final float TWO_PI = (float) (2.0 * Math.PI);
    private static final float[] LOCK_DEG_OPTIONS = {60f, 90f, 135f, 180f};

    private final float[] rawQuat = new float[4];
    private final float[] currentQuat = new float[4];
    private final float[] neutralQuat = new float[4];
    private volatile boolean hasLastQuat;
    private volatile boolean hasNeutral;
    private volatile float unwrappedWheelRad;
    private volatile float smoothedWheelDeg;
    private volatile float prevTargetDeg;
    private volatile float angularSpeedDegSec;
    private volatile long lastSensorTimestampNs;
    private volatile float lastNotifiedGyroSteer;
    private volatile float wheelMaxDeg = 90f;
    private final Object sendLock = new Object();
    private volatile boolean sendPending;
    private volatile float gyroSteer;
    private volatile float touchSteer;
    private volatile boolean touchingWheel;
    private volatile boolean touchingLeftStick;
    private volatile float throttle;
    private volatile float brake;
    private volatile float clutch;
    private volatile float handbrake;
    private volatile float lx;
    private volatile float ly;
    private volatile float rx;
    private volatile float ry;
    private volatile float lt;
    private volatile float rt;
    private volatile boolean dpadUp;
    private volatile boolean dpadDown;
    private volatile boolean dpadLeft;
    private volatile boolean dpadRight;
    private volatile boolean[] buttons = new boolean[16];
    private volatile String currentModeName = "racepad";

    private boolean gyroEnabled = true;
    private String discoveredHost = "";
    private String discoveredPin = "";
    private String discoveredName = "";

    private Dialog setupDialog;
    private EditText addressField;
    private EditText pinField;
    private TextView dialogStatusText;
    private Button dialogConnectBtn;
    private TextView discoveredCardText;
    private Button autoConnectBtn;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyImmersive();

        controllerPad = new ControllerPad(this);
        controllerPad.setListener(this);
        setContentView(controllerPad);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
            if (rotationSensor == null) {
                rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            }
        }

        SharedPreferences prefs = getSharedPreferences("controller", MODE_PRIVATE);
        wheelMaxDeg = prefs.getFloat("wheel_max_deg", 90f);
        gyroEnabled = rotationSensor != null && prefs.getBoolean("wheel_rotation_enabled", true);
        controllerPad.setWheelMaxDeg(wheelMaxDeg);
        controllerPad.setGyroEnabled(gyroEnabled);
        controllerPad.importLayoutJson(ControllerPad.MODE_RACEPAD, prefs.getString("layout_racepad_v1", ""));
        controllerPad.importLayoutJson(ControllerPad.MODE_GAMEPAD, prefs.getString("layout_gamepad_v1", ""));

        discoveredHost = prefs.getString("last_host", "");
        discoveredPin = prefs.getString("last_pin", "");

        if (discoveredHost.isEmpty()) {
            postOpenSetup();
        }
    }

    private void applyImmersive() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
        getWindow().setStatusBarColor(BG_COLOR);
        getWindow().setNavigationBarColor(BG_COLOR);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    private void postOpenSetup() {
        controllerPad.postDelayed(this::onOpenSetup, 250);
    }

    @Override
    public void onInputChanged(
            String mode,
            float steer, boolean isTouchingWheel,
            float newThrottle, float newBrake, float newClutch, float newHandbrake,
            float newLx, float newLy, float newRx, float newRy,
            float newLt, float newRt,
            boolean newDpadUp, boolean newDpadDown, boolean newDpadLeft, boolean newDpadRight,
            boolean[] newButtons
    ) {
        this.currentModeName = mode;
        this.touchSteer = steer;
        this.touchingWheel = isTouchingWheel || (controllerPad != null && controllerPad.isSteeringTouchOrBlend());
        this.touchingLeftStick = controllerPad != null && controllerPad.isLeftStickTouched();
        this.throttle = newThrottle;
        this.brake = newBrake;
        this.clutch = newClutch;
        this.handbrake = newHandbrake;
        this.lx = newLx;
        this.ly = newLy;
        this.rx = newRx;
        this.ry = newRy;
        this.lt = newLt;
        this.rt = newRt;
        this.dpadUp = newDpadUp;
        this.dpadDown = newDpadDown;
        this.dpadLeft = newDpadLeft;
        this.dpadRight = newDpadRight;
        if (newButtons != null) {
            System.arraycopy(newButtons, 0, this.buttons, 0, Math.min(newButtons.length, 16));
        }
        synchronized (sendLock) {
            sendPending = true;
            sendLock.notifyAll();
        }
    }

    @Override
    public void onOpenSetup() {
        showSetupDialog();
    }

    @Override
    public void onToggleMode() {
        // Mode toggled in ControllerPad
    }

    @Override
    public void onCalibrate() {
        calibrate();
    }

    @Override
    public void onToggleGyro() {
        if (rotationSensor == null) return;
        gyroEnabled = !gyroEnabled;
        getSharedPreferences("controller", MODE_PRIVATE).edit()
                .putBoolean("wheel_rotation_enabled", gyroEnabled)
                .apply();
        controllerPad.setGyroEnabled(gyroEnabled);
        if (gyroEnabled) calibrate();
    }

    @Override
    public void onLayoutChanged(String mode, String layoutJson) {
        getSharedPreferences("controller", MODE_PRIVATE).edit()
                .putString("layout_" + mode + "_v1", layoutJson)
                .apply();
    }

    private void showSetupDialog() {
        if (setupDialog != null && setupDialog.isShowing()) return;

        setupDialog = new Dialog(this);
        setupDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        setupDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(18), dp(20), dp(18));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xff120e1c);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1.2f), 0xff292038);
        layout.setBackground(bg);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("Pocket Controller Setup");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView closeBtn = new TextView(this);
        closeBtn.setText("✕");
        closeBtn.setTextColor(0xff94a3b8);
        closeBtn.setTextSize(18);
        closeBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
        closeBtn.setOnClickListener(v -> setupDialog.dismiss());
        header.addView(closeBtn, new LinearLayout.LayoutParams(-2, -2));
        layout.addView(header, new LinearLayout.LayoutParams(-1, -2));

        // 1. Auto-Discovery Card
        layout.addView(createSectionTitle("AUTO-DETECTED PC COMPANION"));
        LinearLayout discCard = createCardLayout();
        discoveredCardText = new TextView(this);
        discoveredCardText.setTextSize(12.5f);
        discoveredCardText.setTextColor(0xffcbd5e1);
        updateDiscoveryCard();
        discCard.addView(discoveredCardText);

        autoConnectBtn = createAccentButton("⚡ Quick Connect");
        autoConnectBtn.setOnClickListener(v -> {
            if (!discoveredHost.isEmpty() && !discoveredPin.isEmpty()) {
                addressField.setText(discoveredHost);
                pinField.setText(discoveredPin);
                connect();
            }
        });
        autoConnectBtn.setVisibility(discoveredHost.isEmpty() ? View.GONE : View.VISIBLE);
        discCard.addView(autoConnectBtn, new LinearLayout.LayoutParams(-1, dp(40)));
        layout.addView(discCard);

        // 2. Manual Connection Card
        layout.addView(createSectionTitle("MANUAL CONNECTION"));
        LinearLayout manualCard = createCardLayout();

        addressField = createEditField("PC IPv4 (e.g. 192.168.1.85)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addressField.setText(discoveredHost);
        manualCard.addView(addressField, new LinearLayout.LayoutParams(-1, dp(42)));

        pinField = createEditField("6-digit PIN", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinField.setText(discoveredPin);
        manualCard.addView(pinField, new LinearLayout.LayoutParams(-1, dp(42)));

        dialogConnectBtn = createAccentButton((connected || reconnecting || connecting) ? "Disconnect" : "Connect to PC");
        dialogConnectBtn.setOnClickListener(v -> {
            if (connected || connecting || reconnecting || desiredConnected) {
                disconnect();
            } else {
                connect();
            }
        });
        manualCard.addView(dialogConnectBtn, new LinearLayout.LayoutParams(-1, dp(44)));

        dialogStatusText = new TextView(this);
        dialogStatusText.setText(connected
                ? "Connected to " + activeHost + (udpAcked ? " (UDP fast-path)" : " (TCP)")
                : (reconnecting ? "Reconnecting to " + activeHost + "…" : "Enter your PC address and pairing PIN"));
        dialogStatusText.setTextColor(connected ? 0xff10b981 : (reconnecting ? 0xfff59e0b : 0xff94a3b8));
        dialogStatusText.setTextSize(12);
        dialogStatusText.setGravity(Gravity.CENTER);
        dialogStatusText.setPadding(0, dp(6), 0, 0);
        manualCard.addView(dialogStatusText);
        layout.addView(manualCard);

        // 3. Settings & Layout Customization Card
        layout.addView(createSectionTitle("CONTROLS & CALIBRATION"));
        LinearLayout settingsCard = createCardLayout();

        Switch gyroToggle = new Switch(this);
        gyroToggle.setText("Steering Wheel Rotation");
        gyroToggle.setTextColor(Color.WHITE);
        gyroToggle.setChecked(gyroEnabled);
        gyroToggle.setEnabled(rotationSensor != null);
        gyroToggle.setOnCheckedChangeListener((btn, checked) -> {
            gyroEnabled = checked && rotationSensor != null;
            getSharedPreferences("controller", MODE_PRIVATE).edit()
                    .putBoolean("wheel_rotation_enabled", gyroEnabled)
                    .apply();
            controllerPad.setGyroEnabled(gyroEnabled);
            if (gyroEnabled) calibrate();
        });
        settingsCard.addView(gyroToggle, new LinearLayout.LayoutParams(-1, dp(38)));

        TextView steerHint = new TextView(this);
        steerHint.setText(rotationSensor != null
                ? "Rotate the phone left and right like a steering wheel (tilt ignored)."
                : "No rotation sensor found; drag the virtual steering wheel on screen.");
        steerHint.setTextColor(0xff94a3b8);
        steerHint.setTextSize(11);
        steerHint.setPadding(0, 0, 0, dp(8));
        settingsCard.addView(steerHint);

        TextView rangeLabel = new TextView(this);
        rangeLabel.setText("Wheel Lock Range (each side)");
        rangeLabel.setTextColor(0xffcbd5e1);
        rangeLabel.setTextSize(12);
        rangeLabel.setPadding(0, dp(2), 0, dp(6));
        settingsCard.addView(rangeLabel);

        LinearLayout rangeRow = new LinearLayout(this);
        rangeRow.setOrientation(LinearLayout.HORIZONTAL);
        Button[] rangeBtns = new Button[LOCK_DEG_OPTIONS.length];
        for (int i = 0; i < LOCK_DEG_OPTIONS.length; i++) {
            float degOption = LOCK_DEG_OPTIONS[i];
            Button optBtn = new Button(this);
            optBtn.setText(Math.round(degOption) + "°");
            optBtn.setTextSize(12);
            optBtn.setAllCaps(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(36), 1f);
            if (i < LOCK_DEG_OPTIONS.length - 1) lp.setMargins(0, 0, dp(6), 0);
            rangeRow.addView(optBtn, lp);
            rangeBtns[i] = optBtn;
        }
        Runnable refreshRangeStyles = () -> {
            for (int i = 0; i < LOCK_DEG_OPTIONS.length; i++) {
                boolean selected = Math.abs(wheelMaxDeg - LOCK_DEG_OPTIONS[i]) < 1f;
                GradientDrawable pillBg = new GradientDrawable();
                pillBg.setColor(selected ? 0xff7c3aed : 0xff120e1c);
                pillBg.setCornerRadius(dp(8));
                pillBg.setStroke(dp(1), selected ? 0xffc4b5fd : 0xff292038);
                rangeBtns[i].setBackground(pillBg);
                rangeBtns[i].setTextColor(selected ? Color.WHITE : 0xffcbd5e1);
            }
        };
        for (int i = 0; i < LOCK_DEG_OPTIONS.length; i++) {
            float degOption = LOCK_DEG_OPTIONS[i];
            rangeBtns[i].setOnClickListener(v -> {
                wheelMaxDeg = degOption;
                getSharedPreferences("controller", MODE_PRIVATE).edit()
                        .putFloat("wheel_max_deg", wheelMaxDeg)
                        .apply();
                controllerPad.setWheelMaxDeg(wheelMaxDeg);
                refreshRangeStyles.run();
            });
        }
        refreshRangeStyles.run();
        settingsCard.addView(rangeRow, new LinearLayout.LayoutParams(-1, -2));

        Button centerBtn = createOutlineButton("⌖ Calibrate Neutral Center");
        centerBtn.setOnClickListener(v -> {
            calibrate();
            dialogStatusText.setText("Neutral center calibrated!");
            dialogStatusText.setTextColor(0xff34d399);
        });
        settingsCard.addView(centerBtn, new LinearLayout.LayoutParams(-1, dp(40)));

        Button customizeBtn = createOutlineButton("✎ Customize Buttons (Rearrange & Resize)");
        customizeBtn.setOnClickListener(v -> {
            if (setupDialog != null) setupDialog.dismiss();
            controllerPad.setEditMode(true);
        });
        settingsCard.addView(customizeBtn, new LinearLayout.LayoutParams(-1, dp(40)));

        Button resetLayoutBtn = createOutlineButton("↺ Reset Both Layouts to Default");
        resetLayoutBtn.setOnClickListener(v -> {
            controllerPad.resetAllLayouts();
            dialogStatusText.setText("Racepad & Gamepad layouts reset to default!");
            dialogStatusText.setTextColor(0xff34d399);
        });
        settingsCard.addView(resetLayoutBtn, new LinearLayout.LayoutParams(-1, dp(40)));

        layout.addView(settingsCard);

        scroll.addView(layout);
        setupDialog.setContentView(scroll);
        setupDialog.getWindow().setLayout(dp(440), ViewGroup.LayoutParams.WRAP_CONTENT);
        setupDialog.show();
    }

    private void updateDiscoveryCard() {
        if (discoveredCardText == null) return;
        if (!discoveredHost.isEmpty() && !discoveredPin.isEmpty()) {
            discoveredCardText.setText("Detected: " + (discoveredName.isEmpty() ? "PC" : discoveredName) + "\n"
                    + discoveredHost + " · PIN: " + discoveredPin);
            if (autoConnectBtn != null) autoConnectBtn.setVisibility(View.VISIBLE);
        } else {
            discoveredCardText.setText("Scanning Wi-Fi broadcast (port 8767)…\nKeep PC companion open on the same network.");
            if (autoConnectBtn != null) autoConnectBtn.setVisibility(View.GONE);
        }
    }

    private TextView createSectionTitle(String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(0xffa78bfa);
        tv.setTextSize(10.5f);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setPadding(dp(4), dp(10), dp(4), dp(4));
        return tv;
    }

    private LinearLayout createCardLayout() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xff181324);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), 0xff2e253e);
        card.setBackground(bg);
        return card;
    }

    private EditText createEditField(String hint, int inputType) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint(hint);
        field.setHintTextColor(0xff64748b);
        field.setTextColor(Color.WHITE);
        field.setTextSize(13.5f);
        field.setInputType(inputType);
        field.setPadding(dp(12), dp(6), dp(12), dp(6));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xff0f0c18);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0xff292038);
        field.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(40));
        lp.setMargins(0, dp(4), 0, dp(6));
        field.setLayoutParams(lp);
        return field;
    }

    private Button createAccentButton(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(13);
        btn.setTypeface(null, android.graphics.Typeface.BOLD);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xff7c3aed);
        bg.setCornerRadius(dp(8));
        btn.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(42));
        lp.setMargins(0, dp(6), 0, dp(4));
        btn.setLayoutParams(lp);
        return btn;
    }

    private Button createOutlineButton(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(0xffc4b5fd);
        btn.setTextSize(12.5f);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xff1f192c);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0xff473b5e);
        btn.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(40));
        lp.setMargins(0, dp(6), 0, dp(2));
        btn.setLayoutParams(lp);
        return btn;
    }

    private synchronized void acquireWifiLock() {
        try {
            if (activeWifiLock != null && activeWifiLock.isHeld()) return;
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return;
            int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            WifiManager.WifiLock lock = wifi.createWifiLock(mode, "PocketControllerLowLatency");
            lock.setReferenceCounted(false);
            lock.acquire();
            activeWifiLock = lock;
        } catch (RuntimeException ignored) {}
    }

    private synchronized void releaseWifiLock() {
        WifiManager.WifiLock lock = activeWifiLock;
        activeWifiLock = null;
        if (lock != null && lock.isHeld()) {
            try { lock.release(); } catch (RuntimeException ignored) {}
        }
    }

    private void connect() {
        String host = addressField != null ? addressField.getText().toString().trim() : discoveredHost;
        String pin = pinField != null ? pinField.getText().toString().trim() : discoveredPin;

        if (host.isEmpty() || pin.length() != 6) {
            if (dialogStatusText != null) {
                dialogStatusText.setText("Enter valid PC IP and 6-digit PIN");
                dialogStatusText.setTextColor(0xfff43f5e);
            }
            return;
        }

        getSharedPreferences("controller", MODE_PRIVATE).edit()
                .putString("last_host", host)
                .putString("last_pin", pin)
                .apply();

        activeHost = host;
        activePin = pin;
        desiredConnected = true;
        reconnecting = false;
        connecting = true;
        acquireWifiLock();

        if (dialogConnectBtn != null) dialogConnectBtn.setText("Disconnect");
        if (dialogStatusText != null) {
            dialogStatusText.setText("Connecting to " + host + "…");
            dialogStatusText.setTextColor(0xff38bdf8);
        }

        long token = ++connectionToken;
        Thread worker = new Thread(() -> runConnectionLoop(token), "controller-connection");
        connectionThread = worker;
        worker.start();
    }

    private boolean sendTcpLine(BufferedWriter output, String line) {
        if (output == null) return false;
        synchronized (tcpWriteLock) {
            try {
                output.write(line);
                output.write('\n');
                output.flush();
                return true;
            } catch (IOException exc) {
                return false;
            }
        }
    }

    private void runConnectionLoop(long token) {
        final int[] backoffsMs = {180, 350, 650, 1000, 1500, 2000};
        int attempt = 0;

        while (desiredConnected && token == connectionToken) {
            String host = activeHost;
            String pin = activePin;
            if (!discoveredHost.isEmpty() && discoveredHost.equals(host) && discoveredPin.matches("\\d{6}")) {
                pin = discoveredPin;
                activePin = pin;
            }

            Socket candidate = new Socket();
            DatagramSocket candidateUdp = null;
            boolean pinRejected = false;
            String lastError = "Connection ended";

            try {
                candidate.setTcpNoDelay(true);
                candidate.setKeepAlive(true);
                try { candidate.setTrafficClass(0x10); } catch (Exception ignored) {}
                candidate.connect(new InetSocketAddress(host, PORT), 4000);
                candidate.setSoTimeout(4500);
                if (!desiredConnected || token != connectionToken) throw new InterruptedException("Cancelled");

                socket = candidate;
                BufferedReader reader = new BufferedReader(new InputStreamReader(candidate.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter output = new BufferedWriter(new OutputStreamWriter(candidate.getOutputStream(), StandardCharsets.UTF_8));
                writer = output;

                JSONObject hello = new JSONObject();
                hello.put("type", "hello");
                hello.put("pin", pin);
                hello.put("name", Build.MODEL == null ? "Android Phone" : Build.MODEL);
                hello.put("udp", true);
                if (!sendTcpLine(output, hello.toString())) {
                    throw new IOException("Could not send pairing handshake");
                }

                String response = reader.readLine();
                if (response == null) {
                    throw new IllegalStateException("PC closed connection");
                }
                JSONObject readyObj = new JSONObject(response);
                if (!"ready".equals(readyObj.optString("type"))) {
                    String reason = readyObj.optString("message", "Pairing rejected");
                    if (reason.toLowerCase(Locale.US).contains("pin")) {
                        pinRejected = true;
                    }
                    throw new IllegalStateException(reason);
                }

                if (!desiredConnected || token != connectionToken) throw new InterruptedException("Cancelled");

                sessionToken = readyObj.optString("session", "");
                udpPort = readyObj.optInt("udp_port", 0);
                udpAcked = false;
                udpSeq = 0L;
                tcpAlive = true;
                lastServerRxMs = SystemClock.elapsedRealtime();

                if (udpPort > 0 && !sessionToken.isEmpty()) {
                    try {
                        candidateUdp = new DatagramSocket();
                        try { candidateUdp.setTrafficClass(0x10); } catch (Exception ignored) {}
                        candidateUdp.connect(InetAddress.getByName(host), udpPort);
                        udpSocket = candidateUdp;
                    } catch (Exception ignored) {
                        closeDatagram(candidateUdp);
                        candidateUdp = null;
                        udpSocket = null;
                    }
                }

                connected = true;
                connecting = false;
                reconnecting = false;
                attempt = 0;

                final String displayHost = discoveredName.isEmpty() ? host : discoveredName;
                runOnUiThread(() -> {
                    if (token != connectionToken) return;
                    if (dialogConnectBtn != null) dialogConnectBtn.setText("Disconnect");
                    if (dialogStatusText != null) {
                        dialogStatusText.setText("Connected to " + host);
                        dialogStatusText.setTextColor(0xff10b981);
                    }
                    controllerPad.setConnectionStatus(true, displayHost, "125Hz TCP");
                    if (setupDialog != null && setupDialog.isShowing()) {
                        setupDialog.dismiss();
                    }
                });

                calibrate();

                Thread readerThread = new Thread(() -> {
                    try {
                        while (desiredConnected && connected && token == connectionToken && !candidate.isClosed()) {
                            String line;
                            try {
                                line = reader.readLine();
                            } catch (SocketTimeoutException timeout) {
                                if (SystemClock.elapsedRealtime() - lastServerRxMs > 5500L) {
                                    throw new IOException("PC heartbeat timed out");
                                }
                                continue;
                            }
                            if (line == null) {
                                throw new IOException("Connection closed by PC");
                            }
                            lastServerRxMs = SystemClock.elapsedRealtime();
                            try {
                                JSONObject msg = new JSONObject(line);
                                String msgType = msg.optString("type");
                                if ("ping".equals(msgType)) {
                                    sendTcpLine(output, "{\"type\":\"pong\"}");
                                } else if ("udp_ack".equals(msgType) && msg.optBoolean("ok", true)) {
                                    if (!udpAcked) {
                                        udpAcked = true;
                                        runOnUiThread(() -> {
                                            if (connected && token == connectionToken) {
                                                controllerPad.setConnectionStatus(true, displayHost, "125Hz UDP");
                                            }
                                        });
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    } catch (Exception ignored) {
                        // Reader noticed dead link; wake sender loop immediately.
                    } finally {
                        tcpAlive = false;
                        synchronized (sendLock) {
                            sendLock.notifyAll();
                        }
                    }
                }, "controller-tcp-rx");
                readerThread.setDaemon(true);
                readerThread.start();

                StringBuilder packetBuf = new StringBuilder(384);
                long connectedAtMs = SystemClock.elapsedRealtime();
                long lastTcpKeepaliveMs = connectedAtMs;
                long lastUdpProbeMs = 0L;
                String prevSignature = "";

                while (desiredConnected && connected && tcpAlive && token == connectionToken && !candidate.isClosed()) {
                    synchronized (sendLock) {
                        sendPending = false;
                    }
                    long sentAtNs = System.nanoTime();
                    long nowMs = SystemClock.elapsedRealtime();

                    if (nowMs - lastServerRxMs > 5500L) {
                        throw new IOException("Connection lost (heartbeat timeout)");
                    }

                    String jsonLine = buildInputJson(packetBuf, false);
                    boolean stateChanged = !jsonLine.equals(prevSignature);
                    prevSignature = jsonLine;
                    boolean activeInput = stateChanged || isAnyControlActive();

                    if (udpAcked && candidateUdp != null) {
                        String udpLine = buildInputJson(packetBuf, true);
                        byte[] bytes = udpLine.getBytes(StandardCharsets.UTF_8);
                        try {
                            candidateUdp.send(new DatagramPacket(bytes, bytes.length));
                        } catch (IOException udpErr) {
                            udpAcked = false;
                            if (!sendTcpLine(output, jsonLine)) {
                                throw new IOException("Socket disconnected");
                            }
                        }
                        if (nowMs - lastTcpKeepaliveMs >= 1000L) {
                            if (!sendTcpLine(output, "{\"type\":\"ping\"}")) {
                                throw new IOException("TCP keepalive failed");
                            }
                            lastTcpKeepaliveMs = nowMs;
                        }
                    } else {
                        if (candidateUdp != null && !sessionToken.isEmpty() && (nowMs - connectedAtMs) < 3000L && (nowMs - lastUdpProbeMs) >= 120L) {
                            lastUdpProbeMs = nowMs;
                            String udpLine = buildInputJson(packetBuf, true);
                            byte[] bytes = udpLine.getBytes(StandardCharsets.UTF_8);
                            try {
                                candidateUdp.send(new DatagramPacket(bytes, bytes.length));
                            } catch (IOException ignored) {}
                        }
                        if (!sendTcpLine(output, jsonLine)) {
                            throw new IOException("Socket disconnected");
                        }
                        lastTcpKeepaliveMs = nowMs;
                    }

                    if (writer != output || socket == null || socket.isClosed() || !tcpAlive) {
                        throw new IllegalStateException("Socket disconnected");
                    }

                    long elapsedMs = (System.nanoTime() - sentAtNs) / 1_000_000L;
                    long targetPeriod = activeInput ? 8L : 40L;
                    long minWait = activeInput ? 4L : 8L;
                    long waitMs = Math.max(minWait, targetPeriod - elapsedMs);
                    synchronized (sendLock) {
                        if (!sendPending && desiredConnected && connected && tcpAlive && token == connectionToken) {
                            sendLock.wait(waitMs);
                        }
                    }
                }
            } catch (Exception error) {
                lastError = error.getMessage() == null ? "Connection lost" : error.getMessage();
            } finally {
                connected = false;
                tcpAlive = false;
                udpAcked = false;
                if (socket == candidate) socket = null;
                if (udpSocket == candidateUdp) udpSocket = null;
                writer = null;
                closeDatagram(candidateUdp);
                closeSocket(candidate);
            }

            if (!desiredConnected || token != connectionToken) {
                break;
            }

            if (pinRejected) {
                desiredConnected = false;
                connecting = false;
                reconnecting = false;
                releaseWifiLock();
                final String errMsg = lastError;
                runOnUiThread(() -> {
                    if (dialogConnectBtn != null) dialogConnectBtn.setText("Connect to PC");
                    if (dialogStatusText != null) {
                        dialogStatusText.setText(errMsg);
                        dialogStatusText.setTextColor(0xfff43f5e);
                    }
                    controllerPad.setConnectionStatus(false, "DISCONNECTED", "0Hz");
                });
                return;
            }

            reconnecting = true;
            connecting = true;
            final String displayHost = discoveredName.isEmpty() ? host : discoveredName;
            final String retryReason = lastError;
            runOnUiThread(() -> {
                if (token != connectionToken || !desiredConnected) return;
                if (dialogConnectBtn != null) dialogConnectBtn.setText("Disconnect");
                if (dialogStatusText != null) {
                    dialogStatusText.setText("Reconnecting to " + host + " (" + retryReason + ")…");
                    dialogStatusText.setTextColor(0xfff59e0b);
                }
                controllerPad.setReconnectingStatus(displayHost);
            });

            int delayMs = backoffsMs[Math.min(attempt, backoffsMs.length - 1)];
            attempt++;
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                break;
            }
        }

        connected = false;
        connecting = false;
        reconnecting = false;
        releaseWifiLock();
        if (token == connectionToken) {
            runOnUiThread(() -> {
                if (dialogConnectBtn != null) dialogConnectBtn.setText("Connect to PC");
                if (dialogStatusText != null) {
                    dialogStatusText.setText("Disconnected");
                    dialogStatusText.setTextColor(0xff94a3b8);
                }
                controllerPad.setConnectionStatus(false, "DISCONNECTED", "0Hz");
            });
        }
    }

    private boolean isAnyControlActive() {
        if (touchingWheel || touchingLeftStick || throttle > 0.005f || brake > 0.005f
                || clutch > 0.005f || handbrake > 0.005f || lt > 0.005f || rt > 0.005f
                || Math.abs(lx) > 0.01f || Math.abs(ly) > 0.01f || Math.abs(rx) > 0.01f || Math.abs(ry) > 0.01f
                || dpadUp || dpadDown || dpadLeft || dpadRight) {
            return true;
        }
        boolean[] current = buttons;
        for (int i = 0; i < 16; i++) {
            if (current[i]) return true;
        }
        return false;
    }

    private String buildInputJson(StringBuilder sb, boolean withUdpHeader) {
        sb.setLength(0);
        String mode = currentModeName;
        boolean wheelControlled = touchingWheel || !gyroEnabled;
        float steer = finite(wheelControlled ? touchSteer : gyroSteer);
        float steerDeg = Math.round(steer * wheelMaxDeg * 10f) / 10f;
        float outLx = ("gamepad".equals(mode) && gyroEnabled && !touchingLeftStick && !touchingWheel)
                ? steer
                : ("gamepad".equals(mode) && touchingWheel ? steer : lx);

        sb.append("{\"type\":\"input\"");
        if (withUdpHeader) {
            sb.append(",\"s\":\"").append(sessionToken).append("\",\"seq\":").append(++udpSeq);
        }
        sb.append(",\"mode\":\"").append(mode).append('"')
                .append(",\"steer\":").append(round4(steer))
                .append(",\"steer_deg\":").append(steerDeg)
                .append(",\"throttle\":").append(round4(finite(throttle)))
                .append(",\"brake\":").append(round4(finite(brake)))
                .append(",\"clutch\":").append(round4(finite(clutch)))
                .append(",\"handbrake\":").append(round4(finite(handbrake)))
                .append(",\"lx\":").append(round4(finite(outLx)))
                .append(",\"ly\":").append(round4(finite(ly)))
                .append(",\"rx\":").append(round4(finite(rx)))
                .append(",\"ry\":").append(round4(finite(ry)))
                .append(",\"lt\":").append(round4(finite(lt)))
                .append(",\"rt\":").append(round4(finite(rt)))
                .append(",\"dpad\":{\"up\":").append(dpadUp)
                .append(",\"down\":").append(dpadDown)
                .append(",\"left\":").append(dpadLeft)
                .append(",\"right\":").append(dpadRight)
                .append("},\"buttons\":[");
        boolean[] current = buttons;
        for (int i = 0; i < 16; i++) {
            if (i > 0) sb.append(',');
            sb.append(i < current.length && current[i]);
        }
        sb.append("]}");
        return sb.toString();
    }

    private static float round4(float value) {
        return Math.round(value * 10000f) / 10000f;
    }

    private float finite(float value) {
        return Float.isNaN(value) || Float.isInfinite(value) ? 0f : Math.max(-1f, Math.min(1f, value));
    }

    private void disconnect() {
        ++connectionToken;
        desiredConnected = false;
        connected = false;
        connecting = false;
        reconnecting = false;
        tcpAlive = false;
        udpAcked = false;

        final Socket oldSocket = socket;
        final BufferedWriter oldWriter = writer;
        final DatagramSocket oldUdp = udpSocket;
        socket = null;
        writer = null;
        udpSocket = null;

        synchronized (sendLock) {
            sendLock.notifyAll();
        }
        Thread connWorker = connectionThread;
        connectionThread = null;
        if (connWorker != null) connWorker.interrupt();
        releaseWifiLock();

        Thread byeClose = new Thread(() -> {
            if (oldWriter != null) {
                sendTcpLine(oldWriter, "{\"type\":\"bye\"}");
            }
            closeDatagram(oldUdp);
            closeSocket(oldSocket);
        }, "controller-bye");
        byeClose.setDaemon(true);
        byeClose.start();

        if (dialogConnectBtn != null) dialogConnectBtn.setText("Connect to PC");
        if (dialogStatusText != null) {
            dialogStatusText.setText("Disconnected");
            dialogStatusText.setTextColor(0xff94a3b8);
        }
        controllerPad.setConnectionStatus(false, "DISCONNECTED", "0Hz");
    }

    private void closeSocket(Socket value) {
        if (value != null) {
            try { value.close(); } catch (Exception ignored) {}
        }
    }

    private void closeDatagram(DatagramSocket value) {
        if (value != null) {
            try { value.close(); } catch (Exception ignored) {}
        }
    }

    private void calibrate() {
        if (hasLastQuat) {
            System.arraycopy(currentQuat, 0, neutralQuat, 0, 4);
            hasNeutral = true;
        } else {
            hasNeutral = false;
        }
        unwrappedWheelRad = 0f;
        smoothedWheelDeg = 0f;
        prevTargetDeg = 0f;
        angularSpeedDegSec = 0f;
        lastSensorTimestampNs = 0L;
        lastNotifiedGyroSteer = 0f;
        gyroSteer = 0f;
        touchSteer = 0f;
        if (controllerPad != null) controllerPad.resetSteering();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private synchronized void acquireDiscoveryLock(long generation) {
        if (generation != discoveryGeneration || !discovering) return;
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return;
            WifiManager.MulticastLock lock = wifi.createMulticastLock("PocketControllerDiscovery");
            lock.setReferenceCounted(false);
            lock.acquire();
            discoveryMulticastLock = lock;
        } catch (RuntimeException ignored) {}
    }

    private synchronized void releaseDiscoveryLock(long generation) {
        if (generation != discoveryGeneration) return;
        WifiManager.MulticastLock lock = discoveryMulticastLock;
        discoveryMulticastLock = null;
        if (lock != null && lock.isHeld()) {
            try { lock.release(); } catch (RuntimeException ignored) {}
        }
    }

    private boolean isDiscoveryCurrent(long generation) {
        return discovering && generation == discoveryGeneration;
    }

    private void startDiscovery() {
        if (discovering) return;
        long generation = ++discoveryGeneration;
        discovering = true;
        acquireDiscoveryLock(generation);
        Thread worker = new Thread(() -> {
            DatagramSocket ds = null;
            boolean failed = false;
            try {
                ds = new DatagramSocket(null);
                discoverySocket = ds;
                if (!isDiscoveryCurrent(generation)) return;
                ds.setReuseAddress(true);
                ds.bind(new InetSocketAddress(DISCOVERY_PORT));
                ds.setSoTimeout(1200);
                byte[] buffer = new byte[2048];
                while (isDiscoveryCurrent(generation)) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    try {
                        ds.receive(packet);
                    } catch (SocketTimeoutException ignored) {
                        continue;
                    } catch (IOException exc) {
                        failed = isDiscoveryCurrent(generation) && !ds.isClosed();
                        break;
                    }

                    try {
                        String jsonStr = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                        JSONObject beacon = new JSONObject(jsonStr);
                        if (!"pocket-controller".equals(beacon.optString("service"))) continue;
                        String foundHost = packet.getAddress().getHostAddress();
                        String pin = beacon.optString("pin", "");
                        String hostname = beacon.optString("hostname", "PC");
                        if (!pin.matches("\\d{6}")) continue;
                        runOnUiThread(() -> {
                            if (isDiscoveryCurrent(generation)) onDiscoveredPc(foundHost, pin, hostname);
                        });
                    } catch (Exception ignored) {}
                }
            } catch (IOException exc) {
                failed = isDiscoveryCurrent(generation) && (ds == null || !ds.isClosed());
            } catch (RuntimeException exc) {
                failed = isDiscoveryCurrent(generation);
            } finally {
                if (discoverySocket == ds) discoverySocket = null;
                if (ds != null) {
                    try { ds.close(); } catch (Exception ignored) {}
                }
                if (generation == discoveryGeneration) {
                    discovering = false;
                    if (discoveryThread == Thread.currentThread()) discoveryThread = null;
                    releaseDiscoveryLock(generation);
                    if (failed) {
                        runOnUiThread(() -> {
                            if (generation == discoveryGeneration && discoveredHost.isEmpty()
                                    && discoveredCardText != null) {
                                discoveredCardText.setText("Automatic discovery unavailable. Enter the PC address and PIN manually.");
                            }
                        });
                    }
                }
            }
        }, "pc-discovery");
        discoveryThread = worker;
        worker.start();
    }

    private void stopDiscovery() {
        long generation = ++discoveryGeneration;
        discovering = false;
        DatagramSocket ds = discoverySocket;
        discoverySocket = null;
        if (ds != null) {
            try { ds.close(); } catch (Exception ignored) {}
        }
        Thread worker = discoveryThread;
        discoveryThread = null;
        if (worker != null) worker.interrupt();
        releaseDiscoveryLock(generation);
    }

    private void onDiscoveredPc(String host, String pin, String hostname) {
        if (host == null || host.isEmpty()) return;
        String previousHost = discoveredHost;
        String savedHost = getSharedPreferences("controller", MODE_PRIVATE).getString("last_host", "");
        String currentHost = addressField == null ? "" : addressField.getText().toString().trim();
        boolean useDetectedHost = currentHost.isEmpty()
                || currentHost.equalsIgnoreCase(savedHost)
                || currentHost.equalsIgnoreCase(previousHost)
                || currentHost.equalsIgnoreCase(host);

        discoveredHost = host;
        discoveredName = hostname;
        if (pin != null && pin.matches("\\d{6}")) discoveredPin = pin;

        if (addressField != null && useDetectedHost) addressField.setText(host);
        if (pinField != null && useDetectedHost && !discoveredPin.isEmpty()) pinField.setText(discoveredPin);
        if (useDetectedHost && !discoveredPin.isEmpty()) {
            getSharedPreferences("controller", MODE_PRIVATE).edit()
                    .putString("last_host", host)
                    .putString("last_pin", discoveredPin)
                    .apply();
        }
        updateDiscoveryCard();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (sensorManager != null && rotationSensor != null) {
            hasNeutral = false;
            hasLastQuat = false;
            lastSensorTimestampNs = 0L;
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_FASTEST);
        }
        startDiscovery();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersive();
    }

    @Override
    protected void onStop() {
        stopDiscovery();
        if (sensorManager != null) sensorManager.unregisterListener(this);
        disconnect();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stopDiscovery();
        disconnect();
        super.onDestroy();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        int type = event.sensor.getType();
        if (type != Sensor.TYPE_GAME_ROTATION_VECTOR && type != Sensor.TYPE_ROTATION_VECTOR) return;

        SensorManager.getQuaternionFromVector(rawQuat, event.values);
        float norm = (float) Math.sqrt(
                rawQuat[0] * rawQuat[0] + rawQuat[1] * rawQuat[1]
                        + rawQuat[2] * rawQuat[2] + rawQuat[3] * rawQuat[3]
        );
        if (norm < 1e-6f) return;
        for (int i = 0; i < 4; i++) rawQuat[i] /= norm;

        if (hasLastQuat) {
            float dot = rawQuat[0] * currentQuat[0] + rawQuat[1] * currentQuat[1]
                    + rawQuat[2] * currentQuat[2] + rawQuat[3] * currentQuat[3];
            if (dot < 0f) {
                for (int i = 0; i < 4; i++) rawQuat[i] = -rawQuat[i];
            }
        }
        System.arraycopy(rawQuat, 0, currentQuat, 0, 4);
        hasLastQuat = true;

        if (!hasNeutral) {
            System.arraycopy(currentQuat, 0, neutralQuat, 0, 4);
            hasNeutral = true;
            unwrappedWheelRad = 0f;
            smoothedWheelDeg = 0f;
            prevTargetDeg = 0f;
            angularSpeedDegSec = 0f;
            lastSensorTimestampNs = event.timestamp;
            gyroSteer = 0f;
            if (controllerPad != null) controllerPad.setDisplayedGyroSteer(0f);
            return;
        }

        // Swing-Twist around screen normal (+Z): clockwise rotation = positive right steer.
        float rw = neutralQuat[0] * currentQuat[0] + neutralQuat[1] * currentQuat[1]
                + neutralQuat[2] * currentQuat[2] + neutralQuat[3] * currentQuat[3];
        float rz = neutralQuat[0] * currentQuat[3] - neutralQuat[1] * currentQuat[2]
                + neutralQuat[2] * currentQuat[1] - neutralQuat[3] * currentQuat[0];

        float rawTwistRad = wrapAnglePi(-2f * (float) Math.atan2(rz, rw));
        int turns = Math.round((unwrappedWheelRad - rawTwistRad) / TWO_PI);
        unwrappedWheelRad = rawTwistRad + turns * TWO_PI;

        float maxTrack = Math.max(wheelMaxDeg * 1.5f, 270f);
        float targetDeg = Math.max(-maxTrack, Math.min(maxTrack, (float) Math.toDegrees(unwrappedWheelRad)));

        float dt = lastSensorTimestampNs == 0L
                ? 0.008f
                : Math.max(0.001f, Math.min(0.05f, (event.timestamp - lastSensorTimestampNs) * 1e-9f));
        lastSensorTimestampNs = event.timestamp;

        // Tuned velocity-adaptive One-Euro filter: zero noticeable phase lag, rock-solid center stability.
        float instSpeed = Math.abs(targetDeg - prevTargetDeg) / dt;
        prevTargetDeg = targetDeg;
        float speedAlpha = 1f - (float) Math.exp(-TWO_PI * 14f * dt);
        angularSpeedDegSec += (instSpeed - angularSpeedDegSec) * speedAlpha;

        float cutoffHz = 9.0f + Math.min(34f, angularSpeedDegSec * 0.24f);
        float smoothAlpha = 1f - (float) Math.exp(-TWO_PI * cutoffHz * dt);
        smoothedWheelDeg += (targetDeg - smoothedWheelDeg) * smoothAlpha;

        float deadDeg = 1.0f;
        float absDeg = Math.abs(smoothedWheelDeg);
        float effectiveDeg = absDeg <= deadDeg
                ? 0f
                : Math.signum(smoothedWheelDeg) * Math.min(
                        wheelMaxDeg,
                        (absDeg - deadDeg) * (wheelMaxDeg / Math.max(1f, wheelMaxDeg - deadDeg))
                );

        gyroSteer = finite(effectiveDeg / wheelMaxDeg);
        if (Math.abs(gyroSteer - lastNotifiedGyroSteer) >= 0.0015f || (gyroSteer == 0f && lastNotifiedGyroSteer != 0f)) {
            lastNotifiedGyroSteer = gyroSteer;
            synchronized (sendLock) {
                sendPending = true;
                sendLock.notifyAll();
            }
        }
        if (controllerPad != null) {
            controllerPad.setDisplayedGyroSteer(gyroSteer);
        }
    }

    private static float wrapAnglePi(float rad) {
        while (rad > Math.PI) rad -= TWO_PI;
        while (rad <= -Math.PI) rad += TWO_PI;
        return rad;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
