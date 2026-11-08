package com.mirrortap.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * 分隔线右侧的悬浮把手：上下拖动改比例，轻点暂停/恢复。
 * 它整个挂在分隔线下方，所以永远不会和上半区的触摸层抢位置。
 */
public class DividerHandle extends View {

    public interface Listener {
        void onHandleDrag(float screenY);

        void onHandleDragEnd();

        void onHandleTap();
    }

    public static final int WIDTH_DP = 34;
    public static final int HEIGHT_DP = 62;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final float density;
    private final int touchSlop;

    private Listener listener;
    private boolean paused;
    private boolean dragging;
    private boolean pressed;
    private float downY;

    public DividerHandle(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setClickable(true);
        setContentDescription("镜像点击控制把手");
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(
                Math.round(WIDTH_DP * density),
                Math.round(HEIGHT_DP * density));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float w = getWidth();
        final float h = getHeight();
        final float r = Math.min(w, h) / 2f;

        rect.set(0, 0, w, h);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(paused ? 0xD92A2113 : (pressed ? 0xE61B2530 : 0xCC10151B));
        canvas.drawRoundRect(rect, r, r, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f * density);
        paint.setColor(paused ? 0x66FFB84D : 0x33FFFFFF);
        canvas.drawRoundRect(rect, r, r, paint);

        final float cx = w / 2f;
        final float cy = h / 2f;

        if (paused) {
            // ▶
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFFFB84D);
            float s = 7f * density;
            path.reset();
            path.moveTo(cx - s * 0.55f, cy - s);
            path.lineTo(cx + s * 0.85f, cy);
            path.lineTo(cx - s * 0.55f, cy + s);
            path.close();
            canvas.drawPath(path, paint);
        } else {
            // 上下两个箭头，表示这条线可以拖
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.9f * density);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(0xFF3DDC97);
            drawChevron(canvas, cx, cy - 9f * density, true);
            drawChevron(canvas, cx, cy + 9f * density, false);

            paint.setColor(0x40FFFFFF);
            paint.setStrokeWidth(1f * density);
            canvas.drawLine(cx - 5f * density, cy, cx + 5f * density, cy, paint);
        }
    }

    private void drawChevron(Canvas canvas, float cx, float cy, boolean up) {
        float dx = 5f * density;
        float dy = 3.5f * density;
        path.reset();
        if (up) {
            path.moveTo(cx - dx, cy + dy);
            path.lineTo(cx, cy - dy);
            path.lineTo(cx + dx, cy + dy);
        } else {
            path.moveTo(cx - dx, cy - dy);
            path.lineTo(cx, cy + dy);
            path.lineTo(cx + dx, cy - dy);
        }
        canvas.drawPath(path, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = true;
                dragging = false;
                downY = event.getRawY();
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!dragging && Math.abs(event.getRawY() - downY) > touchSlop) {
                    dragging = true;
                }
                if (dragging && listener != null) {
                    listener.onHandleDrag(event.getRawY());
                }
                return true;

            case MotionEvent.ACTION_UP:
                pressed = false;
                invalidate();
                if (dragging) {
                    if (listener != null) {
                        listener.onHandleDragEnd();
                    }
                } else if (listener != null) {
                    listener.onHandleTap();
                }
                dragging = false;
                return true;

            case MotionEvent.ACTION_CANCEL:
                pressed = false;
                if (dragging && listener != null) {
                    listener.onHandleDragEnd();
                }
                dragging = false;
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }
}
