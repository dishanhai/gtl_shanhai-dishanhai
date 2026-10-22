package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.scaled.GuiMotion;

import java.util.Objects;

/** 山海商店 tooltip 出場狀態：按懸停目標識別，不跟隨每幀更新的文字或滑鼠位置重播。 */
final class ShopTooltipMotion {
    private static final long ENTER_MS = 150L;
    private Object target;
    private long enteredAtMs;
    private boolean shown;

    float show(Object key, long nowMs, boolean reduceMotion) {
        if (!Objects.equals(target, key)) {
            target = key;
            enteredAtMs = nowMs;
        }
        shown = true;
        if (reduceMotion) enteredAtMs = nowMs - ENTER_MS;
        float t = GuiMotion.clamp01((nowMs - enteredAtMs) / (float) ENTER_MS);
        return 1f - (1f - t) * (1f - t);
    }

    void endFrame() {
        if (!shown) reset();
        shown = false;
    }

    void reset() {
        target = null;
        shown = false;
    }
}
