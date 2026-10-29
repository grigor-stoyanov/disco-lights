package com.grigor.disco;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;

/** Reads the microphone, measures loudness, a small spectrum and bass beats. */
class AudioEngine extends Thread {
    static final int BANDS = 16;
    private static final int RATE = 44100;
    private static final int N = 1024;

    private final DiscoView view;
    private volatile boolean running = true;
    private final float[] coeff = new float[BANDS];
    private final float[] bandPeak = new float[BANDS];

    AudioEngine(DiscoView view) {
        super("disco-audio");
        this.view = view;
        // Log-spaced band centres from 50 Hz to 8 kHz.
        for (int b = 0; b < BANDS; b++) {
            double f = 50 * Math.pow(8000.0 / 50.0, b / (double) (BANDS - 1));
            coeff[b] = (float) (2 * Math.cos(2 * Math.PI * f / RATE));
            bandPeak[b] = 1e-6f;
        }
    }

    void shutdown() {
        running = false;
        interrupt();
    }

    @Override
    public void run() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, N * 4));
        } catch (Exception e) {
            view.setMessage("Could not open the microphone.");
            return;
        }
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            view.setMessage("Could not open the microphone.");
            rec.release();
            return;
        }
        rec.startRecording();

        short[] buf = new short[N];
        float[] x = new float[N];
        float[] bands = new float[BANDS];
        float lp = 0f;
        float avgBass = 0f;
        float levelPeak = 1e-4f;
        long lastBeat = 0;

        while (running) {
            int r = rec.read(buf, 0, N);
            if (r <= 0) continue;

            double sum = 0, bass = 0;
            for (int i = 0; i < r; i++) {
                float s = buf[i] / 32768f;
                x[i] = s;
                sum += s * s;
                lp += 0.02f * (s - lp); // ~140 Hz low-pass for the kick drum
                bass += lp * lp;
            }
            float rms = (float) Math.sqrt(sum / r);
            float bassE = (float) (bass / r);

            // Auto gain: loud club or quiet room both fill the screen.
            levelPeak = Math.max(levelPeak * 0.997f, rms);
            float level = Math.min(1f, rms / Math.max(levelPeak, 1e-4f));
            if (rms < 0.002f) level *= rms / 0.002f; // silence stays dark

            for (int b = 0; b < BANDS; b++) {
                float s1 = 0, s2 = 0, c = coeff[b];
                for (int i = 0; i < r; i++) {
                    float s0 = x[i] + c * s1 - s2;
                    s2 = s1;
                    s1 = s0;
                }
                float p = s1 * s1 + s2 * s2 - c * s1 * s2;
                bandPeak[b] = Math.max(bandPeak[b] * 0.996f, p);
                float v = (float) Math.sqrt(p / Math.max(bandPeak[b], 1e-6f));
                bands[b] = rms < 0.002f ? v * rms / 0.002f : v;
            }

            boolean beat = false;
            long now = SystemClock.uptimeMillis();
            if (bassE > avgBass * 1.6f && bassE > 2e-6f && now - lastBeat > 180) {
                beat = true;
                lastBeat = now;
            }
            avgBass = avgBass * 0.93f + bassE * 0.07f;

            view.onAudio(level, bands, beat);
        }
        try {
            rec.stop();
        } catch (Exception ignored) {
        }
        rec.release();
    }
}
