package com.pocketcontroller;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.Arrays;

final class ControllerPad extends View {
    interface Listener {
        void onInputChanged(float steer, boolean steeringTouch, float throttle, float brake, boolean[] buttons);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Listener listener;
    private float manualSteer;
    private boolean steeringTouch;
    private float throttle;
    private float brake;
    private boolean[] buttons = new boolean[8];
    private float shownSteer;
    private float shownThrottle;
    private float shownBrake;
    private boolean[] shownButtons = new boolean[8];

    ControllerPad(Context context) {
        super(context);
        setFocusable(true);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    void setListener(Listener value) { listener = value; }

    void setDisplayedInput(float steer, float gas, float stop, boolean[] pressed) {
        shownSteer = clamp(steer, -1f, 1f);
        shownThrottle = clamp(gas, 0f, 1f);
        shownBrake = clamp(stop, 0f, 1f);
        shownButtons = Arrays.copyOf(pressed, 8);
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xff17131f);
        canvas.drawRoundRect(new RectF(0, 0, w, h), 24, 24, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.3f);
        paint.setColor(0xff3b3049);
        canvas.drawRoundRect(new RectF(1, 1, w - 1, h - 1), 24, 24, paint);

        float cx = w * 0.23f, cy = h * 0.54f;
        float radius = Math.min(h * 0.34f, w * 0.19f);
        text(canvas, "STEERING", cx, h * 0.14f, 12, 0xffc7b5ef, Paint.Align.CENTER);
        circle(canvas, cx, cy, radius, 0xffb49aff, false, 3f);
        circle(canvas, cx, cy, radius * 0.76f, 0xff463a55, false, 2f);
        line(canvas, cx - radius * .78f, cy - radius * .14f, cx + radius * .78f, cy - radius * .14f, 0xff51455f, 5f);
        line(canvas, cx, cy - radius * .12f, cx, cy + radius * .68f, 0xff51455f, 5f);
        float angle = (float) (Math.PI * (0.5f + shownSteer * 0.38f));
        line(canvas, cx, cy, cx + (float)Math.cos(angle) * radius * .72f, cy + (float)Math.sin(angle) * radius * .72f, 0xffb39aff, 4f);
        text(canvas, String.format(java.util.Locale.US, "%+d°", Math.round(shownSteer * 45)), cx, cy + radius + 26, 15, 0xffffffff, Paint.Align.CENTER);
        text(canvas, "Tilt or drag to steer", cx, h * .92f, 12, 0xffa99eb8, Paint.Align.CENTER);

        drawPedal(canvas, w * .57f, w * .66f, h * .23f, h * .78f, "GAS", shownThrottle, 0xff8b5cf6);
        drawPedal(canvas, w * .69f, w * .78f, h * .23f, h * .78f, "BRAKE", shownBrake, 0xffe879a8);

        float bx = w * .89f, by = h * .50f, r = Math.min(h * .095f, w * .055f);
        drawButton(canvas, bx, by - r * 1.15f, r, "Y", 3);
        drawButton(canvas, bx - r * 1.15f, by, r, "X", 2);
        drawButton(canvas, bx + r * 1.15f, by, r, "B", 1);
        drawButton(canvas, bx, by + r * 1.15f, r, "A", 0);
        text(canvas, "HOLD TO PRESS", bx, h * .86f, 10, 0xffa99eb8, Paint.Align.CENTER);
    }

    private void drawPedal(Canvas canvas, float left, float right, float top, float bottom, String label, float value, int color) {
        float cx = (left + right) / 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xff0e0c12);
        canvas.drawRoundRect(new RectF(left, top, right, bottom), 20, 20, paint);
        float fillTop = bottom - (bottom - top) * value;
        paint.setColor(color);
        canvas.drawRoundRect(new RectF(left + 3, fillTop, right - 3, bottom - 3), 17, 17, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        paint.setColor(value > .1f ? 0xffe1d4ff : 0xff544966);
        canvas.drawRoundRect(new RectF(left, top, right, bottom), 20, 20, paint);
        text(canvas, label, cx, top - 10, 11, 0xffc7b5ef, Paint.Align.CENTER);
        text(canvas, Math.round(value * 100) + "%", cx, bottom + 21, 12, 0xffffffff, Paint.Align.CENTER);
    }

