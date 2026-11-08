package com.mirrortap.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * 分隔线自己的一条 6dp 高的细窗口（不可触摸）。
 *
 * 之所以把线从 {@link MirrorPanel} 里拆出来：补点原位时需要把整个触摸层窗口摘掉
 * （否则 App 的 filterTouchesWhenObscured 会丢掉那一下），如果线跟着触摸层一起消失，
 * 每次点按屏幕就会闪一下。拆成独立窗口后，摘触摸层时线还在，视觉上不闪。
 */
public class DividerStrip extends View {

    public static final int HEIGHT_DP = 6;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    public DividerStrip(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setBackground(null);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float w = getWidth();
        final float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        final float cy = h / 2f;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x40FFFFFF);
        canvas.drawRect(0, cy - density * 0.5f, w, cy + density * 0.5f, paint);

        // 两端绿色小握把
        paint.setColor(0x552FE3A0);
        float gripW = 26 * density;
        float gripH = 3 * density;
        canvas.drawRoundRect(0, cy - gripH / 2f, gripW, cy + gripH / 2f, gripH, gripH, paint);
        canvas.drawRoundRect(w - gripW, cy - gripH / 2f, w, cy + gripH / 2f, gripH, gripH, paint);
    }
}
