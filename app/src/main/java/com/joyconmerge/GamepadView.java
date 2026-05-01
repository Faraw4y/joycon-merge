package com.joyconmerge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import java.util.HashSet;
import java.util.Set;

/**
 * Draws a Joy-Con combined gamepad diagram.
 * Call setButtonPressed(name, true/false) and setStick(left/right, x, y) to update.
 */
public class GamepadView extends View {

    // Button names used as keys
    public static final String BTN_A       = "A";
    public static final String BTN_B       = "B";
    public static final String BTN_X       = "X";
    public static final String BTN_Y       = "Y";
    public static final String BTN_L       = "L";
    public static final String BTN_ZL      = "ZL";
    public static final String BTN_R       = "R";
    public static final String BTN_ZR      = "ZR";
    public static final String BTN_PLUS    = "+";
    public static final String BTN_MINUS   = "−";
    public static final String BTN_L3      = "L3";
    public static final String BTN_R3      = "R3";
    public static final String BTN_HOME    = "⌂";
    public static final String BTN_CAP     = "□";
    public static final String DPAD_UP     = "↑";
    public static final String DPAD_DOWN   = "↓";
    public static final String DPAD_LEFT   = "←";
    public static final String DPAD_RIGHT  = "→";

    private final Set<String> pressed = new HashSet<>();
    private float lx = 0, ly = 0, rx = 0, ry = 0;

