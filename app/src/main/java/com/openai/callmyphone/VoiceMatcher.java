package com.openai.callmyphone;

import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

public final class VoiceMatcher {
    public static final int SAMPLE_RATE = 16000;
    private static final int FRAME = 320;
    private static final int HOP = 160;
    private static final double[] FREQS = {
            250, 350, 450, 600, 750, 950, 1200,
            1500, 1850, 2250, 2700, 3200, 3800
    };

    private VoiceMatcher() {}

    public static double[][] extract(short[] pcm) {
        if (pcm == null || pcm.length < 1200) return new double[0][0];
        short[] trimmed = trimSilence(pcm);
        if (trimmed.length < 1200) return new double[0][0];

        int frames = 1 + Math.max(0, (trimmed.length - FRAME) / HOP);
        double[][] out = new double[frames][FREQS.length];
        double[] window = hamming(FRAME);

        for (int f = 0; f < frames; f++) {
            int offset = f * HOP;
            double mean = 0.0;
            for (int b = 0; b < FREQS.length; b++) {
                double p = goertzel(trimmed, offset, window, FREQS[b]);
                double v = Math.log1p(p);
                out[f][b] = v;
                mean += v;
            }
            mean /= FREQS.length;
            double norm = 0.0;
            for (int b = 0; b < FREQS.length; b++) {
                out[f][b] -= mean;
                norm += out[f][b] * out[f][b];
            }
            norm = Math.sqrt(norm) + 1e-9;
            for (int b = 0; b < FREQS.length; b++) out[f][b] /= norm;
        }
        return out;
    }

    public static double distance(double[][] a, double[][] b) {
        if (a == null || b == null || a.length < 3 || b.length < 3) return 999.0;
        int n = a.length, m = b.length;
        double[][] dp = new double[n + 1][m + 1];
        for (int i = 0; i <= n; i++) {
            for (int j = 0; j <= m; j++) dp[i][j] = Double.POSITIVE_INFINITY;
        }
        dp[0][0] = 0.0;

        for (int i = 1; i <= n; i++) {
            int expected = (int) Math.round((double) i * m / n);
            int band = Math.max(6, Math.abs(n - m) + 4);
            int j0 = Math.max(1, expected - band);
            int j1 = Math.min(m, expected + band);
            for (int j = j0; j <= j1; j++) {
                double cost = cosineDistance(a[i - 1], b[j - 1]);
                double prev = Math.min(dp[i - 1][j], Math.min(dp[i][j - 1], dp[i - 1][j - 1]));
                dp[i][j] = cost + prev;
            }
        }

        double path = dp[n][m] / Math.max(n, m);
        double durationPenalty = Math.abs(Math.log((double) n / (double) m)) * 0.16;
        return path + durationPenalty;
    }

    public static boolean isMatch(double[][] template, short[] candidate) {
        double[][] c = extract(candidate);
        if (c.length < 3) return false;
        return distance(template, c) < 0.42;
    }

    public static String encode(double[][] features) {
        if (features == null || features.length == 0) return "";
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(bos);
            dos.writeInt(features.length);
            dos.writeInt(features[0].length);
            for (double[] row : features) {
                for (double v : row) dos.writeFloat((float) v);
            }
            dos.flush();
            return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable t) {
            return "";
        }
    }

    public static double[][] decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) return new double[0][0];
        try {
            byte[] raw = Base64.decode(encoded, Base64.NO_WRAP);
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(raw));
            int rows = dis.readInt();
            int cols = dis.readInt();
            if (rows <= 0 || rows > 500 || cols != FREQS.length) return new double[0][0];
            double[][] out = new double[rows][cols];
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) out[r][c] = dis.readFloat();
            }
            return out;
        } catch (Throwable t) {
            return new double[0][0];
        }
    }

    public static double rms(short[] data, int len) {
        if (data == null || len <= 0) return 0.0;
        double sum = 0.0;
        int n = Math.min(len, data.length);
        for (int i = 0; i < n; i++) {
            double v = data[i];
            sum += v * v;
        }
        return Math.sqrt(sum / n);
    }

    private static short[] trimSilence(short[] pcm) {
        int block = 320;
        int blocks = pcm.length / block;
        if (blocks < 2) return pcm;
        double[] levels = new double[blocks];
        double max = 0.0;
        for (int b = 0; b < blocks; b++) {
            double sum = 0.0;
            for (int i = 0; i < block; i++) {
                double v = pcm[b * block + i];
                sum += v * v;
            }
            levels[b] = Math.sqrt(sum / block);
            max = Math.max(max, levels[b]);
        }
        double threshold = Math.max(420.0, max * 0.22);
        int first = 0;
        while (first < blocks && levels[first] < threshold) first++;
        int last = blocks - 1;
        while (last >= first && levels[last] < threshold) last--;
        if (first >= blocks || last < first) return new short[0];
        first = Math.max(0, first - 1);
        last = Math.min(blocks - 1, last + 1);
        int start = first * block;
        int end = Math.min(pcm.length, (last + 1) * block);
        short[] out = new short[end - start];
        System.arraycopy(pcm, start, out, 0, out.length);
        return out;
    }

    private static double[] hamming(int n) {
        double[] w = new double[n];
        for (int i = 0; i < n; i++) w[i] = 0.54 - 0.46 * Math.cos(2.0 * Math.PI * i / (n - 1));
        return w;
    }

    private static double goertzel(short[] pcm, int offset, double[] window, double freq) {
        double k = 0.5 + ((FRAME * freq) / SAMPLE_RATE);
        double omega = (2.0 * Math.PI * k) / FRAME;
        double coeff = 2.0 * Math.cos(omega);
        double q0 = 0.0, q1 = 0.0, q2 = 0.0;
        for (int i = 0; i < FRAME; i++) {
            q0 = coeff * q1 - q2 + pcm[offset + i] * window[i];
            q2 = q1;
            q1 = q0;
        }
        double power = q1 * q1 + q2 * q2 - coeff * q1 * q2;
        return Math.max(0.0, power);
    }

    private static double cosineDistance(double[] a, double[] b) {
        double dot = 0.0, aa = 0.0, bb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            aa += a[i] * a[i];
            bb += b[i] * b[i];
        }
        if (aa < 1e-12 || bb < 1e-12) return 1.0;
        double cos = dot / (Math.sqrt(aa) * Math.sqrt(bb));
        cos = Math.max(-1.0, Math.min(1.0, cos));
        return 1.0 - cos;
    }
}
