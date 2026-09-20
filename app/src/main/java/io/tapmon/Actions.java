package io.tapmon;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

/**
 * タップに割り当てられる動作。
 *
 * 追加の権限が要るものは一つも入れていない。
 *   ・戻る/ホーム/履歴/通知/クイック設定/画面ロック/電源メニュー/スクリーンショット
 *     … アクセシビリティサービスの performGlobalAction
 *   ・再生停止/曲送り/曲戻し … AudioManager#dispatchMediaKeyEvent（権限不要）
 *   ・ライト … CameraManager#setTorchMode（CAMERA 権限は不要）
 *   ・アプリを起動 … マニフェストの queries で見える範囲だけ
 */
final class Actions {

    static final String TAG = "tapmon";

    static final int NONE = 0;
    static final int BACK = 1;
    static final int HOME = 2;
    static final int RECENTS = 3;
    static final int NOTIFICATIONS = 4;
    static final int QUICK_SETTINGS = 5;
    static final int SCREENSHOT = 6;
    static final int LOCK_SCREEN = 7;
    static final int POWER_DIALOG = 8;
    static final int PLAY_PAUSE = 9;
    static final int NEXT_TRACK = 10;
    static final int PREV_TRACK = 11;
    static final int TORCH = 12;
    static final int LAUNCH_APP = 13;

    /** 設定画面に並べる順 */
    static final int[] ALL = {
            NONE, BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, SCREENSHOT,
            LOCK_SCREEN, POWER_DIALOG, PLAY_PAUSE, NEXT_TRACK, PREV_TRACK, TORCH, LAUNCH_APP,
    };

    private Actions() {
    }

    static int labelRes(int action) {
        switch (action) {
            case BACK: return R.string.act_back;
            case HOME: return R.string.act_home;
            case RECENTS: return R.string.act_recents;
            case NOTIFICATIONS: return R.string.act_notifications;
            case QUICK_SETTINGS: return R.string.act_quick_settings;
            case SCREENSHOT: return R.string.act_screenshot;
            case LOCK_SCREEN: return R.string.act_lock;
            case POWER_DIALOG: return R.string.act_power;
            case PLAY_PAUSE: return R.string.act_play_pause;
            case NEXT_TRACK: return R.string.act_next;
            case PREV_TRACK: return R.string.act_prev;
            case TORCH: return R.string.act_torch;
            case LAUNCH_APP: return R.string.act_app;
            default: return R.string.act_none;
        }
    }

    /** この端末・この OS で選べるか。 */
    static boolean available(Context c, int action) {
        switch (action) {
            case SCREENSHOT:
                // GLOBAL_ACTION_TAKE_SCREENSHOT は Android 11 から
                return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
            case TORCH:
                return torchCameraId(c) != null;
            default:
                return true;
        }
    }

    /** どちらかのタップにライトが割り当てられているか（見張りを始めるかの判断）。 */
    static boolean usesTorch(Context c) {
        return Prefs.action(c, Prefs.SLOT_DOUBLE) == TORCH
                || Prefs.action(c, Prefs.SLOT_TRIPLE) == TORCH;
    }

