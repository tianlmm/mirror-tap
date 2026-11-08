package com.mirrortap.app;

import android.content.Context;
import android.graphics.PointF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * 覆盖在屏幕上半区的透明层：吃掉上半区的触摸，把镜像后的位置交给服务去点。
 * 它自己完全不绘制内容（视觉部分在 {@link HudLayer}）。
 */
public class MirrorPanel extends View {

    public interface Listener {
        /** 上半区的一次点按。x/y 是屏幕坐标，heldMs 是手指按住的时长。 */
        void onMirrorTap(float x, float y, long heldMs);

        /** 上半区的一次滑动，points 是屏幕坐标序列。 */
        void onMirrorDrag(List<PointF> points, long heldMs);
    }

    private final ArrayList<PointF> points = new ArrayList<>();
    private final int touchSlop;

    private Listener listener;
    private boolean mirrorDrag = true;

    private float downX;
    private float downY;
    private float lastX;
    private float lastY;
    private long downAt;
    private boolean multiTouch;

    public MirrorPanel(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setBackground(null);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setMirrorDrag(boolean mirrorDrag) {
        this.mirrorDrag = mirrorDrag;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final float x = event.getRawX();
        final float y = event.getRawY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                points.clear();
                points.add(new PointF(x, y));
                downX = lastX = x;
                downY = lastY = y;
                downAt = SystemClock.uptimeMillis();
                multiTouch = false;
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                multiTouch = true;
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!multiTouch && mirrorDrag && distance(x, y, lastX, lastY) > touchSlop * 0.35f) {
                    points.add(new PointF(x, y));
                    lastX = x;
                    lastY = y;
                }
                return true;

            case MotionEvent.ACTION_UP: {
                if (multiTouch) {
                    multiTouch = false;
                    points.clear();
                    return true;
                }
                long held = SystemClock.uptimeMillis() - downAt;
                boolean moved = distance(x, y, downX, downY) > touchSlop;
                if (listener != null) {
                    if (mirrorDrag && moved && points.size() > 1) {
                        points.add(new PointF(x, y));
                        listener.onMirrorDrag(new ArrayList<>(points), held);
                    } else {
                        listener.onMirrorTap(downX, downY, held);
                    }
                }
                points.clear();
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                points.clear();
                multiTouch = false;
                return true;

            default:
                return true;
        }
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        return (float) Math.hypot(x1 - x2, y1 - y2);
    }
}
