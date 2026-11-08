package com.mirrortap.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 负责接住「手指那一半」的触摸，同时也负责画分隔线、淡色底和落点反馈。
 * 整个应用只有两个窗口：它 + 右侧把手，没有全屏悬浮窗。
 *
 * 它铺在屏幕的上半部分或下半部分（由 {@link #setDividerAtBottom(boolean)} 决定），
 * 另一半始终留给系统正常触摸。
 */
public class MirrorPanel extends View {

    public interface Listener {
        void onMirrorTap(float x, float y, long heldMs);

        void onMirrorDrag(List<PointF> points, long heldMs);
    }

    private static final long RIPPLE_MS = 420L;

    private final ArrayList<PointF> points = new ArrayList<>();
    private final ArrayList<Ripple> ripples = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final int touchSlop;

    private Listener listener;
    private boolean tintEnabled = true;
    private boolean dividerAtBottom = true;
    private int offsetY;

    private float downX;
    private float downY;
    private float lastX;
    private float lastY;
    private long downAt;
    private boolean multiTouch;
    private boolean pressed;

    private static final class Ripple {
        final float x;
        final float y;
        final long start;

        Ripple(float x, float y, long start) {
            this.x = x;
            this.y = y;
            this.start = start;
        }
    }

    public MirrorPanel(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setBackground(null);
        setWillNotDraw(false);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setTintEnabled(boolean enabled) {
        this.tintEnabled = enabled;
        invalidate();
    }

    /** true = 分隔线画在自己下边缘（上半屏输入）；false = 画在上边缘（下半屏输入）。 */
    public void setDividerAtBottom(boolean atBottom) {
        this.dividerAtBottom = atBottom;
        invalidate();
    }

    /** 自己在屏幕上的纵向偏移，用来把屏幕坐标换算成自己的坐标。 */
    public void setOffsetY(int offsetY) {
        this.offsetY = offsetY;
        invalidate();
    }

    public void cancelTouch() {
        pressed = false;
        multiTouch = false;
        points.clear();
        invalidate();
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
                pressed = true;
                invalidate();
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                multiTouch = true;
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!multiTouch) {
                    if (distance(x, y, downX, downY) > touchSlop) {
                        pressed = false;
                        invalidate();
                    }
                    if (distance(x, y, lastX, lastY) > touchSlop * 0.35f) {
                        points.add(new PointF(x, y));
                        lastX = x;
                        lastY = y;
                    }
                }
                return true;

            case MotionEvent.ACTION_UP: {
                pressed = false;
                if (multiTouch) {
                    multiTouch = false;
                    points.clear();
                    invalidate();
                    return true;
                }
                long held = SystemClock.uptimeMillis() - downAt;
                boolean moved = distance(x, y, downX, downY) > touchSlop;
                addRipple(downX, downY - offsetY);
                if (listener != null) {
                    if (moved && points.size() > 1) {
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
                pressed = false;
                multiTouch = false;
                points.clear();
                invalidate();
                return true;

            default:
                return true;
        }
    }

    private void addRipple(float x, float y) {
        ripples.add(new Ripple(x, y, SystemClock.uptimeMillis()));
        if (ripples.size() > 6) {
            ripples.remove(0);
        }
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float w = getWidth();
        final float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        // 手指区的淡色底：一眼看出哪半边被接管了
        if (tintEnabled) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x0DFFFFFF);
            canvas.drawRect(0, 0, w, h, paint);
        }

        // 分隔线：画在朝向另一半的那条边上
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x40FFFFFF);
        if (dividerAtBottom) {
            canvas.drawRect(0, h - density, w, h, paint);
        } else {
            canvas.drawRect(0, 0, w, density, paint);
        }

        // 两端绿色小握把，提示这条线可以拖
        paint.setColor(0x552FE3A0);
        float gripW = 26 * density;
        float gripH = 3 * density;
        if (dividerAtBottom) {
            canvas.drawRoundRect(0, h - gripH, gripW, h, gripH, gripH, paint);
            canvas.drawRoundRect(w - gripW, h - gripH, w, h, gripH, gripH, paint);
        } else {
            canvas.drawRoundRect(0, 0, gripW, gripH, gripH, gripH, paint);
            canvas.drawRoundRect(w - gripW, 0, w, gripH, gripH, gripH, paint);
        }

        // 手指按下的即时反馈
        if (pressed) {
            float py = downY - offsetY;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f * density);
            paint.setColor(0x883DDC97);
            canvas.drawCircle(downX, py, 22 * density, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xAA3DDC97);
            canvas.drawCircle(downX, py, 5 * density, paint);
        }

        // 抬起后的水波纹
        long now = SystemClock.uptimeMillis();
        Iterator<Ripple> it = ripples.iterator();
        while (it.hasNext()) {
            Ripple r = it.next();
            float t = (now - r.start) / (float) RIPPLE_MS;
            if (t >= 1f) {
                it.remove();
                continue;
            }
            int alpha = (int) (190 * (1f - t));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f * density);
            paint.setColor((alpha << 24) | 0x3DDC97);
            canvas.drawCircle(r.x, r.y, (8f + 26f * t) * density, paint);
        }
        if (!ripples.isEmpty()) {
            postInvalidateOnAnimation();
        }
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        return (float) Math.hypot(x1 - x2, y1 - y2);
    }
}
