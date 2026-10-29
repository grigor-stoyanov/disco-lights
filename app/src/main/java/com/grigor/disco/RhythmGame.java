package com.grigor.disco;

import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import java.util.ArrayList;
import java.util.Random;

/**
 * Tap-the-circle rhythm game. Each detected beat schedules a circle that should be
 * hit a whole number of beats later, so circles land on the music.
 */
class RhythmGame {
    private static final int GREAT = 80, GOOD = 160, OK = 250; // timing windows in ms

    private static class Note {
        float x, y;
        long hitAt;
        int number;
        float hue;
        boolean done;
    }

    private static class Popup {
        float x, y;
        long at;
        String text;
        int color;
    }

    private final ArrayList<Note> notes = new ArrayList<>();
    private final ArrayList<Popup> popups = new ArrayList<>();
    private final Random rnd = new Random();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] hsv = new float[3];
    private final SharedPreferences prefs;

    private int w, h;
    private float lastX = -1, lastY = -1;
    private int number;
    private long score, best;
    private int combo, maxCombo;
    private int hits300, hits100, hits50, misses;
    private long approach = 1000;

    RhythmGame(SharedPreferences prefs) {
        this.prefs = prefs;
        best = prefs.getLong("best", 0);
        stroke.setStyle(Paint.Style.STROKE);
        label.setColor(Color.WHITE);
        label.setFakeBoldText(true);
    }

    void reset() {
        notes.clear();
        popups.clear();
        score = 0;
        combo = maxCombo = 0;
        hits300 = hits100 = hits50 = misses = 0;
        number = 0;
        lastX = lastY = -1;
    }

    /** A bass beat was heard at {@code now}; {@code beatMs} is the tempo, or 0 if not known yet. */
    void onBeat(long now, long beatMs) {
        if (w == 0) return;
        if (beatMs <= 0) beatMs = 500;
        long beats = Math.max(1, Math.round(900.0 / beatMs));
        approach = beats * beatMs;
        long hitAt = now + approach;
        for (Note n : notes) {
            if (!n.done && Math.abs(n.hitAt - hitAt) < Math.max(200, beatMs * 0.6)) return;
        }
        if (rnd.nextFloat() < 0.2f) return; // leave some breathing room

        float r = radius();
        float margin = r * 1.4f;
        Note n = new Note();
        if (lastX < 0) {
            n.x = w / 2f;
            n.y = h / 2f;
        } else {
            float d = Math.min(w, h) * (0.18f + rnd.nextFloat() * 0.22f);
            for (int tries = 0; tries < 12; tries++) {
                double a = rnd.nextDouble() * Math.PI * 2;
                n.x = lastX + (float) Math.cos(a) * d;
                n.y = lastY + (float) Math.sin(a) * d;
                if (n.x > margin && n.x < w - margin && n.y > margin * 1.3f && n.y < h - margin * 1.3f) break;
            }
            n.x = Math.max(margin, Math.min(w - margin, n.x));
            n.y = Math.max(margin * 1.3f, Math.min(h - margin * 1.3f, n.y));
        }
        lastX = n.x;
        lastY = n.y;
        number = number % 4 + 1;
        n.number = number;
        n.hitAt = hitAt;
        n.hue = rnd.nextInt(360);
        notes.add(n);
    }

    void onTap(float x, float y, long now) {
        float r = radius() * 1.25f;
        Note target = null;
        for (Note n : notes) {
            if (n.done) continue;
            float dx = x - n.x, dy = y - n.y;
            if (dx * dx + dy * dy <= r * r && (target == null || n.hitAt < target.hitAt)) target = n;
        }
        if (target == null) return;
        long err = Math.abs(now - target.hitAt);
        if (err > OK) {
            if (now < target.hitAt - OK && target.hitAt - now < approach / 2) miss(target, now);
            return;
        }
        int pts;
        if (err <= GREAT) {
            pts = 300;
            hits300++;
        } else if (err <= GOOD) {
            pts = 100;
            hits100++;
        } else {
            pts = 50;
            hits50++;
        }
        combo++;
        maxCombo = Math.max(maxCombo, combo);
        score += pts * (1 + combo / 10);
        if (score > best) {
            best = score;
            prefs.edit().putLong("best", best).apply();
        }
        target.done = true;
        popup(target, now, String.valueOf(pts), pts == 300 ? Color.rgb(120, 220, 255)
                : pts == 100 ? Color.rgb(120, 255, 140) : Color.rgb(255, 220, 90));
    }

    private void miss(Note n, long now) {
        n.done = true;
        misses++;
        combo = 0;
        popup(n, now, "MISS", Color.rgb(255, 80, 80));
    }

    private void popup(Note n, long now, String text, int color) {
        Popup p = new Popup();
        p.x = n.x;
        p.y = n.y;
        p.at = now;
        p.text = text;
        p.color = color;
        popups.add(p);
    }

    private float radius() {
        return Math.min(w, h) * 0.085f;
    }

    private int color(float hue, float s, float v) {
        hsv[0] = ((hue % 360) + 360) % 360;
        hsv[1] = s;
        hsv[2] = v;
        return Color.HSVToColor(hsv);
    }

    void draw(Canvas c, int width, int height, long now, float level) {
        w = width;
        h = height;
        float r = radius();

        for (int i = notes.size() - 1; i >= 0; i--) {
            Note n = notes.get(i);
            if (!n.done && now - n.hitAt > OK) miss(n, now);
            if (n.done) notes.remove(i);
        }

        // Draw the latest-due notes first so the next one to hit sits on top.
        for (int i = notes.size() - 1; i >= 0; i--) {
            Note n = notes.get(i);
            long left = n.hitAt - now;
            float t = Math.max(0f, Math.min(1f, left / (float) approach));
            int alpha = (int) (255 * Math.min(1f, (1f - t) * 3f));

            fill.setColor(color(n.hue, 0.85f, 0.55f + level * 0.45f));
            fill.setAlpha(alpha);
            c.drawCircle(n.x, n.y, r, fill);

            stroke.setColor(Color.WHITE);
            stroke.setAlpha(alpha);
            stroke.setStrokeWidth(r * 0.12f);
            c.drawCircle(n.x, n.y, r, stroke);

            if (left > 0) {
                stroke.setColor(color(n.hue, 0.6f, 1f));
                stroke.setAlpha(alpha);
                stroke.setStrokeWidth(r * 0.08f);
                c.drawCircle(n.x, n.y, r * (1f + 2.6f * t), stroke);
            }

            label.setTextAlign(Paint.Align.CENTER);
            label.setTextSize(r);
            label.setAlpha(alpha);
            c.drawText(String.valueOf(n.number), n.x, n.y + r * 0.35f, label);
        }

        for (int i = popups.size() - 1; i >= 0; i--) {
            Popup p = popups.get(i);
            long age = now - p.at;
            if (age > 600) {
                popups.remove(i);
                continue;
            }
            label.setTextAlign(Paint.Align.CENTER);
            label.setTextSize(r * 0.7f);
            label.setColor(p.color);
            label.setAlpha((int) (255 * (1f - age / 600f)));
            c.drawText(p.text, p.x, p.y - r * 1.3f - age * 0.06f, label);
            label.setColor(Color.WHITE);
        }

        float pad = Math.min(w, h) * 0.04f;
        float small = Math.min(w, h) / 20f;
        label.setAlpha(230);
        label.setTextAlign(Paint.Align.LEFT);
        label.setTextSize(small * 1.3f);
        c.drawText(String.valueOf(score), pad, pad + small * 1.3f, label);
        label.setTextSize(small * 0.8f);
        label.setAlpha(170);
        c.drawText("BEST " + best + "   ACC " + accuracy() + "%", pad, pad + small * 2.4f, label);
        if (combo > 1) {
            label.setTextSize(small * 1.6f);
            label.setAlpha(230);
            c.drawText(combo + "x", pad, h - pad, label);
        }
        label.setTextAlign(Paint.Align.RIGHT);
        label.setTextSize(small * 0.7f);
        label.setAlpha(140);
        c.drawText("HOLD TO EXIT", w - pad, h - pad, label);
        label.setAlpha(255);
    }

    private int accuracy() {
        int total = hits300 + hits100 + hits50 + misses;
        if (total == 0) return 100;
        return Math.round((hits300 * 300f + hits100 * 100f + hits50 * 50f) * 100f / (total * 300f));
    }
}
