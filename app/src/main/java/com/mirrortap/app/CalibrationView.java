package com.mirrortap.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * 校准图：一块竖着的「屏幕」，上半画输入点，下半画落点，两点都能直接拖。
 * 拖上面的点 = 换一个参考位置；拖下面的点 = 调整整体落点偏移。
 */
public class CalibrationView extends View {

    public interface Listener {
        /**
         * @param inputPct   输入的参考位置（0~1）
         * @param outputPct  拖到的落点（0~1）
         * @param movedOutput true 表示用户拖的是落点（需要改偏移），false 表示只换了输入参考点
         */
        void onCalibrate(float inputPct, float outputPct, boolean movedOutput);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frame = new RectF();
    private final DashPathEffect dash;
    private final float density;

    private float split = 0.5f;
    private float inputPct = 0.1f;
    private float outputPct = 0.6f;
    private int dragging;

    private Listener listener;

    public CalibrationView(Context context) {
        this(context, null);
    }

    public CalibrationView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        dash = new DashPathEffect(new float[]{5f * density, 4f * density}, 0f);
        textPaint.setTextSize(11f * density);
        textPaint.setColor(0xFFE9EFF6);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setSplit(float split) {
        this.split = split;
        invalidate();
    }

    public void setPoints(float inputPct, float outputPct) {
        this.inputPct = inputPct;
        this.outputPct = outputPct;
        invalidate();
    }

    public float getInputPct() {
        return inputPct;
    }

    public float getOutputPct() {
        return outputPct;
    }

    private void computeFrame() {
        float pad = 12f * density;
        frame.set(pad, pad, getWidth() - pad, getHeight() - pad);
    }

    private float yOf(float pct) {
        return frame.top + frame.height() * pct;
    }

    private float pctOf(float y) {
        return Math.max(0f, Math.min(1f, (y - frame.top) / frame.height()));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        computeFrame();
        if (frame.width() <= 0 || frame.height() <= 0) {
            return;
        }
        float r = 14f * density;
        float midY = yOf(split);

        // 屏幕外框
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x08FFFFFF);
        canvas.drawRoundRect(frame, r, r, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f * density);
        paint.setColor(0x24FFFFFF);
        canvas.drawRoundRect(frame, r, r, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x0DFFFFFF);
        canvas.drawRect(frame.left, frame.top, frame.right, midY, paint);

        // 分隔线
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.4f * density);
        paint.setColor(0x883DDC97);
        paint.setPathEffect(dash);
        canvas.drawLine(frame.left, midY, frame.right, midY, paint);
        paint.setPathEffect(null);

        // 右侧 10% 刻度
        paint.setStrokeWidth(1f * density);
        paint.setColor(0x22FFFFFF);
        for (int i = 1; i < 10; i++) {
            float y = yOf(i / 10f);
            float len = (i == 5) ? 12f * density : 6f * density;
            canvas.drawLine(frame.right - len, y, frame.right, y, paint);
        }

        float x = frame.left + frame.width() * 0.34f;
        float yA = yOf(inputPct);
        float yB = yOf(outputPct);

        // 输入 → 落点 的连线
        paint.setStrokeWidth(1.2f * density);
        paint.setColor(0x553DDC97);
        paint.setPathEffect(dash);
        canvas.drawLine(x, yA, x, yB, paint);
        paint.setPathEffect(null);

        // 输入点
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF3DDC97);
        canvas.drawCircle(x, yA, 7f * density, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f * density);
        paint.setColor(0x663DDC97);
        canvas.drawCircle(x, yA, 13f * density, paint);

        // 落点
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF10151B);
        canvas.drawCircle(x, yB, 9f * density, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f * density);
        paint.setColor(0xFF3DDC97);
        canvas.drawCircle(x, yB, 9f * density, paint);
        paint.setStrokeWidth(1f * density);
        paint.setColor(0xFF3DDC97);
        canvas.drawLine(x - 4 * density, yB, x + 4 * density, yB, paint);
        canvas.drawLine(x, yB - 4 * density, x, yB + 4 * density, paint);

        // 文字
        canvas.drawText(String.format(Locale.US, "输入 %.1f%%", inputPct * 100f),
                x + 20 * density, yA + 4 * density, textPaint);
        canvas.drawText(String.format(Locale.US, "落点 %.1f%%", outputPct * 100f),
                x + 20 * density, yB + 4 * density, textPaint);

        textPaint.setColor(0x7AFFFFFF);
        canvas.drawText("拖这里", frame.left + 10 * density, yA + 4 * density, textPaint);
        canvas.drawText("或拖这里", frame.left + 10 * density, yB + 4 * density, textPaint);
        textPaint.setColor(0xFFE9EFF6);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        computeFrame();
        if (frame.height() <= 0) {
            return false;
        }
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float dA = Math.abs(y - yOf(inputPct));
                float dB = Math.abs(y - yOf(outputPct));
                dragging = dA <= dB ? 1 : 2;
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float pct = pctOf(y);
                if (dragging == 1) {
                    float limit = split - 0.01f;
                    inputPct = Math.max(0f, Math.min(limit, pct));
                } else if (dragging == 2) {
                    float limit = split + 0.01f;
                    outputPct = Math.max(limit, Math.min(1f, pct));
                }
                invalidate();
                if (listener != null) {
                    listener.onCalibrate(inputPct, outputPct, dragging == 2);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = 0;
                return true;
        }
        return super.onTouchEvent(event);
    }
}
