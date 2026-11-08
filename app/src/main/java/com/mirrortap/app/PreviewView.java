package com.mirrortap.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** 首页顶部那张示意图：上半区点一下 → 下半区镜像位置亮一下。 */
public class PreviewView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frame = new RectF();
    private final DashPathEffect dash;

    private final float density;
    private ValueAnimator animator;
    private float phase;

    public PreviewView(Context context) {
        this(context, null);
    }

    public PreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        dash = new DashPathEffect(new float[]{5f * density, 4f * density}, 0f);
        textPaint.setTextSize(10f * density);
        textPaint.setColor(0x7AFFFFFF);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(2600L);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(animation -> {
                phase = (float) animation.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float pad = 12f * density;
        frame.set(pad, pad, getWidth() - pad, getHeight() - pad);
        if (frame.width() <= 0 || frame.height() <= 0) {
            return;
        }
        final float radius = 14f * density;
        final float mid = (frame.top + frame.bottom) / 2f;

        // 外框
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x08FFFFFF);
        canvas.drawRoundRect(frame, radius, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f * density);
        paint.setColor(0x24FFFFFF);
        canvas.drawRoundRect(frame, radius, radius, paint);

        // 上半区淡淡的填充
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x0DFFFFFF);
        RectF top = new RectF(frame.left, frame.top, frame.right, mid);
        canvas.drawRoundRect(top, radius, radius, paint);

        // 分隔线
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.4f * density);
        paint.setColor(0x883DDC97);
        paint.setPathEffect(dash);
        canvas.drawLine(frame.left + 10 * density, mid, frame.right - 10 * density, mid, paint);
        paint.setPathEffect(null);

        final float cx = frame.left + frame.width() * 0.62f;
        final float half = (mid - frame.top);
        final float topY = mid - half * 0.46f;
        final float bottomY = mid + half * 0.46f;

        // 文字
        canvas.drawText("点这里", frame.left + 12 * density, topY + 4 * density, textPaint);
        canvas.drawText("自动点这里", frame.left + 12 * density, bottomY + 4 * density, textPaint);

        // 上半区的手指落点
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xE63DDC97);
        canvas.drawCircle(cx, topY, 5.5f * density, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f * density);
        paint.setColor(0x553DDC97);
        canvas.drawCircle(cx, topY, (8f + 3f * (float) Math.sin(phase * Math.PI * 4)) * density, paint);

        // 镜像落点
        float pop = 0f;
        if (phase > 0.42f && phase < 0.75f) {
            pop = (float) Math.sin((phase - 0.42f) / 0.33f * Math.PI);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x333DDC97);
        canvas.drawCircle(cx, bottomY, 5.5f * density, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f * density);
        paint.setColor(0xCC3DDC97);
        canvas.drawCircle(cx, bottomY, (7f + 16f * pop) * density, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor((int) (0xE6 * Math.max(0.25f, pop)) << 24 | 0x3DDC97);
        canvas.drawCircle(cx, bottomY, (4f + 2.5f * pop) * density, paint);

        // 中间那条“镜像”箭头
        if (phase > 0.18f && phase < 0.45f) {
            float t = (phase - 0.18f) / 0.27f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.4f * density);
            paint.setColor(0x663DDC97);
            paint.setPathEffect(dash);
            canvas.drawLine(cx, topY + 8 * density,
                    cx, topY + 8 * density + (bottomY - topY - 16 * density) * t, paint);
            paint.setPathEffect(null);
        }
    }
}