    /**
     * 実行する。arg は LAUNCH_APP のときだけ使う（"パッケージ名/クラス名"）。
     * 呼び出しは主スレッドから。
     */
    static void run(AccessibilityService svc, int action, String arg) {
        switch (action) {
            case NONE:
                return;
            case BACK:
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                return;
            case HOME:
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
                return;
            case RECENTS:
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS);
                return;
            case NOTIFICATIONS:
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
                return;
            case QUICK_SETTINGS:
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS);
                return;
            case SCREENSHOT:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT);
                }
                return;
            case LOCK_SCREEN:
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
                return;
            case POWER_DIALOG:
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG);
                return;
            case PLAY_PAUSE:
                mediaKey(svc, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                return;
            case NEXT_TRACK:
                mediaKey(svc, KeyEvent.KEYCODE_MEDIA_NEXT);
                return;
            case PREV_TRACK:
                mediaKey(svc, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                return;
            case TORCH:
                toggleTorch(svc);
                return;
            case LAUNCH_APP:
                launch(svc, arg);
                return;
            default:
        }
    }

    // ---- メディア ----

    /**
     * 押して離すまでを送る。宛先は Android が選ぶ（いま鳴っている、または最後に
     * 鳴ったメディアセッション）ので、どの音楽アプリでも同じように効く。
     */
    private static void mediaKey(Context c, int keyCode) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }

    // ---- ライト ----

    private static String torchId;
    private static boolean torchIdResolved;
    private static volatile boolean torchOn;
    private static boolean torchWatching;

    private static String torchCameraId(Context c) {
        if (torchIdResolved) return torchId;
        torchIdResolved = true;
        CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) return null;
        try {
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Boolean has = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(has)
                        && (facing == null || facing == CameraCharacteristics.LENS_FACING_BACK)) {
                    torchId = id;
                    break;
                }
            }
        } catch (CameraAccessException | RuntimeException e) {
            Log.w(TAG, "ライトを探せませんでした: " + e);
        }
        return torchId;
    }

    /**
     * いまの点灯状態を知るための見張り。ライトを割り当てているときだけ始める。
     * 他のアプリやクイック設定から点けた・消したも、これで拾える。
     */
    static void startTorchWatch(Context c, Handler h) {
        if (torchWatching || torchCameraId(c) == null) return;
        CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) return;
        try {
            cm.registerTorchCallback(torchCallback, h);
            torchWatching = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "ライトの見張りを始められませんでした: " + e);
        }
    }

    static void stopTorchWatch(Context c) {
        if (!torchWatching) return;
        CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
        if (cm != null) {
            try {
                cm.unregisterTorchCallback(torchCallback);
            } catch (RuntimeException ignored) {
            }
        }
        torchWatching = false;
    }

    private static final CameraManager.TorchCallback torchCallback =
            new CameraManager.TorchCallback() {
                @Override
                public void onTorchModeChanged(String cameraId, boolean enabled) {
                    if (cameraId.equals(torchId)) torchOn = enabled;
                }

                @Override
                public void onTorchModeUnavailable(String cameraId) {
                    if (cameraId.equals(torchId)) torchOn = false;
                }
            };

    private static void toggleTorch(Context c) {
        String id = torchCameraId(c);
        if (id == null) return;
        CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) return;
        try {
            cm.setTorchMode(id, !torchOn);
        } catch (CameraAccessException | RuntimeException e) {
            // カメラを使っているアプリがあるときは断られる。そのときは何もしない。
            Log.w(TAG, "ライトを切り替えられませんでした: " + e);
        }
    }

    /**
     * 消えている画面を点ける。アプリの起動を消灯中に割り当てたときだけ使う。
     *
     * 画面を点ける公開の手段はこれしかない（PowerManager#wakeUp はシステム専用）。
     * SCREEN_BRIGHT_WAKE_LOCK は API 17 で非推奨になったが、いまも効く。
     * 持ち続けると画面が消えなくなるので、3 秒で自動的に離れる形で握る。
     * なお、端末がロックされていればロック画面が出る。そこから先は利用者の手による。
     */
    private static void wakeScreen(Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        if (pm == null || pm.isInteractive()) return;
        try {
            @SuppressWarnings("deprecation")
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "tapmon:wake");
            wl.acquire(3000L);
        } catch (RuntimeException e) {
            Log.w(TAG, "画面を点けられませんでした: " + e);
        }
    }

    // ---- アプリの起動 ----

    private static void launch(AccessibilityService svc, String arg) {
        if (arg == null || arg.isEmpty()) return;
        // 画面が消えているときに起動しても見えない。先に画面を点ける。
        wakeScreen(svc);
        ComponentName cn = ComponentName.unflattenFromString(arg);
        if (cn == null) return;
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            svc.startActivity(i);
        } catch (RuntimeException e) {
            Log.w(TAG, "起動できませんでした: " + arg + " / " + e);
        }
    }
}
