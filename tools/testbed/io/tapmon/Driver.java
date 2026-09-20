package io.tapmon;

import android.os.Handler;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** TapDetector に作った波形を流し込んで、何回タップと数えるかを測る。 */
public final class Driver {

    // 端末を少し傾けて持っている想定の重力
    static final float GX = 0.6f, GY = -4.0f, GZ = 8.93f;

    static Random rnd = new Random(12345);

    /**
     * 1 回の叩きの波形。実機で測った形に合わせてある。
     * 4ms ほどで頂点まで立ち上がり、そのあと時定数 12ms で減衰しながら揺れる。
     * (最初は 8ms の半正弦で書いていたが、実測はもっと長く尾を引いていた)
     */
    static float impulse(float tMs, float amp, float widthMs) {
        if (tMs < 0 || tMs > 80) return 0f;
        float rise = 4f;
        float env = tMs < rise ? tMs / rise : (float) Math.exp(-(tMs - rise) / 12.0);
        float osc = tMs < rise ? 1f : (float) Math.cos(2 * Math.PI * 55.0 * (tMs - rise) / 1000.0);
        return amp * env * osc;
    }

    static final class Result {
        List<Integer> fired = new ArrayList<>();
        float peak, rejected, noise;
        int knocks;
        List<Long> firedAtMs = new ArrayList<>();
    }

    /**
     * @param taps      叩く時刻 (ms)
     * @param amp       叩きの強さ (m/s^2)
     * @param axis      0=Z(背面を叩く) 1=-Z(画面側) 2=X(横)
     * @param hz        読み取り間隔
     * @param walk      歩行の揺れを混ぜるか
     * @param lift      持ち上げる動きを混ぜるか
     * @param batch     まとめ配送のサンプル数 (1 = 溜めない)
     */
    static Result run(float[] taps, float amp, int axis, int hz, boolean walk, boolean lift,
                      int batch, int sens, boolean triple, boolean gate, float durationMs) {
        final Result res = new Result();
        Handler h = new Handler();
        TapDetector d = new TapDetector(new TapDetector.Listener() {
            @Override
            public void onTaps(int count) {
                res.fired.add(count);
                res.firedAtMs.add(SystemClock.nowNs / 1000000L);
            }
        }, h);
        d.setSensitivity(sens);
        d.setTripleWanted(triple);
        d.setNoiseGate(gate);
        d.reset();

        float stepMs = 1000f / hz;
        SystemClock.nowNs = 0;
        int n = (int) (durationMs / stepMs);
        long[] pendTs = new long[Math.max(batch, 1)];
        float[][] pendVal = new float[Math.max(batch, 1)][3];
        int held = 0;

        for (int i = 0; i < n; i++) {
            float tMs = i * stepMs;
            float x = GX, y = GY, z = GZ;

            if (walk) {
                // 2Hz 前後の上下動と腕の振り。歩行の実測はこの程度の大きさ。
                double w = 2 * Math.PI * 1.9 * tMs / 1000.0;
                z += 3.0f * (float) Math.sin(w) + 1.2f * (float) Math.sin(3 * w);
                y += 2.0f * (float) Math.cos(w * 0.5);
                x += 1.5f * (float) Math.sin(w * 1.3);
            }
            if (lift) {
                // 400ms かけて持ち上げ、400ms かけて止まる
                if (tMs > 300 && tMs < 1100) {
                    z += 6.0f * (float) Math.sin(Math.PI * (tMs - 300) / 800.0);
                }
            }
            for (float tap : taps) {
                float v = impulse(tMs - tap, amp, 8f);
                if (v == 0f) continue;
                if (axis == 0) {
                    z += v;
                    x += 0.15f * v;
                    y += 0.15f * v;
                } else if (axis == 1) {
                    z -= v;
                } else {
                    x += v;
                    z += 0.2f * v;
                }
            }
            // センサーの雑音
            x += (float) rnd.nextGaussian() * 0.03f;
            y += (float) rnd.nextGaussian() * 0.03f;
            z += (float) rnd.nextGaussian() * 0.03f;

            long tsNs = (long) (tMs * 1e6f);
            if (batch <= 1) {
                SystemClock.nowNs = tsNs;
                d.onSample(tsNs, x, y, z);
                h.pump();
            } else {
                pendTs[held] = tsNs;
                pendVal[held][0] = x;
                pendVal[held][1] = y;
                pendVal[held][2] = z;
                held++;
                if (held == batch) {
                    SystemClock.nowNs = tsNs;        // まとめて届くので到着は最後の時刻
                    for (int k = 0; k < held; k++) {
                        d.onSample(pendTs[k], pendVal[k][0], pendVal[k][1], pendVal[k][2]);
                    }
                    held = 0;
                    h.pump();
                }
            }
        }
        // 窓が閉じるのを待つ
        for (int k = 0; k < 50; k++) {
            SystemClock.nowNs += 20L * 1000000L;
            h.pump();
        }
        res.peak = d.lastPeak;
        res.rejected = d.lastRejected;
        res.noise = d.noise;
        res.knocks = d.knockCount;
        return res;
    }

