package com.pocketcontroller;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class MainActivity extends Activity implements SensorEventListener {
    private static final int BG = Color.rgb(12, 10, 16);
    private static final int PANEL = Color.rgb(27, 22, 36);
    private static final int PURPLE = Color.rgb(154, 115, 255);
    private static final int MUTED = Color.rgb(185, 176, 200);
    private static final int PORT = 8766;

    private EditText addressField;
    private EditText pinField;
    private Button connectButton;
    private TextView statusText;
    private Switch gyroSwitch;
    private ControllerPad controllerPad;
    private SensorManager sensorManager;
    private Sensor rotationSensor;

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

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildUi();
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rotationSensor == null) {
            statusText.setText("No orientation sensor · touch steering is available");
            gyroSwitch.setChecked(false);
            gyroSwitch.setEnabled(false);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(10));
        root.setBackgroundColor(BG);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Pocket Controller");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, 1);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        statusText = new TextView(this);
        statusText.setText("Enter your PC address and pairing PIN");
        statusText.setTextColor(MUTED);
        statusText.setTextSize(12);
        statusText.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        header.addView(statusText, new LinearLayout.LayoutParams(0, -2, 1.5f));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(38)));

        LinearLayout connectRow = new LinearLayout(this);
        connectRow.setGravity(Gravity.CENTER_VERTICAL);
        connectRow.setPadding(dp(8), dp(5), dp(8), dp(5));
        connectRow.setBackgroundColor(PANEL);
        addressField = editField("PC address", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        SharedPreferences prefs = getSharedPreferences("controller", MODE_PRIVATE);
        addressField.setText(prefs.getString("address", ""));
        connectRow.addView(addressField, new LinearLayout.LayoutParams(0, dp(42), 1.5f));
        pinField = editField("PIN", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinField.setMaxEms(6);
        connectRow.addView(pinField, new LinearLayout.LayoutParams(0, dp(42), 0.7f));
        connectButton = new Button(this);
        connectButton.setText("Connect");
        connectButton.setOnClickListener(view -> {
            if (connected || connecting) disconnect();
            else connect();
        });
        connectRow.addView(connectButton, new LinearLayout.LayoutParams(dp(112), dp(42)));
        gyroSwitch = new Switch(this);
        gyroSwitch.setText("Gyro steer");
        gyroSwitch.setTextColor(Color.WHITE);
        gyroSwitch.setTextSize(13);
        gyroSwitch.setChecked(true);
        gyroSwitch.setPadding(dp(8), 0, dp(4), 0);
        gyroSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (checked) calibrate();
            if (controllerPad != null) controllerPad.invalidate();
        });
        connectRow.addView(gyroSwitch, new LinearLayout.LayoutParams(-2, dp(42)));
        Button centerButton = new Button(this);
        centerButton.setText("Center");
        centerButton.setOnClickListener(view -> calibrate());
        connectRow.addView(centerButton, new LinearLayout.LayoutParams(dp(90), dp(42)));
        root.addView(connectRow, new LinearLayout.LayoutParams(-1, dp(54)));

        controllerPad = new ControllerPad(this);
        controllerPad.setListener((steer, steeringTouch, newThrottle, newBrake, newButtons) -> {
            touchSteer = steer;
            touchingWheel = steeringTouch;
            throttle = newThrottle;
            brake = newBrake;
            buttons = newButtons;
        });
        root.addView(controllerPad, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private EditText editField(String hint, int inputType) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint(hint);
        field.setHintTextColor(MUTED);
        field.setTextColor(Color.WHITE);
        field.setTextSize(14);
        field.setInputType(inputType);
        field.setPadding(dp(10), 0, dp(10), 0);
        return field;
    }

    private void connect() {
        String host = addressField.getText().toString().trim();
        String pin = pinField.getText().toString().trim();
        if (host.isEmpty() || pin.length() != 6) {
            statusText.setText("Enter the PC address and its six-digit PIN");
            return;
        }
        getSharedPreferences("controller", MODE_PRIVATE).edit().putString("address", host).apply();
        connecting = true;
        connectButton.setText("Cancel");
        statusText.setText("Connecting to " + host + "…");
        long token = ++connectionToken;
        Thread worker = new Thread(() -> runConnection(host, pin, token), "controller-connection");
        worker.start();
    }

    private void runConnection(String host, String pin, long token) {
        Socket candidate = new Socket();
        try {
            candidate.connect(new InetSocketAddress(host, PORT), 5000);
            if (token != connectionToken) throw new InterruptedException("Connection cancelled");
            socket = candidate;
            BufferedReader reader = new BufferedReader(new InputStreamReader(candidate.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter output = new BufferedWriter(new OutputStreamWriter(candidate.getOutputStream(), StandardCharsets.UTF_8));
            writer = output;
            JSONObject hello = new JSONObject();
            hello.put("type", "hello");
            hello.put("pin", pin);
            hello.put("name", android.os.Build.MODEL == null ? "Android phone" : android.os.Build.MODEL);
            output.write(hello.toString());
            output.newLine();
            output.flush();
            String response = reader.readLine();
            if (response == null || !response.contains("\"type\":\"ready\"")) {
                String reason = response == null ? "PC closed the connection" : new JSONObject(response).optString("message", "Pairing was rejected");
                throw new IllegalStateException(reason);
            }
            if (token != connectionToken) throw new InterruptedException("Connection cancelled");
            connected = true;
            connecting = false;
            runOnUiThread(() -> {
                connectButton.setText("Disconnect");
                statusText.setText("Connected · keep this screen open while playing");
            });
            calibrate();
            while (connected && token == connectionToken && !candidate.isClosed()) {
                JSONObject packet = inputPacket();
                output.write(packet.toString());
                output.newLine();
                output.flush();
                if (outputError(output)) throw new IllegalStateException("PC stopped receiving input");
                Thread.sleep(25);
            }
        } catch (Exception error) {
            boolean cancelled = token != connectionToken;
            connected = false;
            connecting = false;
            closeSocket(candidate);
            String message = error.getMessage() == null ? "Connection ended" : error.getMessage();
            if (!cancelled) runOnUiThread(() -> {
                connectButton.setText("Connect");
                statusText.setText(message);
            });
            return;
        } finally {
            if (socket == candidate) socket = null;
            if (writer != null && (candidate.isClosed() || token != connectionToken)) writer = null;
        }
        connected = false;
        connecting = false;
        closeSocket(candidate);
        runOnUiThread(() -> {
            connectButton.setText("Connect");
            statusText.setText("Disconnected");
        });
    }

    private boolean outputError(BufferedWriter output) {
        return writer != output || socket == null || socket.isClosed();
    }

    private JSONObject inputPacket() throws Exception {
        float steer = touchingWheel || !gyroSwitch.isChecked() ? touchSteer : gyroSteer;
        JSONObject packet = new JSONObject();
        packet.put("type", "input");
        packet.put("steer", finite(steer));
        packet.put("throttle", finite(throttle));
        packet.put("brake", finite(brake));
        JSONArray pressed = new JSONArray();
        boolean[] current = buttons;
        for (int i = 0; i < 8; i++) pressed.put(i < current.length && current[i]);
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
        connectButton.setText("Connect");
        statusText.setText("Disconnected");
    }

    private void closeSocket(Socket value) {
        if (value != null) {
            try { value.close(); } catch (Exception ignored) { }
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

    @Override
    protected void onResume() {
        super.onResume();
        if (sensorManager != null && rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) sensorManager.unregisterListener(this);
        disconnect();
    }

    @Override
    protected void onDestroy() {
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
        if (controllerPad != null) controllerPad.setDisplayedInput(gyroSwitch.isChecked() && !touchingWheel ? gyroSteer : touchSteer, throttle, brake, buttons);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }
}
