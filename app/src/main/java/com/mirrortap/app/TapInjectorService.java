package com.mirrortap.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.PointF;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;

import java.util.List;

/**
 * 唯一能「替用户点一下」的官方通道：无障碍服务 + dispatchGesture。
 * 这里不读取任何屏幕内容，只负责把一条路径注入成一次触摸。
 */
public class TapInjectorService extends AccessibilityService {

    private static volatile TapInjectorService sInstance;

    public static TapInjectorService get() {
        return sInstance;
    }

    public static boolean isConnected() {
        return sInstance != null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不需要读取界面内容
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        sInstance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        super.onDestroy();
    }

    /**
     * 把一串屏幕坐标注入成一次触摸。
     *
     * @param points     屏幕坐标（px），至少一个点
     * @param durationMs 按下到抬起的时长，点一下用 40~60ms
     */
    public boolean stroke(List<PointF> points, long durationMs) {
        return stroke(points, durationMs, null);
    }

    /**
     * @param onFinish 手势播完（或被取消）后回调，用来恢复悬浮层的可触摸标志
     */
    public boolean stroke(List<PointF> points, long durationMs, final Runnable onFinish) {
        if (points == null || points.isEmpty()) {
            return false;
        }
        try {
            Path path = new Path();
            PointF first = points.get(0);
            path.moveTo(first.x, first.y);
            for (int i = 1; i < points.size(); i++) {
                PointF p = points.get(i);
                path.lineTo(p.x, p.y);
            }
            long max = GestureDescription.getMaxGestureDuration();
            long duration = Math.max(1L, Math.min(durationMs, max));
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, duration);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(stroke)
                    .build();
            GestureResultCallback callback = null;
            if (onFinish != null) {
                callback = new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        onFinish.run();
                    }

                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        onFinish.run();
                    }
                };
            }
            return dispatchGesture(gesture, callback, new Handler(Looper.getMainLooper()));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否已经在系统设置里被勾选启用（连接前也能判断）。 */
    public static boolean isEnabledInSettings(Context context) {
        try {
            String flat = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(flat)) {
                return false;
            }
            String target = context.getPackageName() + "/" + TapInjectorService.class.getName();
            TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
            splitter.setString(flat);
            while (splitter.hasNext()) {
                String item = splitter.next();
                if (item != null && item.equalsIgnoreCase(target)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