    static void show(String name, Result r, String expect) {
        String got = r.fired.isEmpty() ? "なし" : r.fired.toString();
        boolean ok = got.equals(expect) || (expect.equals("なし") && r.fired.isEmpty());
        System.out.printf("%-38s  結果=%-10s 期待=%-10s %s  (山=%d 直近=%.1f 弾いた=%.1f 揺れ=%.2f)%n",
                name, got, expect, ok ? "OK" : "**ちがう**",
                r.knocks, r.peak, r.rejected, r.noise);
    }

    /** 実機で記録した波形をそのまま流し込む。 */
    static void replay(String path, int sens, boolean triple, int skip) throws Exception {
        java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(path), "UTF-8"));
        String line;
        int two = 0, three = 0, none = 0, total = 0;
        List<Integer> knocks = new ArrayList<>();
        while ((line = br.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            String[] f = line.split(",");
            float dtMs = Float.parseFloat(f[0]) * skip;
            final Result res = new Result();
            Handler h = new Handler();
            TapDetector d = new TapDetector(new TapDetector.Listener() {
                @Override
                public void onTaps(int count) {
                    res.fired.add(count);
                }
            }, h);
            d.setSensitivity(sens);
            d.setTripleWanted(triple);
            d.setNoiseGate(true);
            d.reset();
            SystemClock.nowNs = 0;
            int idx = 0;
            for (int i = 1; i < f.length; i += skip) {
                float lz = Float.parseFloat(f[i]);
                long ts = (long) (idx * dtMs * 1e6f);
                SystemClock.nowNs = ts;
                // 記録してあるのは重力を抜いた Z 成分。重力を足し戻して食わせる。
                d.onSample(ts, GX, GY, GZ + lz);
                h.pump();
                idx++;
            }
            for (int k = 0; k < 60; k++) {
                SystemClock.nowNs += 20L * 1000000L;
                h.pump();
            }
            total++;
            knocks.add(d.knockCount);
            if (res.fired.contains(3)) three++;
            else if (res.fired.contains(2)) two++;
            else none++;
        }
        br.close();
        System.out.printf("  感度=%d %-14s 2回と判定 %2d / 3回 %2d / 反応なし %2d  (全 %d 本, 数えた山 %s)%n",
                sens, skip > 1 ? ("間引き1/" + skip) : "そのまま", two, three, none, total, knocks);
    }

    public static void main(String[] args) throws Exception {
        int S = TapDetector.DEFAULT_SENS;   // 標準

        show("静止 / 2回叩き 8.0",
                run(new float[]{500, 700}, 8f, 0, 200, false, false, 1, S, false, true, 2000), "[2]");
        show("静止 / 2回叩き 8.0 / 3回タップ有効",
                run(new float[]{500, 700}, 8f, 0, 200, false, false, 1, S, true, true, 2000), "[2]");
        show("静止 / 3回叩き / 3回タップ有効",
                run(new float[]{500, 700, 900}, 8f, 0, 200, false, false, 1, S, true, true, 2000), "[3]");
        show("静止 / 3回叩き / 3回タップ無効",
                run(new float[]{500, 700, 900}, 8f, 0, 200, false, false, 1, S, false, true, 2000), "[2]");
        show("静止 / 1回だけ",
                run(new float[]{500}, 8f, 0, 200, false, false, 1, S, false, true, 2000), "なし");
        show("静止 / 間が空いた2回 (600ms)",
                run(new float[]{500, 1100}, 8f, 0, 200, false, false, 1, S, false, true, 2500), "なし");
        show("静止 / 速い連打 (120ms)",
                run(new float[]{500, 620}, 8f, 0, 200, false, false, 1, S, false, true, 2000), "[2]");
        show("静止 / 弱い叩き 3.0",
                run(new float[]{500, 700}, 3f, 0, 200, false, false, 1, S, false, true, 2000), "なし");
        show("静止 / 弱い叩き 3.0 / 感度=とても敏感",
                run(new float[]{500, 700}, 3f, 0, 200, false, false, 1, 4, false, true, 2000), "[2]");
        show("画面側を2回叩く",
                run(new float[]{500, 700}, 8f, 1, 200, false, false, 1, S, false, true, 2000), "なし");
        show("横から2回叩く",
                run(new float[]{500, 700}, 8f, 2, 200, false, false, 1, S, false, true, 2000), "なし");
        show("持ち上げて置く (叩かない)",
                run(new float[]{}, 0f, 0, 200, false, true, 1, S, false, true, 2500), "なし");
        show("歩行 (叩かない) / 揺れ止め入",
                run(new float[]{}, 0f, 0, 200, true, false, 1, S, false, true, 4000), "なし");
        show("歩行 + 2回叩き / 揺れ止め入",
                run(new float[]{2500, 2700}, 9f, 0, 200, true, false, 1, S, false, true, 4000), "なし");
        show("歩行 + 2回叩き / 揺れ止め切",
                run(new float[]{2500, 2700}, 9f, 0, 200, true, false, 1, S, false, false, 4000), "[2]");
        show("まとめ配送 10 サンプル / 3回叩き",
                run(new float[]{500, 700, 900}, 8f, 0, 200, false, false, 10, S, true, true, 2000), "[3]");
        show("まとめ配送 10 サンプル / 2回叩き",
                run(new float[]{500, 700}, 8f, 0, 200, false, false, 10, S, true, true, 2000), "[2]");

        System.out.println();
        System.out.println("---- 実機 (Xperia XQ-FS44) で記録した 22 本の波形を再生 ----");
        System.out.println("  (1 本 = 背面を叩いた前後 743ms。ほとんどが 2 回叩きの一組)");
        for (int s2 = 0; s2 < 5; s2++) replay("traces.csv", s2, false, 1);
        System.out.println("  -- 省電力 (215Hz を 1/2 に間引いて約 108Hz) --");
        for (int s2 = 1; s2 < 5; s2++) replay("traces.csv", s2, false, 2);

        System.out.println();
        System.out.println("---- 読み取り間隔で山の高さがどう変わるか (叩き 8.0) ----");
        for (int hz : new int[]{200, 100, 50}) {
            int hit = 0;
            float sum = 0;
            for (int shift = 0; shift < 20; shift++) {
                // 叩く時刻をサンプル境界に対してずらして 20 通り試す
                float t0 = 500f + shift * 0.25f;
                Result r = run(new float[]{t0, t0 + 200}, 8f, 0, hz, false, false, 1, S, false,
                        true, 2000);
                if (!r.fired.isEmpty()) hit++;
                sum += r.peak;
            }
            System.out.printf("%3dHz: 20 回中 %2d 回成立 / 山の高さの平均 %.1f%n", hz, hit, sum / 20);
        }

        System.out.println();
        System.out.println("---- 叩きの強さと感度の関係 (200Hz, 20 通りのずれで成立した回数) ----");
        System.out.printf("%-10s", "強さ\\感度");
        String[] names = {"とても鈍い", "鈍い", "標準", "敏感", "とても敏感"};
        for (String n : names) System.out.printf("%12s", n);
        System.out.println();
        for (float amp : new float[]{2f, 3f, 4f, 6f, 8f, 12f}) {
            System.out.printf("%-10.0f", amp);
            for (int s = 0; s < 5; s++) {
                int hit = 0;
                for (int shift = 0; shift < 20; shift++) {
                    float t0 = 500f + shift * 0.25f;
                    Result r = run(new float[]{t0, t0 + 200}, amp, 0, 200, false, false, 1, s,
                            false, true, 2000);
                    if (!r.fired.isEmpty()) hit++;
                }
                System.out.printf("%12s", hit + "/20");
            }
            System.out.println();
        }
    }
}
