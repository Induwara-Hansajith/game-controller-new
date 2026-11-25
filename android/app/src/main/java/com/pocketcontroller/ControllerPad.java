package com.pocketcontroller;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
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
        void onInputChanged(float steer, boolean steeringTouch, float throttle, float brake, boolean[] buttons);
        void onOpenSetup();
        void onToggleMode();
        void onCalibrate();
        void onToggleGyro();
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rectTmp = new RectF();
    private final Path pathTmp = new Path();

    private Listener listener;
    private Vibrator vibrator;

    private int currentMode = MODE_RACEPAD;
    private boolean connected = false;
    private String pcDisplayName = "PC";
    private String latencyText = "60Hz";
    private boolean gyroEnabled = true;

    // Output state
    private float manualSteer = 0f;
    private boolean steeringTouch = false;
    private float throttle = 0f;
    private float brake = 0f;
    private final boolean[] buttons = new boolean[8];

    // Visual rendered state
    private float displayedSteer = 0f;
    private float displayedThrottle = 0f;
    private float displayedBrake = 0f;
    private final boolean[] displayedButtons = new boolean[8];

    // Colors
    private static final int COLOR_BG = 0xff07050b;
    private static final int COLOR_PANEL = 0xff120e1c;
    private static final int COLOR_PANEL_BORDER = 0xff292038;
    private static final int COLOR_ACCENT = 0xff8b5cf6;
    private static final int COLOR_ACCENT_GLOW = 0xffa855f7;
    private static final int COLOR_THROTTLE = 0xff10b981;
    private static final int COLOR_BRAKE = 0xfff43f5e;
    private static final int COLOR_TEXT_MUTED = 0xff94a3b8;
    private static final int COLOR_TEXT_LIGHT = 0xffe2e8f0;

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

    public void setDisplayedInput(float steer, float gas, float stop, boolean[] pressed) {
        this.displayedSteer = clamp(steer, -1f, 1f);
        this.displayedThrottle = clamp(gas, 0f, 1f);
        this.displayedBrake = clamp(stop, 0f, 1f);
        if (pressed != null) {
            System.arraycopy(pressed, 0, this.displayedButtons, 0, Math.min(pressed.length, 8));
        }
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();

        // 1. Deep OLED Background
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COLOR_BG);
        canvas.drawRect(0, 0, w, h, paint);

        // 2. Draw Top HUD Bar
        drawTopHud(canvas, w, h);

        // 3. Draw Body based on Mode
        if (currentMode == MODE_RACEPAD) {
            drawRacepad(canvas, w, h);
        } else {
            drawGamepad(canvas, w, h);
        }
    }

    private void drawTopHud(Canvas canvas, float w, float h) {
        float barH = dp(38);

        // Left Pill: Connection Badge
        float badgeW = dp(140);
        rectTmp.set(dp(12), dp(6), dp(12) + badgeW, dp(6) + barH);
        drawPill(canvas, rectTmp, COLOR_PANEL, COLOR_PANEL_BORDER);
        // Pulse Dot
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(connected ? 0xff10b981 : 0xfff43f5e);
        canvas.drawCircle(dp(22), dp(6) + barH / 2f, dp(4), paint);
        // Text
        String connStr = connected ? (pcDisplayName + " · " + latencyText) : "TAP TO CONNECT";
        drawText(canvas, connStr, dp(32), dp(6) + barH / 2f + dp(3.5f), 10.5f, connected ? 0xff34d399 : 0xffcbd5e1, Paint.Align.LEFT, true);

        // Center Pill: Mode Switcher (RACEPAD / GAMEPAD)
        float modeW = dp(130);
        float modeL = (w - modeW) / 2f;
        rectTmp.set(modeL, dp(6), modeL + modeW, dp(6) + barH);
        drawPill(canvas, rectTmp, 0xff1e182a, COLOR_ACCENT);
        String modeName = currentMode == MODE_RACEPAD ? "🏎 RACEPAD" : "🎮 GAMEPAD";
        drawText(canvas, modeName, w / 2f, dp(6) + barH / 2f + dp(3.5f), 11.5f, 0xffe9d5ff, Paint.Align.CENTER, true);

        // Right Pills: Calibration (Center) & Setup
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

        // --- TOP PADDLES: GEAR DOWN (LB / btn 4) & GEAR UP (RB / btn 5) ---
        float paddleW = w * 0.22f;
        float paddleH = dp(34);
        // Left Paddle (Gear -)
        boolean gearDown = displayedButtons[4];
        rectTmp.set(dp(12), topY, dp(12) + paddleW, topY + paddleH);
        drawPill(canvas, rectTmp, gearDown ? COLOR_ACCENT : COLOR_PANEL, gearDown ? 0xffffffff : COLOR_PANEL_BORDER);
        drawText(canvas, "◀ GEAR DOWN (LB)", dp(12) + paddleW / 2f, topY + paddleH / 2f + dp(3.5f), 10.5f, gearDown ? 0xffffffff : 0xffc4b5fd, Paint.Align.CENTER, true);

        // Right Paddle (Gear +)
        boolean gearUp = displayedButtons[5];
        rectTmp.set(w - dp(12) - paddleW, topY, w - dp(12), topY + paddleH);
        drawPill(canvas, rectTmp, gearUp ? COLOR_ACCENT : COLOR_PANEL, gearUp ? 0xffffffff : COLOR_PANEL_BORDER);
        drawText(canvas, "GEAR UP ▶ (RB)", w - dp(12) - paddleW / 2f, topY + paddleH / 2f + dp(3.5f), 10.5f, gearUp ? 0xffffffff : 0xffc4b5fd, Paint.Align.CENTER, true);

        // --- LEFT / CENTER: STEERING WHEEL & ARC GAUGE ---
        float cx = w * 0.25f;
        float cy = (h + topY + paddleH) / 2f - dp(4);
        float radius = Math.min(w * 0.21f, (h - topY - paddleH) * 0.44f);

        drawSteeringGauge(canvas, cx, cy, radius, displayedSteer);

        // --- PEDALS: BRAKE & THROTTLE ---
        float pedalTop = topY + dp(12);
        float pedalBottom = h - dp(38);
        float pedalW = w * 0.11f;

        // Brake Pedal (Rose gradient)
        float brakeLeft = w * 0.52f;
        float brakeRight = brakeLeft + pedalW;
        drawPedal(canvas, brakeLeft, brakeRight, pedalTop, pedalBottom, "BRAKE", displayedBrake, COLOR_BRAKE, 0xffbe123c);

        // Throttle Pedal (Emerald/Cyan gradient)
        float gasLeft = brakeRight + dp(14);
        float gasRight = gasLeft + pedalW;
        drawPedal(canvas, gasLeft, gasRight, pedalTop, pedalBottom, "GAS", displayedThrottle, COLOR_THROTTLE, 0xff047857);

        // Quick Drift Handbrake (Button 6 / LT) below pedals
        float subBtnW = (gasRight - brakeLeft - dp(10)) / 2f;
        float subBtnH = dp(28);
        float subBtnY = h - dp(32);

        boolean handbrake = displayedButtons[6];
        rectTmp.set(brakeLeft, subBtnY, brakeLeft + subBtnW, subBtnY + subBtnH);
        drawPill(canvas, rectTmp, handbrake ? 0xffe11d48 : COLOR_PANEL, handbrake ? 0xffffffff : COLOR_PANEL_BORDER);
        drawText(canvas, "HANDBRAKE (LT)", brakeLeft + subBtnW / 2f, subBtnY + subBtnH / 2f + dp(3.5f), 9.5f, handbrake ? 0xffffffff : 0xfffda4af, Paint.Align.CENTER, true);

        // Nitro / Boost (Button 7 / RT)
        boolean boost = displayedButtons[7];
        float boostLeft = brakeLeft + subBtnW + dp(10);
        rectTmp.set(boostLeft, subBtnY, boostLeft + subBtnW, subBtnY + subBtnH);
        drawPill(canvas, rectTmp, boost ? 0xff0284c7 : COLOR_PANEL, boost ? 0xffffffff : COLOR_PANEL_BORDER);
        drawText(canvas, "NITRO (RT)", boostLeft + subBtnW / 2f, subBtnY + subBtnH / 2f + dp(3.5f), 9.5f, boost ? 0xffffffff : 0xff7dd3fc, Paint.Align.CENTER, true);

        // --- ACTION BUTTONS: A, B, X, Y DIAMOND CLUSTER ---
        float diamondCx = w * 0.88f;
        float diamondCy = cy;
        float btnRadius = Math.min(w * 0.052f, h * 0.09f);
        float offset = btnRadius * 1.35f;

        drawCircleButton(canvas, diamondCx, diamondCy - offset, btnRadius, "Y", 3);
        drawCircleButton(canvas, diamondCx - offset, diamondCy, btnRadius, "X", 2);
        drawCircleButton(canvas, diamondCx + offset, diamondCy, btnRadius, "B", 1);
        drawCircleButton(canvas, diamondCx, diamondCy + offset, btnRadius, "A", 0);

        // Bottom label
        drawText(canvas, "ACTION CLUSTER", diamondCx, h - dp(16), 9.5f, COLOR_TEXT_MUTED, Paint.Align.CENTER, true);
    }

    private void drawGamepad(Canvas canvas, float w, float h) {
        float topY = dp(48);

        // Shoulders
        float shoulderW = w * 0.20f;
        float shoulderH = dp(36);
        // L1 / L2
        boolean l1 = displayedButtons[4];
        rectTmp.set(dp(16), topY, dp(16) + shoulderW, topY + shoulderH);
        drawPill(canvas, rectTmp, l1 ? COLOR_ACCENT : COLOR_PANEL, l1 ? 0xffffffff : COLOR_PANEL_BORDER);
        drawText(canvas, "L1 / LB", dp(16) + shoulderW / 2f, topY + shoulderH / 2f + dp(3.5f), 11f, l1 ? 0xffffffff : 0xffc4b5fd, Paint.Align.CENTER, true);

        // R1 / R2
        boolean r1 = displayedButtons[5];
        rectTmp.set(w - dp(16) - shoulderW, topY, w - dp(16), topY + shoulderH);
        drawPill(canvas, rectTmp, r1 ? COLOR_ACCENT : COLOR_PANEL, r1 ? 0xffffffff : COLOR_PANEL_BORDER);
        drawText(canvas, "R1 / RB", w - dp(16) - shoulderW / 2f, topY + shoulderH / 2f + dp(3.5f), 11f, r1 ? 0xffffffff : 0xffc4b5fd, Paint.Align.CENTER, true);

        // Left: Virtual D-Pad
        float dpadCx = w * 0.25f;
        float dpadCy = (h + topY) / 2f;
        float dpadR = dp(34);
        drawDpadArm(canvas, dpadCx, dpadCy - dpadR * 1.1f, dp(26), dp(32), "▲", displayedButtons[6]);
        drawDpadArm(canvas, dpadCx, dpadCy + dpadR * 1.1f, dp(26), dp(32), "▼", displayedButtons[7]);
        drawDpadArm(canvas, dpadCx - dpadR * 1.1f, dpadCy, dp(32), dp(26), "◀", displayedSteer < -0.3f);
        drawDpadArm(canvas, dpadCx + dpadR * 1.1f, dpadCy, dp(32), dp(26), "▶", displayedSteer > 0.3f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COLOR_PANEL);
        canvas.drawCircle(dpadCx, dpadCy, dp(16), paint);

        // Center Buttons: Select & Start
        float centerW = dp(64);
        float centerH = dp(28);
        rectTmp.set(w / 2f - centerW - dp(6), h / 2f, w / 2f - dp(6), h / 2f + centerH);
        drawPill(canvas, rectTmp, displayedButtons[6] ? COLOR_ACCENT : COLOR_PANEL, COLOR_PANEL_BORDER);
        drawText(canvas, "SELECT", w / 2f - centerW / 2f - dp(6), h / 2f + centerH / 2f + dp(3.5f), 9.5f, 0xffcbd5e1, Paint.Align.CENTER, true);

        rectTmp.set(w / 2f + dp(6), h / 2f, w / 2f + centerW + dp(6), h / 2f + centerH);
        drawPill(canvas, rectTmp, displayedButtons[7] ? COLOR_ACCENT : COLOR_PANEL, COLOR_PANEL_BORDER);
        drawText(canvas, "START", w / 2f + centerW / 2f + dp(6), h / 2f + centerH / 2f + dp(3.5f), 9.5f, 0xffcbd5e1, Paint.Align.CENTER, true);

        // Right: ABXY Diamond
        float diamondCx = w * 0.82f;
        float diamondCy = dpadCy;
        float btnRadius = Math.min(w * 0.052f, h * 0.09f);
        float offset = btnRadius * 1.35f;

        drawCircleButton(canvas, diamondCx, diamondCy - offset, btnRadius, "Y", 3);
        drawCircleButton(canvas, diamondCx - offset, diamondCy, btnRadius, "X", 2);
        drawCircleButton(canvas, diamondCx + offset, diamondCy, btnRadius, "B", 1);
        drawCircleButton(canvas, diamondCx, diamondCy + offset, btnRadius, "A", 0);
    }

    private void drawDpadArm(Canvas canvas, float cx, float cy, float w, float h, String label, boolean active) {
        rectTmp.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        drawPill(canvas, rectTmp, active ? COLOR_ACCENT : COLOR_PANEL, active ? 0xffffffff : COLOR_PANEL_BORDER);
        drawText(canvas, label, cx, cy + dp(4), 12f, active ? 0xffffffff : 0xff94a3b8, Paint.Align.CENTER, true);
    }

    private void drawSteeringGauge(Canvas canvas, float cx, float cy, float radius, float steerVal) {
        // Outer Arc Track
        rectTmp.set(cx - radius, cy - radius, cx + radius, cy + radius);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(6));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0xff1f192c);
        canvas.drawArc(rectTmp, 135, 270, false, paint);

        // Active Arc Indicator
        paint.setColor(COLOR_ACCENT);
        float sweep = steerVal * 135f;
        canvas.drawArc(rectTmp, 270, sweep, false, paint);

        // Degree Graduation Ticks
        for (int deg = -90; deg <= 90; deg += 30) {
            double rad = Math.toRadians(270 + deg * 1.5);
            float inner = radius - (deg == 0 ? dp(14) : dp(8));
            float outer = radius + dp(4);
            float x1 = cx + (float) Math.cos(rad) * inner;
            float y1 = cy + (float) Math.sin(rad) * inner;
            float x2 = cx + (float) Math.cos(rad) * outer;
            float y2 = cy + (float) Math.sin(rad) * outer;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(deg == 0 ? dp(2.5f) : dp(1.5f));
            paint.setColor(deg == 0 ? 0xfff59e0b : 0xff473b5e);
            canvas.drawLine(x1, y1, x2, y2, paint);
        }

        // Inner Wheel Body (rotates with steer)
        canvas.save();
        float rotDeg = steerVal * 45f;
        canvas.rotate(rotDeg, cx, cy);

        // Wheel Rim
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(10));
        paint.setColor(0xff1e172a);
        canvas.drawCircle(cx, cy, radius * 0.76f, paint);

        // Wheel Rim Accent
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.8f));
        paint.setColor(steeringTouch ? 0xffa855f7 : 0xff4f4066);
        canvas.drawCircle(cx, cy, radius * 0.76f + dp(5), paint);

        // Top Stripe (Amber Marker)
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xfff59e0b);
        rectTmp.set(cx - dp(4), cy - radius * 0.76f - dp(5), cx + dp(4), cy - radius * 0.76f + dp(5));
        canvas.drawRoundRect(rectTmp, dp(2), dp(2), paint);

        // Wheel Spokes
        paint.setColor(0xff292038);
        paint.setStrokeWidth(dp(6));
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(cx - radius * 0.65f, cy, cx + radius * 0.65f, cy, paint);
        canvas.drawLine(cx, cy, cx, cy + radius * 0.65f, paint);

        // Center Hub
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COLOR_PANEL);
        canvas.drawCircle(cx, cy, radius * 0.32f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(COLOR_ACCENT);
        canvas.drawCircle(cx, cy, radius * 0.32f, paint);

        canvas.restore();

        // Digital Angle Readout in Center
        int angle = Math.round(steerVal * 45f);
        String angleStr = (angle > 0 ? "+" : "") + angle + "°";
        drawText(canvas, angleStr, cx, cy + dp(6), 18f, 0xffffffff, Paint.Align.CENTER, true);

        // Subtext under wheel
        String steerMode = steeringTouch ? "TOUCH STEERING" : (gyroEnabled ? "GYRO TILT ACTIVE" : "TOUCH TO STEER");
        drawText(canvas, steerMode, cx, cy + radius + dp(14), 10f, steeringTouch ? 0xffa855f7 : COLOR_TEXT_MUTED, Paint.Align.CENTER, true);
    }

    private void drawPedal(Canvas canvas, float left, float right, float top, float bottom, String label, float value, int colorTop, int colorBottom) {
        float cx = (left + right) / 2f;
        float h = bottom - top;

        // Container Track
        rectTmp.set(left, top, right, bottom);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COLOR_PANEL);
        canvas.drawRoundRect(rectTmp, dp(14), dp(14), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor(value > 0.05f ? colorTop : COLOR_PANEL_BORDER);
        canvas.drawRoundRect(rectTmp, dp(14), dp(14), paint);

        // Fill bar from bottom up
        float fillTop = bottom - h * value;
        if (value > 0.01f) {
            rectTmp.set(left + dp(3), fillTop, right - dp(3), bottom - dp(3));
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(cx, fillTop, cx, bottom, colorTop, colorBottom, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rectTmp, dp(11), dp(11), paint);
            paint.setShader(null);
        }

        // Pedal Name at Top
        drawText(canvas, label, cx, top + dp(16), 11f, value > 0.05f ? 0xffffffff : COLOR_TEXT_MUTED, Paint.Align.CENTER, true);

        // Percentage Readout at Bottom
        int pct = Math.round(value * 100);
        drawText(canvas, pct + "%", cx, bottom - dp(10), 12.5f, 0xffffffff, Paint.Align.CENTER, true);
    }

    private void drawCircleButton(Canvas canvas, float cx, float cy, float radius, String label, int index) {
        boolean pressed = index < displayedButtons.length && displayedButtons[index];

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pressed ? COLOR_ACCENT : COLOR_PANEL);
        canvas.drawCircle(cx, cy, radius, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(pressed ? 0xffffffff : COLOR_PANEL_BORDER);
        canvas.drawCircle(cx, cy, radius, paint);

        // Glowing border when pressed
        if (pressed) {
            paint.setStrokeWidth(dp(4));
            paint.setColor(0x80c084fc);
            canvas.drawCircle(cx, cy, radius + dp(2), paint);
        }

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

        // 1. Single-tap triggers for HUD buttons on ACTION_DOWN / ACTION_POINTER_DOWN
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
            boolean[] nextButtons = new boolean[8];

            int count = lifted ? Math.max(0, event.getPointerCount() - 1) : event.getPointerCount();
            int liftedIndex = event.getActionIndex();

            for (int i = 0, used = 0; i < event.getPointerCount(); i++) {
                if (lifted && i == liftedIndex) continue;
                if (used >= count) break;
                used++;

                float x = event.getX(i);
                float y = event.getY(i);

                if (y <= dp(44)) continue; // ignore HUD row in continuous input

                if (currentMode == MODE_RACEPAD) {
                    // Paddles: Gear Down (LB / btn 4), Gear Up (RB / btn 5)
                    float paddleW = w * 0.22f;
                    float paddleH = dp(38);
                    if (y >= dp(48) && y <= dp(48) + paddleH) {
                        if (x <= dp(12) + paddleW) {
                            nextButtons[4] = true;
                            continue;
                        } else if (x >= w - dp(12) - paddleW) {
                            nextButtons[5] = true;
                            continue;
                        }
                    }

                    // Steering Wheel area (Left ~45% of screen)
                    float cx = w * 0.25f;
                    float cy = (h + dp(48) + dp(34)) / 2f - dp(4);
                    float radius = Math.min(w * 0.21f, (h - dp(48) - dp(34)) * 0.44f);
                    if (x < w * 0.48f && Math.hypot(x - cx, y - cy) <= radius * 1.35f) {
                        nextSteeringTouch = true;
                        nextSteer = clamp((x - cx) / (radius * 0.75f), -1f, 1f);
                        continue;
                    }

                    // Pedals area
                    float pedalTop = dp(48) + dp(12);
                    float pedalBottom = h - dp(38);
                    float pedalW = w * 0.11f;
                    float brakeLeft = w * 0.52f;
                    float brakeRight = brakeLeft + pedalW;
                    float gasLeft = brakeRight + dp(14);
                    float gasRight = gasLeft + pedalW;

                    if (x >= brakeLeft - dp(6) && x <= brakeRight + dp(6) && y >= pedalTop - dp(8) && y <= pedalBottom + dp(8)) {
                        // Proportional touch based on height
                        float pct = clamp((pedalBottom - y) / (pedalBottom - pedalTop), 0.2f, 1.0f);
                        nextBrake = Math.max(nextBrake, pct);
                        continue;
                    }

                    if (x >= gasLeft - dp(6) && x <= gasRight + dp(6) && y >= pedalTop - dp(8) && y <= pedalBottom + dp(8)) {
                        // Proportional touch based on height
                        float pct = clamp((pedalBottom - y) / (pedalBottom - pedalTop), 0.2f, 1.0f);
                        nextThrottle = Math.max(nextThrottle, pct);
                        continue;
                    }

                    // Sub-buttons below pedals: Handbrake (btn 6) and Boost (btn 7)
                    float subBtnW = (gasRight - brakeLeft - dp(10)) / 2f;
                    float subBtnH = dp(34);
                    float subBtnY = h - dp(36);
                    if (y >= subBtnY - dp(6)) {
                        if (x >= brakeLeft && x <= brakeLeft + subBtnW) {
                            nextButtons[6] = true;
                            continue;
                        } else if (x >= brakeLeft + subBtnW + dp(10) && x <= gasRight) {
                            nextButtons[7] = true;
                            continue;
                        }
                    }

                    // Diamond Buttons (A, B, X, Y)
                    int btnIdx = hitDiamondButton(x, y, w, h);
                    if (btnIdx >= 0) {
                        nextButtons[btnIdx] = true;
                    }
                } else {
                    // GAMEPAD MODE
                    float shoulderW = w * 0.20f;
                    float shoulderH = dp(38);
                    if (y >= dp(48) && y <= dp(48) + shoulderH) {
                        if (x <= dp(16) + shoulderW) {
                            nextButtons[4] = true; // L1
                            continue;
                        } else if (x >= w - dp(16) - shoulderW) {
                            nextButtons[5] = true; // R1
                            continue;
                        }
                    }

                    // D-Pad / Steering
                    float dpadCx = w * 0.25f;
                    float dpadCy = (h + dp(48)) / 2f;
                    float dist = (float) Math.hypot(x - dpadCx, y - dpadCy);
                    if (dist <= dp(70)) {
                        float dx = x - dpadCx;
                        float dy = y - dpadCy;
                        if (Math.abs(dx) > Math.abs(dy)) {
                            nextSteer = dx > 0 ? 1f : -1f;
                        } else {
                            if (dy < 0) nextThrottle = 1f; // Up = Gas
                            else nextBrake = 1f; // Down = Brake
                        }
                        continue;
                    }

                    // Select / Start
                    float centerW = dp(64);
                    float centerH = dp(32);
                    if (y >= h / 2f - dp(10) && y <= h / 2f + centerH + dp(10)) {
                        if (x >= w / 2f - centerW - dp(10) && x <= w / 2f - dp(6)) {
                            nextButtons[6] = true;
                            continue;
                        } else if (x >= w / 2f + dp(6) && x <= w / 2f + centerW + dp(10)) {
                            nextButtons[7] = true;
                            continue;
                        }
                    }

                    // Action cluster
                    int btnIdx = hitDiamondButton(x, y, w, h);
                    if (btnIdx >= 0) {
                        nextButtons[btnIdx] = true;
                    }
                }
            }

            // Detect new button presses for haptic feedback
            for (int b = 0; b < 8; b++) {
                if (nextButtons[b] && !buttons[b]) {
                    triggerHaptic();
                    break;
                }
            }

            manualSteer = nextSteer;
            steeringTouch = nextSteeringTouch;
            throttle = nextThrottle;
            brake = nextBrake;
            System.arraycopy(nextButtons, 0, buttons, 0, 8);

            displayedSteer = steeringTouch ? manualSteer : displayedSteer;
            displayedThrottle = throttle;
            displayedBrake = brake;
            System.arraycopy(buttons, 0, displayedButtons, 0, 8);

            if (listener != null) {
                listener.onInputChanged(manualSteer, steeringTouch, throttle, brake, Arrays.copyOf(buttons, 8));
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
        float badgeW = dp(140);
        if (x <= dp(12) + badgeW) {
            if (!connected && listener != null) {
                listener.onOpenSetup();
            }
            return;
        }

        // Center Pill: Mode Switch
        float modeW = dp(130);
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
        float diamondCx = currentMode == MODE_RACEPAD ? w * 0.88f : w * 0.82f;
        float cy = currentMode == MODE_RACEPAD ? (h + dp(48) + dp(34)) / 2f - dp(4) : (h + dp(48)) / 2f;
        float btnRadius = Math.min(w * 0.052f, h * 0.09f);
        float offset = btnRadius * 1.35f;

        // Centers for Y(3), X(2), B(1), A(0)
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
        manualSteer = 0f;
        steeringTouch = false;
        throttle = 0f;
        brake = 0f;
        Arrays.fill(buttons, false);
        Arrays.fill(displayedButtons, false);
        if (listener != null) {
            listener.onInputChanged(0f, false, 0f, 0f, new boolean[8]);
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
