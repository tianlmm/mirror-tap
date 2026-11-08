package com.mirrortap.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 悬浮层服务：
 * 1) 上半屏铺一层透明窗口接收触摸；
 * 2) 把触摸点按 y = 2 * 分隔线 - y 镜像到下半屏，用无障碍服务点下去；
 * 3) 分隔线右侧的小把手可以上下拖、可以暂停。
 */
public class MirrorOverlayService extends Service
        implements MirrorPanel.Listener, DividerHandle.Listener {

    public static final String ACTION_START = "com.mirrortap.action.START";
    public static final String ACTION_STOP = "com.mirrortap.action.STOP";
    public static final String ACTION_TOGGLE = "com.mirrortap.action.TOGGLE";
    public static final String ACTION_RELOAD = "com.mirrortap.action.RELOAD";

    private static final String CHANNEL_ID = "mirror_overlay";
    private static final int NOTIFY_ID = 1001;
    private static final long TAP_DURATION_MS = 55L;
    private static final long LONG_PRESS_MS = 520L;

    private static volatile boolean sRunning = false;
    private static volatile boolean sPaused = false;

    private WindowManager windowManager;
    private NotificationManager notificationManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private HudLayer hud;
    private MirrorPanel panel;
    private DividerHandle handle;

    private int screenWidth;
    private int screenHeight;
    private float split = Prefs.DEFAULT_SPLIT;
    private boolean paused = false;
    private boolean attached = false;
    private long lastToastAt = 0L;

    public static boolean isRunning() {
        return sRunning;
    }

    public static boolean isPaused() {
        return sPaused;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannel();
        split = Prefs.split(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }

        startInForeground();

        if (ACTION_TOGGLE.equals(action)) {
            ensureAttached();
            setPaused(!paused);
            return START_STICKY;
        }

        if (ACTION_RELOAD.equals(action)) {
            split = Prefs.split(this);
            applyPrefs();
        }

        ensureAttached();
        layoutAll();
        return START_STICKY;
    }

    // ---------------------------------------------------------------- 窗口

    private void startInForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFY_ID, notification);
        }
        sRunning = true;
    }

    private void ensureAttached() {
        if (attached) {
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.tip_need_overlay));
            shutdown();
            return;
        }
        readMetrics();

        hud = new HudLayer(this);
        addLayer(hud, screenWidth, screenHeight, 0, 0, false);

        panel = new MirrorPanel(this);
        panel.setListener(this);
        addLayer(panel, screenWidth, Math.round(screenHeight * split), 0, 0, true);

        handle = new DividerHandle(this);
        handle.setListener(this);
        handle.setPaused(paused);
        addLayer(handle, handleWidth(), handleHeight(), handleX(), handleY(), true);

        attached = true;
        applyPrefs();
        layoutAll();
    }

    private int handleWidth() {
        return Math.round(DividerHandle.WIDTH_DP * getResources().getDisplayMetrics().density);
    }

    private int handleHeight() {
        return Math.round(DividerHandle.HEIGHT_DP * getResources().getDisplayMetrics().density);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int handleX() {
        return Math.max(0, screenWidth - handleWidth() - dp(2));
    }

    private int handleY() {
        return Math.round(screenHeight * split) + dp(6);
    }

    private WindowManager.LayoutParams makeParams(int w, int h, int x, int y, boolean touchable) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (!touchable) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = x;
        lp.y = y;
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        lp.setTitle("MirrorTap");
        return lp;
    }

    private void addLayer(View view, int w, int h, int x, int y, boolean touchable) {
        try {
            windowManager.addView(view, makeParams(w, h, x, y, touchable));
        } catch (Throwable t) {
            toast("悬浮层创建失败：" + t.getClass().getSimpleName());
        }
    }

    private void updateLayer(View view, int w, int h, int x, int y) {
        if (view == null || view.getWindowToken() == null) {
            return;
        }
        WindowManager.LayoutParams lp =
                (WindowManager.LayoutParams) view.getLayoutParams();
        if (lp == null) {
            return;
        }
        lp.width = w;
        lp.height = h;
        lp.x = x;
        lp.y = y;
        try {
            windowManager.updateViewLayout(view, lp);
        } catch (Throwable ignored) {
        }
    }

    private void readMetrics() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Rect bounds =
                        windowManager.getMaximumWindowMetrics().getBounds();
                screenWidth = bounds.width();
                screenHeight = bounds.height();
            } else {
                WindowManager wm = windowManager;
                android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
                wm.getDefaultDisplay().getRealMetrics(dm);
                screenWidth = dm.widthPixels;
                screenHeight = dm.heightPixels;
            }
        } catch (Throwable ignored) {
        }
        if (screenWidth <= 0 || screenHeight <= 0) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            screenWidth = dm.widthPixels;
            screenHeight = dm.heightPixels;
        }
    }

    private void layoutAll() {
        if (!attached || windowManager == null) {
            return;
        }
        readMetrics();
        int panelHeight = Math.round(screenHeight * split);

        if (hud != null) {
            updateLayer(hud, screenWidth, screenHeight, 0, 0);
            hud.setSplit(panelHeight);
            hud.setTintEnabled(Prefs.tint(this));
            hud.setActive(!paused);
        }
        if (panel != null && panel.getWindowToken() != null) {
            updateLayer(panel, screenWidth, panelHeight, 0, 0);
        }
        if (handle != null) {
            updateLayer(handle, handleWidth(), handleHeight(), handleX(), handleY());
        }
    }

    private void applyPrefs() {
        if (panel != null) {
            panel.setMirrorDrag(Prefs.mirrorDrag(this));
        }
        if (hud != null) {
            hud.setTintEnabled(Prefs.tint(this));
        }
    }

    private void setPaused(boolean value) {
        paused = value;
        sPaused = value;

        if (attached) {
            if (panel != null) {
                boolean added = panel.getWindowToken() != null;
                if (paused && added) {
                    try {
                        windowManager.removeView(panel);
                    } catch (Throwable ignored) {
                    }
                } else if (!paused && !added) {
                    addLayer(panel, screenWidth, Math.round(screenHeight * split), 0, 0, true);
                }
            }
            if (hud != null) {
                hud.setActive(!paused);
            }
            if (handle != null) {
                handle.setPaused(paused);
            }
        }
        updateNotification();
    }

    private void teardown() {
        removeLayer(panel);
        removeLayer(handle);
        removeLayer(hud);
        panel = null;
        handle = null;
        hud = null;
        attached = false;
    }

    private void removeLayer(View view) {
        if (view == null || view.getWindowToken() == null) {
            return;
        }
        try {
            windowManager.removeView(view);
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 镜像

    @Override
    public void onMirrorTap(float x, float y, long heldMs) {
        float splitPx = screenHeight * split;
        float mirroredY = 2f * splitPx - y;
        long duration = heldMs >= LONG_PRESS_MS ? Math.min(heldMs, 1500L) : TAP_DURATION_MS;
        inject(Collections.singletonList(
                new PointF(clampX(x), clampY(mirroredY))), duration);
    }

    @Override
    public void onMirrorDrag(List<PointF> points, long heldMs) {
        if (points == null || points.isEmpty()) {
            return;
        }
        float splitPx = screenHeight * split;
        List<PointF> mirrored = new ArrayList<>(points.size());
        for (PointF p : points) {
            mirrored.add(new PointF(clampX(p.x), clampY(2f * splitPx - p.y)));
        }
        long duration = Math.max(120L, Math.min(heldMs, 1500L));
        inject(mirrored, duration);
    }

    private float clampX(float x) {
        return Math.max(1f, Math.min(x, screenWidth - 2f));
    }

    private float clampY(float y) {
        return Math.max(1f, Math.min(y, screenHeight - 2f));
    }

    private void inject(List<PointF> points, long duration) {
        TapInjectorService injector = TapInjectorService.get();
        if (injector == null) {
            toast(getString(R.string.tip_need_a11y));
            return;
        }
        boolean ok = injector.stroke(points, duration);
        if (!ok) {
            return;
        }
        if (Prefs.haptic(this)) {
            vibrate();
        }
        if (Prefs.ghost(this) && hud != null) {
            PointF p = points.get(0);
            hud.addGhost(p.x, p.y);
        }
    }

    private void vibrate() {
        try {
            Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 把手

    @Override
    public void onHandleDrag(float screenY) {
        if (screenHeight <= 0) {
            return;
        }
        split = Prefs.clamp(screenY / (float) screenHeight);
        layoutAll();
    }

    @Override
    public void onHandleDragEnd() {
        Prefs.setSplit(this, split);
        updateNotification();
    }

    @Override
    public void onHandleTap() {
        setPaused(!paused);
    }

    // ---------------------------------------------------------------- 通知

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26 || notificationManager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.channel_desc));
        channel.setShowBadge(false);
        channel.setSound(null, null);
        notificationManager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        PendingIntent open = PendingIntent.getActivity(this, 10, openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent toggleIntent = new Intent(this, MirrorOverlayService.class).setAction(ACTION_TOGGLE);
        PendingIntent toggle = PendingIntent.getService(this, 11, toggleIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stopIntent = new Intent(this, MirrorOverlayService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 12, stopIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String title = getString(paused ? R.string.notif_title_paused : R.string.notif_title);
        String text = getString(R.string.notif_text, Math.round(split * 100f));

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_mirror)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(0,
                        getString(paused ? R.string.notif_resume : R.string.notif_pause), toggle).build())
                .addAction(new Notification.Action.Builder(0,
                        getString(R.string.notif_quit), stop).build());
        return builder.build();
    }

    private void updateNotification() {
        if (!sRunning || notificationManager == null) {
            return;
        }
        try {
            notificationManager.notify(NOTIFY_ID, buildNotification());
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (!attached) {
            return;
        }
        // 转屏 / 分屏变化后窗口尺寸要重建，等系统布局稳定再动
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!attached) {
                    return;
                }
                teardown();
                ensureAttached();
            }
        }, 320L);
    }

    private void shutdown() {
        sRunning = false;
        sPaused = false;
        teardown();
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        sPaused = false;
        teardown();
        super.onDestroy();
    }

    private void toast(String message) {
        long now = System.currentTimeMillis();
        if (now - lastToastAt < 1500L) {
            return;
        }
        lastToastAt = now;
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
