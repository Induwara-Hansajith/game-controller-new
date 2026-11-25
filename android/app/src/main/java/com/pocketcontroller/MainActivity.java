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
import android.os.Bundle;
import android.net.wifi.WifiManager;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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

    private volatile Socket socket;
    private volatile BufferedWriter writer;
    private volatile boolean connected;
    private volatile boolean connecting;
    private volatile long connectionToken;

    private volatile float currentRoll;
    private volatile float neutralRoll;
    private volatile boolean hasNeutral;
    private volatile float gyroSteer;
    private volatile float touchSteer;
    private volatile boolean touchingWheel;
    private volatile float throttle;
    private volatile float brake;
    private volatile boolean[] buttons = new boolean[8];

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
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }
        if (rotationSensor == null) {
            gyroEnabled = false;
            controllerPad.setGyroEnabled(false);
        }

        SharedPreferences prefs = getSharedPreferences("controller", MODE_PRIVATE);
        discoveredHost = prefs.getString("last_host", "");
        discoveredPin = prefs.getString("last_pin", "");

        // Auto-open setup if no previous connection saved
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
    public void onInputChanged(float steer, boolean isTouchingWheel, float newThrottle, float newBrake, boolean[] newButtons) {
        this.touchSteer = steer;
        this.touchingWheel = isTouchingWheel;
        this.throttle = newThrottle;
        this.brake = newBrake;
        if (newButtons != null) {
            System.arraycopy(newButtons, 0, this.buttons, 0, Math.min(newButtons.length, 8));
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
        gyroEnabled = !gyroEnabled;
        controllerPad.setGyroEnabled(gyroEnabled);
        if (gyroEnabled) calibrate();
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

        // Header with Close
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

        dialogConnectBtn = createAccentButton(connected ? "Disconnect" : (connecting ? "Cancel" : "Connect to PC"));
        dialogConnectBtn.setOnClickListener(v -> {
            if (connected || connecting) {
                disconnect();
            } else {
                connect();
            }
        });
        manualCard.addView(dialogConnectBtn, new LinearLayout.LayoutParams(-1, dp(44)));

        dialogStatusText = new TextView(this);
        dialogStatusText.setText(connected ? "Connected to " + discoveredHost : "Enter your PC address and pairing PIN");
        dialogStatusText.setTextColor(connected ? 0xff10b981 : 0xff94a3b8);
        dialogStatusText.setTextSize(12);
        dialogStatusText.setGravity(Gravity.CENTER);
        dialogStatusText.setPadding(0, dp(6), 0, 0);
        manualCard.addView(dialogStatusText);
        layout.addView(manualCard);

        // 3. Settings Card
        layout.addView(createSectionTitle("CONTROLS & CALIBRATION"));
        LinearLayout settingsCard = createCardLayout();

        Switch gyroToggle = new Switch(this);
        gyroToggle.setText("Gyroscope Tilt Steering");
        gyroToggle.setTextColor(Color.WHITE);
        gyroToggle.setChecked(gyroEnabled);
        gyroToggle.setOnCheckedChangeListener((btn, checked) -> {
            gyroEnabled = checked;
            controllerPad.setGyroEnabled(checked);
            if (checked) calibrate();
        });
        settingsCard.addView(gyroToggle, new LinearLayout.LayoutParams(-1, dp(40)));

        Button centerBtn = createOutlineButton("⌖ Calibrate Neutral Center");
        centerBtn.setOnClickListener(v -> {
            calibrate();
            dialogStatusText.setText("Neutral center calibrated!");
            dialogStatusText.setTextColor(0xff34d399);
        });
        settingsCard.addView(centerBtn, new LinearLayout.LayoutParams(-1, dp(40)));

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

        connecting = true;
        if (dialogConnectBtn != null) dialogConnectBtn.setText("Cancel");
        if (dialogStatusText != null) {
            dialogStatusText.setText("Connecting to " + host + "…");
            dialogStatusText.setTextColor(0xff38bdf8);
        }

        long token = ++connectionToken;
        Thread worker = new Thread(() -> runConnection(host, pin, token), "controller-connection");
        worker.start();
    }

    private void runConnection(String host, String pin, long token) {
        Socket candidate = new Socket();
        try {
            candidate.connect(new InetSocketAddress(host, PORT), 4500);
            if (token != connectionToken) throw new InterruptedException("Cancelled");

            socket = candidate;
            BufferedReader reader = new BufferedReader(new InputStreamReader(candidate.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter output = new BufferedWriter(new OutputStreamWriter(candidate.getOutputStream(), StandardCharsets.UTF_8));
            writer = output;

            JSONObject hello = new JSONObject();
            hello.put("type", "hello");
            hello.put("pin", pin);
            hello.put("name", android.os.Build.MODEL == null ? "Android Phone" : android.os.Build.MODEL);
            output.write(hello.toString());
            output.newLine();
            output.flush();

            String response = reader.readLine();
            if (response == null || !response.contains("\"type\":\"ready\"")) {
                String reason = response == null ? "PC closed connection" : new JSONObject(response).optString("message", "Pairing rejected");
                throw new IllegalStateException(reason);
            }

            if (token != connectionToken) throw new InterruptedException("Cancelled");
            connected = true;
            connecting = false;

            runOnUiThread(() -> {
                if (dialogConnectBtn != null) dialogConnectBtn.setText("Disconnect");
                if (dialogStatusText != null) {
                    dialogStatusText.setText("Connected to " + host);
                    dialogStatusText.setTextColor(0xff10b981);
                }
                controllerPad.setConnectionStatus(true, discoveredName.isEmpty() ? host : discoveredName, "60Hz · 4ms");
                if (setupDialog != null && setupDialog.isShowing()) {
                    setupDialog.dismiss();
                }
            });

            calibrate();

            while (connected && token == connectionToken && !candidate.isClosed()) {
                JSONObject packet = inputPacket();
                output.write(packet.toString());
                output.newLine();
                output.flush();
                if (writer != output || socket == null || socket.isClosed()) {
                    throw new IllegalStateException("Socket disconnected");
                }
                Thread.sleep(20);
            }
        } catch (Exception error) {
            boolean cancelled = token != connectionToken;
            connected = false;
            connecting = false;
            closeSocket(candidate);
            String message = error.getMessage() == null ? "Connection ended" : error.getMessage();
            if (!cancelled) {
                runOnUiThread(() -> {
                    if (dialogConnectBtn != null) dialogConnectBtn.setText("Connect to PC");
                    if (dialogStatusText != null) {
                        dialogStatusText.setText(message);
                        dialogStatusText.setTextColor(0xfff43f5e);
                    }
                    controllerPad.setConnectionStatus(false, "DISCONNECTED", "0Hz");
                });
            }
            return;
        } finally {
            if (socket == candidate) socket = null;
            if (writer != null && (candidate.isClosed() || token != connectionToken)) writer = null;
        }

        connected = false;
        connecting = false;
        closeSocket(candidate);
        runOnUiThread(() -> {
            if (dialogConnectBtn != null) dialogConnectBtn.setText("Connect to PC");
            if (dialogStatusText != null) {
                dialogStatusText.setText("Disconnected");
                dialogStatusText.setTextColor(0xff94a3b8);
            }
            controllerPad.setConnectionStatus(false, "DISCONNECTED", "0Hz");
        });
    }

    private JSONObject inputPacket() throws Exception {
        float steer = touchingWheel || !gyroEnabled ? touchSteer : gyroSteer;
        JSONObject packet = new JSONObject();
        packet.put("type", "input");
        packet.put("steer", finite(steer));
        packet.put("throttle", finite(throttle));
        packet.put("brake", finite(brake));
        JSONArray pressed = new JSONArray();
        boolean[] current = buttons;
        for (int i = 0; i < 8; i++) {
            pressed.put(i < current.length && current[i]);
        }
        packet.put("buttons", pressed);
        return packet;
    }

    private float finite(float value) {
        return Float.isNaN(value) || Float.isInfinite(value) ? 0f : Math.max(-1f, Math.min(1f, value));
    }

    private void disconnect() {
        ++connectionToken;
        connected = false;
        connecting = false;
        Socket old = socket;
        socket = null;
        writer = null;
        closeSocket(old);
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

    private void calibrate() {
        if (hasNeutral) neutralRoll = currentRoll;
        gyroSteer = 0f;
        if (controllerPad != null) controllerPad.invalidate();
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
        } catch (RuntimeException ignored) {
            // UDP discovery still works on devices that do not grant the Wi-Fi lock.
        }
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
                    } catch (Exception ignored) {
                        // Ignore malformed or unrelated datagrams and keep listening.
                    }
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
    protected void onResume() {
        super.onResume();
        applyImmersive();
        if (sensorManager != null && rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        }
        startDiscovery();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopDiscovery();
        if (sensorManager != null) sensorManager.unregisterListener(this);
        disconnect();
    }

    @Override
    protected void onDestroy() {
        stopDiscovery();
        disconnect();
        super.onDestroy();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
        float[] matrix = new float[9];
        float[] orientation = new float[3];
        SensorManager.getRotationMatrixFromVector(matrix, event.values);
        SensorManager.getOrientation(matrix, orientation);
        currentRoll = orientation[2];
        if (!hasNeutral) {
            neutralRoll = currentRoll;
            hasNeutral = true;
        }
        float delta = currentRoll - neutralRoll;
        while (delta > Math.PI) delta -= (float) (2 * Math.PI);
        while (delta < -Math.PI) delta += (float) (2 * Math.PI);
        gyroSteer = finite(delta / (float) Math.toRadians(45));
        if (controllerPad != null) {
            controllerPad.setDisplayedInput(
                    gyroEnabled && !touchingWheel ? gyroSteer : touchSteer,
                    throttle,
                    brake,
                    buttons
            );
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