    private void drawButton(Canvas canvas, float x, float y, float radius, String label, int index) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(index < shownButtons.length && shownButtons[index] ? 0xff7545d8 : 0xff17131f);
        canvas.drawCircle(x, y, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        paint.setColor(index < shownButtons.length && shownButtons[index] ? 0xffcdb8ff : 0xff665a72);
        canvas.drawCircle(x, y, radius, paint);
        text(canvas, label, x, y + 5, 16, 0xffffffff, Paint.Align.CENTER);
    }

    private void circle(Canvas canvas, float x, float y, float radius, int color, boolean fill, float width) {
        paint.setStyle(fill ? Paint.Style.FILL : Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setColor(color);
        canvas.drawCircle(x, y, radius, paint);
    }

    private void line(Canvas canvas, float x1, float y1, float x2, float y2, int color, float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(color);
        canvas.drawLine(x1, y1, x2, y2, paint);
    }

    private void text(Canvas canvas, String value, float x, float y, float size, int color, Paint.Align align) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTextSize(size * getResources().getDisplayMetrics().scaledDensity);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        paint.setTextAlign(align);
        canvas.drawText(value, x, y, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL) {
            clearInput();
            return true;
        }
        boolean lifted = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP;
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_MOVE || lifted) {
            float w = getWidth(), h = getHeight();
            float nextSteer = 0f, nextThrottle = 0f, nextBrake = 0f;
            boolean nextSteeringTouch = false;
            boolean[] nextButtons = new boolean[8];
            int count = lifted ? Math.max(0, event.getPointerCount() - 1) : event.getPointerCount();
            int liftedIndex = event.getActionIndex();
            for (int i = 0, used = 0; i < event.getPointerCount(); i++) {
                if (lifted && i == liftedIndex) continue;
                if (used >= count) break;
                used++;
                float x = event.getX(i), y = event.getY(i);
                float cx = w * .23f, cy = h * .54f;
                float radius = Math.min(h * .34f, w * .19f);
                if (x < w * .48f && Math.abs(y - cy) < radius * 1.35f) {
                    nextSteeringTouch = true;
                    nextSteer = clamp((x - cx) / (radius * .72f), -1f, 1f);
                } else if (x >= w * .55f && x < w * .665f && y > h * .20f && y < h * .82f) {
                    nextThrottle = 1f;
                } else if (x >= w * .68f && x < w * .80f && y > h * .20f && y < h * .82f) {
                    nextBrake = 1f;
                } else {
                    int index = hitButton(x, y, w, h);
                    if (index >= 0) nextButtons[index] = true;
                }
            }
            manualSteer = nextSteer;
            steeringTouch = nextSteeringTouch;
            throttle = nextThrottle;
            brake = nextBrake;
            buttons = nextButtons;
            shownSteer = steeringTouch ? manualSteer : shownSteer;
            shownThrottle = throttle;
            shownBrake = brake;
            shownButtons = Arrays.copyOf(buttons, 8);
            if (listener != null) listener.onInputChanged(manualSteer, steeringTouch, throttle, brake, Arrays.copyOf(buttons, 8));
            invalidate();
            return true;
        }
        return true;
    }

    private int hitButton(float x, float y, float w, float h) {
        float bx = w * .89f, by = h * .50f, r = Math.min(h * .095f, w * .055f) * 1.25f;
        float[][] centers = {{bx, by + r * .92f}, {bx + r * .92f, by}, {bx - r * .92f, by}, {bx, by - r * .92f}};
        for (int i = 0; i < centers.length; i++) {
            float dx = x - centers[i][0], dy = y - centers[i][1];
            if (dx * dx + dy * dy <= r * r) return i;
        }
        return -1;
    }

    private void clearInput() {
        manualSteer = 0; steeringTouch = false; throttle = 0; brake = 0; buttons = new boolean[8];
        if (listener != null) listener.onInputChanged(0, false, 0, 0, new boolean[8]);
        invalidate();
    }

    private static float clamp(float value, float low, float high) { return Math.max(low, Math.min(high, value)); }
}
