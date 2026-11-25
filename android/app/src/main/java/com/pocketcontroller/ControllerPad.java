package com.pocketcontroller;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.Arrays;
import java.util.Locale;

public final class ControllerPad extends View {
    public static final int MODE_RACEPAD = 0;
    public static final int MODE_GAMEPAD = 1;

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
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rectTmp = new RectF();

    private Listener listener;
    private Vibrator vibrator;

    private int currentMode = MODE_RACEPAD;
    private boolean connected = false;
    private String pcDisplayName = "PC";
    private String latencyText = "60Hz";
    private boolean gyroEnabled = true;

    // Output & rendered state
    private float steer = 0f;
    private boolean steeringTouch = false;
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

    // 16 buttons
    private final boolean[] buttons = new boolean[16];
    private final boolean[] displayedButtons = new boolean[16];

    // Colors
    private static final int COLOR_BG = 0xff07050b;
    private static final int COLOR_PANEL = 0xff120e1c;
    private static final int COLOR_PANEL_BORDER = 0xff292038;
    private static final int COLOR_ACCENT = 0xff8b5cf6;
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
    }

    public void setListener(Listener value) {
        this.listener = value;
    }

    public void setConnectionStatus(boolean isConnected, String pcName, String latency) {
        this.connected = isConnected;
        this.pcDisplayName = pcName == null || pcName.isEmpty() ? "PC" : pcName;
        this.latencyText = latency == null || latency.isEmpty() ? "60Hz" : latency;
        postInvalidateOnAnimation();
    }

    public void setMode(int mode) {
        this.currentMode = mode;
        clearInput();
        postInvalidateOnAnimation();
    }

    public int getMode() {
        return currentMode;
    }

    public void setGyroEnabled(boolean enabled) {
        this.gyroEnabled = enabled;
        postInvalidateOnAnimation();
    }

    public void setDisplayedGyroSteer(float gyroVal) {
        if (!steeringTouch && gyroEnabled) {
            this.steer = clamp(gyroVal, -1f, 1f);
            if (currentMode == MODE_GAMEPAD && Math.abs(lx) < 0.05f) {
                this.lx = this.steer;
            }
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();

        // Background
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COLOR_BG);
        canvas.drawRect(0, 0, w, h, paint);

        // Top HUD Bar
        drawTopHud(canvas, w, h);

        // Body: Racepad (Sim Racing/Truck) vs Gamepad (Steam/Xbox)
        if (currentMode == MODE_RACEPAD) {
            drawRacepad(canvas, w, h);
        } else {
            drawGamepad(canvas, w, h);
        }
    }

    private void drawTopHud(Canvas canvas, float w, float h) {
        float barH = dp(38);

        // Left Pill: Connection Badge
        float badgeW = dp(145);
        rectTmp.set(dp(12), dp(6), dp(12) + badgeW, dp(6) + barH);
        drawPill(canvas, rectTmp, COLOR_PANEL, COLOR_PANEL_BORDER);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(connected ? 0xff10b981 : 0xfff43f5e);
        canvas.drawCircle(dp(22), dp(6) + barH / 2f, dp(4), paint);
        String connStr = connected ? (pcDisplayName + " · " + latencyText) : "TAP TO CONNECT";
        drawText(canvas, connStr, dp(32), dp(6) + barH / 2f + dp(3.5f), 10.5f, connected ? 0xff34d399 : 0xffcbd5e1, Paint.Align.LEFT, true);

        // Center Pill: Mode Switcher (RACEPAD / GAMEPAD)
        float modeW = dp(140);
        float modeL = (w - modeW) / 2f;
        rectTmp.set(modeL, dp(6), modeL + modeW, dp(6) + barH);
        drawPill(canvas, rectTmp, 0xff1e182a, COLOR_ACCENT);
        String modeName = currentMode == MODE_RACEPAD ? "🏎 SIM RACEPAD" : "🎮 XBOX GAMEPAD";
        drawText(canvas, modeName, w / 2f, dp(6) + barH / 2f + dp(3.5f), 11.5f, 0xffe9d5ff, Paint.Align.CENTER, true);

        // Right Pills: Center & Setup
        float rightBtnW = dp(75);
        float setupL = w - dp(12) - rightBtnW;
        rectTmp.set(setupL, dp(6), setupL + rightBtnW, dp(6) + barH);
        drawPill(canvas, rectTmp, COLOR_PANEL, COLOR_PANEL_BORDER);
        drawText(canvas, "⚙ SETUP", setupL + rightBtnW / 2f, dp(6) + barH / 2f + dp(3.5f), 10.5f, 0xffcbd5e1, Paint.Align.CENTER, true);

        float centerL = setupL - dp(8) - rightBtnW;
        rectTmp.set(centerL, dp(6), centerL + rightBtnW, dp(6) + barH);
        drawPill(canvas, rectTmp, COLOR_PANEL, COLOR_PANEL_BORDER);
        drawText(canvas, "⌖ CENTER", centerL + rightBtnW / 2f, dp(6) + barH / 2f + dp(3.5f), 10.5f, 0xffcbd5e1, Paint.Align.CENTER, true);
    }

    private void drawRacepad(Canvas canvas, float w, float h) {
        float topY = dp(48);

        // --- PADDLES: GEAR DOWN (LB / btn 4 & PADDLE_L / btn 11) & GEAR UP (RB / btn 5 & PADDLE_R / btn 12) ---
        float paddleW = w * 0.22f;
        float paddleH = dp(34);
        boolean gearDown = displayedButtons[4] || displayedButtons[11];
        rectTmp.set(dp(12), topY, dp(12) + paddleW, topY + paddleH);
        drawPill(canvas, rectTmp, gearDown ? COLOR_ACCENT : COLOR_PANEL, gearDown ? 0xffffffff : COLOR_PANEL_BORDER);
        drawText(canvas, "◀ GEAR DOWN (LB)", dp(12) + paddleW / 2f, topY + paddleH / 2f + dp(3.5f), 10.5f, gearDown ? 0xffffffff : 0xffc4b5fd, Paint.Align.CENTER, true);

        boolean gearUp = displayedButtons[5] || displayedButtons[12];
        rectTmp.set(w - dp(12) - paddleW, topY, w - dp(12), topY + paddleH);
        drawPill(canvas, rectTmp, gearUp ? COLOR_ACCENT : COLOR_PANEL, gearUp ? 0xffffffff : COLOR_PANEL_BORDER);
        drawText(canvas, "GEAR UP ▶ (RB)", w - dp(12) - paddleW / 2f, topY + paddleH / 2f + dp(3.5f), 10.5f, gearUp ? 0xffffffff : 0xffc4b5fd, Paint.Align.CENTER, true);

        // --- STEERING WHEEL & ARC GAUGE ---
        float cx = w * 0.23f;
        float cy = (h + topY + paddleH) / 2f - dp(16);
        float radius = Math.min(w * 0.19f, (h - topY - paddleH) * 0.40f);
        drawSteeringGauge(canvas, cx, cy, radius, steer);

        // 4 Simulator Quick Toggles under Wheel: LIGHTS(8), HORN(9), WIPERS(13), IGNITION(12)
        float subRowY = h - dp(32);
        float subW = (w * 0.44f - dp(24)) / 4f;
        String[] simLabels = {"LIGHTS", "HORN", "WIPERS", "IGN"};
        int[] simIndices = {8, 9, 13, 12};
        for (int i = 0; i < 4; i++) {
            float sx = dp(12) + i * (subW + dp(4));
            rectTmp.set(sx, subRowY, sx + subW, subRowY + dp(26));
            boolean active = displayedButtons[simIndices[i]];
            drawPill(canvas, rectTmp, active ? COLOR_ACCENT : COLOR_PANEL, active ? 0xffffffff : COLOR_PANEL_BORDER);
            drawText(canvas, simLabels[i], sx + subW / 2f, subRowY + dp(16), 8.5f, active ? 0xffffffff : 0xff94a3b8, Paint.Align.CENTER, true);
        }

        // --- 3 SIMULATOR PEDALS: CLUTCH, BRAKE, GAS ---
        float pedalTop = topY + dp(8);
        float pedalBottom = h - dp(42);
        float pedalW = w * 0.082f;

        // Clutch Pedal (Amber gradient)
        float clutchL = w * 0.47f;
        float clutchR = clutchL + pedalW;
        drawPedal(canvas, clutchL, clutchR, pedalTop, pedalBottom, "CLUTCH", clutch, COLOR_CLUTCH, 0xffb45309);

        // Brake Pedal (Rose gradient)
        float brakeL = clutchR + dp(10);
        float brakeR = brakeL + pedalW;
        drawPedal(canvas, brakeL, brakeR, pedalTop, pedalBottom, "BRAKE", brake, COLOR_BRAKE, 0xffbe123c);

        // Gas / Throttle Pedal (Emerald gradient)
        float gasL = brakeR + dp(10);
        float gasR = gasL + pedalW;
        drawPedal(canvas, gasL, gasR, pedalTop, pedalBottom, "GAS", throttle, COLOR_THROTTLE, 0xff047857);

        // Bottom Simulator Pills below Pedals: HANDBRAKE(15), NITRO(13), CRUISE(14), PIT(8)
        float pbSubW = (gasR - clutchL - dp(9)) / 4f;
        String[] pbLabels = {"HANDBRAKE", "NITRO", "CRUISE", "PIT"};
        int[] pbIndices = {15, 13, 14, 8};
        for (int i = 0; i < 4; i++) {
            float px = clutchL + i * (pbSubW + dp(3));
            rectTmp.set(px, subRowY, px + pbSubW, subRowY + dp(26));
            boolean active = displayedButtons[pbIndices[i]];
            drawPill(canvas, rectTmp, active ? 0xffe11d48 : COLOR_PANEL, active ? 0xffffffff : COLOR_PANEL_BORDER);
            drawText(canvas, pbLabels[i], px + pbSubW / 2f, subRowY + dp(16), 8f, active ? 0xffffffff : 0xfffda4af, Paint.Align.CENTER, true);
        }

        // --- RIGHT CLUSTER: ACTION BUTTONS (A, B, X, Y) + CAM(6) / MAP(7) ---
        float diamondCx = w * 0.88f;
        float diamondCy = cy;
        float btnRadius = Math.min(w * 0.048f, h * 0.085f);
        float offset = btnRadius * 1.35f;

        drawCircleButton(canvas, diamondCx, diamondCy - offset, btnRadius, "Y", 3, 0xfff59e0b);
        drawCircleButton(canvas, diamondCx - offset, diamondCy, btnRadius, "X", 2, 0xff06b6d4);
        drawCircleButton(canvas, diamondCx + offset, diamondCy, btnRadius, "B", 1, 0xfff43f5e);
        drawCircleButton(canvas, diamondCx, diamondCy + offset, btnRadius, "A", 0, 0xff10b981);

        // Cam and Map pills above action buttons
        float camW = dp(46);
        rectTmp.set(diamondCx - camW - dp(4), topY + dp(4), diamondCx - dp(4), topY + dp(30));
        drawPill(canvas, rectTmp, displayedButtons[6] ? COLOR_ACCENT : COLOR_PANEL, COLOR_PANEL_BORDER);
        drawText(canvas, "CAM", diamondCx - camW / 2f - dp(4), topY + dp(20), 9f, 0xffcbd5e1, Paint.Align.CENTER, true);

        rectTmp.set(diamondCx + dp(4), topY + dp(4), diamondCx + camW + dp(4), topY + dp(30));
        drawPill(canvas, rectTmp, displayedButtons[7] ? COLOR_ACCENT : COLOR_PANEL, COLOR_PANEL_BORDER);
        drawText(canvas, "MAP", diamondCx + camW / 2f + dp(4), topY + dp(20), 9f, 0xffcbd5e1, Paint.Align.CENTER, true);

        // Mini D-Pad for menus below diamond
        drawMiniDpad(canvas, diamondCx, h - dp(24), dp(18));
    }

    private void drawGamepad(Canvas canvas, float w, float h) {
        float topY = dp(48);

        // --- SHOULDER TRIGGERS & BUMPERS ---
        // Left: LB & LT
        float shoulderW = w * 0.17f;
        float shoulderH = dp(32);
        boolean lb = displayedButtons[4];
        rectTmp.set(dp(16), topY, dp(16) + shoulderW, topY + shoulderH);
        drawPill(canvas, rectTmp, lb ? COLOR_ACCENT : COLOR_PANEL, lb ? 0xffffffff : COLOR_PANEL_BORDER);
        drawText(canvas, "LB (BUMPER)", dp(16) + shoulderW / 2f, topY + shoulderH / 2f + dp(3.5f), 10f, lb ? 0xffffffff : 0xffc4b5fd, Paint.Align.CENTER, true);

        float triggerW = w * 0.13f;
        float ltLeft = dp(16) + shoulderW + dp(8);
        drawTriggerPill(canvas, ltLeft, topY, ltLeft + triggerW, topY + shoulderH, "LT", lt, COLOR_BRAKE);

        // Right: RB & RT
        boolean rb = displayedButtons[5];
        float rbLeft = w - dp(16) - shoulderW;
        rectTmp.set(rbLeft, topY, w - dp(16), topY + shoulderH);
        drawPill(canvas, rectTmp, rb ? COLOR_ACCENT : COLOR_PANEL, rb ? 0xffffffff : COLOR_PANEL_BORDER);
        drawText(canvas, "RB (BUMPER)", rbLeft + shoulderW / 2f, topY + shoulderH / 2f + dp(3.5f), 10f, rb ? 0xffffffff : 0xffc4b5fd, Paint.Align.CENTER, true);

        float rtRight = rbLeft - dp(8);
        drawTriggerPill(canvas, rtRight - triggerW, topY, rtRight, topY + shoulderH, "RT", rt, COLOR_THROTTLE);

        // --- LEFT THUMBSTICK (LX, LY) ---
        float stickR = Math.min(w * 0.11f, h * 0.22f);
        float stickCx = w * 0.22f;
        float stickCy = (h + topY) / 2f - dp(10);
        drawStick(canvas, stickCx, stickCy, stickR, lx, ly, "LEFT STICK (L3)", displayedButtons[9]);

        // Left D-Pad below Stick
        float dpadCx = stickCx;
        float dpadCy = h - dp(40);
        drawMiniDpad(canvas, dpadCx, dpadCy, dp(26));

        // --- CENTER BUTTONS: BACK (6), GUIDE (8), START (7) ---
        float centerW = dp(54);
        float centerH = dp(28);
        float cyCenter = (h + topY) / 2f;

        // Back / View (6)
        rectTmp.set(w / 2f - centerW * 1.5f - dp(8), cyCenter - centerH / 2f, w / 2f - centerW * 0.5f - dp(8), cyCenter + centerH / 2f);
        drawPill(canvas, rectTmp, displayedButtons[6] ? COLOR_ACCENT : COLOR_PANEL, COLOR_PANEL_BORDER);
        drawText(canvas, "VIEW", rectTmp.centerX(), rectTmp.centerY() + dp(3.5f), 9.5f, 0xffcbd5e1, Paint.Align.CENTER, true);

        // Guide / Home (8)
        float guideR = dp(16);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(displayedButtons[8] ? 0xff10b981 : COLOR_PANEL);
        canvas.drawCircle(w / 2f, cyCenter, guideR, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(COLOR_PANEL_BORDER);
        paint.setStrokeWidth(dp(1.5f));
        canvas.drawCircle(w / 2f, cyCenter, guideR, paint);
        drawText(canvas, "⌁", w / 2f, cyCenter + dp(5), 14f, 0xffa78bfa, Paint.Align.CENTER, true);

        // Start / Menu (7)
        rectTmp.set(w / 2f + centerW * 0.5f + dp(8), cyCenter - centerH / 2f, w / 2f + centerW * 1.5f + dp(8), cyCenter + centerH / 2f);
        drawPill(canvas, rectTmp, displayedButtons[7] ? COLOR_ACCENT : COLOR_PANEL, COLOR_PANEL_BORDER);
        drawText(canvas, "MENU", rectTmp.centerX(), rectTmp.centerY() + dp(3.5f), 9.5f, 0xffcbd5e1, Paint.Align.CENTER, true);

        // --- RIGHT THUMBSTICK (RX, RY) ---
        float rStickCx = w * 0.65f;
        float rStickCy = h - dp(56);
        drawStick(canvas, rStickCx, rStickCy, stickR * 0.85f, rx, ry, "RIGHT STICK (R3)", displayedButtons[10]);

        // --- ABXY DIAMOND CLUSTER ---
        float diamondCx = w * 0.84f;
        float diamondCy = (h + topY) / 2f - dp(18);
        float btnRadius = Math.min(w * 0.052f, h * 0.09f);
        float offset = btnRadius * 1.35f;

        drawCircleButton(canvas, diamondCx, diamondCy - offset, btnRadius, "Y", 3, 0xfff59e0b);
        drawCircleButton(canvas, diamondCx - offset, diamondCy, btnRadius, "X", 2, 0xff06b6d4);
        drawCircleButton(canvas, diamondCx + offset, diamondCy, btnRadius, "B", 1, 0xfff43f5e);
        drawCircleButton(canvas, diamondCx, diamondCy + offset, btnRadius, "A", 0, 0xff10b981);
    }

    private void drawTriggerPill(Canvas canvas, float l, float t, float r, float b, String label, float value, int color) {
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
        drawText(canvas, label + " " + pct + "%", (l + r) / 2f, (t + b) / 2f + dp(3.5f), 9.5f, 0xffffffff, Paint.Align.CENTER, true);
    }

    private void drawStick(Canvas canvas, float cx, float cy, float radius, float valX, float valY, String label, boolean click) {
        // Outer Base Ring
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COLOR_PANEL);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.8f));
        paint.setColor(click ? COLOR_ACCENT : COLOR_PANEL_BORDER);
        canvas.drawCircle(cx, cy, radius, paint);

        // Crosshairs
        paint.setColor(0xff231b32);
        paint.setStrokeWidth(dp(1.2f));
        canvas.drawLine(cx - radius * 0.7f, cy, cx + radius * 0.7f, cy, paint);
        canvas.drawLine(cx, cy - radius * 0.7f, cx, cy + radius * 0.7f, paint);

        // Floating Stick Knob
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

        drawText(canvas, label, cx, cy + radius + dp(12), 8.5f, COLOR_TEXT_MUTED, Paint.Align.CENTER, true);
    }

    private void drawMiniDpad(Canvas canvas, float cx, float cy, float span) {
        float armW = span * 0.7f;
        float armH = span * 0.6f;

        // Up
        rectTmp.set(cx - armW / 2f, cy - span, cx + armW / 2f, cy - span + armH);
        drawPill(canvas, rectTmp, dpadUp ? COLOR_ACCENT : COLOR_PANEL, dpadUp ? 0xffffffff : COLOR_PANEL_BORDER);
        drawText(canvas, "▲", cx, cy - span + armH * 0.7f, 9f, dpadUp ? 0xffffffff : 0xff94a3b8, Paint.Align.CENTER, true);

        // Down
        rectTmp.set(cx - armW / 2f, cy + span - armH, cx + armW / 2f, cy + span);
        drawPill(canvas, rectTmp, dpadDown ? COLOR_ACCENT : COLOR_PANEL, dpadDown ? 0xffffffff : COLOR_PANEL_BORDER);
        drawText(canvas, "▼", cx, cy + span - armH * 0.25f, 9f, dpadDown ? 0xffffffff : 0xff94a3b8, Paint.Align.CENTER, true);

        // Left
        rectTmp.set(cx - span, cy - armW / 2f, cx - span + armH, cy + armW / 2f);
        drawPill(canvas, rectTmp, dpadLeft ? COLOR_ACCENT : COLOR_PANEL, dpadLeft ? 0xffffffff : COLOR_PANEL_BORDER);
        drawText(canvas, "◀", cx - span + armH * 0.5f, cy + dp(3), 9f, dpadLeft ? 0xffffffff : 0xff94a3b8, Paint.Align.CENTER, true);

        // Right
        rectTmp.set(cx + span - armH, cy - armW / 2f, cx + span, cy + armW / 2f);
        drawPill(canvas, rectTmp, dpadRight ? COLOR_ACCENT : COLOR_PANEL, dpadRight ? 0xffffffff : COLOR_PANEL_BORDER);
        drawText(canvas, "▶", cx + span - armH * 0.5f, cy + dp(3), 9f, dpadRight ? 0xffffffff : 0xff94a3b8, Paint.Align.CENTER, true);
    }

    private void drawSteeringGauge(Canvas canvas, float cx, float cy, float radius, float steerVal) {
        rectTmp.set(cx - radius, cy - radius, cx + radius, cy + radius);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(6));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0xff1f192c);
        canvas.drawArc(rectTmp, 135, 270, false, paint);

        paint.setColor(COLOR_ACCENT);
        float sweep = steerVal * 135f;
        canvas.drawArc(rectTmp, 270, sweep, false, paint);

        // Graduation Ticks
        for (int deg = -90; deg <= 90; deg += 30) {
            double rad = Math.toRadians(270 + deg * 1.5);
            float inner = radius - (deg == 0 ? dp(14) : dp(8));
            float outer = radius + dp(4);
            paint.setStrokeWidth(deg == 0 ? dp(2.5f) : dp(1.5f));
            paint.setColor(deg == 0 ? 0xfff59e0b : 0xff473b5e);
            canvas.drawLine(cx + (float) Math.cos(rad) * inner, cy + (float) Math.sin(rad) * inner,
                    cx + (float) Math.cos(rad) * outer, cy + (float) Math.sin(rad) * outer, paint);
        }

        // Inner Wheel Body
        canvas.save();
        float rotDeg = steerVal * 45f;
        canvas.rotate(rotDeg, cx, cy);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(10));
        paint.setColor(0xff1e172a);
        canvas.drawCircle(cx, cy, radius * 0.76f, paint);

        paint.setStrokeWidth(dp(1.8f));
        paint.setColor(steeringTouch ? 0xffa855f7 : 0xff4f4066);
        canvas.drawCircle(cx, cy, radius * 0.76f + dp(5), paint);

        // Top Stripe
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xfff59e0b);
        rectTmp.set(cx - dp(4), cy - radius * 0.76f - dp(5), cx + dp(4), cy - radius * 0.76f + dp(5));
        canvas.drawRoundRect(rectTmp, dp(2), dp(2), paint);

        // Spokes
        paint.setColor(0xff292038);
        paint.setStrokeWidth(dp(6));
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(cx - radius * 0.65f, cy, cx + radius * 0.65f, cy, paint);
        canvas.drawLine(cx, cy, cx, cy + radius * 0.65f, paint);

        // Hub
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COLOR_PANEL);
        canvas.drawCircle(cx, cy, radius * 0.32f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(COLOR_ACCENT);
        canvas.drawCircle(cx, cy, radius * 0.32f, paint);

        canvas.restore();

        int angle = Math.round(steerVal * 45f);
        String angleStr = (angle > 0 ? "+" : "") + angle + "°";
        drawText(canvas, angleStr, cx, cy + dp(6), 18f, 0xffffffff, Paint.Align.CENTER, true);
    }

    private void drawPedal(Canvas canvas, float left, float right, float top, float bottom, String label, float value, int colorTop, int colorBottom) {
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

        drawText(canvas, label, cx, top + dp(14), 9.5f, value > 0.05f ? 0xffffffff : COLOR_TEXT_MUTED, Paint.Align.CENTER, true);
        int pct = Math.round(value * 100);
        drawText(canvas, pct + "%", cx, bottom - dp(8), 11.5f, 0xffffffff, Paint.Align.CENTER, true);
    }

    private void drawCircleButton(Canvas canvas, float cx, float cy, float radius, String label, int index, int glowColor) {
        boolean pressed = index < displayedButtons.length && displayedButtons[index];

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pressed ? glowColor : COLOR_PANEL);
        canvas.drawCircle(cx, cy, radius, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(pressed ? 0xffffffff : COLOR_PANEL_BORDER);
        canvas.drawCircle(cx, cy, radius, paint);

        drawText(canvas, label, cx, cy + dp(5.5f), 15f, pressed ? 0xffffffff : 0xffe2e8f0, Paint.Align.CENTER, true);
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
        paint.setTypeface(android.graphics.Typeface.create(bold ? "sans-serif-medium" : "sans-serif", bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
        canvas.drawText(text, x, y, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int actionIndex = event.getActionIndex();
        float w = getWidth(), h = getHeight();

        if (action == MotionEvent.ACTION_CANCEL) {
            clearInput();
            return true;
        }

        // Single-tap triggers for HUD top bar
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            float tx = event.getX(actionIndex);
            float ty = event.getY(actionIndex);
            if (ty <= dp(44)) {
                handleHudTouch(tx, ty, w);
                return true;
            }
        }

        boolean lifted = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP;

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_MOVE || lifted) {
            float nextSteer = 0f;
            boolean nextSteeringTouch = false;
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
            int liftedIndex = event.getActionIndex();

            for (int i = 0, used = 0; i < event.getPointerCount(); i++) {
                if (lifted && i == liftedIndex) continue;
                if (used >= count) break;
                used++;

                float x = event.getX(i);
                float y = event.getY(i);
                if (y <= dp(44)) continue;

                if (currentMode == MODE_RACEPAD) {
                    // Paddles: Gear Down (LB: 4 & PADDLE_L: 11), Gear Up (RB: 5 & PADDLE_R: 12)
                    float paddleW = w * 0.22f;
                    float paddleH = dp(38);
                    if (y >= dp(48) && y <= dp(48) + paddleH) {
                        if (x <= dp(12) + paddleW) {
                            nextButtons[4] = true;
                            nextButtons[11] = true;
                            continue;
                        } else if (x >= w - dp(12) - paddleW) {
                            nextButtons[5] = true;
                            nextButtons[12] = true;
                            continue;
                        }
                    }

                    // Cam(6) / Map(7)
                    float diamondCx = w * 0.88f;
                    float camW = dp(46);
                    if (y >= dp(48) && y <= dp(48) + dp(32)) {
                        if (x >= diamondCx - camW - dp(8) && x <= diamondCx) {
                            nextButtons[6] = true;
                            continue;
                        } else if (x >= diamondCx && x <= diamondCx + camW + dp(8)) {
                            nextButtons[7] = true;
                            continue;
                        }
                    }

                    // Steering Wheel area
                    float cx = w * 0.23f;
                    float cy = (h + dp(48) + dp(34)) / 2f - dp(16);
                    float radius = Math.min(w * 0.19f, (h - dp(48) - dp(34)) * 0.40f);
                    if (x < w * 0.44f && y < h - dp(40) && Math.hypot(x - cx, y - cy) <= radius * 1.35f) {
                        nextSteeringTouch = true;
                        nextSteer = clamp((x - cx) / (radius * 0.75f), -1f, 1f);
                        continue;
                    }

                    // Sub-toggles under Wheel: LIGHTS(8), HORN(9), WIPERS(13), IGN(12)
                    if (y >= h - dp(36) && x <= w * 0.44f) {
                        float subW = (w * 0.44f - dp(24)) / 4f;
                        int[] simIndices = {8, 9, 13, 12};
                        for (int s = 0; s < 4; s++) {
                            float sx = dp(12) + s * (subW + dp(4));
                            if (x >= sx && x <= sx + subW) {
                                nextButtons[simIndices[s]] = true;
                                break;
                            }
                        }
                        continue;
                    }

                    // Pedals: Clutch, Brake, Gas
                    float pedalTop = dp(48) + dp(8);
                    float pedalBottom = h - dp(42);
                    float pedalW = w * 0.082f;
                    float clutchL = w * 0.47f;
                    float clutchR = clutchL + pedalW;
                    float brakeL = clutchR + dp(10);
                    float brakeR = brakeL + pedalW;
                    float gasL = brakeR + dp(10);
                    float gasR = gasL + pedalW;

                    if (y >= pedalTop - dp(8) && y <= pedalBottom + dp(8)) {
                        if (x >= clutchL - dp(6) && x <= clutchR + dp(6)) {
                            nextClutch = Math.max(nextClutch, clamp((pedalBottom - y) / (pedalBottom - pedalTop), 0.2f, 1.0f));
                            continue;
                        } else if (x >= brakeL - dp(6) && x <= brakeR + dp(6)) {
                            nextBrake = Math.max(nextBrake, clamp((pedalBottom - y) / (pedalBottom - pedalTop), 0.2f, 1.0f));
                            continue;
                        } else if (x >= gasL - dp(6) && x <= gasR + dp(6)) {
                            nextThrottle = Math.max(nextThrottle, clamp((pedalBottom - y) / (pedalBottom - pedalTop), 0.2f, 1.0f));
                            continue;
                        }
                    }

                    // Bottom Simulator Pills below Pedals: HANDBRAKE(15), NITRO(13), CRUISE(14), PIT(8)
                    if (y >= h - dp(36) && x >= clutchL && x <= gasR) {
                        float pbSubW = (gasR - clutchL - dp(9)) / 4f;
                        int[] pbIndices = {15, 13, 14, 8};
                        for (int p = 0; p < 4; p++) {
                            float px = clutchL + p * (pbSubW + dp(3));
                            if (x >= px && x <= px + pbSubW) {
                                nextButtons[pbIndices[p]] = true;
                                if (p == 0) nextHandbrake = 1.0f;
                                break;
                            }
                        }
                        continue;
                    }

                    // Mini D-Pad under Diamond
                    if (y >= h - dp(48) && x >= diamondCx - dp(32) && x <= diamondCx + dp(32)) {
                        float dy = y - (h - dp(24));
                        float dx = x - diamondCx;
                        if (Math.abs(dy) > Math.abs(dx)) {
                            if (dy < 0) nextDpadUp = true; else nextDpadDown = true;
                        } else {
                            if (dx < 0) nextDpadLeft = true; else nextDpadRight = true;
                        }
                        continue;
                    }

                    // Diamond Buttons (A:0, B:1, X:2, Y:3)
                    int btnIdx = hitDiamondButton(x, y, w, h);
                    if (btnIdx >= 0) {
                        nextButtons[btnIdx] = true;
                    }
                } else {
                    // GAMEPAD MODE
                    float shoulderW = w * 0.17f;
                    float shoulderH = dp(36);
                    float triggerW = w * 0.13f;

                    // LB & LT
                    if (y >= dp(48) && y <= dp(48) + shoulderH) {
                        if (x <= dp(16) + shoulderW) {
                            nextButtons[4] = true; // LB
                            continue;
                        } else if (x <= dp(16) + shoulderW + triggerW + dp(8)) {
                            nextLt = 1.0f; // LT
                            continue;
                        } else if (x >= w - dp(16) - shoulderW) {
                            nextButtons[5] = true; // RB
                            continue;
                        } else if (x >= w - dp(16) - shoulderW - triggerW - dp(8)) {
                            nextRt = 1.0f; // RT
                            continue;
                        }
                    }

                    // Left Stick (LX, LY)
                    float stickR = Math.min(w * 0.11f, h * 0.22f);
                    float stickCx = w * 0.22f;
                    float stickCy = (h + dp(48)) / 2f - dp(10);
                    float distL = (float) Math.hypot(x - stickCx, y - stickCy);
                    if (distL <= stickR * 1.35f) {
                        if (distL <= dp(16)) {
                            nextButtons[9] = true; // L3 click
                        }
                        nextLx = clamp((x - stickCx) / stickR, -1f, 1f);
                        nextLy = clamp((y - stickCy) / stickR, -1f, 1f);
                        continue;
                    }

                    // D-Pad below Left Stick
                    float dpadCx = stickCx;
                    float dpadCy = h - dp(40);
                    float distDpad = (float) Math.hypot(x - dpadCx, y - dpadCy);
                    if (distDpad <= dp(45)) {
                        float dx = x - dpadCx;
                        float dy = y - dpadCy;
                        if (Math.abs(dx) > Math.abs(dy)) {
                            if (dx < 0) nextDpadLeft = true; else nextDpadRight = true;
                        } else {
                            if (dy < 0) nextDpadUp = true; else nextDpadDown = true;
                        }
                        continue;
                    }

                    // Center Buttons: Back/View(6), Guide(8), Start/Menu(7)
                    float cyCenter = (h + dp(48)) / 2f;
                    if (Math.abs(y - cyCenter) <= dp(24)) {
                        if (x >= w / 2f - dp(70) && x <= w / 2f - dp(18)) {
                            nextButtons[6] = true;
                            continue;
                        } else if (Math.hypot(x - w / 2f, y - cyCenter) <= dp(18)) {
                            nextButtons[8] = true; // Guide
                            continue;
                        } else if (x >= w / 2f + dp(18) && x <= w / 2f + dp(70)) {
                            nextButtons[7] = true;
                            continue;
                        }
                    }

                    // Right Stick (RX, RY)
                    float rStickCx = w * 0.65f;
                    float rStickCy = h - dp(56);
                    float distR = (float) Math.hypot(x - rStickCx, y - rStickCy);
                    if (distR <= stickR * 1.35f) {
                        if (distR <= dp(16)) {
                            nextButtons[10] = true; // R3 click
                        }
                        nextRx = clamp((x - rStickCx) / (stickR * 0.85f), -1f, 1f);
                        nextRy = clamp((y - rStickCy) / (stickR * 0.85f), -1f, 1f);
                        continue;
                    }

                    // ABXY Diamond
                    int btnIdx = hitDiamondButton(x, y, w, h);
                    if (btnIdx >= 0) {
                        nextButtons[btnIdx] = true;
                    }
                }
            }

            // Haptic trigger on newly pressed button
            for (int b = 0; b < 16; b++) {
                if (nextButtons[b] && !buttons[b]) {
                    triggerHaptic();
                    break;
                }
            }

            this.steer = nextSteeringTouch ? nextSteer : (gyroEnabled ? this.steer : nextSteer);
            this.steeringTouch = nextSteeringTouch;
            this.throttle = nextThrottle;
            this.brake = nextBrake;
            this.clutch = nextClutch;
            this.handbrake = nextHandbrake;

            this.lx = (currentMode == MODE_GAMEPAD && gyroEnabled && Math.abs(nextLx) < 0.05f) ? this.steer : nextLx;
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

            if (listener != null) {
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
            invalidate();
            return true;
        }

        return true;
    }

    private void handleHudTouch(float x, float y, float w) {
        float barH = dp(38);
        triggerHaptic();

        // Left Pill: Connection
        float badgeW = dp(145);
        if (x <= dp(12) + badgeW) {
            if (!connected && listener != null) {
                listener.onOpenSetup();
            }
            return;
        }

        // Center Pill: Mode Switch
        float modeW = dp(140);
        float modeL = (w - modeW) / 2f;
        if (x >= modeL && x <= modeL + modeW) {
            currentMode = currentMode == MODE_RACEPAD ? MODE_GAMEPAD : MODE_RACEPAD;
            clearInput();
            if (listener != null) listener.onToggleMode();
            invalidate();
            return;
        }

        // Right Pills: Setup & Center
        float rightBtnW = dp(75);
        float setupL = w - dp(12) - rightBtnW;
        if (x >= setupL) {
            if (listener != null) listener.onOpenSetup();
            return;
        }

        float centerL = setupL - dp(8) - rightBtnW;
        if (x >= centerL && x < setupL) {
            if (listener != null) listener.onCalibrate();
        }
    }

    private int hitDiamondButton(float x, float y, float w, float h) {
        float diamondCx = currentMode == MODE_RACEPAD ? w * 0.88f : w * 0.84f;
        float cy = currentMode == MODE_RACEPAD ? (h + dp(48) + dp(34)) / 2f - dp(16) : (h + dp(48)) / 2f - dp(18);
        float btnRadius = Math.min(w * 0.052f, h * 0.09f);
        float offset = btnRadius * 1.35f;

        float[][] centers = {
                {diamondCx, cy + offset}, // A (0)
                {diamondCx + offset, cy}, // B (1)
                {diamondCx - offset, cy}, // X (2)
                {diamondCx, cy - offset}  // Y (3)
        };

        float hitR = btnRadius * 1.35f;
        for (int i = 0; i < centers.length; i++) {
            float dx = x - centers[i][0];
            float dy = y - centers[i][1];
            if (dx * dx + dy * dy <= hitR * hitR) {
                return i;
            }
        }
        return -1;
    }

    private void triggerHaptic() {
        try {
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(12);
                }
            } else {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            }
        } catch (Exception ignored) {}
    }

    private void clearInput() {
        steer = 0f;
        steeringTouch = false;
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
