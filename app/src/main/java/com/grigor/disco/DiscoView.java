package com.grigor.disco;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.Random;

/** Full-screen light show driven by AudioEngine. Tap = next mode, double tap = edit sign, hold = flashlight strobe. */
class DiscoView extends View {
    private static final String[] MODES = {"PARTY", "PULSE", "STROBE", "RINGS", "BARS", "SIGN"};

    private final MainActivity activity;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GestureDetector gestures;
    private final Random rnd = new Random();
    private final float[] hsv = new float[3];

    private final Object lock = new Object();
    private final float[] bands = new float[AudioEngine.BANDS];
    private final float[] shownBands = new float[AudioEngine.BANDS];
    private volatile float level;
    private volatile int beatCount;
    private volatile String message;

    private int seenBeats;
    private int mode;
    private boolean torchStrobe;
    private float hue;
    private float shownLevel;
    private long flashUntil;
    private long labelUntil;
    private String label = "";
    private final Paint signPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SharedPreferences prefs;
    private String sign;
    private float signX = Float.NaN;
    private final long[] beatTimes = new long[9];
    private int beatIndex;
    private int bpm;
    private final ArrayList<float[]> rings = new ArrayList<>(); // x, y, radius, alpha, hue

    DiscoView(MainActivity activity) {
        super(activity);
        this.activity = activity;
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        signPaint.setFakeBoldText(true);
        signPaint.setTextAlign(Paint.Align.LEFT);
        prefs = activity.getSharedPreferences("disco", 0);
        sign = prefs.getString("sign", "DISCO TIME");
        showLabel("TAP: MODE   DOUBLE TAP: SIGN   HOLD: FLASHLIGHT");
        gestures = new GestureDetector(activity, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                mode = (mode + 1) % MODES.length;
                showLabel(MODES[mode]);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                editSign();
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (!activity.hasTorch()) {
                    showLabel("NO FLASHLIGHT");
                    return;
                }
                torchStrobe = !torchStrobe;
                if (!torchStrobe) activity.setTorch(false);
                showLabel(torchStrobe ? "FLASHLIGHT ON" : "FLASHLIGHT OFF");
            }
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return gestures.onTouchEvent(e) || super.onTouchEvent(e);
    }

    void setMessage(String m) {
        message = m;
        postInvalidate();
    }

    void onAudio(float lvl, float[] b, boolean beat) {
        synchronized (lock) {
            System.arraycopy(b, 0, bands, 0, bands.length);
        }
        level = lvl;
        if (beat) beatCount++;
    }

