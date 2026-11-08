package com.mirrortap.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PointF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Collections;

public class MainActivity extends Activity {

    private TextView overlayState;
    private TextView a11yState;
    private TextView splitValue;
    private TextView runState;
    private SeekBar seekBar;
    private Button toggleButton;
    private RadioGroup directionGroup;
    private RadioGroup dragGroup;
    private RadioGroup mapGroup;
    private TextView diagState;
    private TextView testTarget;
    private int testCount;
    private boolean syncing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        overlayState = findViewById(R.id.overlay_state);
        a11yState = findViewById(R.id.a11y_state);
        splitValue = findViewById(R.id.split_value);
        runState = findViewById(R.id.run_state);
        seekBar = findViewById(R.id.seek_split);
        toggleButton = findViewById(R.id.btn_toggle);
        diagState = findViewById(R.id.diag_state);
        testTarget = findViewById(R.id.test_target);
        testTarget.setText(getString(R.string.test_target, testCount));
        testTarget.setOnClickListener(v -> {
            testCount++;
            testTarget.setText(getString(R.string.test_target, testCount));
        });
        findViewById(R.id.btn_test).setOnClickListener(v -> runInjectionTest());

        findViewById(R.id.btn_overlay).setOnClickListener(v -> requestOverlayPermission());
        findViewById(R.id.btn_a11y).setOnClickListener(v -> openAccessibilitySettings());

