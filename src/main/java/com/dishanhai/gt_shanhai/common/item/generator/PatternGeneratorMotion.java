package com.dishanhai.gt_shanhai.common.item.generator;

/** 山海：生成器錯峰與捲動模糊的純數學。 */
public final class PatternGeneratorMotion {
    private PatternGeneratorMotion() {}
    public static long staggerDelay(int index) { return Math.min(240L, Math.max(0L, index) * 12L); }
    public static float scrollBlur(float velocity) {
        if (Float.isNaN(velocity)) return 0;
        return Math.min(1f, Math.max(0f, Math.abs(velocity) / 1800f - 0.04f));
    }
}