    private void editSign() {
        EditText input = new EditText(activity);
        input.setText(sign);
        input.setSelectAllOnFocus(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        new AlertDialog.Builder(activity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Sign message")
                .setView(input)
                .setPositiveButton("Show", (d, w) -> {
                    String t = input.getText().toString().trim();
                    sign = t.isEmpty() ? "DISCO TIME" : t;
                    prefs.edit().putString("sign", sign).apply();
                    signX = Float.NaN;
                    mode = MODES.length - 1;
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void recordBeat(long now) {
        beatTimes[beatIndex % beatTimes.length] = now;
        beatIndex++;
        if (beatIndex < 5) return;
        int n = Math.min(beatIndex, beatTimes.length);
        long[] gaps = new long[n - 1];
        int g = 0;
        for (int i = 1; i < n; i++) {
            long a = beatTimes[(beatIndex - i) % beatTimes.length];
            long b = beatTimes[(beatIndex - i - 1) % beatTimes.length];
            gaps[g++] = a - b;
        }
        java.util.Arrays.sort(gaps);
        long median = gaps[gaps.length / 2];
        if (median <= 0) return;
        float v = 60000f / median;
        while (v < 70) v *= 2;
        while (v > 190) v /= 2;
        bpm = Math.round(v);
    }

    private void showLabel(String s) {
        label = s;
        labelUntil = SystemClock.uptimeMillis() + 1600;
    }

    private int color(float h, float s, float v) {
        hsv[0] = ((h % 360) + 360) % 360;
        hsv[1] = s;
        hsv[2] = Math.max(0f, Math.min(1f, v));
        return Color.HSVToColor(hsv);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        long now = SystemClock.uptimeMillis();

        // Smooth the incoming numbers so the lights breathe instead of jitter.
        shownLevel += (level - shownLevel) * 0.35f;
        synchronized (lock) {
            for (int i = 0; i < shownBands.length; i++) {
                float t = bands[i];
                shownBands[i] += (t - shownBands[i]) * (t > shownBands[i] ? 0.6f : 0.15f);
            }
        }

        boolean beat = beatCount != seenBeats;
        seenBeats = beatCount;
        hue += 0.6f + shownLevel * 2f;
        if (beat) {
            hue += 40 + rnd.nextInt(80);
            flashUntil = now + 70;
            recordBeat(now);
            if (torchStrobe) activity.flashTorch();
            if (mode == 0 || mode == 3) {
                int n = mode == 3 ? 2 : 1;
                for (int i = 0; i < n; i++) {
                    rings.add(new float[]{rnd.nextFloat() * w, rnd.nextFloat() * h, 10f, 1f, hue + rnd.nextInt(120)});
                }
            }
        }

        switch (mode) {
            case 1: // PULSE
                c.drawColor(color(hue, 1f, 0.08f + shownLevel * 0.92f));
                break;
            case 2: // STROBE
                if (now < flashUntil) c.drawColor(Color.WHITE);
                else c.drawColor(color(hue, 1f, shownLevel * 0.18f));
                break;
            case 3: // RINGS
                c.drawColor(Color.BLACK);
                drawRings(c, w, h);
                break;
            case 4: // BARS
                c.drawColor(Color.BLACK);
                drawBars(c, w, h);
                break;
            case 5: // SIGN
                c.drawColor(color(hue + 180, 1f, 0.04f + shownLevel * 0.12f));
                drawSign(c, w, h, now);
                break;
            default: // PARTY
                c.drawColor(color(hue + 180, 1f, 0.05f + shownLevel * 0.35f));
                drawBars(c, w, h);
                drawRings(c, w, h);
                if (now < flashUntil) c.drawColor(Color.argb(90, 255, 255, 255));
                break;
        }

        if (bpm > 0 && beatIndex > 0 && now - beatTimes[(beatIndex - 1) % beatTimes.length] < 3000) {
            text.setTextSize(Math.min(w, h) / 22f);
            text.setAlpha(170);
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText(bpm + " BPM", w - w * 0.04f, h * 0.06f, text);
            text.setTextAlign(Paint.Align.CENTER);
        }

        String m = message;
        if (m != null) {
            drawLines(c, m, w / 2f, h / 2f, w / 22f, 255);
        } else if (now < labelUntil) {
            int a = (int) Math.min(255, (labelUntil - now) / 3);
            drawLines(c, label, w / 2f, h * 0.88f, w / 16f, a);
        }

        postInvalidateOnAnimation();
    }

    private void drawSign(Canvas c, int w, int h, long now) {
        float size = Math.min(h * 0.55f, w * 0.75f);
        if (now < flashUntil) size *= 1.12f;
        signPaint.setTextSize(size);
        float tw = signPaint.measureText(sign);
        if (Float.isNaN(signX)) signX = w;
        signX -= w * 0.009f * (1f + shownLevel * 1.5f);
        if (signX < -tw) signX = w;
        signPaint.setColor(color(hue, now < flashUntil ? 0.2f : 1f, 1f));
        signPaint.setShadowLayer(size * 0.08f, 0, 0, color(hue + 60, 1f, 1f));
        Paint.FontMetrics fm = signPaint.getFontMetrics();
        float y = h / 2f - (fm.ascent + fm.descent) / 2f;
        c.drawText(sign, signX, y, signPaint);
    }

    private void drawBars(Canvas c, int w, int h) {
        int n = shownBands.length;
        float gap = w * 0.012f;
        float bw = (w - gap * (n + 1)) / n;
        float mid = h / 2f;
        for (int i = 0; i < n; i++) {
            float v = shownBands[i] * shownBands[i] * (0.4f + shownLevel * 0.6f);
            float bh = Math.max(4f, v * h * 0.48f);
            float x = gap + i * (bw + gap);
            paint.setColor(color(hue + i * (300f / n), 1f, 1f));
            c.drawRoundRect(x, mid - bh, x + bw, mid + bh, bw / 3, bw / 3, paint);
        }
    }

    private void drawRings(Canvas c, int w, int h) {
        paint.setStyle(Paint.Style.STROKE);
        float grow = Math.max(w, h) * 0.022f;
        for (int i = rings.size() - 1; i >= 0; i--) {
            float[] r = rings.get(i);
            r[2] += grow * (0.6f + shownLevel);
            r[3] *= 0.955f;
            if (r[3] < 0.03f) {
                rings.remove(i);
                continue;
            }
            paint.setStrokeWidth(18f * r[3] + 4f);
            paint.setColor(color(r[4], 1f, 1f));
            paint.setAlpha((int) (r[3] * 255));
            c.drawCircle(r[0], r[1], r[2], paint);
        }
        paint.setAlpha(255);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawLines(Canvas c, String s, float cx, float cy, float size, int alpha) {
        text.setTextSize(size);
        text.setAlpha(alpha);
        text.setShadowLayer(8f, 0f, 0f, Color.BLACK);
        String[] lines = s.split("\n");
        float lh = size * 1.3f;
        float y = cy - (lines.length - 1) * lh / 2f;
        for (String line : lines) {
            c.drawText(line, cx, y, text);
            y += lh;
        }
    }
}