        seekBar.setMax(60);
        seekBar.setProgress(splitToProgress(Prefs.split(this)));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                Prefs.setSplit(MainActivity.this, progressToSplit(progress));
                updateSplitLabel();
                if (MirrorOverlayService.isRunning()) {
                    reloadService();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });

        setupChoiceGroups();
        bindCheck(R.id.cb_tap_origin, Prefs.KEY_TAP_ORIGIN, false, false);
        bindCheck(R.id.cb_haptic, Prefs.KEY_HAPTIC, true, false);
        bindCheck(R.id.cb_tint, Prefs.KEY_TINT, true, true);
        bindCheck(R.id.cb_boot, Prefs.KEY_BOOT, false, false);

        toggleButton.setOnClickListener(v -> toggleService());

        updateSplitLabel();
    }

    private void setupChoiceGroups() {
        directionGroup = findViewById(R.id.rg_direction);
        dragGroup = findViewById(R.id.rg_drag);
        mapGroup = findViewById(R.id.rg_map);

        mapGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (syncing) {
                return;
            }
            if (checkedId != R.id.rb_map_mirror && checkedId != R.id.rb_map_offset) {
                return;
            }
            Prefs.putString(this, Prefs.KEY_MAP_MODE,
                    checkedId == R.id.rb_map_offset ? Prefs.MAP_OFFSET : Prefs.MAP_MIRROR);
            if (MirrorOverlayService.isRunning()) {
                reloadService();
            }
        });

        directionGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (syncing) {
                return;
            }
            if (checkedId != R.id.rb_dir_down && checkedId != R.id.rb_dir_up) {
                return;
            }
            Prefs.putString(this, Prefs.KEY_DIRECTION,
                    checkedId == R.id.rb_dir_up ? Prefs.DIR_UP : Prefs.DIR_DOWN);
            if (MirrorOverlayService.isRunning()) {
                reloadService();
            }
        });

        dragGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (syncing) {
                return;
            }
            String mode;
            if (checkedId == R.id.rb_drag_mirror) {
                mode = Prefs.DRAG_MIRROR;
            } else if (checkedId == R.id.rb_drag_ignore) {
                mode = Prefs.DRAG_IGNORE;
            } else if (checkedId == R.id.rb_drag_pass) {
                mode = Prefs.DRAG_PASS;
            } else {
                return;
            }
            Prefs.putString(this, Prefs.KEY_DRAG_MODE, mode);
            if (MirrorOverlayService.isRunning()) {
                reloadService();
            }
        });

        syncChoiceGroups();
    }

    private void syncChoiceGroups() {
        if (directionGroup == null || dragGroup == null) {
            return;
        }
        syncing = true;
        int dirId = Prefs.isDownDirection(this) ? R.id.rb_dir_down : R.id.rb_dir_up;
        if (directionGroup.getCheckedRadioButtonId() != dirId) {
            directionGroup.check(dirId);
        }
        String mode = Prefs.dragMode(this);
        int dragId = Prefs.DRAG_MIRROR.equals(mode) ? R.id.rb_drag_mirror
                : Prefs.DRAG_IGNORE.equals(mode) ? R.id.rb_drag_ignore
                : R.id.rb_drag_pass;
        if (dragGroup.getCheckedRadioButtonId() != dragId) {
            dragGroup.check(dragId);
        }
        int mapId = Prefs.MAP_OFFSET.equals(Prefs.mapMode(this))
                ? R.id.rb_map_offset : R.id.rb_map_mirror;
        if (mapGroup != null && mapGroup.getCheckedRadioButtonId() != mapId) {
            mapGroup.check(mapId);
        }
        syncing = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void bindCheck(int viewId, final String key, boolean def, final boolean needReload) {
        CheckBox checkBox = findViewById(viewId);
        checkBox.setChecked(Prefs.get(this, key, def));
        checkBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Prefs.put(MainActivity.this, key, isChecked);
                if (needReload && MirrorOverlayService.isRunning()) {
                    reloadService();
                }
            }
        });
    }

    private void runInjectionTest() {
        TapInjectorService svc = TapInjectorService.get();
        if (svc == null) {
            toast("无障碍服务未连接，先去开启");
            return;
        }
        int[] location = new int[2];
        testTarget.getLocationOnScreen(location);
        float cx = location[0] + testTarget.getWidth() / 2f;
        float cy = location[1] + testTarget.getHeight() / 2f;
        boolean ok = svc.stroke(Collections.singletonList(new PointF(cx, cy)), 55L);
        toast(ok ? "已请求注入，看计数有没有 +1" : "系统拒绝了注入");
    }

    private void refresh() {
        boolean overlayOk = Settings.canDrawOverlays(this);
        overlayState.setText(overlayOk ? R.string.granted : R.string.not_granted);
        overlayState.setTextColor(getColor(overlayOk ? R.color.accent : R.color.warn));
        ((Button) findViewById(R.id.btn_overlay)).setText(
                overlayOk ? R.string.granted : R.string.grant);

        boolean a11yOk = TapInjectorService.isEnabledInSettings(this);
        a11yState.setText(a11yOk ? R.string.granted : R.string.not_granted);
        a11yState.setTextColor(getColor(a11yOk ? R.color.accent : R.color.warn));
        ((Button) findViewById(R.id.btn_a11y)).setText(
                a11yOk ? R.string.granted : R.string.grant);

        boolean running = MirrorOverlayService.isRunning();
        toggleButton.setText(running ? R.string.stop : R.string.start);
        runState.setVisibility(running ? TextView.VISIBLE : TextView.GONE);
        if (running) {
            runState.setText(MirrorOverlayService.isPaused() ? R.string.paused : R.string.running);
            runState.setTextColor(getColor(
                    MirrorOverlayService.isPaused() ? R.color.warn : R.color.accent));
        }

        int progress = splitToProgress(Prefs.split(this));
        if (seekBar.getProgress() != progress) {
            seekBar.setProgress(progress);
        }
        updateSplitLabel();
        syncChoiceGroups();

        boolean connected = TapInjectorService.isConnected();
        String last = MirrorOverlayService.lastEvent();
        diagState.setVisibility(running || connected ? TextView.VISIBLE : TextView.GONE);
        diagState.setText("无障碍" + (connected ? "已连接" : "未连接")
                + " · " + (last.isEmpty() ? "还没有镜像过" : last));
    }

    private void updateSplitLabel() {
        int percent = Math.round(progressToSplit(seekBar.getProgress()) * 100f);
        splitValue.setText(getString(R.string.split_value, percent));
    }

    private static int splitToProgress(float split) {
        float ratio = (Prefs.clamp(split) - Prefs.MIN_SPLIT) / (Prefs.MAX_SPLIT - Prefs.MIN_SPLIT);
        return Math.round(ratio * 60f);
    }

    private static float progressToSplit(int progress) {
        return Prefs.MIN_SPLIT + (Prefs.MAX_SPLIT - Prefs.MIN_SPLIT) * (progress / 60f);
    }

    private void toggleService() {
        if (MirrorOverlayService.isRunning()) {
            startService(new Intent(this, MirrorOverlayService.class)
                    .setAction(MirrorOverlayService.ACTION_STOP));
            runState.setVisibility(TextView.GONE);
            toggleButton.setText(R.string.start);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.tip_need_overlay));
            requestOverlayPermission();
            return;
        }
        if (!TapInjectorService.isEnabledInSettings(this)) {
            toast(getString(R.string.tip_need_a11y));
            openAccessibilitySettings();
            return;
        }
        askNotificationPermission();

        Intent intent = new Intent(this, MirrorOverlayService.class)
                .setAction(MirrorOverlayService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        toggleButton.setText(R.string.stop);
        runState.setVisibility(TextView.VISIBLE);
        runState.setText(R.string.running);
        runState.setTextColor(getColor(R.color.accent));
    }

    private void reloadService() {
        startService(new Intent(this, MirrorOverlayService.class)
                .setAction(MirrorOverlayService.ACTION_RELOAD));
    }

    private void requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            toast("悬浮窗权限已开启");
            return;
        }
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (Throwable ignored) {
                toast("请到系统设置里手动开启悬浮窗权限");
            }
        }
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            toast("请在「已安装的服务 / 下载的服务」里找到「镜像点击」并打开");
        } catch (Throwable t) {
            toast("请到 设置 → 无障碍 里手动开启");
        }
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
