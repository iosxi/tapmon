package io.tapmon;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 設定と調整の画面。
 *
 * 感度は数字で決める。この画面を開いている間、tapmon は叩きを数えるだけで動作は
 * 実行しないので、実際に背面を叩いて「いくつの山が立つか」を見ながら合わせられる。
 * 端末の厚みやケースの有無で山の高さは変わるから、既定値を信じるより測った方が早い。
 */
public final class MainActivity extends Activity {

    private TextView statusA11y;
    private TextView statusSensor;
    private TextView live;
    private TextView sensLabel;
    private SeekBar sensBar;
    private Button a11yButton;
    private Button doubleButton;
    private Button tripleButton;
    private Switch enableSwitch;
    private Switch vibrateSwitch;
    private Switch gateSwitch;
    private Switch saveSwitch;
    private Button screenOffButton;
    private Button watchAppsButton;
    private TextView watchAppsStatus;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(36), dp(24), dp(40));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextColor(0xFFF2F2F5);
        title.setTextSize(30f);
        root.addView(title);

        TextView lead = new TextView(this);
        lead.setText(R.string.lead);
        lead.setTextColor(0xFFA8A8B4);
        lead.setTextSize(13f);
        lead.setLineSpacing(0f, 1.25f);
        addWithTop(root, lead, 8);

        // ---- 状態 ----
        addHeading(root, R.string.h_status, 28);
        statusA11y = addStatus(root);
        statusSensor = addStatus(root);

        a11yButton = new Button(this);
        a11yButton.setText(R.string.open_a11y);
        a11yButton.setOnClickListener(v -> openAccessibilitySettings());
        addWithTop(root, a11yButton, 12);

        enableSwitch = addSwitch(root, R.string.enable, 16);
        enableSwitch.setOnCheckedChangeListener((bv, checked) -> {
            Prefs.setEnabled(this, checked);
            apply();
        });

        // ---- 割り当て ----
        addHeading(root, R.string.h_assign, 28);
        doubleButton = addAssignButton(root, R.string.slot_double, Prefs.SLOT_DOUBLE);
        tripleButton = addAssignButton(root, R.string.slot_triple, Prefs.SLOT_TRIPLE);

        TextView assignNote = addNote(root, R.string.assign_note, 8);
        assignNote.setTextSize(12f);

        // ---- 感度 ----
        addHeading(root, R.string.h_sens, 28);
        sensLabel = new TextView(this);
        sensLabel.setTextColor(0xFFF2F2F5);
        sensLabel.setTextSize(18f);
        addWithTop(root, sensLabel, 4);

        sensBar = new SeekBar(this);
        sensBar.setMax(TapDetector.THRESHOLDS.length - 1);
        sensBar.setProgress(Prefs.sensitivity(this));
        sensBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                Prefs.setSensitivity(MainActivity.this, progress);
                updateSensText();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                apply();
            }
        });
        addWithTop(root, sensBar, 8);

        live = new TextView(this);
        live.setTextColor(0xFF7FE3B0);
        live.setTextSize(13f);
        live.setLineSpacing(0f, 1.35f);
        addWithTop(root, live, 8);

        addNote(root, R.string.sens_note, 8);

        // ---- 誤作動を防ぐ ----
        addHeading(root, R.string.h_guard, 28);
        gateSwitch = addSwitch(root, R.string.guard_noise, 4);
        gateSwitch.setOnCheckedChangeListener((bv, checked) -> {
            Prefs.setNoiseGate(this, checked);
            apply();
        });
        addNote(root, R.string.guard_note, 4);

        // ---- 画面が消えている間 ----
        addHeading(root, R.string.h_screen_off, 28);
        screenOffButton = new Button(this);
        screenOffButton.setOnClickListener(v -> chooseScreenOffMode());
        addWithTop(root, screenOffButton, 4);

        watchAppsButton = new Button(this);
        watchAppsButton.setOnClickListener(v -> chooseWatchApps());
        addWithTop(root, watchAppsButton, 8);

        watchAppsStatus = new TextView(this);
        watchAppsStatus.setTextColor(0xFF7FE3B0);
        watchAppsStatus.setTextSize(13f);
        addWithTop(root, watchAppsStatus, 6);

        addNote(root, R.string.screen_off_note, 8);

        // ---- 電池 ----
        addHeading(root, R.string.h_battery, 28);
        saveSwitch = addSwitch(root, R.string.power_save, 4);
        saveSwitch.setOnCheckedChangeListener((bv, checked) -> {
            Prefs.setPowerSave(this, checked);
            apply();
        });
        addNote(root, R.string.battery_note, 4);

        vibrateSwitch = addSwitch(root, R.string.vibrate, 16);
        vibrateSwitch.setOnCheckedChangeListener((bv, checked) -> Prefs.setVibrate(this, checked));

        // ---- 使い方 ----
        addHeading(root, R.string.h_how, 28);
        addNote(root, R.string.how_body, 6);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // この画面を見ている間は動作を実行しない。感度合わせの邪魔になるため。
        TapmonService.tuning = true;
        refresh();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        TapmonService.tuning = false;
        handler.removeCallbacks(tick);
    }

    /** 設定を保存したあと、動いているサービスにも伝える。 */
    private void apply() {
        TapmonService svc = TapmonService.instance;
        if (svc != null) svc.reload();
        refresh();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateLive();
            handler.postDelayed(this, 150L);
        }
    };

    private void refresh() {
        boolean inSettings = a11yEnabledInSettings();
        TapmonService svc = TapmonService.instance;

        statusA11y.setText(getString(R.string.st_a11y,
                getString(inSettings ? R.string.on : R.string.off)));
        a11yButton.setVisibility(inSettings ? View.GONE : View.VISIBLE);

        if (svc == null) {
            statusSensor.setText(getString(R.string.st_sensor_unknown));
        } else if (!svc.hasAccelerometer()) {
            statusSensor.setText(getString(R.string.st_sensor_none));
        } else {
            statusSensor.setText(getString(R.string.st_sensor, svc.sensorName(),
                    getString(svc.sensing() ? R.string.watching : R.string.waiting)));
        }

        enableSwitch.setChecked(Prefs.enabled(this));
        vibrateSwitch.setChecked(Prefs.vibrate(this));
        gateSwitch.setChecked(Prefs.noiseGate(this));
        saveSwitch.setChecked(Prefs.powerSave(this));
        int offMode = Prefs.screenOffMode(this);
        screenOffButton.setText(getResources()
                .getStringArray(R.array.screen_off_modes)[offMode]);
        // アプリを見ない設定なら、登録の出番はない
        boolean needApps = offMode == Prefs.OFF_APPS || offMode == Prefs.OFF_BOTH;
        watchAppsButton.setEnabled(needApps);
        watchAppsButton.setAlpha(needApps ? 1f : 0.4f);
        int n = Prefs.watchApps(this).size();
        watchAppsButton.setText(n == 0 ? getString(R.string.pick_watch_apps)
                : getString(R.string.pick_watch_apps_n, n));

        // 「起動したのを見たか」は目に見えないと直しようがないので、そのまま出す
        watchAppsStatus.setVisibility(needApps ? View.VISIBLE : View.GONE);
        if (needApps) {
            String seen = svc == null ? null : svc.lastSeenApp();
            if (seen == null) {
                watchAppsStatus.setText(R.string.seen_none);
            } else {
                String label = appLabel(seen + "/x");
                watchAppsStatus.setText(getString(R.string.seen_app,
                        label == null ? seen : label, svc.lastSeenAgoSec()));
            }
        }
        sensBar.setProgress(Prefs.sensitivity(this));
        updateSensText();
        updateAssignText();
        updateLive();

        // 繋がるのを少し待ってもう一度見る（プロセスが作り直された直後は null）
        if (inSettings && svc == null) {
            handler.removeCallbacks(recheck);
            handler.postDelayed(recheck, 700L);
        }
    }

    private final Runnable recheck = new Runnable() {
        @Override
        public void run() {
            refresh();
        }
    };

    private void updateSensText() {
        int level = Prefs.sensitivity(this);
        String[] names = getResources().getStringArray(R.array.sens_names);
        sensLabel.setText(getString(R.string.sens_value,
                names[level], TapDetector.THRESHOLDS[level]));
    }

    /** 叩いた山の高さと揺れの大きさを、そのまま数字で見せる。 */
    private void updateLive() {
        TapmonService svc = TapmonService.instance;
        if (svc == null || !svc.sensing()) {
            live.setText(R.string.live_off);
            return;
        }
        TapDetector d = svc.detector();
        long ago = d.lastKnockAtMs == 0 ? -1
                : (SystemClock.uptimeMillis() - d.lastKnockAtMs) / 1000L;
        live.setText(getString(R.string.live_body,
                d.knockCount, d.lastPeak, d.lastRejected, d.noise, d.threshold(),
                ago < 0 ? getString(R.string.live_never) : getString(R.string.live_ago, ago)));
    }

    // ---- 割り当て ----

    private Button addAssignButton(LinearLayout root, int labelRes, int slot) {
        TextView caption = new TextView(this);
        caption.setText(labelRes);
        caption.setTextColor(0xFFA8A8B4);
        caption.setTextSize(13f);
        addWithTop(root, caption, 14);

        Button b = new Button(this);
        b.setOnClickListener(v -> chooseAction(slot));
        addWithTop(root, b, 2);
        return b;
    }

    private void updateAssignText() {
        doubleButton.setText(summary(Prefs.SLOT_DOUBLE));
        tripleButton.setText(summary(Prefs.SLOT_TRIPLE));
    }

    private String summary(int slot) {
        int action = Prefs.action(this, slot);
        String name = getString(Actions.labelRes(action));
        if (action != Actions.LAUNCH_APP) return name;
        String label = appLabel(Prefs.app(this, slot));
        return label == null ? name : name + ": " + label;
    }

    private void chooseAction(final int slot) {
        final List<Integer> choices = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        int checked = 0;
        int current = Prefs.action(this, slot);
        for (int a : Actions.ALL) {
            if (!Actions.available(this, a)) continue;
            if (a == current) checked = choices.size();
            choices.add(a);
            names.add(getString(Actions.labelRes(a)));
        }
        new AlertDialog.Builder(this)
                .setTitle(slot == Prefs.SLOT_DOUBLE ? R.string.slot_double : R.string.slot_triple)
                .setSingleChoiceItems(names.toArray(new String[0]), checked, (dlg, which) -> {
                    dlg.dismiss();
                    int action = choices.get(which);
                    if (action == Actions.LAUNCH_APP) {
                        chooseApp(slot);
                    } else {
                        Prefs.setAction(this, slot, action);
                        apply();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 起動するアプリを選ぶ。一覧に出るのはホーム画面に並ぶアプリだけで、
     * それはマニフェストの queries で見える範囲そのもの。
     */
    private void chooseApp(final int slot) {
        final List<ResolveInfo> apps = launchableApps();
        String[] names = new String[apps.size()];
        PackageManager pm = getPackageManager();
        for (int i = 0; i < apps.size(); i++) {
            names[i] = apps.get(i).loadLabel(pm).toString();
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.pick_app)
                .setItems(names, (dlg, which) -> {
                    ResolveInfo r = apps.get(which);
                    Prefs.setApp(this, slot,
                            r.activityInfo.packageName + "/" + r.activityInfo.name);
                    Prefs.setAction(this, slot, Actions.LAUNCH_APP);
                    apply();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private List<ResolveInfo> launchableApps() {
        PackageManager pm = getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
        if (list == null) return new ArrayList<>();
        final Collator collator = Collator.getInstance();
        Collections.sort(list, new Comparator<ResolveInfo>() {
            @Override
            public int compare(ResolveInfo a, ResolveInfo b) {
                return collator.compare(a.loadLabel(pm).toString(), b.loadLabel(pm).toString());
            }
        });
        return list;
    }

    private String appLabel(String flat) {
        if (flat == null || flat.isEmpty()) return null;
        int slash = flat.indexOf('/');
        if (slash <= 0) return null;
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(
                    pm.getApplicationInfo(flat.substring(0, slash), 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return null;    // 消されたアプリ
        }
    }

    // ---- 画面が消えている間 ----

    private void chooseScreenOffMode() {
        String[] names = getResources().getStringArray(R.array.screen_off_modes);
        new AlertDialog.Builder(this)
                .setTitle(R.string.h_screen_off)
                .setSingleChoiceItems(names, Prefs.screenOffMode(this), (dlg, which) -> {
                    dlg.dismiss();
                    Prefs.setScreenOffMode(this, which);
                    apply();
                    // アプリを見る設定にしたのに一つも登録が無いなら、そのまま選ばせる
                    if ((which == Prefs.OFF_APPS || which == Prefs.OFF_BOTH)
                            && Prefs.watchApps(this).isEmpty()) {
                        chooseWatchApps();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 消灯中も見張る相手を選ぶ。パッケージごとに一つだけ並べる。 */
    private void chooseWatchApps() {
        final List<ResolveInfo> apps = launchableApps();
        final List<String> pkgs = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        PackageManager pm = getPackageManager();
        for (ResolveInfo r : apps) {
            String pkg = r.activityInfo.packageName;
            if (pkgs.contains(pkg)) continue;
            pkgs.add(pkg);
            names.add(r.loadLabel(pm).toString());
        }
        final Set<String> chosen = Prefs.watchApps(this);
        final boolean[] checked = new boolean[pkgs.size()];
        for (int i = 0; i < pkgs.size(); i++) checked[i] = chosen.contains(pkgs.get(i));

        new AlertDialog.Builder(this)
                .setTitle(R.string.pick_watch_apps)
                .setMultiChoiceItems(names.toArray(new String[0]), checked,
                        (dlg, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(android.R.string.ok, (dlg, w) -> {
                    Set<String> next = new HashSet<>();
                    for (int i = 0; i < pkgs.size(); i++) {
                        if (checked[i]) next.add(pkgs.get(i));
                    }
                    Prefs.setWatchApps(this, next);
                    apply();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---- アクセシビリティ設定 ----

    /** 設定値に tapmon が並んでいるか。 */
    private boolean a11yEnabledInSettings() {
        String list = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list == null || list.isEmpty()) return false;
        String full = getPackageName() + "/" + TapmonService.class.getName();
        String shrt = getPackageName() + "/." + TapmonService.class.getSimpleName();
        for (String part : list.split(":")) {
            String t = part.trim();
            if (t.equalsIgnoreCase(full) || t.equalsIgnoreCase(shrt)) return true;
        }
        return false;
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (RuntimeException ignored) {
            // 端末によっては開けないことがある。その場合は利用者が自分で辿る。
        }
    }

    // ---- 画面組み立ての小道具 ----

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private void addWithTop(LinearLayout parent, View v, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp);
        parent.addView(v, p);
    }

    private void addHeading(LinearLayout parent, int textRes, int topDp) {
        TextView t = new TextView(this);
        t.setText(textRes);
        t.setTextColor(0xFF35D48A);
        t.setTextSize(12f);
        t.setAllCaps(true);
        t.setGravity(Gravity.START);
        addWithTop(parent, t, topDp);
    }

    private TextView addStatus(LinearLayout parent) {
        TextView t = new TextView(this);
        t.setTextColor(0xFFF2F2F5);
        t.setTextSize(14f);
        t.setLineSpacing(0f, 1.2f);
        addWithTop(parent, t, 6);
        return t;
    }

    private TextView addNote(LinearLayout parent, int textRes, int topDp) {
        TextView t = new TextView(this);
        t.setText(textRes);
        t.setTextColor(0xFFA8A8B4);
        t.setTextSize(13f);
        t.setLineSpacing(0f, 1.3f);
        addWithTop(parent, t, topDp);
        return t;
    }

    private Switch addSwitch(LinearLayout parent, int textRes, int topDp) {
        Switch s = new Switch(this);
        s.setText(textRes);
        s.setTextColor(0xFFF2F2F5);
        s.setTextSize(15f);
        addWithTop(parent, s, topDp);
        return s;
    }
}
