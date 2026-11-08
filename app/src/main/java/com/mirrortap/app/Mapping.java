package com.mirrortap.app;

/**
 * 上下屏映射的唯一实现，服务和校准界面共用，保证「预览」和「实际落点」永远一致。
 *
 * 坐标一律用 0~1 的比例（y / 屏幕高度），最后再由调用方乘回去。
 */
public final class Mapping {

    private Mapping() {
    }

    /**
     * @param y         输入点（0~1）
     * @param split     分隔线（0~1）
     * @param offsetMode true = 同样相对位置；false = 对称镜像
     * @param gain      输出半边的缩放（1.0 为原样）
     * @param offset    整体偏移（0~1，正数往下）
     * @return 落点（0~1）
     */
    public static float map(float y, float split, boolean down, boolean offsetMode,
                            float gain, float offset) {
        float inTop = down ? 0f : split;
        float inBottom = down ? split : 1f;
        float outTop = down ? split : 0f;
        float outBottom = down ? 1f : split;

        float base;
        if (offsetMode) {
            float inH = Math.max(0.0001f, inBottom - inTop);
            float outH = Math.max(0.0001f, outBottom - outTop);
            float t = (y - inTop) / inH;
            t = Math.max(0f, Math.min(1f, t));
            base = outTop + t * outH;
        } else {
            base = 2f * split - y;
        }

        float outH = Math.max(0.0001f, outBottom - outTop);
        float mapped = outTop + (base - outTop) * gain + offset;
        float min = Math.min(outTop, outBottom) + 0.001f;
        float max = Math.max(outTop, outBottom) - 0.001f;
        return Math.max(min, Math.min(max, mapped));
    }
}
