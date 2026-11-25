package com.pocketcontroller;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ControllerPad extends View {
    public static final int MODE_RACEPAD = 0;
    public static final int MODE_GAMEPAD = 1;
    public static final int LAYOUT_RACEPAD = 0;
    public static final int LAYOUT_GAMEPAD = 1;
    public static final int LAYOUT_RACEPAD_GYRO = 2;

    private static final float MIN_SCALE = 0.55f;
    private static final float MAX_SCALE = 2.0f;

    private static final int KIND_WHEEL = 1;
    private static final int KIND_PEDAL = 2;
    private static final int KIND_STICK_L = 3;
    private static final int KIND_STICK_R = 4;
    private static final int KIND_DPAD = 5;
    private static final int KIND_TRIGGER = 6;
    private static final int KIND_PILL_BTN = 7;
    private static final int KIND_CIRCLE_BTN = 8;

    public interface Listener {
        void onInputChanged(
                String mode,
                float steer, boolean steeringTouch,
                float throttle, float brake, float clutch, float handbrake,
                float lx, float ly, float rx, float ry,
                float lt, float rt,
                boolean dpadUp, boolean dpadDown, boolean dpadLeft, boolean dpadRight,
                boolean[] buttons
        );
        void onOpenSetup();
        void onToggleMode();
        void onCalibrate();
        void onToggleGyro();
        void onLayoutChanged(String mode, String layoutJson);
    }

    private static final class ControlItem {
        final String id;
        final String label;
        final int kind;
        final int buttonSlot;
        final int accentColor;
        final int altColor;
        final float defCxFrac;
        final float defCyFrac;
        float cxFrac;
        float cyFrac;
        float scale = 1.0f;

        ControlItem(String id, String label, int kind, int buttonSlot, int accentColor, int altColor, float cxFrac, float cyFrac) {
            this.id = id;
            this.label = label;
            this.kind = kind;
            this.buttonSlot = buttonSlot;
            this.accentColor = accentColor;
            this.altColor = altColor;
            this.defCxFrac = cxFrac;
            this.defCyFrac = cyFrac;
            this.cxFrac = cxFrac;
            this.cyFrac = cyFrac;
        }

        void reset() {
            cxFrac = defCxFrac;
            cyFrac = defCyFrac;
            scale = 1.0f;
        }

        boolean isModified() {
            return Math.abs(cxFrac - defCxFrac) > 0.002f
                    || Math.abs(cyFrac - defCyFrac) > 0.002f
                    || Math.abs(scale - 1.0f) > 0.01f;
        }
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rectTmp = new RectF();
    private final RectF arcRectTmp = new RectF();
    private final Path spokePath = new Path();
    private final DashPathEffect editDash = new DashPathEffect(new float[]{10f, 8f}, 0f);
    private final Typeface typefaceBold = Typeface.create("sans-serif-medium", Typeface.BOLD);
    private final Typeface typefaceNormal = Typeface.create("sans-serif", Typeface.NORMAL);

    private Listener listener;
    private Vibrator vibrator;
    private final ExecutorService hapticExecutor = Executors.newSingleThreadExecutor();

    private int currentMode = MODE_RACEPAD;
    private boolean connected = false;
    private boolean reconnecting = false;
    private String pcDisplayName = "PC";
    private String latencyText = "250Hz";
    private boolean gyroEnabled = true;
    private boolean instantTapPedals = false;
    private float wheelMaxDeg = 90f;

    private final List<ControlItem> raceItems = new ArrayList<>();
    private final List<ControlItem> raceGyroItems = new ArrayList<>();
    private final List<ControlItem> gpItems = new ArrayList<>();

    // Edit Mode state
    private boolean editMode = false;
    private boolean snapToGrid = true;
    private ControlItem selectedItem = null;
    private int editDragMode = 0; // 0=none, 1=move, 2=resize_handle, 3=pinch
    private int editPointerId = MotionEvent.INVALID_POINTER_ID;
    private float dragOffsetX = 0f;
    private float dragOffsetY = 0f;
    private float resizeStartDist = 1f;
    private float resizeStartScale = 1f;
    private float pinchStartDist = 1f;
    private float pinchStartScale = 1f;
    private boolean guideCenterX = false;
    private boolean guideCenterY = false;

    // Output & rendered state
    private float steer = 0f;
    private float sensorSteer = 0f;
    private float touchWheelDeg = 0f;
    private float displayWheelDeg = 0f;
    private long lastAnimNs = 0L;
    private int steerPointerId = MotionEvent.INVALID_POINTER_ID;
    private int leftStickPointerId = MotionEvent.INVALID_POINTER_ID;
    private int rightStickPointerId = MotionEvent.INVALID_POINTER_ID;
    private boolean blendingFromTouch = false;
    private float lastTouchAngleDeg = 0f;
    private float lastTouchX = 0f;
    private boolean hitFullLockHaptic = false;
    private boolean steeringTouch = false;
    private boolean leftStickTouched = false;
    private float throttle = 0f;
    private float brake = 0f;
    private float clutch = 0f;
    private float handbrake = 0f;

    private float lx = 0f;
    private float ly = 0f;
    private float rx = 0f;
    private float ry = 0f;
    private float lt = 0f;
    private float rt = 0f;

    private boolean dpadUp = false;
    private boolean dpadDown = false;
    private boolean dpadLeft = false;
    private boolean dpadRight = false;

    private final boolean[] buttons = new boolean[16];
    private final boolean[] displayedButtons = new boolean[16];

    private static final int COLOR_BG = 0xff07050b;
    private static final int COLOR_PANEL = 0xff120e1c;
    private static final int COLOR_PANEL_BORDER = 0xff292038;
    private static final int COLOR_ACCENT = 0xff8b5cf6;
    private static final int COLOR_CYAN = 0xff38bdf8;
    private static final int COLOR_THROTTLE = 0xff10b981;
    private static final int COLOR_BRAKE = 0xfff43f5e;
    private static final int COLOR_CLUTCH = 0xfff59e0b;
    private static final int COLOR_TEXT_MUTED = 0xff94a3b8;

    public ControllerPad(Context context) {
        super(context);
        setFocusable(true);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        try {
            vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        } catch (Exception ignored) {}
        initDefaultLayouts();
    }

    private void initDefaultLayouts() {
        raceItems.clear();
        raceItems.add(new ControlItem("gear_down", "◀ GEAR DOWN", KIND_PILL_BTN, 4, COLOR_ACCENT, 0xffc4b5fd, 0.11f, 0.06f));
        raceItems.add(new ControlItem("cam", "CAM", KIND_PILL_BTN, 6, COLOR_ACCENT, 0xffcbd5e1, 0.255f, 0.06f));
        raceItems.add(new ControlItem("map", "MAP", KIND_PILL_BTN, 7, COLOR_ACCENT, 0xffcbd5e1, 0.345f, 0.06f));
        raceItems.add(new ControlItem("gear_up", "GEAR UP ▶", KIND_PILL_BTN, 5, COLOR_ACCENT, 0xffc4b5fd, 0.88f, 0.06f));

        raceItems.add(new ControlItem("wheel", "STEERING WHEEL", KIND_WHEEL, -1, COLOR_ACCENT, 0, 0.22f, 0.49f));

        raceItems.add(new ControlItem("lights", "LIGHTS", KIND_PILL_BTN, 10, COLOR_ACCENT, 0xff94a3b8, 0.065f, 0.945f));
        raceItems.add(new ControlItem("horn", "HORN", KIND_PILL_BTN, 9, COLOR_ACCENT, 0xff94a3b8, 0.17f, 0.945f));
        raceItems.add(new ControlItem("wipers", "WIPERS", KIND_PILL_BTN, 11, COLOR_ACCENT, 0xff94a3b8, 0.275f, 0.945f));
        raceItems.add(new ControlItem("ign", "IGN", KIND_PILL_BTN, 12, COLOR_ACCENT, 0xff94a3b8, 0.38f, 0.945f));

        raceItems.add(new ControlItem("clutch", "CLUTCH", KIND_PEDAL, -1, COLOR_CLUTCH, 0xffb45309, 0.505f, 0.485f));
        raceItems.add(new ControlItem("brake", "BRAKE", KIND_PEDAL, -1, COLOR_BRAKE, 0xffbe123c, 0.602f, 0.485f));
        raceItems.add(new ControlItem("gas", "GAS", KIND_PEDAL, -1, COLOR_THROTTLE, 0xff047857, 0.70f, 0.485f));

        raceItems.add(new ControlItem("handbrake", "HANDBRAKE", KIND_PILL_BTN, 15, 0xffe11d48, 0xfffda4af, 0.485f, 0.945f));
        raceItems.add(new ControlItem("nitro", "NITRO", KIND_PILL_BTN, 13, 0xffe11d48, 0xfffda4af, 0.57f, 0.945f));
        raceItems.add(new ControlItem("cruise", "CRUISE", KIND_PILL_BTN, 14, 0xffe11d48, 0xfffda4af, 0.65f, 0.945f));
        raceItems.add(new ControlItem("pit", "PIT", KIND_PILL_BTN, 8, 0xffe11d48, 0xfffda4af, 0.73f, 0.945f));

        raceItems.add(new ControlItem("btn_y", "Y", KIND_CIRCLE_BTN, 3, 0xfff59e0b, 0, 0.88f, 0.295f));
        raceItems.add(new ControlItem("btn_x", "X", KIND_CIRCLE_BTN, 2, 0xff06b6d4, 0, 0.822f, 0.445f));
        raceItems.add(new ControlItem("btn_b", "B", KIND_CIRCLE_BTN, 1, 0xfff43f5e, 0, 0.938f, 0.445f));
        raceItems.add(new ControlItem("btn_a", "A", KIND_CIRCLE_BTN, 0, 0xff10b981, 0, 0.88f, 0.595f));
        raceItems.add(new ControlItem("dpad", "D-PAD", KIND_DPAD, -1, COLOR_ACCENT, 0, 0.88f, 0.885f));

        raceGyroItems.clear();
        raceGyroItems.add(new ControlItem("gear_down", "◀ GEAR DOWN", KIND_PILL_BTN, 4, COLOR_ACCENT, 0xffc4b5fd, 0.11f, 0.06f));
        raceGyroItems.add(new ControlItem("cam", "CAM", KIND_PILL_BTN, 6, COLOR_ACCENT, 0xffcbd5e1, 0.255f, 0.06f));
        raceGyroItems.add(new ControlItem("map", "MAP", KIND_PILL_BTN, 7, COLOR_ACCENT, 0xffcbd5e1, 0.345f, 0.06f));
        raceGyroItems.add(new ControlItem("gear_up", "GEAR UP ▶", KIND_PILL_BTN, 5, COLOR_ACCENT, 0xffc4b5fd, 0.88f, 0.06f));

        raceGyroItems.add(new ControlItem("lights", "LIGHTS", KIND_PILL_BTN, 10, COLOR_ACCENT, 0xff94a3b8, 0.065f, 0.945f));
        raceGyroItems.add(new ControlItem("horn", "HORN", KIND_PILL_BTN, 9, COLOR_ACCENT, 0xff94a3b8, 0.17f, 0.945f));
        raceGyroItems.add(new ControlItem("wipers", "WIPERS", KIND_PILL_BTN, 11, COLOR_ACCENT, 0xff94a3b8, 0.275f, 0.945f));
        raceGyroItems.add(new ControlItem("ign", "IGN", KIND_PILL_BTN, 12, COLOR_ACCENT, 0xff94a3b8, 0.38f, 0.945f));

        raceGyroItems.add(new ControlItem("brake", "BRAKE", KIND_PEDAL, -1, COLOR_BRAKE, 0xffbe123c, 0.155f, 0.485f));
        raceGyroItems.add(new ControlItem("clutch", "CLUTCH", KIND_PEDAL, -1, COLOR_CLUTCH, 0xffb45309, 0.415f, 0.485f));
        raceGyroItems.add(new ControlItem("gas", "GAS", KIND_PEDAL, -1, COLOR_THROTTLE, 0xff047857, 0.650f, 0.485f));

        raceGyroItems.add(new ControlItem("handbrake", "HANDBRAKE", KIND_PILL_BTN, 15, 0xffe11d48, 0xfffda4af, 0.485f, 0.945f));
        raceGyroItems.add(new ControlItem("nitro", "NITRO", KIND_PILL_BTN, 13, 0xffe11d48, 0xfffda4af, 0.57f, 0.945f));
        raceGyroItems.add(new ControlItem("cruise", "CRUISE", KIND_PILL_BTN, 14, 0xffe11d48, 0xfffda4af, 0.65f, 0.945f));
        raceGyroItems.add(new ControlItem("pit", "PIT", KIND_PILL_BTN, 8, 0xffe11d48, 0xfffda4af, 0.73f, 0.945f));

        raceGyroItems.add(new ControlItem("btn_y", "Y", KIND_CIRCLE_BTN, 3, 0xfff59e0b, 0, 0.88f, 0.295f));
        raceGyroItems.add(new ControlItem("btn_x", "X", KIND_CIRCLE_BTN, 2, 0xff06b6d4, 0, 0.822f, 0.445f));
        raceGyroItems.add(new ControlItem("btn_b", "B", KIND_CIRCLE_BTN, 1, 0xfff43f5e, 0, 0.938f, 0.445f));
        raceGyroItems.add(new ControlItem("btn_a", "A", KIND_CIRCLE_BTN, 0, 0xff10b981, 0, 0.88f, 0.595f));
        raceGyroItems.add(new ControlItem("dpad", "D-PAD", KIND_DPAD, -1, COLOR_ACCENT, 0, 0.88f, 0.885f));

        gpItems.clear();
        gpItems.add(new ControlItem("lb", "LB (BUMPER)", KIND_PILL_BTN, 4, COLOR_ACCENT, 0xffc4b5fd, 0.105f, 0.06f));
        gpItems.add(new ControlItem("lt", "LT", KIND_TRIGGER, -1, COLOR_BRAKE, 0, 0.265f, 0.06f));
        gpItems.add(new ControlItem("rt", "RT", KIND_TRIGGER, -1, COLOR_THROTTLE, 0, 0.735f, 0.06f));
        gpItems.add(new ControlItem("rb", "RB (BUMPER)", KIND_PILL_BTN, 5, COLOR_ACCENT, 0xffc4b5fd, 0.895f, 0.06f));

        gpItems.add(new ControlItem("lstick", "LEFT STICK", KIND_STICK_L, -1, COLOR_ACCENT, 0, 0.18f, 0.40f));
        gpItems.add(new ControlItem("dpad", "D-PAD", KIND_DPAD, -1, COLOR_ACCENT, 0, 0.18f, 0.81f));
        gpItems.add(new ControlItem("l3", "L3", KIND_PILL_BTN, 9, COLOR_ACCENT, 0xffcbd5e1, 0.325f, 0.79f));

        gpItems.add(new ControlItem("view", "VIEW", KIND_PILL_BTN, 6, COLOR_ACCENT, 0xffcbd5e1, 0.405f, 0.24f));
        gpItems.add(new ControlItem("guide", "⌘", KIND_CIRCLE_BTN, 8, 0xff10b981, 0, 0.50f, 0.24f));
        gpItems.add(new ControlItem("menu", "MENU", KIND_PILL_BTN, 7, COLOR_ACCENT, 0xffcbd5e1, 0.595f, 0.24f));

        gpItems.add(new ControlItem("r3", "R3", KIND_PILL_BTN, 10, COLOR_ACCENT, 0xffcbd5e1, 0.475f, 0.79f));
        gpItems.add(new ControlItem("rstick", "RIGHT STICK", KIND_STICK_R, -1, COLOR_ACCENT, 0, 0.615f, 0.69f));

        gpItems.add(new ControlItem("btn_y", "Y", KIND_CIRCLE_BTN, 3, 0xfff59e0b, 0, 0.86f, 0.33f));
        gpItems.add(new ControlItem("btn_x", "X", KIND_CIRCLE_BTN, 2, 0xff06b6d4, 0, 0.792f, 0.495f));
        gpItems.add(new ControlItem("btn_b", "B", KIND_CIRCLE_BTN, 1, 0xfff43f5e, 0, 0.928f, 0.495f));
        gpItems.add(new ControlItem("btn_a", "A", KIND_CIRCLE_BTN, 0, 0xff10b981, 0, 0.86f, 0.66f));
    }

    private int activeLayoutId() {
        if (currentMode == MODE_GAMEPAD) return LAYOUT_GAMEPAD;
        return gyroEnabled ? LAYOUT_RACEPAD_GYRO : LAYOUT_RACEPAD;
    }

    private List<ControlItem> itemsForMode(int layoutId) {
        if (layoutId == LAYOUT_GAMEPAD) return gpItems;
        if (layoutId == LAYOUT_RACEPAD_GYRO) return raceGyroItems;
        return raceItems;
    }

    private List<ControlItem> activeItems() {
        return itemsForMode(activeLayoutId());
    }

    public void setListener(Listener value) {
        this.listener = value;
    }

    public void setConnectionStatus(boolean isConnected, String pcName, String latency) {
        this.connected = isConnected;
        this.reconnecting = false;
        this.pcDisplayName = pcName == null || pcName.isEmpty() ? "PC" : pcName;
        this.latencyText = latency == null || latency.isEmpty() ? "125Hz" : latency;
        postInvalidateOnAnimation();
    }

    public void setReconnectingStatus(String pcName) {
        this.connected = false;
        this.reconnecting = true;
        this.pcDisplayName = pcName == null || pcName.isEmpty() ? "PC" : pcName;
        this.latencyText = "RETRY";
        postInvalidateOnAnimation();
    }

    public void setMode(int mode) {
        this.currentMode = mode;
        selectedItem = null;
        clearInput();
        postInvalidateOnAnimation();
    }

    public int getMode() {
        return currentMode;
    }

    public void setEditMode(boolean enabled) {
        if (this.editMode == enabled) return;
        this.editMode = enabled;
        this.selectedItem = null;
        this.editDragMode = 0;
        this.editPointerId = MotionEvent.INVALID_POINTER_ID;
        this.guideCenterX = false;
        this.guideCenterY = false;
        clearInput();
        postInvalidateOnAnimation();
    }

    public boolean isEditMode() {
        return editMode;
    }

    public String exportLayoutJson(int mode) {
        try {
            JSONObject root = new JSONObject();
            for (ControlItem item : itemsForMode(mode)) {
                JSONObject obj = new JSONObject();
                obj.put("x", Math.round(item.cxFrac * 10000f) / 10000f);
                obj.put("y", Math.round(item.cyFrac * 10000f) / 10000f);
                obj.put("s", Math.round(item.scale * 1000f) / 1000f);
                root.put(item.id, obj);
            }
            return root.toString();
        } catch (Exception ignored) {
            return "{}";
        }
    }

    public void importLayoutJson(int mode, String json) {
        if (json == null || json.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(json);
            List<ControlItem> items = itemsForMode(mode);
            Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String id = keys.next();
                JSONObject obj = root.optJSONObject(id);
                if (obj == null) continue;
                for (ControlItem item : items) {
                    if (item.id.equals(id)) {
                        float x = (float) obj.optDouble("x", item.defCxFrac);
                        float y = (float) obj.optDouble("y", item.defCyFrac);
                        float s = (float) obj.optDouble("s", 1.0);
                        if (!Float.isNaN(x) && !Float.isInfinite(x)) item.cxFrac = clamp(x, 0.02f, 0.98f);
                        if (!Float.isNaN(y) && !Float.isInfinite(y)) item.cyFrac = clamp(y, 0.02f, 0.98f);
                        if (!Float.isNaN(s) && !Float.isInfinite(s)) item.scale = clamp(s, MIN_SCALE, MAX_SCALE);
                        break;
                    }
                }
            }
            postInvalidateOnAnimation();
        } catch (Exception ignored) {}
    }

    public void resetLayout(int layoutId) {
        for (ControlItem item : itemsForMode(layoutId)) {
            item.reset();
        }
        if (layoutId == activeLayoutId()) {
            guideCenterX = false;
            guideCenterY = false;
        }
        notifyLayoutChanged(layoutId);
        postInvalidateOnAnimation();
    }

    public void resetAllLayouts() {
        resetLayout(LAYOUT_RACEPAD);
        resetLayout(LAYOUT_RACEPAD_GYRO);
        resetLayout(LAYOUT_GAMEPAD);
    }

    private void notifyLayoutChanged(int layoutId) {
        if (listener != null) {
            String modeName = layoutId == LAYOUT_GAMEPAD
                    ? "gamepad"
                    : (layoutId == LAYOUT_RACEPAD_GYRO ? "racepad_gyro" : "racepad");
            listener.onLayoutChanged(modeName, exportLayoutJson(layoutId));
        }
    }

    public void setInstantTapPedals(boolean instant) {
        this.instantTapPedals = instant;
    }

    public void setGyroEnabled(boolean enabled) {
        if (this.gyroEnabled == enabled) return;
        this.gyroEnabled = enabled;
        selectedItem = null;
        editDragMode = 0;
        editPointerId = MotionEvent.INVALID_POINTER_ID;
        if (enabled) {
            steeringTouch = false;
            blendingFromTouch = false;
            steerPointerId = MotionEvent.INVALID_POINTER_ID;
        }
        postInvalidateOnAnimation();
    }

    public void setWheelMaxDeg(float maxDeg) {
        this.wheelMaxDeg = clamp(maxDeg, 30f, 360f);
        this.touchWheelDeg = clamp(this.touchWheelDeg, -this.wheelMaxDeg, this.wheelMaxDeg);
        this.displayWheelDeg = clamp(this.displayWheelDeg, -this.wheelMaxDeg, this.wheelMaxDeg);
        postInvalidateOnAnimation();
    }

    public boolean isLeftStickTouched() {
        return leftStickTouched;
    }

    public boolean isSteeringTouchOrBlend() {
        return steeringTouch || blendingFromTouch;
    }

    public void resetSteering() {
        sensorSteer = 0f;
        touchWheelDeg = 0f;
        displayWheelDeg = 0f;
        steer = 0f;
        steeringTouch = false;
        blendingFromTouch = false;
        steerPointerId = MotionEvent.INVALID_POINTER_ID;
        lastAnimNs = 0L;
        if (currentMode == MODE_GAMEPAD && !leftStickTouched) {
            lx = 0f;
        }
        postInvalidateOnAnimation();
    }

    public void setDisplayedGyroSteer(float gyroVal) {
        this.sensorSteer = clamp(gyroVal, -1f, 1f);
        if (!steeringTouch && gyroEnabled && !editMode) {
            if (!blendingFromTouch) {
                displayWheelDeg = this.sensorSteer * wheelMaxDeg;
                this.steer = this.sensorSteer;
                if (currentMode == MODE_GAMEPAD && !leftStickTouched) {
                    this.lx = this.steer;
                }
            }
            postInvalidateOnAnimation();
        }
    }

    private float playTop() {
        return dp(46);
    }

    private float playHeight(float h) {
        return Math.max(dp(120), (h - dp(4)) - playTop());
    }

    private float baseHalfW(ControlItem item, float w, float h) {
        float ph = playHeight(h);
        switch (item.kind) {
            case KIND_WHEEL:
                return Math.min(w * 0.18f, ph * 0.38f);
            case KIND_STICK_L:
                return Math.min(w * 0.092f, ph * 0.195f);
            case KIND_STICK_R:
                return Math.min(w * 0.092f, ph * 0.195f) * 0.90f;
            case KIND_DPAD:
                return currentMode == MODE_RACEPAD
                        ? Math.min(dp(17.5f), ph * 0.068f)
                        : Math.min(dp(22.5f), ph * 0.082f);
            case KIND_PEDAL:
                if (currentMode == MODE_RACEPAD && gyroEnabled && ("brake".equals(item.id) || "gas".equals(item.id))) {
                    return w * 0.098f;
                }
                return w * 0.041f;
            case KIND_TRIGGER:
                return w * 0.065f;
            case KIND_CIRCLE_BTN:
                if ("guide".equals(item.id)) return dp(17);
                return Math.min(w * 0.045f, ph * 0.095f);
            case KIND_PILL_BTN:
            default:
                if ("gear_down".equals(item.id) || "gear_up".equals(item.id)) return w * 0.095f;
                if ("lb".equals(item.id) || "rb".equals(item.id)) return w * 0.085f;
                if ("cam".equals(item.id) || "map".equals(item.id)) return clamp(w * 0.036f, dp(20), dp(28));
                if ("view".equals(item.id) || "menu".equals(item.id)) return dp(27);
                if ("l3".equals(item.id) || "r3".equals(item.id)) return dp(24);
                return clamp(w * 0.046f, dp(25), dp(38));
        }
    }

    private float baseHalfH(ControlItem item, float w, float h) {
        float ph = playHeight(h);
        switch (item.kind) {
            case KIND_WHEEL:
            case KIND_STICK_L:
            case KIND_STICK_R:
            case KIND_DPAD:
            case KIND_CIRCLE_BTN:
                return baseHalfW(item, w, h);
            case KIND_PEDAL:
                return ph * 0.34f;
            case KIND_TRIGGER:
                return dp(16);
            case KIND_PILL_BTN:
            default:
                if ("gear_down".equals(item.id) || "gear_up".equals(item.id)) return dp(17);
                if ("lb".equals(item.id) || "rb".equals(item.id)) return dp(16);
                if ("cam".equals(item.id) || "map".equals(item.id) || "view".equals(item.id) || "menu".equals(item.id)
                        || "l3".equals(item.id) || "r3".equals(item.id)) {
                    return dp(15);
                }
                return dp(13.5f);
        }
    }

    private float itemHalfW(ControlItem item, float w, float h) {
        return baseHalfW(item, w, h) * item.scale;
    }

    private float itemHalfH(ControlItem item, float w, float h) {
        return baseHalfH(item, w, h) * item.scale;
    }

    private float itemCx(ControlItem item, float w, float h) {
        float hw = itemHalfW(item, w, h);
        float margin = dp(4);
        float minX = Math.min(w * 0.5f, hw + margin);
        float maxX = Math.max(minX, w - hw - margin);
        return clamp(item.cxFrac * w, minX, maxX);
    }

    private float itemCy(ControlItem item, float w, float h) {
        float topY = playTop();
        float ph = playHeight(h);
        float hh = itemHalfH(item, w, h);
        float margin = dp(4);
        float minY = Math.min(topY + ph * 0.5f, topY + hh + margin);
        float maxY = Math.max(minY, h - hh - margin);
        return clamp(topY + item.cyFrac * ph, minY, maxY);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        if (w <= 0f || h <= 0f) return;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COLOR_BG);
        canvas.drawRect(0, 0, w, h, paint);

        if (editMode) {
            drawEditBackground(canvas, w, h);
            drawEditHud(canvas, w);
        } else {
            drawTopHud(canvas, w);
        }

        if (!editMode && currentMode == MODE_RACEPAD) {
            updateSteeringAnimation();
        }

        for (ControlItem item : activeItems()) {
            drawControlItem(canvas, item, w, h);
        }

        if (editMode) {
            drawEditOverlays(canvas, w, h);
        }
    }

    private void drawEditBackground(Canvas canvas, float w, float h) {
        float topY = playTop();
        if (snapToGrid) {
            float step = dp(18);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xff1d1630);
            for (float gx = dp(12); gx < w - dp(8); gx += step) {
                for (float gy = topY + dp(8); gy < h - dp(8); gy += step) {
                    canvas.drawCircle(gx, gy, dp(1.1f), paint);
                }
            }
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(guideCenterX ? 0xffa855f7 : 0x333c2e5c);
        canvas.drawLine(w / 2f, topY, w / 2f, h, paint);

        float midY = topY + playHeight(h) / 2f;
        paint.setColor(guideCenterY ? 0xffa855f7 : 0x333c2e5c);
        canvas.drawLine(0f, midY, w, midY, paint);
    }

    private void drawTopHud(Canvas canvas, float w) {
        float barH = dp(36);
        float top = dp(5);
        float padX = dp(10);
        float gap = dp(6);

        float badgeW = clamp(w * 0.22f, dp(124), dp(160));
        float setupW = clamp(w * 0.105f, dp(60), dp(74));
        float centerW = clamp(w * 0.112f, dp(64), dp(80));
        float editW = clamp(w * 0.098f, dp(56), dp(70));

        float setupL = w - padX - setupW;
        float centerL = setupL - gap - centerW;
        float editL = centerL - gap - editW;
        float badgeR = padX + badgeW;

        // Left Pill: Connection Badge
        rectTmp.set(padX, top, badgeR, top + barH);
        drawPill(canvas, rectTmp, COLOR_PANEL, reconnecting ? 0xfff59e0b : COLOR_PANEL_BORDER);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(connected ? 0xff10b981 : (reconnecting ? 0xfff59e0b : 0xfff43f5e));
        canvas.drawCircle(padX + dp(11), top + barH / 2f, dp(4), paint);
        String connStr = connected
                ? (pcDisplayName + " · " + latencyText)
                : (reconnecting ? "RECONNECTING…" : "TAP TO CONNECT");
        int connColor = connected ? 0xff34d399 : (reconnecting ? 0xfffbbf24 : 0xffcbd5e1);
        drawText(canvas, connStr, padX + dp(20), top + barH / 2f + dp(3.5f), 10f, connColor, Paint.Align.LEFT, true);

        // Center Pill: Mode Switcher (with live gyro angle gauge when virtual wheel is hidden)
        float availCenter = (editL - badgeR) - gap * 2f;
        float modeW = Math.min(clamp(w * 0.24f, dp(122), dp(170)), Math.max(dp(98), availCenter));
        float modeL = badgeR + (editL - badgeR - modeW) / 2f;
        rectTmp.set(modeL, top, modeL + modeW, top + barH);
        drawPill(canvas, rectTmp, 0xff1e182a, COLOR_ACCENT);
        if (currentMode == MODE_RACEPAD && gyroEnabled) {
            int deg = Math.round(displayWheelDeg);
            String degText = (deg > 0 ? "+" : "") + deg + "°";
            drawText(canvas, "🏎 RACE · " + degText, rectTmp.centerX(), top + barH / 2f + dp(1.5f), 10.2f, 0xffe9d5ff, Paint.Align.CENTER, true);
            float trackL = modeL + dp(16);
            float trackR = modeL + modeW - dp(16);
            float trackY = top + barH - dp(6);
            float midX = (trackL + trackR) * 0.5f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2.6f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(0xff2c223f);
            canvas.drawLine(trackL, trackY, trackR, trackY, paint);
            float steerVal = clamp(displayWheelDeg / Math.max(1f, wheelMaxDeg), -1f, 1f);
            float tipX = midX + steerVal * (trackR - midX);
            if (Math.abs(steerVal) > 0.02f) {
                paint.setColor(Math.abs(steerVal) > 0.72f ? COLOR_CYAN : COLOR_ACCENT);
                canvas.drawLine(midX, trackY, tipX, trackY, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xfff59e0b);
            canvas.drawCircle(midX, trackY, dp(1.8f), paint);
        } else {
            String modeName = currentMode == MODE_RACEPAD ? "🏎 RACEPAD" : "🎮 GAMEPAD";
            drawText(canvas, modeName, rectTmp.centerX(), top + barH / 2f + dp(3.5f), 11f, 0xffe9d5ff, Paint.Align.CENTER, true);
        }

        // Right Pills: Edit, Center, Setup
        rectTmp.set(editL, top, editL + editW, top + barH);
        drawPill(canvas, rectTmp, COLOR_PANEL, COLOR_PANEL_BORDER);
        drawText(canvas, "✎ EDIT", rectTmp.centerX(), top + barH / 2f + dp(3.5f), 10f, 0xff38bdf8, Paint.Align.CENTER, true);

        rectTmp.set(centerL, top, centerL + centerW, top + barH);
        drawPill(canvas, rectTmp, COLOR_PANEL, COLOR_PANEL_BORDER);
        drawText(canvas, "⌖ CENTER", rectTmp.centerX(), top + barH / 2f + dp(3.5f), 10f, 0xffcbd5e1, Paint.Align.CENTER, true);

        rectTmp.set(setupL, top, setupL + setupW, top + barH);
        drawPill(canvas, rectTmp, COLOR_PANEL, COLOR_PANEL_BORDER);
        drawText(canvas, "⚙ SETUP", rectTmp.centerX(), top + barH / 2f + dp(3.5f), 10f, 0xffcbd5e1, Paint.Align.CENTER, true);
    }

    private float[] editBarRects(float w) {
        float scale = clamp(w / dp(650), 0.78f, 1.0f);
        float gap = dp(5) * scale;
        float padX = dp(10);
        float doneW = dp(66) * scale;
        float resetAllW = dp(56) * scale;
        float resetItemW = dp(60) * scale;
        float snapW = dp(54) * scale;
        float pmW = dp(34) * scale;

        float doneL = w - padX - doneW;
        float resetAllL = doneL - gap - resetAllW;
        float resetItemL = resetAllL - gap - resetItemW;
        float snapL = resetItemL - gap - snapW;
        float plusL = snapL - gap - pmW;
        float minusL = plusL - gap - pmW;

        return new float[]{
                minusL, minusL + pmW,
                plusL, plusL + pmW,
                snapL, snapL + snapW,
                resetItemL, resetItemL + resetItemW,
                resetAllL, resetAllL + resetAllW,
                doneL, doneL + doneW
        };
    }

    private void drawEditHud(Canvas canvas, float w) {
        float barH = dp(36);
        float top = dp(5);
        float[] r = editBarRects(w);

        String title;
        int titleColor;
        if (selectedItem != null) {
            title = selectedItem.label + " · " + Math.round(selectedItem.scale * 100f) + "%";
            titleColor = 0xff38bdf8;
        } else {
            title = "EDIT " + (currentMode == MODE_GAMEPAD ? "GAMEPAD" : (gyroEnabled ? "RACEPAD (GYRO)" : "RACEPAD (WHEEL)")) + " · DRAG OR PINCH";
            titleColor = 0xffe9d5ff;
        }
        rectTmp.set(dp(10), top, r[0] - dp(8), top + barH);
        drawPill(canvas, rectTmp, 0xff161124, selectedItem != null ? COLOR_CYAN : COLOR_ACCENT);
        drawText(canvas, title, rectTmp.left + dp(12), top + barH / 2f + dp(3.5f), 10f, titleColor, Paint.Align.LEFT, true);

        boolean hasSel = selectedItem != null;
        rectTmp.set(r[0], top, r[1], top + barH);
        drawPill(canvas, rectTmp, COLOR_PANEL, hasSel ? COLOR_CYAN : COLOR_PANEL_BORDER);
        drawText(canvas, "−", rectTmp.centerX(), top + barH / 2f + dp(4.5f), 14f, hasSel ? 0xffffffff : 0xff475569, Paint.Align.CENTER, true);

        rectTmp.set(r[2], top, r[3], top + barH);
        drawPill(canvas, rectTmp, COLOR_PANEL, hasSel ? COLOR_CYAN : COLOR_PANEL_BORDER);
        drawText(canvas, "+", rectTmp.centerX(), top + barH / 2f + dp(4.5f), 14f, hasSel ? 0xffffffff : 0xff475569, Paint.Align.CENTER, true);

        rectTmp.set(r[4], top, r[5], top + barH);
        drawPill(canvas, rectTmp, snapToGrid ? 0xff2b1f47 : COLOR_PANEL, snapToGrid ? COLOR_ACCENT : COLOR_PANEL_BORDER);
        drawText(canvas, "SNAP", rectTmp.centerX(), top + barH / 2f + dp(3.5f), 9.5f, snapToGrid ? 0xffe9d5ff : 0xff94a3b8, Paint.Align.CENTER, true);

        boolean canResetItem = hasSel && selectedItem.isModified();
        rectTmp.set(r[6], top, r[7], top + barH);
        drawPill(canvas, rectTmp, COLOR_PANEL, canResetItem ? 0xfff59e0b : COLOR_PANEL_BORDER);
        drawText(canvas, "↺ 1", rectTmp.centerX(), top + barH / 2f + dp(3.5f), 9.5f, canResetItem ? 0xfffbbf24 : 0xff64748b, Paint.Align.CENTER, true);

        rectTmp.set(r[8], top, r[9], top + barH);
        drawPill(canvas, rectTmp, COLOR_PANEL, 0xfff43f5e);
        drawText(canvas, "↺ ALL", rectTmp.centerX(), top + barH / 2f + dp(3.5f), 9.5f, 0xfffda4af, Paint.Align.CENTER, true);

        rectTmp.set(r[10], top, r[11], top + barH);
        drawPill(canvas, rectTmp, 0xff065f46, 0xff10b981);
        drawText(canvas, "✓ DONE", rectTmp.centerX(), top + barH / 2f + dp(3.5f), 9.8f, 0xffffffff, Paint.Align.CENTER, true);
    }

    private void drawControlItem(Canvas canvas, ControlItem item, float w, float h) {
        float cx = itemCx(item, w, h);
        float cy = itemCy(item, w, h);
        float hw = itemHalfW(item, w, h);
        float hh = itemHalfH(item, w, h);
        float s = item.scale;

        switch (item.kind) {
            case KIND_WHEEL:
                drawSteeringGauge(canvas, cx, cy, hw, s);
                break;
            case KIND_PEDAL: {
                float val = "clutch".equals(item.id) ? clutch : ("brake".equals(item.id) ? brake : throttle);
                drawPedal(canvas, cx - hw, cx + hw, cy - hh, cy + hh, item.label, val, item.accentColor, item.altColor, s);
                break;
            }
            case KIND_STICK_L:
                drawStick(canvas, cx, cy, hw, lx, ly, "LEFT STICK", displayedButtons[9], s);
                break;
            case KIND_STICK_R:
                drawStick(canvas, cx, cy, hw, rx, ry, "RIGHT STICK", displayedButtons[10], s);
                break;
            case KIND_DPAD:
                drawMiniDpad(canvas, cx, cy, hw, s);
                break;
            case KIND_TRIGGER: {
                float val = "lt".equals(item.id) ? lt : rt;
                drawTriggerPill(canvas, cx - hw, cy - hh, cx + hw, cy + hh, item.label, val, item.accentColor, s);
                break;
            }
            case KIND_CIRCLE_BTN:
                drawCircleButton(canvas, cx, cy, hw, item.label, item.buttonSlot, item.accentColor, s);
                break;
            case KIND_PILL_BTN:
            default: {
                boolean active = item.buttonSlot >= 0 && item.buttonSlot < displayedButtons.length && displayedButtons[item.buttonSlot];
                int fill = active ? item.accentColor : COLOR_PANEL;
                int stroke = active ? 0xffffffff : COLOR_PANEL_BORDER;
                int textCol = active ? 0xffffffff : (item.altColor != 0 ? item.altColor : 0xffcbd5e1);
                rectTmp.set(cx - hw, cy - hh, cx + hw, cy + hh);
                drawPill(canvas, rectTmp, fill, stroke);
                float spBase = (item.label.length() > 7 ? 8.5f : 9.5f) * clamp(s, 0.72f, 1.45f);
                drawText(canvas, item.label, cx, cy + dp(3.3f) * clamp(s, 0.75f, 1.4f), spBase, textCol, Paint.Align.CENTER, true);
                break;
            }
        }
    }

    private void drawEditOverlays(Canvas canvas, float w, float h) {
        for (ControlItem item : activeItems()) {
            float cx = itemCx(item, w, h);
            float cy = itemCy(item, w, h);
            float hw = itemHalfW(item, w, h);
            float hh = itemHalfH(item, w, h);
            boolean isSel = item == selectedItem;

            rectTmp.set(cx - hw - dp(2), cy - hh - dp(2), cx + hw + dp(2), cy + hh + dp(2));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(isSel ? dp(2.0f) : dp(1.1f));
            paint.setColor(isSel ? COLOR_CYAN : 0x996d5996);
            paint.setPathEffect(isSel ? null : editDash);
            canvas.drawRoundRect(rectTmp, dp(10), dp(10), paint);
            paint.setPathEffect(null);

            if (isSel) {
                float hx = Math.min(w - dp(10), rectTmp.right);
                float hy = Math.min(h - dp(10), rectTmp.bottom);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(COLOR_CYAN);
                canvas.drawCircle(hx, hy, dp(8.5f), paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1.8f));
                paint.setColor(0xffffffff);
                canvas.drawCircle(hx, hy, dp(8.5f), paint);
                drawText(canvas, "↘", hx, hy + dp(3.2f), 9.5f, 0xff06121e, Paint.Align.CENTER, true);
            }
        }
    }

    private void drawTriggerPill(Canvas canvas, float l, float t, float r, float b, String label, float value, int color, float s) {
        rectTmp.set(l, t, r, b);
        drawPill(canvas, rectTmp, COLOR_PANEL, COLOR_PANEL_BORDER);
        float fillW = (r - l) * value;
        if (fillW > dp(4)) {
            rectTmp.set(l + dp(2), t + dp(2), l + fillW - dp(2), b - dp(2));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            float rad = rectTmp.height() / 2f;
            canvas.drawRoundRect(rectTmp, rad, rad, paint);
        }
        int pct = Math.round(value * 100);
        float textSp = 9.5f * clamp(s, 0.72f, 1.45f);
        drawText(canvas, label + " " + pct + "%", (l + r) / 2f, (t + b) / 2f + dp(3.3f) * clamp(s, 0.75f, 1.4f), textSp, 0xffffffff, Paint.Align.CENTER, true);
    }

    private void drawStick(Canvas canvas, float cx, float cy, float radius, float valX, float valY, String label, boolean click, float s) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COLOR_PANEL);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.8f));
        paint.setColor(click ? COLOR_ACCENT : COLOR_PANEL_BORDER);
        canvas.drawCircle(cx, cy, radius, paint);

        paint.setColor(0xff231b32);
        paint.setStrokeWidth(dp(1.2f));
        canvas.drawLine(cx - radius * 0.7f, cy, cx + radius * 0.7f, cy, paint);
        canvas.drawLine(cx, cy - radius * 0.7f, cx, cy + radius * 0.7f, paint);

        float knobR = radius * 0.44f;
        float kx = cx + valX * (radius - knobR);
        float ky = cy + valY * (radius - knobR);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(click ? COLOR_ACCENT : 0xff1e172a);
        canvas.drawCircle(kx, ky, knobR, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(click ? 0xffffffff : 0xff5b4878);
        canvas.drawCircle(kx, ky, knobR, paint);

        drawText(canvas, label, cx, cy + radius - dp(6), 7.8f * clamp(s, 0.75f, 1.35f), COLOR_TEXT_MUTED, Paint.Align.CENTER, true);
    }

    private void drawMiniDpad(Canvas canvas, float cx, float cy, float span, float s) {
        float armW = span * 0.7f;
        float armH = span * 0.6f;
        float arrowSp = 8.5f * clamp(s, 0.72f, 1.45f);

        rectTmp.set(cx - armW / 2f, cy - span, cx + armW / 2f, cy - span + armH);
        drawPill(canvas, rectTmp, dpadUp ? COLOR_ACCENT : COLOR_PANEL, dpadUp ? 0xffffffff : COLOR_PANEL_BORDER);
        drawText(canvas, "▲", cx, cy - span + armH * 0.72f, arrowSp, dpadUp ? 0xffffffff : 0xff94a3b8, Paint.Align.CENTER, true);

        rectTmp.set(cx - armW / 2f, cy + span - armH, cx + armW / 2f, cy + span);
        drawPill(canvas, rectTmp, dpadDown ? COLOR_ACCENT : COLOR_PANEL, dpadDown ? 0xffffffff : COLOR_PANEL_BORDER);
        drawText(canvas, "▼", cx, cy + span - armH * 0.24f, arrowSp, dpadDown ? 0xffffffff : 0xff94a3b8, Paint.Align.CENTER, true);

        rectTmp.set(cx - span, cy - armW / 2f, cx - span + armH, cy + armW / 2f);
        drawPill(canvas, rectTmp, dpadLeft ? COLOR_ACCENT : COLOR_PANEL, dpadLeft ? 0xffffffff : COLOR_PANEL_BORDER);
        drawText(canvas, "◀", cx - span + armH * 0.5f, cy + dp(3) * clamp(s, 0.75f, 1.4f), arrowSp, dpadLeft ? 0xffffffff : 0xff94a3b8, Paint.Align.CENTER, true);

        rectTmp.set(cx + span - armH, cy - armW / 2f, cx + span, cy + armW / 2f);
        drawPill(canvas, rectTmp, dpadRight ? COLOR_ACCENT : COLOR_PANEL, dpadRight ? 0xffffffff : COLOR_PANEL_BORDER);
        drawText(canvas, "▶", cx + span - armH * 0.5f, cy + dp(3) * clamp(s, 0.75f, 1.4f), arrowSp, dpadRight ? 0xffffffff : 0xff94a3b8, Paint.Align.CENTER, true);
    }

    private void updateSteeringAnimation() {
        long now = System.nanoTime();
        float dt = lastAnimNs == 0L ? 0.016f : clamp((now - lastAnimNs) / 1_000_000_000f, 0.001f, 0.05f);
        lastAnimNs = now;

        if (steeringTouch) {
            displayWheelDeg = touchWheelDeg;
            return;
        }

        if (gyroEnabled) {
            float targetDeg = clamp(sensorSteer, -1f, 1f) * wheelMaxDeg;
            if (blendingFromTouch) {
                float diff = targetDeg - displayWheelDeg;
                if (Math.abs(diff) > 0.35f) {
                    float k = 1f - (float) Math.exp(-dt * 32f);
                    displayWheelDeg += diff * k;
                    this.steer = clamp(displayWheelDeg / wheelMaxDeg, -1f, 1f);
                    touchWheelDeg = displayWheelDeg;
                    if (currentMode == MODE_GAMEPAD && !leftStickTouched) {
                        this.lx = this.steer;
                    }
                    notifyInputChanged();
                    postInvalidateOnAnimation();
                    return;
                }
                blendingFromTouch = false;
            }
            displayWheelDeg = targetDeg;
            this.steer = clamp(sensorSteer, -1f, 1f);
            return;
        }

        // Smooth caster self-centering when touch steering is released.
        if (Math.abs(displayWheelDeg) > 0.35f) {
            float k = 1f - (float) Math.exp(-dt * 22f);
            displayWheelDeg += (0f - displayWheelDeg) * k;
            touchWheelDeg = displayWheelDeg;
            this.steer = clamp(displayWheelDeg / wheelMaxDeg, -1f, 1f);
            notifyInputChanged();
            postInvalidateOnAnimation();
        } else if (displayWheelDeg != 0f || this.steer != 0f || touchWheelDeg != 0f) {
            displayWheelDeg = 0f;
            touchWheelDeg = 0f;
            this.steer = 0f;
            notifyInputChanged();
        }
    }

    private void drawSteeringGauge(Canvas canvas, float cx, float cy, float radius, float s) {
        float steerVal = clamp(displayWheelDeg / Math.max(1f, wheelMaxDeg), -1f, 1f);
        float arcHalf = Math.min(135f, wheelMaxDeg);

        arcRectTmp.set(cx - radius, cy - radius, cx + radius, cy + radius);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(5.5f) * clamp(s, 0.7f, 1.5f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0xff1b1528);
        canvas.drawArc(arcRectTmp, 270f - arcHalf, arcHalf * 2f, false, paint);

        float sweep = steerVal * arcHalf;
        if (Math.abs(sweep) > 0.5f) {
            paint.setColor(steeringTouch ? 0xffa855f7 : (Math.abs(steerVal) > 0.72f ? 0xff38bdf8 : COLOR_ACCENT));
            canvas.drawArc(arcRectTmp, 270f, sweep, false, paint);
        }

        int step = 15;
        int maxTick = Math.round(arcHalf);
        for (int deg = -maxTick; deg <= maxTick; deg += step) {
            boolean major = (deg == 0) || (Math.abs(deg) % 30 == 0) || (Math.abs(deg) == maxTick);
            double rad = Math.toRadians(270.0 + deg);
            float inner = radius - (deg == 0 ? dp(13) : (major ? dp(8.5f) : dp(5.5f))) * clamp(s, 0.7f, 1.4f);
            float outer = radius + dp(3.5f) * clamp(s, 0.7f, 1.4f);
            paint.setStrokeWidth(deg == 0 ? dp(2.4f) : (major ? dp(1.5f) : dp(1f)));
            paint.setColor(deg == 0 ? 0xfff59e0b : (major ? 0xff5b4a7a : 0xff2f2543));
            canvas.drawLine(
                    cx + (float) Math.cos(rad) * inner,
                    cy + (float) Math.sin(rad) * inner,
                    cx + (float) Math.cos(rad) * outer,
                    cy + (float) Math.sin(rad) * outer,
                    paint
            );
        }

        float rimR = radius * 0.76f;
        float rimThick = Math.max(dp(9), radius * 0.135f);
        float hubR = radius * 0.27f;

        canvas.save();
        canvas.rotate(displayWheelDeg, cx, cy);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(rimThick + dp(3));
        paint.setColor(0xff050308);
        canvas.drawCircle(cx, cy + dp(1.5f), rimR, paint);

        drawTaperedSpoke(canvas, cx, cy, rimR * 0.94f, hubR * 0.7f, 188f, dp(10) * s, dp(14) * s);
        drawTaperedSpoke(canvas, cx, cy, rimR * 0.94f, hubR * 0.7f, -8f, dp(10) * s, dp(14) * s);
        drawTaperedSpoke(canvas, cx, cy, rimR * 0.94f, hubR * 0.7f, 90f, dp(12) * s, dp(17) * s);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(rimThick);
        paint.setColor(0xff1a1524);
        canvas.drawCircle(cx, cy, rimR, paint);

        paint.setStrokeWidth(dp(1.8f));
        paint.setColor(steeringTouch ? 0xffc084fc : (Math.abs(steerVal) > 0.04f ? 0xff7c3aed : 0xff463761));
        canvas.drawCircle(cx, cy, rimR + rimThick * 0.5f - dp(0.5f), paint);

        paint.setStrokeWidth(dp(1.2f));
        paint.setColor(0xff2c223f);
        canvas.drawCircle(cx, cy, rimR - rimThick * 0.5f + dp(1.2f), paint);

        arcRectTmp.set(cx - rimR, cy - rimR, cx + rimR, cy + rimR);
        paint.setStrokeWidth(rimThick + dp(3.5f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0xff271f36);
        canvas.drawArc(arcRectTmp, 158f, 44f, false, paint);
        canvas.drawArc(arcRectTmp, -22f, 44f, false, paint);

        paint.setStrokeWidth(dp(1.5f));
        paint.setColor(steeringTouch ? 0xffa855f7 : 0xff5a487a);
        canvas.drawArc(arcRectTmp, 162f, 36f, false, paint);
        canvas.drawArc(arcRectTmp, -18f, 36f, false, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xfff59e0b);
        float stripeHalfW = Math.max(dp(3f), rimThick * 0.34f);
        rectTmp.set(
                cx - stripeHalfW,
                cy - rimR - rimThick * 0.54f,
                cx + stripeHalfW,
                cy - rimR + rimThick * 0.54f
        );
        canvas.drawRoundRect(rectTmp, dp(2), dp(2), paint);
        paint.setColor(0xfffde68a);
        rectTmp.inset(dp(1.2f), dp(1.4f));
        canvas.drawRoundRect(rectTmp, dp(1f), dp(1f), paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xff130f1d);
        canvas.drawCircle(cx, cy, hubR, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(steeringTouch ? 0xffc084fc : 0xff5b4878);
        canvas.drawCircle(cx, cy, hubR, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xff786799);
        float boltRingR = hubR * 0.78f;
        for (int b = 0; b < 6; b++) {
            double brad = Math.toRadians(b * 60.0);
            canvas.drawCircle(
                    cx + (float) Math.cos(brad) * boltRingR,
                    cy + (float) Math.sin(brad) * boltRingR,
                    dp(1.5f),
                    paint
            );
        }

        canvas.restore();

        int angle = Math.round(displayWheelDeg);
        String angleStr = (angle > 0 ? "+" : "") + angle + "°";
        float badgeW = Math.max(dp(42) * clamp(s, 0.75f, 1.4f), hubR * 1.85f);
        float badgeH = dp(22) * clamp(s, 0.75f, 1.4f);
        rectTmp.set(cx - badgeW / 2f, cy - badgeH / 2f, cx + badgeW / 2f, cy + badgeH / 2f);
        drawPill(canvas, rectTmp, 0xe6100c18, steeringTouch ? 0xffc084fc : 0xff463761);
        drawText(canvas, angleStr, cx, cy + dp(4.2f) * clamp(s, 0.75f, 1.35f), 12.5f * clamp(s, 0.72f, 1.4f), 0xffffffff, Paint.Align.CENTER, true);

        String modeTag = steeringTouch
                ? String.format(Locale.US, "TOUCH · ±%d°", Math.round(wheelMaxDeg))
                : (gyroEnabled
                        ? String.format(Locale.US, "ROTATE · ±%d°", Math.round(wheelMaxDeg))
                        : String.format(Locale.US, "DRAG · ±%d°", Math.round(wheelMaxDeg)));
        drawText(canvas, modeTag, cx, cy + radius - dp(3), 7.8f * clamp(s, 0.75f, 1.3f), COLOR_TEXT_MUTED, Paint.Align.CENTER, true);
    }

    private void drawTaperedSpoke(Canvas canvas, float cx, float cy, float outerLen, float innerLen,
                                  float angleDeg, float outerHalfW, float innerHalfW) {
        double rad = Math.toRadians(angleDeg);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        float px = -sin;
        float py = cos;

        float ix = cx + cos * innerLen;
        float iy = cy + sin * innerLen;
        float ox = cx + cos * outerLen;
        float oy = cy + sin * outerLen;

        spokePath.reset();
        spokePath.moveTo(ix + px * innerHalfW * 0.5f, iy + py * innerHalfW * 0.5f);
        spokePath.lineTo(ox + px * outerHalfW * 0.5f, oy + py * outerHalfW * 0.5f);
        spokePath.lineTo(ox - px * outerHalfW * 0.5f, oy - py * outerHalfW * 0.5f);
        spokePath.lineTo(ix - px * innerHalfW * 0.5f, iy - py * innerHalfW * 0.5f);
        spokePath.close();

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xff251d36);
        canvas.drawPath(spokePath, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.2f));
        paint.setColor(0xff3f3259);
        canvas.drawPath(spokePath, paint);
    }

    private void drawPedal(Canvas canvas, float left, float right, float top, float bottom, String label, float value, int colorTop, int colorBottom, float s) {
        float cx = (left + right) / 2f;
        float h = bottom - top;

        rectTmp.set(left, top, right, bottom);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COLOR_PANEL);
        canvas.drawRoundRect(rectTmp, dp(12), dp(12), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor(value > 0.05f ? colorTop : COLOR_PANEL_BORDER);
        canvas.drawRoundRect(rectTmp, dp(12), dp(12), paint);

        float fillTop = bottom - h * value;
        if (value > 0.01f) {
            rectTmp.set(left + dp(2.5f), fillTop, right - dp(2.5f), bottom - dp(2.5f));
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(cx, fillTop, cx, bottom, colorTop, colorBottom, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rectTmp, dp(10), dp(10), paint);
            paint.setShader(null);
        }

        float labelSp = 9.0f * clamp(s, 0.72f, 1.4f);
        float pctSp = 11.0f * clamp(s, 0.72f, 1.4f);
        drawText(canvas, label, cx, top + dp(14) * clamp(s, 0.8f, 1.3f), labelSp, value > 0.05f ? 0xffffffff : COLOR_TEXT_MUTED, Paint.Align.CENTER, true);
        int pct = Math.round(value * 100);
        drawText(canvas, pct + "%", cx, bottom - dp(8), pctSp, 0xffffffff, Paint.Align.CENTER, true);
    }

    private void drawCircleButton(Canvas canvas, float cx, float cy, float radius, String label, int index, int glowColor, float s) {
        boolean pressed = index >= 0 && index < displayedButtons.length && displayedButtons[index];

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pressed ? glowColor : COLOR_PANEL);
        canvas.drawCircle(cx, cy, radius, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(pressed ? 0xffffffff : COLOR_PANEL_BORDER);
        canvas.drawCircle(cx, cy, radius, paint);

        int textCol = pressed ? 0xffffffff : ("guide".equals(label) || "⌘".equals(label) ? 0xffa78bfa : 0xffe2e8f0);
        drawText(canvas, label, cx, cy + dp(5) * clamp(s, 0.75f, 1.4f), 14.5f * clamp(s, 0.7f, 1.55f), textCol, Paint.Align.CENTER, true);
    }

    private void drawPill(Canvas canvas, RectF rect, int fillColor, int strokeColor) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(fillColor);
        float r = rect.height() / 2f;
        canvas.drawRoundRect(rect, r, r, paint);

        if (strokeColor != 0) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.2f));
            paint.setColor(strokeColor);
            canvas.drawRoundRect(rect, r, r, paint);
        }
    }

    private void drawText(Canvas canvas, String text, float x, float y, float sizeSp, int color, Paint.Align align, boolean bold) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTextSize(dp(sizeSp));
        paint.setTextAlign(align);
        paint.setTypeface(bold ? typefaceBold : typefaceNormal);
        canvas.drawText(text, x, y, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float w = getWidth(), h = getHeight();
        if (w <= 0f || h <= 0f) return true;

        if (editMode) {
            return handleEditTouch(event, w, h);
        }

        int action = event.getActionMasked();
        int actionIndex = event.getActionIndex();

        if (action == MotionEvent.ACTION_CANCEL) {
            clearInput();
            return true;
        }

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            float tx = event.getX(actionIndex);
            float ty = event.getY(actionIndex);
            int downId = event.getPointerId(actionIndex);
            if (ty <= dp(44)) {
                handleHudTouch(tx, w);
                return true;
            }
            ControlItem hit = findHitControl(tx, ty, w, h);
            if (hit != null) {
                if (currentMode == MODE_RACEPAD && hit.kind == KIND_WHEEL && steerPointerId == MotionEvent.INVALID_POINTER_ID) {
                    float cx = itemCx(hit, w, h);
                    float cy = itemCy(hit, w, h);
                    steerPointerId = downId;
                    steeringTouch = true;
                    blendingFromTouch = false;
                    touchWheelDeg = displayWheelDeg;
                    lastTouchAngleDeg = (float) Math.toDegrees(Math.atan2(ty - cy, tx - cx));
                    lastTouchX = tx;
                    hitFullLockHaptic = Math.abs(touchWheelDeg) >= wheelMaxDeg - 0.5f;
                    lastAnimNs = 0L;
                } else if (currentMode == MODE_GAMEPAD && hit.kind == KIND_STICK_L && leftStickPointerId == MotionEvent.INVALID_POINTER_ID) {
                    leftStickPointerId = downId;
                } else if (currentMode == MODE_GAMEPAD && hit.kind == KIND_STICK_R && rightStickPointerId == MotionEvent.INVALID_POINTER_ID) {
                    rightStickPointerId = downId;
                }
            }
        }

        boolean lifted = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP;
        int liftedIndex = event.getActionIndex();
        if (lifted) {
            int liftedId = event.getPointerId(liftedIndex);
            if (liftedId == steerPointerId) {
                steerPointerId = MotionEvent.INVALID_POINTER_ID;
                blendingFromTouch = true;
            }
            if (liftedId == leftStickPointerId) leftStickPointerId = MotionEvent.INVALID_POINTER_ID;
            if (liftedId == rightStickPointerId) rightStickPointerId = MotionEvent.INVALID_POINTER_ID;
        }

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_MOVE || lifted) {
            float nextSteer = 0f;
            boolean nextSteeringTouch = false;
            boolean nextLeftStickTouch = false;
            float nextThrottle = 0f;
            float nextBrake = 0f;
            float nextClutch = 0f;
            float nextHandbrake = 0f;

            float nextLx = 0f, nextLy = 0f;
            float nextRx = 0f, nextRy = 0f;
            float nextLt = 0f, nextRt = 0f;
            boolean nextDpadUp = false, nextDpadDown = false, nextDpadLeft = false, nextDpadRight = false;
            boolean[] nextButtons = new boolean[16];

            int count = lifted ? Math.max(0, event.getPointerCount() - 1) : event.getPointerCount();
            List<ControlItem> items = activeItems();

            for (int i = 0, used = 0; i < event.getPointerCount(); i++) {
                if (lifted && i == liftedIndex) continue;
                if (used >= count) break;
                used++;

                int pointerId = event.getPointerId(i);
                float x = event.getX(i);
                float y = event.getY(i);

                if (currentMode == MODE_RACEPAD && pointerId == steerPointerId) {
                    ControlItem wheel = findItemById(items, "wheel");
                    if (wheel != null) {
                        float cx = itemCx(wheel, w, h);
                        float cy = itemCy(wheel, w, h);
                        float radius = itemHalfW(wheel, w, h);
                        float dist = (float) Math.hypot(x - cx, y - cy);
                        float prevTouchDeg = touchWheelDeg;

                        if (dist >= radius * 0.20f) {
                            float curAngleDeg = (float) Math.toDegrees(Math.atan2(y - cy, x - cx));
                            float deltaDeg = wrapDeg180(curAngleDeg - lastTouchAngleDeg);
                            lastTouchAngleDeg = curAngleDeg;
                            touchWheelDeg = clamp(touchWheelDeg + deltaDeg, -wheelMaxDeg, wheelMaxDeg);
                        } else {
                            float dx = x - lastTouchX;
                            touchWheelDeg = clamp(touchWheelDeg + (dx / Math.max(1f, radius * 0.72f)) * 45f, -wheelMaxDeg, wheelMaxDeg);
                            lastTouchAngleDeg = (float) Math.toDegrees(Math.atan2(y - cy, x - cx));
                        }
                        lastTouchX = x;
                        displayWheelDeg = touchWheelDeg;
                        nextSteeringTouch = true;
                        nextSteer = clamp(touchWheelDeg / Math.max(1f, wheelMaxDeg), -1f, 1f);

                        if ((prevTouchDeg < 0f && touchWheelDeg >= 0f) || (prevTouchDeg > 0f && touchWheelDeg <= 0f)) {
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        }
                        boolean atLock = Math.abs(touchWheelDeg) >= wheelMaxDeg - 0.5f;
                        if (atLock && !hitFullLockHaptic) {
                            triggerLightTick();
                        }
                        hitFullLockHaptic = atLock;
                    }
                    continue;
                }

                if (currentMode == MODE_GAMEPAD && pointerId == leftStickPointerId) {
                    ControlItem stick = findItemById(items, "lstick");
                    if (stick != null) {
                        float stickR = Math.max(1f, itemHalfW(stick, w, h));
                        float dx = x - itemCx(stick, w, h);
                        float dy = y - itemCy(stick, w, h);
                        float dist = (float) Math.hypot(dx, dy);
                        if (dist > stickR) {
                            dx *= stickR / dist;
                            dy *= stickR / dist;
                        }
                        nextLeftStickTouch = true;
                        nextLx = clamp(dx / stickR, -1f, 1f);
                        nextLy = clamp(dy / stickR, -1f, 1f);
                    }
                    continue;
                }

                if (currentMode == MODE_GAMEPAD && pointerId == rightStickPointerId) {
                    ControlItem stick = findItemById(items, "rstick");
                    if (stick != null) {
                        float stickR = Math.max(1f, itemHalfW(stick, w, h));
                        float dx = x - itemCx(stick, w, h);
                        float dy = y - itemCy(stick, w, h);
                        float dist = (float) Math.hypot(dx, dy);
                        if (dist > stickR) {
                            dx *= stickR / dist;
                            dy *= stickR / dist;
                        }
                        nextRx = clamp(dx / stickR, -1f, 1f);
                        nextRy = clamp(dy / stickR, -1f, 1f);
                    }
                    continue;
                }

                if (y <= dp(44)) continue;

                ControlItem hit = findHitControl(x, y, w, h);
                if (hit == null) continue;

                switch (hit.kind) {
                    case KIND_PEDAL: {
                        float val;
                        if (instantTapPedals) {
                            val = 1.0f;
                        } else {
                            float cy = itemCy(hit, w, h);
                            float hh = itemHalfH(hit, w, h);
                            float top = cy - hh;
                            float bottom = cy + hh;
                            float hSpan = Math.max(1f, bottom - top);
                            float effBottom = bottom - hSpan * 0.06f;
                            float effTop = top + hSpan * 0.22f;
                            val = clamp((effBottom - y) / Math.max(1f, effBottom - effTop), 0.03f, 1.0f);
                        }
                        if ("clutch".equals(hit.id)) nextClutch = Math.max(nextClutch, val);
                        else if ("brake".equals(hit.id)) nextBrake = Math.max(nextBrake, val);
                        else if ("gas".equals(hit.id)) nextThrottle = Math.max(nextThrottle, val);
                        break;
                    }
                    case KIND_TRIGGER: {
                        if ("lt".equals(hit.id)) nextLt = 1.0f;
                        else if ("rt".equals(hit.id)) nextRt = 1.0f;
                        break;
                    }
                    case KIND_DPAD: {
                        float dx = x - itemCx(hit, w, h);
                        float dy = y - itemCy(hit, w, h);
                        if (Math.abs(dx) > Math.abs(dy)) {
                            if (dx < 0) nextDpadLeft = true;
                            else nextDpadRight = true;
                        } else {
                            if (dy < 0) nextDpadUp = true;
                            else nextDpadDown = true;
                        }
                        break;
                    }
                    case KIND_CIRCLE_BTN:
                    case KIND_PILL_BTN: {
                        if (hit.buttonSlot >= 0 && hit.buttonSlot < 16) {
                            nextButtons[hit.buttonSlot] = true;
                            if (hit.buttonSlot == 15) nextHandbrake = 1.0f;
                        }
                        break;
                    }
                    default:
                        break;
                }
            }

            boolean newBtnPress = false;
            for (int b = 0; b < 16; b++) {
                if (nextButtons[b] && !buttons[b]) {
                    newBtnPress = true;
                    break;
                }
            }

            if (nextSteeringTouch) {
                this.steer = nextSteer;
            } else if (this.steeringTouch) {
                this.steer = clamp(displayWheelDeg / Math.max(1f, wheelMaxDeg), -1f, 1f);
                lastAnimNs = System.nanoTime();
            } else {
                this.steer = clamp(displayWheelDeg / Math.max(1f, wheelMaxDeg), -1f, 1f);
            }
            this.steeringTouch = nextSteeringTouch;
            this.leftStickTouched = nextLeftStickTouch;
            this.throttle = nextThrottle;
            this.brake = nextBrake;
            this.clutch = nextClutch;
            this.handbrake = nextHandbrake;

            this.lx = (currentMode == MODE_GAMEPAD && gyroEnabled && !nextLeftStickTouch) ? this.steer : nextLx;
            this.ly = nextLy;
            this.rx = nextRx;
            this.ry = nextRy;
            this.lt = currentMode == MODE_GAMEPAD ? nextLt : nextBrake;
            this.rt = currentMode == MODE_GAMEPAD ? nextRt : nextThrottle;

            this.dpadUp = nextDpadUp;
            this.dpadDown = nextDpadDown;
            this.dpadLeft = nextDpadLeft;
            this.dpadRight = nextDpadRight;

            System.arraycopy(nextButtons, 0, buttons, 0, 16);
            System.arraycopy(buttons, 0, displayedButtons, 0, 16);

            notifyInputChanged();
            if (newBtnPress) triggerHaptic();
            postInvalidateOnAnimation();
            return true;
        }

        return true;
    }

    private boolean handleEditTouch(MotionEvent event, float w, float h) {
        int action = event.getActionMasked();
        int actionIndex = event.getActionIndex();
        float topY = playTop();
        float ph = playHeight(h);

        if (action == MotionEvent.ACTION_CANCEL) {
            editDragMode = 0;
            editPointerId = MotionEvent.INVALID_POINTER_ID;
            guideCenterX = false;
            guideCenterY = false;
            invalidate();
            return true;
        }

        if (action == MotionEvent.ACTION_DOWN) {
            float tx = event.getX(0);
            float ty = event.getY(0);
            if (ty <= dp(44)) {
                handleEditHudTouch(tx, w);
                return true;
            }

            if (selectedItem != null) {
                float cx = itemCx(selectedItem, w, h);
                float cy = itemCy(selectedItem, w, h);
                float hw = itemHalfW(selectedItem, w, h);
                float hh = itemHalfH(selectedItem, w, h);
                float hx = Math.min(w - dp(10), cx + hw + dp(2));
                float hy = Math.min(h - dp(10), cy + hh + dp(2));
                if (Math.hypot(tx - hx, ty - hy) <= dp(24)) {
                    editDragMode = 2;
                    editPointerId = event.getPointerId(0);
                    resizeStartDist = Math.max(dp(16), (float) Math.hypot(tx - cx, ty - cy));
                    resizeStartScale = selectedItem.scale;
                    triggerLightTick();
                    invalidate();
                    return true;
                }
            }

            ControlItem hit = findEditHitControl(tx, ty, w, h);
            if (hit != null) {
                selectedItem = hit;
                editDragMode = 1;
                editPointerId = event.getPointerId(0);
                dragOffsetX = itemCx(hit, w, h) - tx;
                dragOffsetY = itemCy(hit, w, h) - ty;
                triggerLightTick();
            } else {
                selectedItem = null;
                editDragMode = 0;
                editPointerId = MotionEvent.INVALID_POINTER_ID;
            }
            invalidate();
            return true;
        }

        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            if (event.getPointerCount() >= 2) {
                if (selectedItem == null) {
                    float midX = (event.getX(0) + event.getX(1)) * 0.5f;
                    float midY = (event.getY(0) + event.getY(1)) * 0.5f;
                    selectedItem = findEditHitControl(midX, midY, w, h);
                }
                if (selectedItem != null) {
                    editDragMode = 3;
                    pinchStartDist = Math.max(dp(20), (float) Math.hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0)));
                    pinchStartScale = selectedItem.scale;
                    guideCenterX = false;
                    guideCenterY = false;
                    invalidate();
                }
            }
            return true;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            if (selectedItem == null) return true;

            if (editDragMode == 3 && event.getPointerCount() >= 2) {
                float curDist = (float) Math.hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0));
                float factor = curDist / Math.max(1f, pinchStartDist);
                selectedItem.scale = clamp(pinchStartScale * factor, MIN_SCALE, MAX_SCALE);
                invalidate();
                return true;
            }

            int pIdx = event.findPointerIndex(editPointerId);
            if (pIdx < 0) return true;
            float x = event.getX(pIdx);
            float y = event.getY(pIdx);

            if (editDragMode == 2) {
                float cx = itemCx(selectedItem, w, h);
                float cy = itemCy(selectedItem, w, h);
                float dist = Math.max(dp(10), (float) Math.hypot(x - cx, y - cy));
                selectedItem.scale = clamp(resizeStartScale * (dist / resizeStartDist), MIN_SCALE, MAX_SCALE);
                invalidate();
                return true;
            }

            if (editDragMode == 1) {
                float hw = itemHalfW(selectedItem, w, h);
                float hh = itemHalfH(selectedItem, w, h);
                float rawCx = clamp(x + dragOffsetX, hw + dp(4), w - hw - dp(4));
                float rawCy = clamp(y + dragOffsetY, topY + hh + dp(4), h - hh - dp(4));

                guideCenterX = false;
                guideCenterY = false;
                float midX = w * 0.5f;
                float midY = topY + ph * 0.5f;

                if (snapToGrid) {
                    if (Math.abs(rawCx - midX) <= dp(7)) {
                        rawCx = midX;
                        guideCenterX = true;
                    } else {
                        float step = dp(8);
                        rawCx = Math.round(rawCx / step) * step;
                        rawCx = clamp(rawCx, hw + dp(4), w - hw - dp(4));
                    }
                    if (Math.abs(rawCy - midY) <= dp(7)) {
                        rawCy = midY;
                        guideCenterY = true;
                    } else {
                        float step = dp(8);
                        rawCy = topY + Math.round((rawCy - topY) / step) * step;
                        rawCy = clamp(rawCy, topY + hh + dp(4), h - hh - dp(4));
                    }
                }

                selectedItem.cxFrac = clamp(rawCx / Math.max(1f, w), 0.02f, 0.98f);
                selectedItem.cyFrac = clamp((rawCy - topY) / Math.max(1f, ph), 0.02f, 0.98f);
                invalidate();
            }
            return true;
        }

        if (action == MotionEvent.ACTION_POINTER_UP) {
            if (editDragMode == 3 && event.getPointerCount() <= 2) {
                notifyLayoutChanged(activeLayoutId());
                editDragMode = 0;
                editPointerId = MotionEvent.INVALID_POINTER_ID;
            }
            return true;
        }

        if (action == MotionEvent.ACTION_UP) {
            if (editDragMode != 0) {
                notifyLayoutChanged(activeLayoutId());
            }
            editDragMode = 0;
            editPointerId = MotionEvent.INVALID_POINTER_ID;
            guideCenterX = false;
            guideCenterY = false;
            invalidate();
            return true;
        }

        return true;
    }

    private void handleEditHudTouch(float x, float w) {
        float[] r = editBarRects(w);
        triggerHaptic();
        if (x >= r[0] && x <= r[1]) {
            if (selectedItem != null) {
                selectedItem.scale = clamp(Math.round((selectedItem.scale - 0.1f) * 20f) / 20f, MIN_SCALE, MAX_SCALE);
                notifyLayoutChanged(activeLayoutId());
                invalidate();
            }
        } else if (x >= r[2] && x <= r[3]) {
            if (selectedItem != null) {
                selectedItem.scale = clamp(Math.round((selectedItem.scale + 0.1f) * 20f) / 20f, MIN_SCALE, MAX_SCALE);
                notifyLayoutChanged(activeLayoutId());
                invalidate();
            }
        } else if (x >= r[4] && x <= r[5]) {
            snapToGrid = !snapToGrid;
            invalidate();
        } else if (x >= r[6] && x <= r[7]) {
            if (selectedItem != null) {
                selectedItem.reset();
                notifyLayoutChanged(activeLayoutId());
                invalidate();
            }
        } else if (x >= r[8] && x <= r[9]) {
            resetLayout(activeLayoutId());
        } else if (x >= r[10] && x <= r[11]) {
            setEditMode(false);
        }
    }

    private ControlItem findItemById(List<ControlItem> items, String id) {
        for (ControlItem item : items) {
            if (item.id.equals(id)) return item;
        }
        return null;
    }

    private ControlItem findHitControl(float x, float y, float w, float h) {
        ControlItem bestBtn = null;
        float bestBtnDistSq = Float.MAX_VALUE;
        ControlItem bestLarge = null;
        float bestLargeDistSq = Float.MAX_VALUE;

        for (ControlItem item : activeItems()) {
            float cx = itemCx(item, w, h);
            float cy = itemCy(item, w, h);
            float hw = itemHalfW(item, w, h);
            float hh = itemHalfH(item, w, h);
            float dx = x - cx;
            float dy = y - cy;
            float distSq = dx * dx + dy * dy;

            if (item.kind == KIND_WHEEL) {
                if (distSq <= (hw * 1.18f) * (hw * 1.18f) && distSq < bestLargeDistSq) {
                    bestLargeDistSq = distSq;
                    bestLarge = item;
                }
            } else if (item.kind == KIND_STICK_L || item.kind == KIND_STICK_R) {
                if (distSq <= (hw * 1.22f) * (hw * 1.22f) && distSq < bestLargeDistSq) {
                    bestLargeDistSq = distSq;
                    bestLarge = item;
                }
            } else if (item.kind == KIND_CIRCLE_BTN) {
                float hitR = Math.max(1f, hw * 1.16f);
                float normDist = distSq / (hitR * hitR);
                if (normDist <= 1.0f && normDist < bestBtnDistSq) {
                    bestBtnDistSq = normDist;
                    bestBtn = item;
                }
            } else if (item.kind == KIND_DPAD) {
                float hitR = Math.max(1f, hw * 1.32f);
                float normDist = distSq / (hitR * hitR);
                if (normDist <= 1.0f && normDist < bestBtnDistSq) {
                    bestBtnDistSq = normDist;
                    bestBtn = item;
                }
            } else {
                float pad = item.kind == KIND_PEDAL ? dp(5) : dp(3);
                if (x >= cx - hw - pad && x <= cx + hw + pad && y >= cy - hh - pad && y <= cy + hh + pad) {
                    float normDist = distSq / Math.max(1f, hw * hw + hh * hh);
                    if (normDist < bestBtnDistSq) {
                        bestBtnDistSq = normDist;
                        bestBtn = item;
                    }
                }
            }
        }
        return bestBtn != null ? bestBtn : bestLarge;
    }

    private ControlItem findEditHitControl(float x, float y, float w, float h) {
        List<ControlItem> items = activeItems();
        ControlItem best = null;
        float bestDistSq = Float.MAX_VALUE;
        for (ControlItem item : items) {
            float cx = itemCx(item, w, h);
            float cy = itemCy(item, w, h);
            float hw = itemHalfW(item, w, h) + dp(4);
            float hh = itemHalfH(item, w, h) + dp(4);
            if (x >= cx - hw && x <= cx + hw && y >= cy - hh && y <= cy + hh) {
                float d2 = (x - cx) * (x - cx) + (y - cy) * (y - cy);
                // Prefer smaller buttons when stacked over a wheel/pedal
                float areaWeight = (item.kind == KIND_WHEEL || item.kind == KIND_PEDAL) ? 2.5f : 1.0f;
                if (d2 * areaWeight < bestDistSq) {
                    bestDistSq = d2 * areaWeight;
                    best = item;
                }
            }
        }
        return best;
    }

    private void handleHudTouch(float x, float w) {
        triggerHaptic();
        float padX = dp(10);
        float gap = dp(6);
        float badgeW = clamp(w * 0.22f, dp(124), dp(160));
        float setupW = clamp(w * 0.105f, dp(60), dp(74));
        float centerW = clamp(w * 0.112f, dp(64), dp(80));
        float editW = clamp(w * 0.098f, dp(56), dp(70));

        float setupL = w - padX - setupW;
        float centerL = setupL - gap - centerW;
        float editL = centerL - gap - editW;
        float badgeR = padX + badgeW;

        if (x <= badgeR) {
            if (listener != null) listener.onOpenSetup();
            return;
        }

        if (x >= editL && x < centerL) {
            setEditMode(true);
            return;
        }

        if (x >= centerL && x < setupL) {
            if (listener != null) listener.onCalibrate();
            return;
        }

        if (x >= setupL) {
            if (listener != null) listener.onOpenSetup();
            return;
        }

        float availCenter = (editL - badgeR) - gap * 2f;
        float modeW = Math.min(clamp(w * 0.24f, dp(122), dp(170)), Math.max(dp(98), availCenter));
        float modeL = badgeR + (editL - badgeR - modeW) / 2f;
        if (x >= modeL && x <= modeL + modeW) {
            currentMode = currentMode == MODE_RACEPAD ? MODE_GAMEPAD : MODE_RACEPAD;
            selectedItem = null;
            clearInput();
            if (listener != null) listener.onToggleMode();
            invalidate();
        }
    }

    private void triggerHaptic() {
        try {
            if (vibrator != null && vibrator.hasVibrator()) {
                hapticExecutor.execute(() -> {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator.vibrate(VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE));
                        } else {
                            vibrator.vibrate(10);
                        }
                    } catch (Exception ignored) {}
                });
            } else {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            }
        } catch (Exception ignored) {}
    }

    private void notifyInputChanged() {
        if (listener == null) return;
        listener.onInputChanged(
                currentMode == MODE_RACEPAD ? "racepad" : "gamepad",
                this.steer, this.steeringTouch,
                this.throttle, this.brake, this.clutch, this.handbrake,
                this.lx, this.ly, this.rx, this.ry,
                this.lt, this.rt,
                this.dpadUp, this.dpadDown, this.dpadLeft, this.dpadRight,
                Arrays.copyOf(buttons, 16)
        );
    }

    private void triggerLightTick() {
        try {
            if (vibrator != null && vibrator.hasVibrator() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                hapticExecutor.execute(() -> {
                    try {
                        vibrator.vibrate(VibrationEffect.createOneShot(7, 80));
                    } catch (Exception ignored) {}
                });
            } else {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
        } catch (Exception ignored) {}
    }

    private static float wrapDeg180(float deg) {
        while (deg > 180f) deg -= 360f;
        while (deg <= -180f) deg += 360f;
        return deg;
    }

    private void clearInput() {
        steer = 0f;
        sensorSteer = 0f;
        touchWheelDeg = 0f;
        displayWheelDeg = 0f;
        steerPointerId = MotionEvent.INVALID_POINTER_ID;
        leftStickPointerId = MotionEvent.INVALID_POINTER_ID;
        rightStickPointerId = MotionEvent.INVALID_POINTER_ID;
        blendingFromTouch = false;
        lastAnimNs = 0L;
        steeringTouch = false;
        leftStickTouched = false;
        throttle = 0f;
        brake = 0f;
        clutch = 0f;
        handbrake = 0f;
        lx = 0f; ly = 0f; rx = 0f; ry = 0f; lt = 0f; rt = 0f;
        dpadUp = false; dpadDown = false; dpadLeft = false; dpadRight = false;
        Arrays.fill(buttons, false);
        Arrays.fill(displayedButtons, false);
        if (listener != null) {
            listener.onInputChanged(
                    currentMode == MODE_RACEPAD ? "racepad" : "gamepad",
                    0f, false, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f, 0f,
                    false, false, false, false,
                    new boolean[16]
            );
        }
        invalidate();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }
}
