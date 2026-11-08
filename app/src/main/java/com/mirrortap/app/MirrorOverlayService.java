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
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 悬浮层服务。
 *
 * 只有两个窗口，没有全屏窗口：
 *   1) MirrorPanel —— 只铺「手指那一半」（上半或下半，可切换），画分隔线，接住触摸；
 *   2) DividerHandle —— 分隔线旁边的小把手，拖动改比例、轻点暂停、长按退出。
 *
 * 镜像规则（两个方向通用）：y' = 2 × 分隔线 − y，x 不变。
 *
 * 滑动有三种处理方式：
 *   pass   —— 原样注入回原位置（临时把触摸层设为不可触摸），App 能正常滚动；
 *   mirror —— 镜像到另一半；
 *   ignore —— 只镜像点按。
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
    private static volatile String sLastEvent = "";

    /** 上一次镜像的结果，给首页诊断行用。 */
    public static String lastEvent() {
        return sLastEvent;
    }

    private WindowManager windowManager;
    private NotificationManager notificationManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private MirrorPanel panel;
    private DividerHandle handle;
    private DividerStrip divider;

    private int screenWidth;
    private int screenHeight;
    private int statusBarTop;
    private int navBarBottom;
    private float split = Prefs.DEFAULT_SPLIT;
    private boolean downDirection = true;
    private String dragMode = Prefs.DRAG_PASS;
    private String mapMode = Prefs.MAP_OFFSET;

    private boolean paused = false;
    private boolean attached = false;
    private boolean panelAttached = false;
    private boolean panelTouchable = true;
    private boolean handleAttached = false;
    private boolean dividerAttached = false;
    private long lastToastAt = 0L;
    private long suppressHandleUntil = 0L;

    private final Runnable restoreTouchable = new Runnable() {
        @Override
        public void run() {
            setPanelTouchable(true);
            if (panel != null) {
                panel.setIgnoreTouch(false);
            }
        }
    };

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
        loadPrefs();
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
            loadPrefs();
            applyPrefs();
        }

        ensureAttached();
        layoutAll();
        return START_STICKY;
    }

    private void loadPrefs() {
        split = Prefs.split(this);
        downDirection = Prefs.isDownDirection(this);
        dragMode = Prefs.dragMode(this);
        mapMode = Prefs.mapMode(this);
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

        panel = new MirrorPanel(this);
        panel.setListener(this);
        panel.setTintEnabled(Prefs.tint(this));
        if (!paused) {
            addPanel();
        }

        divider = new DividerStrip(this);
        dividerAttached = addLayer(divider, screenWidth, dividerHeight(), 0, dividerY(), false);

        handle = new DividerHandle(this);
        handle.setListener(this);
        handle.setPaused(paused);
        handleAttached = addLayer(handle, handleWidth(), handleHeight(), handleX(), handleY(), true);

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

    private int splitPx() {
        return Math.round(screenHeight * split);
    }

    /** 触摸层顶边：上半屏输入时让开状态栏（至少 24dp），下半屏输入时就是分隔线。 */
    private int panelTop() {
        int splitY = splitPx();
        if (downDirection) {
            int bar = Math.max(statusBarTop, dp(24));
            return Math.min(bar, Math.max(0, splitY - dp(48)));
        }
        return splitY;
    }

    /** 触摸层底边：上半屏输入时到分隔线，下半屏输入时避开导航栏。 */
    private int panelBottom() {
        int splitY = splitPx();
        if (downDirection) {
            return splitY;
        }
        return Math.max(splitY + dp(48), screenHeight - navBarBottom);
    }

    private int panelHeight() {
        return Math.max(dp(24), panelBottom() - panelTop());
    }

    private int dividerHeight() {
        return Math.round(DividerStrip.HEIGHT_DP * getResources().getDisplayMetrics().density);
    }

    private int dividerY() {
        return splitPx() - dividerHeight() / 2;
    }

    private int handleX() {
        return Math.max(0, screenWidth - handleWidth() - dp(2));
    }

    private int handleY() {
        int splitY = splitPx();
        if (downDirection) {
            return splitY + dp(6);
        }
        return Math.max(0, splitY - handleHeight() - dp(6));
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

    private boolean addLayer(View view, int w, int h, int x, int y, boolean touchable) {
        try {
            windowManager.addView(view, makeParams(w, h, x, y, touchable));
            return true;
        } catch (Throwable t) {
            toast("悬浮层创建失败：" + t.getClass().getSimpleName());
            return false;
        }
    }

    private void addPanel() {
        if (panel == null || panelAttached) {
            return;
        }
        panelAttached = addLayer(panel, screenWidth, panelHeight(), 0, panelTop(), true);
        panelTouchable = true;
    }

    private void removePanel() {
        panelAttached = false;
        panelTouchable = true;
        if (panel == null) {
            return;
        }
        try {
            windowManager.removeViewImmediate(panel);
            return;
        } catch (Throwable ignored) {
        }
        try {
            windowManager.removeView(panel);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 需要让触摸穿透时，重建窗口而不是改 flag。
     * updateViewLayout 改 FLAG_NOT_TOUCHABLE 在部分 ROM 上不生效，
     * 会导致注入的点击被自己的触摸层吃掉；创建窗口时带上标志则一定生效。
     */
    private void setPanelTouchable(boolean touchable) {
        if (panel == null) {
            return;
        }
        if (!panelAttached) {
            // 上一次重建失败，恢复时直接补回来
            if (touchable) {
                addPanel();
            }
            return;
        }
        if (panelTouchable == touchable) {
            return;
        }
        panelTouchable = touchable;
        try {
            windowManager.removeViewImmediate(panel);
        } catch (Throwable ignored) {
        }
        try {
            windowManager.addView(panel, makeParams(screenWidth, panelHeight(), 0, panelTop(), touchable));
        } catch (Throwable t) {
            panelAttached = false;
        }
    }

    private void updateLayer(View view, int w, int h, int x, int y) {
        if (view == null || !attached) {
            return;
        }
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) view.getLayoutParams();
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
                WindowInsets insets = windowManager.getCurrentWindowMetrics().getWindowInsets();
                statusBarTop = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top;
                navBarBottom = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom;
                if (statusBarTop <= 0) {
                    // 某些 ROM 对悬浮窗服务不给 insets，退回资源值
                    statusBarTop = resourceSize("status_bar_height");
                }
            } else {
                android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
                windowManager.getDefaultDisplay().getRealMetrics(dm);
                screenWidth = dm.widthPixels;
                screenHeight = dm.heightPixels;
                statusBarTop = resourceSize("status_bar_height");
                navBarBottom = 0;
            }
        } catch (Throwable ignored) {
        }
        if (screenWidth <= 0 || screenHeight <= 0) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            screenWidth = dm.widthPixels;
            screenHeight = dm.heightPixels;
        }
        if (statusBarTop < 0) {
            statusBarTop = 0;
        }
        if (navBarBottom < 0) {
            navBarBottom = 0;
        }
    }

    private int resourceSize(String name) {
        try {
            int id = getResources().getIdentifier(name, "dimen", "android");
            return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private void layoutAll() {
        if (!attached || windowManager == null) {
            return;
        }
        readMetrics();
        if (panel != null) {
            panel.setDividerAtBottom(downDirection);
            panel.setOffsetY(panelTop());
        }
        if (panelAttached) {
            updateLayer(panel, screenWidth, panelHeight(), 0, panelTop());
        }
        if (dividerAttached) {
            updateLayer(divider, screenWidth, dividerHeight(), 0, dividerY());
        }
        if (handleAttached) {
            updateLayer(handle, handleWidth(), handleHeight(), handleX(), handleY());
        }
    }

    private void applyPrefs() {
        if (panel != null) {
            panel.setTintEnabled(Prefs.tint(this));
            panel.setDividerAtBottom(downDirection);
        }
        layoutAll();
    }

    private void setPaused(boolean value) {
        paused = value;
        sPaused = value;

        if (attached) {
            if (paused) {
                if (panel != null) {
                    panel.cancelTouch();
                }
                removePanel();
                removeLayer(divider);
                dividerAttached = false;
            } else if (!panelAttached) {
                if (!dividerAttached && divider != null) {
                    dividerAttached = addLayer(divider, screenWidth, dividerHeight(), 0, dividerY(), false);
                }
                addPanel();
                restackHandle();
            }
            if (handle != null) {
                handle.setPaused(paused);
            }
        }
        updateNotification();
    }

    /** 保证把手始终在触摸层上面（重新加窗口后 z 序会变）。 */
    private void restackHandle() {
        if (handle == null || !handleAttached) {
            return;
        }
        try {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) handle.getLayoutParams();
            windowManager.removeView(handle);
            windowManager.addView(handle, lp);
        } catch (Throwable ignored) {
        }
    }

    private void teardown() {
        removePanel();
        removeLayer(divider);
        removeLayer(handle);
        dividerAttached = false;
        handleAttached = false;
        panel = null;
        handle = null;
        attached = false;
    }

    private void removeLayer(View view) {
        if (view == null) {
            return;
        }
        try {
            windowManager.removeViewImmediate(view);
            return;
        } catch (Throwable ignored) {
        }
        try {
            windowManager.removeView(view);
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 镜像

    @Override
    public void onMirrorTap(float x, float y, long heldMs) {
        float splitY = screenHeight * split;
        long duration = heldMs >= LONG_PRESS_MS ? Math.min(heldMs, 1500L) : TAP_DURATION_MS;
        PointF origin = new PointF(clampX(x), clampY(y));
        PointF target = new PointF(clampX(x), clampY(mapY(y)));

        if (Prefs.tapOrigin(this)) {
            tapOriginAndMirror(origin, target, duration);
            return;
        }
        boolean ok = stroke(Collections.singletonList(target), duration, null);
        record(origin, target, ok, null);
        if (ok && Prefs.haptic(this)) {
            vibrate();
        }
    }

    /**
     * 镜像 + 原位各点一次。
     * 先注入镜像（立即有反馈），再把触摸层重建成「不可触摸」的窗口，
     * 然后把原始那一下注入回原位，最后重建回可触摸。
     */
    private void tapOriginAndMirror(final PointF origin, final PointF target, final long duration) {
        boolean mirrored = stroke(Collections.singletonList(target), duration, null);
        record(origin, target, mirrored, null);
        if (!mirrored) {
            return;
        }
        if (Prefs.haptic(this)) {
            vibrate();
        }

        // 原位那一下必须没有窗口压在 App 上面，否则会被 filterTouchesWhenObscured 丢掉，
        // 所以这里把触摸层整个摘掉，注入完再挂回去（视觉上会闪一下分隔线，可接受）
        handler.removeCallbacks(restoreTouchable);
        if (panel != null) {
            panel.setIgnoreTouch(true);
        }
        removePanel();

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                TapInjectorService svc = TapInjectorService.get();
                if (svc == null) {
                    restoreTouchable.run();
                    return;
                }
                // 原位点按用短一点的按下时长，缩短触摸层缺席的时间
                long tapDuration = Math.min(duration, 40L);
                boolean ok = svc.stroke(Collections.singletonList(origin), tapDuration, restoreTouchable);
                record(origin, target, true, ok);
                if (!ok) {
                    restoreTouchable.run();
                }
            }
        }, 50L);
        handler.postDelayed(restoreTouchable, duration + 1200L);
    }

    private void record(PointF origin, PointF target, boolean mirrorOk, Boolean originOk) {
        StringBuilder sb = new StringBuilder(String.format(java.util.Locale.US,
                "(%d,%d) → (%d,%d) 镜像%s",
                (int) origin.x, (int) origin.y, (int) target.x, (int) target.y,
                mirrorOk ? "✓" : "✗"));
        if (originOk != null) {
            sb.append(" 原位").append(originOk ? "✓" : "✗");
        }
        sLastEvent = sb.toString();
    }

    @Override
    public void onMirrorDrag(List<PointF> points, long heldMs) {
        if (points == null || points.isEmpty()) {
            return;
        }
        if (Prefs.DRAG_IGNORE.equals(dragMode)) {
            return;
        }
        long duration = Math.max(60L, Math.min(heldMs, 1500L));

        if (Prefs.DRAG_PASS.equals(dragMode)) {
            // 原样还给原 App：临时把触摸层设为不可触摸，注入后再恢复
            passThrough(new ArrayList<>(points), duration);
            return;
        }

        List<PointF> mirrored = new ArrayList<>(points.size());
        for (PointF p : points) {
            mirrored.add(new PointF(clampX(p.x), clampY(mapY(p.y))));
        }
        inject(mirrored, duration);
    }

    private void passThrough(final List<PointF> points, long duration) {
        TapInjectorService injector = TapInjectorService.get();
        if (injector == null) {
            toast(getString(R.string.tip_need_a11y));
            return;
        }
        // 同理：滑动要落到下面那个 App 上，上方不能有我们的窗口
        handler.removeCallbacks(restoreTouchable);
        if (panel != null) {
            panel.setIgnoreTouch(true);
        }
        removePanel();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                TapInjectorService injector = TapInjectorService.get();
                if (injector == null) {
                    restoreTouchable.run();
                    return;
                }
                if (!injector.stroke(points, duration, restoreTouchable)) {
                    restoreTouchable.run();
                }
            }
        }, 70L);
        handler.postDelayed(restoreTouchable, duration + 900L);
    }

    /** 把触摸点映射到另一半（和校准界面共用 {@link Mapping}）。 */
    private float mapY(float y) {
        float pct = y / Math.max(1f, screenHeight);
        float mapped = Mapping.map(pct, split, downDirection,
                Prefs.MAP_OFFSET.equals(mapMode),
                Prefs.calGain(this), Prefs.calOffset(this));
        return mapped * screenHeight;
    }

    private float clampX(float x) {
        return Math.max(1f, Math.min(x, screenWidth - 2f));
    }

    private float clampY(float y) {
        return Math.max(1f, Math.min(y, screenHeight - 2f));
    }

    private boolean stroke(List<PointF> points, long duration, Runnable onFinish) {
        TapInjectorService injector = TapInjectorService.get();
        if (injector == null) {
            toast(getString(R.string.tip_need_a11y));
            return false;
        }
        markInjection(duration);
        boolean ok = injector.stroke(points, duration, onFinish);
        if (!ok) {
            toast("镜像注入被系统拒绝");
        }
        return ok;
    }

    private void inject(List<PointF> points, long duration) {
        if (!stroke(points, duration, null)) {
            return;
        }
        if (Prefs.haptic(this)) {
            vibrate();
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

    /** 自己注入手势期间，把手不响应，免得镜像点落到把手上把它拖走。 */
    private void markInjection(long durationMs) {
        suppressHandleUntil = SystemClock.uptimeMillis() + durationMs + 150L;
    }

    private boolean handleSuppressed() {
        return SystemClock.uptimeMillis() < suppressHandleUntil;
    }

    @Override
    public void onHandleDrag(float screenY) {
        if (screenHeight <= 0 || Prefs.lockSplit(this) || handleSuppressed()) {
            return;
        }
        // 拖动也按 0.1% 取整，和界面上的微调保持一致
        split = Prefs.clamp(Math.round(screenY / (float) screenHeight * 1000f) / 1000f);
        layoutAll();
    }

    @Override
    public void onHandleDragEnd() {
        Prefs.setSplit(this, split);
        updateNotification();
    }

    @Override
    public void onHandleTap() {
        if (handleSuppressed()) {
            return;
        }
        setPaused(!paused);
        toast(paused ? "已暂停 · 触摸已还给系统" : "已恢复镜像");
    }

    /** 长按把手：兜底退出。 */
    @Override
    public void onHandleLongPress() {
        toast("已退出镜像点击");
        shutdown();
    }

    // ---------------------------------------------------------------- 通知

    private void createChannel() {
        if (notificationManager == null) {
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
        String text = getString(R.string.notif_text,
                String.format(java.util.Locale.US, "%.1f", split * 100f));

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
        handler.removeCallbacks(restoreTouchable);
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
