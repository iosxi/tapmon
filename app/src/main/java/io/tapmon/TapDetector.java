package io.tapmon;

import android.os.Handler;
import android.os.SystemClock;

/**
 * 加速度の波形から「背面を 2 回 / 3 回叩いた」を見つける。
 *
 * 学習モデルは使わない。指で叩いた衝撃には、他の動きと分けられる素性が三つある。
 *
 *   1. 向き   … 背面を叩くと端末は画面の側へ押される。Android の座標系では +Z。
 *               画面側を叩いた場合や横揺れは -Z や XY に出るので、そこで弾ける。
 *   2. 短さ   … 衝撃は数ミリ秒から数十ミリ秒で終わる。持ち上げる・置く・傾けるは
 *               もっと長く続く。山が {@link #MAX_PEAK_NS} 以内に収まらなければ捨てる。
 *   3. 静けさ … 歩行や乗り物の揺れが続いている間は、どんなしきい値でも誤検出する。
 *               直近の揺れの平均が大きい間は最初から見送る。
 *
 * この三つを通った山を「叩き (knock)」と数え、{@link #WINDOW_NS} 以内に 2 つ並べば
 * 2 回タップ、3 つ並べば 3 回タップとする。
 *
 * 時間はすべて SensorEvent#timestamp（端末の単調増加時計）で測る。
 * 到着時刻を使うと、センサーのまとめ配送（バッチ）で間隔が潰れて測れなくなる。
 */
final class TapDetector {

    interface Listener {
        /** count は 2 か 3。センサーの筋（別スレッド）から呼ばれる。 */
        void onTaps(int count);
    }

    /**
     * 感度の段。数字は +Z 方向の加速度のしきい値 (m/s^2)。小さいほど敏感。
     *
     * 実機 (Xperia XQ-FS44 / LSM6DSO) で普通の強さの背面タップを 36 回測ったところ、
     * 山の高さは最小 3.8 / 中央 5.7 / 最大 8.8 だった。一方、端末を持ち替えるなどの
     * 普通の扱いで立つ山は 1〜3 に収まる。その隙間に「標準」を置いてある。
     */
    static final float[] THRESHOLDS = {6.5f, 4.5f, 3.2f, 2.6f, 2.0f};
    static final int DEFAULT_SENS = 2;

    /** 山がこれより長く続くならタップではない（持ち上げ・置く・揺れ） */
    private static final long MAX_PEAK_NS = 70L * 1000000L;
    /** 山を抜けてから次を数え始めるまでの休み。人の連打の最速は 100ms 前後 */
    private static final long REFRACTORY_NS = 80L * 1000000L;
    /**
     * 次の叩きを待つ窓。
     *
     * 実測した 2 回タップ 16 組の間隔は 214〜507ms、中央 349ms だった。
     * 350ms では 16 組中 7 組が窓から落ちる。450ms なら落ちるのは 1 組。
     * これ以上広げると、3 回タップを使うときの待ちが長くなるのと、
     * 無関係な 2 つの衝撃が偶然 1 組と見なされる危険が増える。
     */
    private static final long WINDOW_NS = 450L * 1000000L;
    private static final long WINDOW_MS = WINDOW_NS / 1000000L;

    /** 揺れの平均がこれを超えている間は見送る (m/s^2) */
    private static final float NOISE_MAX = 2.0f;
    /** 揺れの平均に 1 サンプルが足せる上限。叩きの山で平均が跳ね上がらないように */
    private static final float NOISE_CAP = 4.0f;

    private static final float GRAVITY_TAU = 0.5f;   // 重力の追従の遅さ (秒)
    private static final float NOISE_TAU = 1.5f;     // 揺れの平均の窓 (秒)
    /** 山の頂が Z に寄っていること。横揺れを弾く */
    private static final float Z_SHARE = 0.5f;

    private final Listener listener;
    private final Handler handler;

    private float threshold = THRESHOLDS[DEFAULT_SENS];
    private boolean tripleWanted;
    private boolean noiseGate = true;

    // --- 波形の状態 ---
    private boolean primed;          // 重力の推定が落ち着いたか
    private float gx, gy, gz;
    private long lastNs;

    private boolean inPeak;
    private long peakStartNs;
    private float peakMax;
    private long lastCommitNs;

    // --- 叩きの並び ---
    private int knocks;
    private long firstNs;
    private long secondNs;
    private boolean pending;         // 3 回目を待っている最中か

    // --- 調整画面に見せる値（別スレッドから読む） ---
    volatile float lastPeak;         // 直近に通った山の高さ
    volatile float lastRejected;     // 直近に弾いた山の高さ（しきい値を決める手がかり）
    volatile float noise;            // いまの揺れの平均
    volatile int knockCount;         // 通算の叩き数
    volatile long lastKnockAtMs;

    TapDetector(Listener listener, Handler handler) {
        this.listener = listener;
        this.handler = handler;
    }

