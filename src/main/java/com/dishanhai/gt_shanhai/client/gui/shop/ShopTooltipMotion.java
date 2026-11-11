package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.sh_better_ui.client.gui.scaled.GuiMotion;

import java.util.Objects;

/** 山海商店 tooltip 出場狀態：按懸停目標識別，不跟隨每幀更新的文字或滑鼠位置重播。 */
final class ShopTooltipMotion {
    private static final long ENTER_MS = 150L;
    private Object target;
    private long enteredAtMs;
    private boolean shown;

    float show(Object key, long nowMs, boolean reduceMotion) {
        return show(key, nowMs, reduceMotion, 0);
    }

    float show(Object key, long nowMs, boolean reduceMotion, int delayMs) {
        if (!Objects.equals(target, key)) {
            target = key;
            enteredAtMs = nowMs;
        }
        shown = true;
        long elapsed = Math.max(0L, nowMs - enteredAtMs);
        if (elapsed < delayMs) return -1f;
        if (reduceMotion) {
            enteredAtMs = nowMs - delayMs - ENTER_MS;
            return 1f;
        }
        float t = GuiMotion.clamp01((elapsed - delayMs) / (float) ENTER_MS);
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