    private final Paint bodyPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnOnPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textOnPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stickPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stickDotPaint= new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shoulderPaint= new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shoulderOnPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);

    public GamepadView(Context ctx) { super(ctx); init(); }
    public GamepadView(Context ctx, AttributeSet attrs) { super(ctx, attrs); init(); }
    public GamepadView(Context ctx, AttributeSet attrs, int defStyle) { super(ctx, attrs, defStyle); init(); }

    private void init() {
        bodyPaint.setColor(0xFF2C2C2E);
        bodyPaint.setStyle(Paint.Style.FILL);

        btnPaint.setColor(0xFF48484A);
        btnPaint.setStyle(Paint.Style.FILL);

        btnOnPaint.setColor(0xFF30D158);
        btnOnPaint.setStyle(Paint.Style.FILL);

        textPaint.setColor(0xFFEBEBF5);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);

        textOnPaint.setColor(0xFF1C1C1E);
        textOnPaint.setTextAlign(Paint.Align.CENTER);
        textOnPaint.setTypeface(Typeface.DEFAULT_BOLD);

        stickPaint.setColor(0xFF3A3A3C);
        stickPaint.setStyle(Paint.Style.FILL);

        stickDotPaint.setColor(0xFF636366);
        stickDotPaint.setStyle(Paint.Style.FILL);

        shoulderPaint.setColor(0xFF3A3A3C);
        shoulderPaint.setStyle(Paint.Style.FILL);

        shoulderOnPaint.setColor(0xFF30D158);
        shoulderOnPaint.setStyle(Paint.Style.FILL);

        labelPaint.setColor(0xFF8E8E93);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTypeface(Typeface.DEFAULT);
    }

    public float getLX() { return lx; }
    public float getLY() { return ly; }
    public float getRX() { return rx; }
    public float getRY() { return ry; }

    public void setButtonPressed(String btn, boolean on) {
        boolean changed = on ? pressed.add(btn) : pressed.remove(btn);
        if (changed) postInvalidate();
    }

    public void setStick(boolean left, float x, float y) {
        if (left) { lx = x; ly = y; } else { rx = x; ry = y; }
        postInvalidate();
    }

    public void resetAll() {
        pressed.clear();
        lx = ly = rx = ry = 0;
        postInvalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        // Aspect ratio ~2.2:1 (wide gamepad)
        int h = (int)(w / 2.2f);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        float W = getWidth(), H = getHeight();
        float s = W / 320f; // scale factor based on 320dp reference width

        textPaint.setTextSize(9 * s);
        textOnPaint.setTextSize(9 * s);
        labelPaint.setTextSize(7 * s);

        // Body
        RectF body = new RectF(4*s, 20*s, W-4*s, H-4*s);
        bodyPaint.setColor(0xFF2C2C2E);
        c.drawRoundRect(body, 24*s, 24*s, bodyPaint);

        // Left grip bump
        RectF lg = new RectF(4*s, H*0.5f, 50*s, H-4*s);
        c.drawRoundRect(lg, 20*s, 20*s, bodyPaint);
        // Right grip bump
        RectF rg = new RectF(W-50*s, H*0.5f, W-4*s, H-4*s);
        c.drawRoundRect(rg, 20*s, 20*s, bodyPaint);

        // Shoulder buttons ZL / ZR (top bar)
        float shW = 60*s, shH = 14*s;
        RectF zl = new RectF(4*s, 8*s, 4*s+shW, 8*s+shH);
        RectF zr = new RectF(W-4*s-shW, 8*s, W-4*s, 8*s+shH);
        drawShoulderBtn(c, zl, BTN_ZL, "ZL", s);
        drawShoulderBtn(c, zr, BTN_ZR, "ZR", s);

        // L / R (below ZL/ZR)
        float lrY = 20*s, lrH = 12*s;
        RectF lr = new RectF(4*s, lrY, 4*s+shW*0.85f, lrY+lrH);
        RectF rr = new RectF(W-4*s-shW*0.85f, lrY, W-4*s, lrY+lrH);
        drawShoulderBtn(c, lr, BTN_L, "L", s);
        drawShoulderBtn(c, rr, BTN_R, "R", s);

        // Center area divider line (subtle)
        Paint div = new Paint(Paint.ANTI_ALIAS_FLAG);
        div.setColor(0xFF3A3A3C);
        div.setStrokeWidth(1*s);
        c.drawLine(W/2, 30*s, W/2, H-8*s, div);

        // Left stick
        float lsX = 72*s, lsY = H*0.42f;
        float stR = 22*s;
        drawStick(c, lsX, lsY, stR, lx, ly, BTN_L3, s);

        // D-pad
        float dpX = 38*s, dpY = H*0.68f;
        drawDpad(c, dpX, dpY, 14*s, s);

        // Capture (□) and Minus (−) between dpad and center
        float midLeftX = 128*s;
        drawSmallBtn(c, midLeftX - 14*s, H*0.42f, 10*s, BTN_CAP,  "□", s);
        drawSmallBtn(c, midLeftX + 4*s,  H*0.42f, 10*s, BTN_MINUS, "−", s);

        // Right stick
        float rsX = W - 72*s, rsY = H*0.62f;
        drawStick(c, rsX, rsY, stR, rx, ry, BTN_R3, s);

        // ABXY cluster
        float abX = W - 52*s, abY = H*0.42f;
        float btnR = 12*s;
        drawRoundBtn(c, abX,          abY - 18*s, btnR, BTN_X, "X", 0xFF0A84FF, s);
        drawRoundBtn(c, abX + 18*s,   abY,        btnR, BTN_A, "A", 0xFF30D158, s);
        drawRoundBtn(c, abX - 18*s,   abY,        btnR, BTN_B, "B", 0xFFFF453A, s);
        drawRoundBtn(c, abX,          abY + 18*s, btnR, BTN_Y, "Y", 0xFFFFD60A, s);

        // Plus (+) and Home (⌂) between center and right
        float midRightX = W - 128*s;
        drawSmallBtn(c, midRightX - 4*s,  H*0.42f, 10*s, BTN_PLUS, "+", s);
        drawHomeBtn( c, midRightX + 14*s, H*0.42f, 11*s, s);
    }

    private void drawShoulderBtn(Canvas c, RectF r, String key, String label, float s) {
        boolean on = pressed.contains(key);
        Paint p = on ? shoulderOnPaint : shoulderPaint;
        c.drawRoundRect(r, 5*s, 5*s, p);
        float cx = r.centerX(), cy = r.centerY() + 3.5f*s;
        textPaint.setTextSize(8*s);
        textOnPaint.setTextSize(8*s);
        c.drawText(label, cx, cy, on ? textOnPaint : textPaint);
    }

    private void drawStick(Canvas c, float cx, float cy, float r, float dx, float dy, String l3key, float s) {
        // Base circle
        boolean l3 = pressed.contains(l3key);
        stickPaint.setColor(l3 ? 0xFF30D158 : 0xFF3A3A3C);
        c.drawCircle(cx, cy, r, stickPaint);
        // Dot (knob) displaced by dx/dy normalized -1..1
        float maxDisp = r * 0.45f;
        float kx = cx + dx * maxDisp;
        float ky = cy + dy * maxDisp;
        stickDotPaint.setColor(l3 ? 0xFF1C1C1E : 0xFF8E8E93);
        c.drawCircle(kx, ky, r * 0.55f, stickDotPaint);
        // Label
        labelPaint.setTextSize(6.5f*s);
        c.drawText(l3key, cx, cy + r + 8*s, labelPaint);
    }

    private void drawDpad(Canvas c, float cx, float cy, float arm, float s) {
        Paint dp = new Paint(Paint.ANTI_ALIAS_FLAG);
        dp.setStyle(Paint.Style.FILL);
        float thick = arm * 0.9f;

        // Up
        dp.setColor(pressed.contains(DPAD_UP) ? 0xFF30D158 : 0xFF3A3A3C);
        c.drawRoundRect(cx-thick/2, cy-arm*2, cx+thick/2, cy, 4*s, 4*s, dp);
        // Down
        dp.setColor(pressed.contains(DPAD_DOWN) ? 0xFF30D158 : 0xFF3A3A3C);
        c.drawRoundRect(cx-thick/2, cy, cx+thick/2, cy+arm*2, 4*s, 4*s, dp);
        // Left
        dp.setColor(pressed.contains(DPAD_LEFT) ? 0xFF30D158 : 0xFF3A3A3C);
        c.drawRoundRect(cx-arm*2, cy-thick/2, cx, cy+thick/2, 4*s, 4*s, dp);
        // Right
        dp.setColor(pressed.contains(DPAD_RIGHT) ? 0xFF30D158 : 0xFF3A3A3C);
        c.drawRoundRect(cx, cy-thick/2, cx+arm*2, cy+thick/2, 4*s, 4*s, dp);
        // Center square
        dp.setColor(0xFF2C2C2E);
        c.drawRect(cx-thick/2, cy-thick/2, cx+thick/2, cy+thick/2, dp);

        // Arrow labels
        textPaint.setTextSize(7*s);
        textOnPaint.setTextSize(7*s);
        Paint tp = pressed.contains(DPAD_UP) ? textOnPaint : textPaint;
        c.drawText("▲", cx, cy-arm*1.1f, pressed.contains(DPAD_UP) ? textOnPaint : textPaint);
        c.drawText("▼", cx, cy+arm*1.55f, pressed.contains(DPAD_DOWN) ? textOnPaint : textPaint);
        c.drawText("◀", cx-arm*1.1f, cy+3*s, pressed.contains(DPAD_LEFT) ? textOnPaint : textPaint);
        c.drawText("▶", cx+arm*1.1f, cy+3*s, pressed.contains(DPAD_RIGHT) ? textOnPaint : textPaint);
    }

    private void drawRoundBtn(Canvas c, float cx, float cy, float r, String key, String label, int onColor, float s) {
        boolean on = pressed.contains(key);
        Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
        bp.setStyle(Paint.Style.FILL);
        bp.setColor(on ? onColor : 0xFF48484A);
        c.drawCircle(cx, cy, r, bp);
        textPaint.setTextSize(9*s);
        textOnPaint.setTextSize(9*s);
        c.drawText(label, cx, cy + 3.5f*s, on ? textOnPaint : textPaint);
    }

    private void drawSmallBtn(Canvas c, float cx, float cy, float r, String key, String label, float s) {
        boolean on = pressed.contains(key);
        Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
        bp.setStyle(Paint.Style.FILL);
        bp.setColor(on ? 0xFF30D158 : 0xFF3A3A3C);
        c.drawCircle(cx, cy, r, bp);
        textPaint.setTextSize(8*s);
        textOnPaint.setTextSize(8*s);
        c.drawText(label, cx, cy + 3*s, on ? textOnPaint : textPaint);
    }

    private void drawHomeBtn(Canvas c, float cx, float cy, float r, float s) {
        boolean on = pressed.contains(BTN_HOME);
        Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
        bp.setStyle(Paint.Style.FILL);
        bp.setColor(on ? 0xFFFFD60A : 0xFF3A3A3C);
        c.drawCircle(cx, cy, r, bp);
        textPaint.setTextSize(10*s);
        textOnPaint.setTextSize(10*s);
        c.drawText("⌂", cx, cy + 4*s, on ? textOnPaint : textPaint);
    }
}
