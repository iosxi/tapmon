package io.tapmon;

import android.content.Context;
import android.content.SharedPreferences;

/** 覚えておくのは設定だけ。記録も統計も残さない。 */
final class Prefs {

    static final int SLOT_DOUBLE = 0;
    static final int SLOT_TRIPLE = 1;

    private static final String FILE = "tapmon";
    private static final String K_ENABLED = "enabled";
    private static final String K_ACTION = "action_";      // + slot
    private static final String K_APP = "app_";            // + slot
    private static final String K_SENS = "sens";
    private static final String K_VIBRATE = "vibrate";
    private static final String K_POWER_SAVE = "power_save";
    private static final String K_NOISE_GATE = "noise_gate";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        return sp(c).getBoolean(K_ENABLED, true);
    }

    static void setEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(K_ENABLED, v).apply();
    }

    /** 既定は 2 回タップ = 戻る、3 回タップ = なし。 */
    static int action(Context c, int slot) {
        int def = (slot == SLOT_DOUBLE) ? Actions.BACK : Actions.NONE;
        return sp(c).getInt(K_ACTION + slot, def);
    }

    static void setAction(Context c, int slot, int action) {
        sp(c).edit().putInt(K_ACTION + slot, action).apply();
    }

    /** 「アプリを起動」の行き先（"パッケージ名/クラス名"）。 */
    static String app(Context c, int slot) {
        return sp(c).getString(K_APP + slot, "");
    }

    static void setApp(Context c, int slot, String flat) {
        sp(c).edit().putString(K_APP + slot, flat).apply();
    }

    static int sensitivity(Context c) {
        int v = sp(c).getInt(K_SENS, TapDetector.DEFAULT_SENS);
        return Math.max(0, Math.min(TapDetector.THRESHOLDS.length - 1, v));
    }

    static void setSensitivity(Context c, int v) {
        sp(c).edit().putInt(K_SENS, v).apply();
    }

    static boolean vibrate(Context c) {
        return sp(c).getBoolean(K_VIBRATE, true);
    }

    static void setVibrate(Context c, boolean v) {
        sp(c).edit().putBoolean(K_VIBRATE, v).apply();
    }

    /** センサーを 200Hz ではなく 100Hz で読む。電池は減りにくいが取りこぼしやすい。 */
    static boolean powerSave(Context c) {
        return sp(c).getBoolean(K_POWER_SAVE, false);
    }

    static void setPowerSave(Context c, boolean v) {
        sp(c).edit().putBoolean(K_POWER_SAVE, v).apply();
    }

    /** 歩行中など揺れが続いている間は反応しない。 */
    static boolean noiseGate(Context c) {
        return sp(c).getBoolean(K_NOISE_GATE, true);
    }

    static void setNoiseGate(Context c, boolean v) {
        sp(c).edit().putBoolean(K_NOISE_GATE, v).apply();
    }
}