    void setSensitivity(int level) {
        threshold = THRESHOLDS[Math.max(0, Math.min(THRESHOLDS.length - 1, level))];
    }

    float threshold() {
        return threshold;
    }

    void setTripleWanted(boolean v) {
        tripleWanted = v;
    }

    void setNoiseGate(boolean v) {
        noiseGate = v;
    }

    /** 見張りを始める / やめるたびに呼ぶ。前回の残りを引きずらない。 */
    void reset() {
        primed = false;
        inPeak = false;
        knocks = 0;
        noise = 0f;
        cancelPending();
    }

    /**
     * 加速度 1 サンプル。x,y,z は m/s^2（重力を含む生の値）。
     */
    void onSample(long tNs, float x, float y, float z) {
        if (!primed) {
            gx = x;
            gy = y;
            gz = z;
            lastNs = tNs;
            // 不応期の起点。Long.MIN_VALUE を置くと引き算が桁あふれして
            // 「ずっと不応期」になる（実際にそれで一度も反応しなくなった）。
            lastCommitNs = tNs - REFRACTORY_NS;
            primed = true;
            return;
        }
        float dt = (tNs - lastNs) / 1e9f;
        lastNs = tNs;
        // センサーが止まっていた後などに飛んだ時刻が来る。その 1 サンプルは捨てる。
        if (dt <= 0f || dt > 0.5f) {
            gx = x;
            gy = y;
            gz = z;
            inPeak = false;
            return;
        }

        // 重力はゆっくり追う。速い成分（叩き）は引き算で残る。
        float ga = dt / (GRAVITY_TAU + dt);
        gx += ga * (x - gx);
        gy += ga * (y - gy);
        gz += ga * (z - gz);

        float lx = x - gx;
        float ly = y - gy;
        float lz = z - gz;
        float mag = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);

        // 揺れの平均。1 サンプルの寄与に上限を付けてあるので、短い叩きでは跳ねない。
        float na = dt / (NOISE_TAU + dt);
        noise += na * (Math.min(mag, NOISE_CAP) - noise);

        if (inPeak) {
            if (lz > peakMax) peakMax = lz;
            if (tNs - peakStartNs > MAX_PEAK_NS) {
                // 長すぎる。叩きではなかった。
                inPeak = false;
                lastRejected = peakMax;
                lastCommitNs = tNs;          // 揺れが収まるまで少し待つ
            } else if (lz < threshold * 0.4f) {
                inPeak = false;
                lastCommitNs = tNs;
                commit(peakStartNs, peakMax);
            }
            return;
        }

        if (tNs - lastCommitNs < REFRACTORY_NS) return;
        if (lz < threshold) return;
        if (noiseGate && noise > NOISE_MAX) {
            lastRejected = lz;
            return;
        }
        if (lz < Z_SHARE * mag) {           // 頂が Z に寄っていない = 横揺れ
            lastRejected = lz;
            return;
        }
        inPeak = true;
        peakStartNs = tNs;
        peakMax = lz;
    }

    private void commit(long tNs, float amp) {
        lastPeak = amp;
        knockCount++;
        lastKnockAtMs = SystemClock.uptimeMillis();

        if (knocks == 1 && tNs - firstNs <= WINDOW_NS) {
            knocks = 2;
            secondNs = tNs;
            if (tripleWanted) {
                // 3 回目が来るかもしれない。窓の残りだけ待ってから 2 回として出す。
                pending = true;
                handler.postDelayed(fireDouble, remainingMs(tNs));
            } else {
                knocks = 0;
                fire(2);
            }
            return;
        }
        if (knocks == 2 && pending && tNs - secondNs <= WINDOW_NS) {
            cancelPending();
            knocks = 0;
            fire(3);
            return;
        }
        // それ以外は新しい 1 回目として数え直す
        cancelPending();
        knocks = 1;
        firstNs = tNs;
    }

    /**
     * 叩きの時刻から窓の終わりまで、あと何ミリ秒か。
     *
     * センサーがまとめて配送してくると、叩きの時刻はもう過去になっている。
     * その遅れを引いて待つ。時計の系が違う端末に当たっても破綻しないよう、
     * 遅れが理屈に合う範囲のときだけ差し引く。
     */
    private long remainingMs(long tNs) {
        long lagMs = (SystemClock.elapsedRealtimeNanos() - tNs) / 1000000L;
        if (lagMs < 0 || lagMs > WINDOW_MS) return WINDOW_MS;
        return WINDOW_MS - lagMs;
    }

    private final Runnable fireDouble = new Runnable() {
        @Override
        public void run() {
            pending = false;
            knocks = 0;
            fire(2);
        }
    };

    private void cancelPending() {
        if (pending) {
            pending = false;
            handler.removeCallbacks(fireDouble);
        }
    }

    private void fire(int count) {
        listener.onTaps(count);
    }
}
