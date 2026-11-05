package com.dishanhai.gt_shanhai.common.item.generator;

import com.dishanhai.sh_better_ui.client.gui.scaled.GuiMotion;

/** 山海：生成器舊入口；純視覺計算由 SH-Better-UI 提供。 */
public final class PatternGeneratorMotion {
    private PatternGeneratorMotion() {}
    public static long staggerDelay(int index) { return GuiMotion.staggerDelay(index); }
    public static float scrollBlur(float velocity) { return GuiMotion.scrollBlur(velocity); }
}
