package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.scaled.GuiMotion;
import com.dishanhai.gt_shanhai.client.gui.scaled.Spring;

/** 山海提示動效：高度改變時保持彈簧速度，退場從當前透明度接續。 */
final class ShopNoticeMotion {
    private static final long ENTER_MS = 200L;
    private static final long EXIT_MS = 220L;
    private final Spring position;
    private final long enteredAtMs;
    private boolean leaving;
    private long leftAtMs;
    private float exitOpacity;
    private float exitOffset;

    ShopNoticeMotion(float initialOffset, long nowMs) {
        position = new Spring(Spring.SCROLL, initialOffset);
        enteredAtMs = nowMs;
    }

    void moveTo(float offset, long nowMs) {
        if (leaving) return;
        position.update(nowMs);
        position.setTarget(offset, nowMs);
    }

    void leave(long nowMs) {
        if (leaving) return;
        exitOpacity = opacity(nowMs, false);
        exitOffset = offset(nowMs, false);
        leftAtMs = nowMs;
        leaving = true;
    }

    float offset(long nowMs, boolean reduceMotion) {
        if (reduceMotion) {
            position.snap(position.target());
            return position.value();
        }
        if (leaving) return exitOffset + 8f * ease((nowMs - leftAtMs) / (float) EXIT_MS);
        position.update(nowMs);
        return position.value();
    }

    float opacity(long nowMs, boolean reduceMotion) {
        if (reduceMotion) return leaving ? 0f : 1f;
        return leaving ? exitOpacity * (1f - ease((nowMs - leftAtMs) / (float) EXIT_MS))
                : ease((nowMs - enteredAtMs) / (float) ENTER_MS);
    }

    boolean finished(long nowMs) {
        return leaving && nowMs - leftAtMs >= EXIT_MS;
    }

    static float ease(float progress) {
        float t = GuiMotion.clamp01(progress);
        return t * t * (3f - 2f * t);
    }
}
