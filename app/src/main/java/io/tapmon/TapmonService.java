package io.tapmon;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * 背面タップを見張る本体。
 *
 * 電池のために決めたこと。
 *
 *   1. 画面が消えている間はセンサーを外す。これが一番効く。
 *      逆に画面が点いている間は CPU がもともと起きているので、加速度計を足す分の
 *      負担は小さい。消えている間の tapmon の消費はゼロ（常駐はしているが何もしない）。
 *   2. まとめ配送 (maxReportLatencyUs) を使う。センサー側で数十ミリ秒ぶんを溜めてから
 *      渡してもらえば、CPU を起こす回数がその分だけ減る。溜めても 1 サンプルごとの
 *      時刻は保たれるので、判定は鈍らない。対応していない端末では黙って無視される。
 *   3. 判定は主スレッドから外す。UI スレッドを毎秒何十回も起こさない。
 *   4. 前面サービスも通知も持たない。アクセシビリティサービスはシステムが束縛して
 *      保つので、常駐のための仕掛けが要らない。
 *   5. 外部ライブラリを一切読まない。起動時に広げる dex が小さいほど速くて軽い。
 */
public final class TapmonService extends AccessibilityService
        implements SensorEventListener, TapDetector.Listener {

    /** 設定画面から状態を見るための参照。同じプロセスなので直に持てる。 */
    static TapmonService instance;

    /**
     * 設定画面が前面にある間は true。叩きを数えるだけで、動作は実行しない。
     * 感度を合わせている最中に「戻る」が飛んで画面が消えるのを防ぐため。
     */
    static volatile boolean tuning;

    private SensorManager sensors;
    private Sensor accelerometer;
    private HandlerThread thread;
    private Handler sensorHandler;
    private Handler mainHandler;
    private AudioManager audio;
    private TapDetector detector;

    private boolean sensing;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensors == null ? null
                : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        mainHandler = new Handler(Looper.getMainLooper());

        thread = new HandlerThread("tapmon-sensor");
        thread.start();
        sensorHandler = new Handler(thread.getLooper());
        detector = new TapDetector(this, sensorHandler);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        // どちらもシステムだけが送れる放送なので、Android 14 以降でも
        // RECEIVER_EXPORTED / NOT_EXPORTED の指定は要らない。
        registerReceiver(screenReceiver, f);

        instance = this;
        reload();
        Log.i(Actions.TAG, "見張りを始めました (センサー: "
                + (accelerometer == null ? "なし" : accelerometer.getName()) + ")");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        stopSensing();
        Actions.stopTorchWatch(this);
        try {
            unregisterReceiver(screenReceiver);
        } catch (RuntimeException ignored) {
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
        Log.i(Actions.TAG, "見張りを終えました");
        return super.onUnbind(intent);
    }

    /** 設定を変えたら呼ぶ。設定画面から直に叩かれる。 */
    void reload() {
        if (detector == null) return;
        detector.setSensitivity(Prefs.sensitivity(this));
        detector.setTripleWanted(Prefs.action(this, Prefs.SLOT_TRIPLE) != Actions.NONE);
        detector.setNoiseGate(Prefs.noiseGate(this));

        if (Actions.usesTorch(this)) {
            Actions.startTorchWatch(this, mainHandler);
        } else {
            Actions.stopTorchWatch(this);
        }

        stopSensing();          // 読み取り間隔が変わっているかもしれないので付け直す
        if (screenOn()) startSensing();
    }

    // ---- センサーの開け閉め ----

    private boolean screenOn() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    private void startSensing() {
        if (sensing || accelerometer == null || sensors == null) return;
        if (!Prefs.enabled(this)) return;

        // 200Hz を超えると HIGH_SAMPLING_RATE_SENSORS 権限が要る (Android 12 以降)。
        // tapmon はちょうど 200Hz までで止める。省電力では 100Hz。
        int periodUs = Prefs.powerSave(this) ? 10000 : 5000;
        // 判定の状態を消すのもセンサーの筋の上で。別スレッドから触らない。
        sensorHandler.post(resetDetector);
        sensing = sensors.registerListener(this, accelerometer, periodUs, 50000, sensorHandler);
        if (!sensing) Log.w(Actions.TAG, "センサーを開けませんでした");
    }

    private void stopSensing() {
        if (!sensing || sensors == null) return;
        sensors.unregisterListener(this);
        sensing = false;
        sensorHandler.post(resetDetector);
    }

    private final Runnable resetDetector = new Runnable() {
        @Override
        public void run() {
            detector.reset();
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
                startSensing();
            } else {
                stopSensing();
            }
        }
    };

    // ---- センサーから ----

    @Override
    public void onSensorChanged(SensorEvent e) {
        detector.onSample(e.timestamp, e.values[0], e.values[1], e.values[2]);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    /** 判定はセンサーの筋で走る。実行は主スレッドに渡す。 */
    @Override
    public void onTaps(final int count) {
        // 端末ごとの当たり外れを追えるように、気づいたことだけは残す。
        // 山の高さが分かれば、感度をどちらへ動かせばよいか決められる。
        Log.i(Actions.TAG, count + " 回タップ (山の高さ " + detector.lastPeak
                + " / しきい値 " + detector.threshold() + ")");
        if (tuning) {
            // 調整中。数えて見せるだけで、動作はしない。
            if (Prefs.vibrate(this)) buzz(count);
            return;
        }
        if (audio != null && audio.getMode() != AudioManager.MODE_NORMAL) {
            // 通話中・通話の呼び出し中は触らない。事故になる。
            return;
        }
        final int slot = (count >= 3) ? Prefs.SLOT_TRIPLE : Prefs.SLOT_DOUBLE;
        final int action = Prefs.action(this, slot);
        if (action == Actions.NONE) return;
        if (Prefs.vibrate(this)) buzz(count);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Actions.run(TapmonService.this, action, Prefs.app(TapmonService.this, slot));
            }
        });
    }

    /** 気づいた合図。2 回なら 1 度、3 回なら 2 度、短く震わせる。 */
    private void buzz(int count) {
        Vibrator v;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm =
                    (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            v = vm == null ? null : vm.getDefaultVibrator();
        } else {
            v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }
        if (v == null || !v.hasVibrator()) return;
        try {
            if (count >= 3) {
                v.vibrate(VibrationEffect.createWaveform(new long[]{0, 18, 60, 18}, -1));
            } else {
                v.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (RuntimeException ignored) {
        }
    }

    // ---- 設定画面に見せる状態 ----

    boolean sensing() {
        return sensing;
    }

    boolean hasAccelerometer() {
        return accelerometer != null;
    }

    String sensorName() {
        return accelerometer == null ? null : accelerometer.getName();
    }

    TapDetector detector() {
        return detector;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 画面の中身は受け取らない設定にしてある（res/xml/accessibility_service.xml）。
    }

    @Override
    public void onInterrupt() {
    }
}
