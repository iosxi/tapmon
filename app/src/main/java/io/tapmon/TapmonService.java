package io.tapmon;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.util.List;
import java.util.Set;

/**
 * 背面タップを見張る本体。
 *
 * 電池のために決めたこと。
 *
 *   1. 画面が消えている間はセンサーを外す。これが一番効く。
 *      逆に画面が点いている間は CPU がもともと起きているので、加速度計を足す分の
 *      負担は小さい。消えている間の tapmon の消費はゼロ（常駐はしているが何もしない）。
 *      設定で消灯中も見張れるが、そこだけは電池を使う（下の「画面が消えている間」を見よ）。
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
    private PowerManager.WakeLock wakeLock;

    /**
     * 鳴り止んでからも見張りを続ける長さ。叩いて止めたあと、叩いて鳴らし直せるように。
     *
     * 鳴っている間しか見張らないと、止めた瞬間に見張りも終わるので鳴らし直せない。
     */
    private static final long PLAY_GRACE_MS = 5 * 60 * 1000L;

    /**
     * 「登録したアプリ」で見張るときの打ち切り。
     *
     * 何も起きないまま消灯が続くなら、いつかは CPU を寝かせないと一晩中起きたままになる。
     * タップを見つけるか音が鳴れば、そこから数え直す。
     */
    private static final long APP_CAP_MS = 30 * 60 * 1000L;

    /**
     * 見張っている間の見直しの間隔。
     *
     * 解放を再生状態の通知だけに頼ると握りっぱなしになる。実際になった:
     * AudioManager#isMusicActive() は鳴り止んだ直後にもまだ true を返すことがあり、
     * 通知の瞬間に判じると「まだ鳴っている」と読んでしまう。そのあと通知はもう来ない。
     * 握っている間は自分で見直す。
     */
    private static final long RECHECK_MS = 30 * 1000L;

    private boolean sensing;
    private boolean playbackWatching;
    private boolean rechecking;

    private long screenOffAtMs;
    private long lastAudioAtMs;
    private volatile long lastTapAtMs;

    /**
     * 最後に前面にあったアプリ。画面が消えたあとも、その値が残る。
     *
     * 「登録したアプリを使っていたとき」を判じるのに要る。取るのはパッケージ名だけで、
     * 画面の中身は読まない（そもそも canRetrieveWindowContent=false で読めない）。
     * この受け取り自体、アプリを登録したときにだけ動的に有効にする。
     */
    private volatile String lastForegroundPkg;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensors == null ? null
                : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        mainHandler = new Handler(Looper.getMainLooper());
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tapmon:screen-off");
            wakeLock.setReferenceCounted(false);   // 握り損ね・離し損ねを作らない
        }

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
        reload();      // 中で updateWatch() まで行く
        Log.i(Actions.TAG, "見張りを始めました (センサー: "
                + (accelerometer == null ? "なし" : accelerometer.getName()) + ")");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        stopSensing();
        stopPlaybackWatch();
        setRechecking(false);
        releaseWake();
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

        applyEventTypes();
        stopSensing();          // 読み取り間隔が変わっているかもしれないので付け直す
        updateWatch();
    }

    /**
     * いま見張るべきか、CPU を起こしておくべきかを、ここ一箇所で決める。
     * 画面の開閉・設定の変更・再生の開始終了、どの入口から来てもここに集まる。
     */
    private void updateWatch() {
        boolean wantSensor;
        boolean wantWake = false;
        boolean wantPlaybackWatch = false;

        if (!Prefs.enabled(this)) {
            wantSensor = false;
        } else if (screenOn()) {
            // 画面が点いている間は CPU がもともと起きている。ウェイクロックは要らない。
            wantSensor = true;
        } else {
            long now = SystemClock.elapsedRealtime();
            int mode = Prefs.screenOffMode(this);
            boolean playing = audio != null && audio.isMusicActive();
            if (playing) lastAudioAtMs = now;

            // 鳴っている間と、鳴り止んでからしばらく
            boolean audioSide = playing
                    || (lastAudioAtMs > 0 && now - lastAudioAtMs < PLAY_GRACE_MS);
            // 何も起きないまま時間が経ったら打ち切る
            long since = Math.max(screenOffAtMs, Math.max(lastAudioAtMs, lastTapAtMs));
            boolean withinCap = now - since < APP_CAP_MS;

            boolean byPlaying = (mode == Prefs.OFF_PLAYING || mode == Prefs.OFF_BOTH) && audioSide;
            boolean byApp = (mode == Prefs.OFF_APPS || mode == Prefs.OFF_BOTH)
                    && appRegistered() && withinCap;
            wantPlaybackWatch = (mode == Prefs.OFF_PLAYING || mode == Prefs.OFF_BOTH);
            wantSensor = (mode == Prefs.OFF_ALWAYS) || byPlaying || byApp;
            wantWake = wantSensor;
        }

        if (wantWake) acquireWake();
        if (wantSensor) startSensing(); else stopSensing();
        if (!wantWake) releaseWake();
        if (wantPlaybackWatch) startPlaybackWatch(); else stopPlaybackWatch();
        setRechecking(wantWake);
    }

    /** 握っている間だけ、ときどき自分で見直す。 */
    private void setRechecking(boolean on) {
        if (on == rechecking) return;
        rechecking = on;
        if (on) {
            mainHandler.postDelayed(recheck, RECHECK_MS);
        } else {
            mainHandler.removeCallbacks(recheck);
        }
    }

    private final Runnable recheck = new Runnable() {
        @Override
        public void run() {
            rechecking = false;     // updateWatch に付け直させる
            updateWatch();
        }
    };

    /** 最後に前面にあったアプリが、見張る相手として登録されているか。 */
    private boolean appRegistered() {
        String pkg = lastForegroundPkg;
        if (pkg == null) return false;
        return Prefs.watchApps(this).contains(pkg);
    }

    /**
     * 前面アプリの受け取りを、要るときだけ開ける。
     *
     * 「登録したアプリ」を使わない限り、tapmon は画面上のイベントを一つも受け取らない。
     * 使うときだけ typeWindowStateChanged を開ける。開けても届くのはどの窓が前に来たか
     * だけで、画面の中身は読まない。
     */
    private void applyEventTypes() {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        int mode = Prefs.screenOffMode(this);
        int want = (mode == Prefs.OFF_APPS || mode == Prefs.OFF_BOTH)
                ? AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED : 0;
        if (info.eventTypes == want) return;
        info.eventTypes = want;
        try {
            setServiceInfo(info);
        } catch (RuntimeException e) {
            Log.w(Actions.TAG, "受け取るイベントを変えられませんでした: " + e);
        }
    }

    // ---- 画面が消えている間 ----

    /**
     * CPU を寝かせない。
     *
     * この端末の加速度計には起床できる版が無い（dumpsys sensorservice で確認）。
     * 起床できるのは significant_motion や pick_up_gesture のような「大きな動き」を
     * 見るものばかりで、数十ミリ秒の叩きは見分けられない。だから画面が消えている間に
     * 叩きを拾うには、CPU を起こしたままにするより他にない。電池を使う道なので、
     * 既定では握らないし、握るのも「音が鳴っている間だけ」に絞ってある。
     */
    private void acquireWake() {
        if (wakeLock == null || wakeLock.isHeld()) return;
        wakeLock.acquire();
        Log.i(Actions.TAG, "画面消灯中の見張りを始めました (CPU を起こしたままにします)");
    }

    private void releaseWake() {
        if (wakeLock == null || !wakeLock.isHeld()) return;
        wakeLock.release();
        Log.i(Actions.TAG, "画面消灯中の見張りを終えました");
    }

    /** 鳴り始め・鳴り終わりを教えてもらう。権限は要らない。 */
    private void startPlaybackWatch() {
        if (playbackWatching || audio == null) return;
        try {
            audio.registerAudioPlaybackCallback(playbackCallback, mainHandler);
            playbackWatching = true;
        } catch (RuntimeException e) {
            Log.w(Actions.TAG, "再生の見張りを始められませんでした: " + e);
        }
    }

    private void stopPlaybackWatch() {
        if (!playbackWatching || audio == null) return;
        try {
            audio.unregisterAudioPlaybackCallback(playbackCallback);
        } catch (RuntimeException ignored) {
        }
        playbackWatching = false;
    }

    private final AudioManager.AudioPlaybackCallback playbackCallback =
            new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    updateWatch();
                }
            };

    // ---- センサーの開け閉め ----

    private boolean screenOn() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    private void startSensing() {
        if (sensing || accelerometer == null || sensors == null) return;

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
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                screenOffAtMs = SystemClock.elapsedRealtime();
            }
            updateWatch();
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
        lastTapAtMs = SystemClock.elapsedRealtime();   // 打ち切りを数え直す
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
        // 届くのは「登録したアプリ」を使うときだけ。しかも見るのはパッケージ名だけ。
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence pkg = event.getPackageName();
        if (pkg == null || pkg.length() == 0) return;
        String name = pkg.toString();
        // 自分自身と、通知パネルなどのシステムの窓では前面アプリを塗り替えない
        if (name.equals(getPackageName()) || name.startsWith("com.android.systemui")) return;
        lastForegroundPkg = name;
    }

    @Override
    public void onInterrupt() {
    }
}
