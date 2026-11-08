package com.mirrortap.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 全屏的「只画不给摸」层：分隔线、上半区浅色提示、以及每次镜像落点的水波纹。
 * 设置 FLAG_NOT_TOUCHABLE，所以它绝不挡住任何触摸。
 */
public class HudLayer extends View {

    private static final long GHOST_MS = 360L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Ghost> ghosts = new ArrayList<>();
    private final float density;

    private float splitPx;
    private boolean tintEnabled = true;
    private boolean active = true;

    private static final class Ghost {
        final float x;
        final float y;
        final long start;

        Ghost(float x, float y, long start) {
            this.x = x;
            this.y = y;
            this.start = start;
        }
    }

    public HudLayer(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setBackground(null);
    }

    public void setSplit(float splitPx) {
        this.splitPx = splitPx;
        invalidate();
    }

    public void setTintEnabled(boolean enabled) {
        this.tintEnabled = enabled;
        invalidate();
    }

    public void setActive(boolean active) {
        this.active = active;
        if (!active) {
            ghosts.clear();
        }
        invalidate();
    }

    public void addGhost(float x, float y) {
        ghosts.add(new Ghost(x, y, SystemClock.uptimeMillis()));
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!active || splitPx <= 0) {
            return;
        }
        final int w = getWidth();

        // 上半区提示底色（很淡，能看出哪半边是“手指区”）
        if (tintEnabled) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x0FFFFFFF);
            canvas.drawRect(0, 0, w, splitPx, paint);
        }

        // 分隔线
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x40FFFFFF);
        canvas.drawRect(0, splitPx - density * 0.5f, w, splitPx + density * 0.5f, paint);

        // 两端的绿色小握把，提示这条线可以拖
        paint.setColor(0x552FE3A0);
        float gripW = 26 * density;
        float gripH = 3 * density;
        canvas.drawRoundRect(0, splitPx - gripH / 2f, gripW, splitPx + gripH / 2f, gripH, gripH, paint);
        canvas.drawRoundRect(w - gripW, splitPx - gripH / 2f, w, splitPx + gripH / 2f, gripH, gripH, paint);

        // 落点水波纹
        long now = SystemClock.uptimeMillis();
        Iterator<Ghost> it = ghosts.iterator();
        while (it.hasNext()) {
            Ghost g = it.next();
            float t = (now - g.start) / (float) GHOST_MS;
            if (t >= 1f) {
                it.remove();
                continue;
            }
            int alpha = (int) (200 * (1f - t));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f * density);
            paint.setColor(Color.argb(alpha, 61, 220, 151));
            canvas.drawCircle(g.x, g.y, (8f + 26f * t) * density, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(Math.max(0, alpha - 60), 61, 220, 151));
            canvas.drawCircle(g.x, g.y, 4.5f * density, paint);
        }
        if (!ghosts.isEmpty()) {
            postInvalidateOnAnimation();
        }
    }
}
