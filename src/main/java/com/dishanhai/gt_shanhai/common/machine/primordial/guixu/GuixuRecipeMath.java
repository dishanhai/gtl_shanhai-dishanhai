package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

/** 山海：沿用 Integrate 的主機物質模組門控公式。 */
public final class GuixuRecipeMath {
    private GuixuRecipeMath() {}

    public static int outputMultiplier(int level) {
        return level <= 0 ? 1 : 1 + level;
    }

    public static double reductionFactor(int level) {
        return level <= 0 ? 1.0D : 1.0D - Math.pow(0.95D, 17.0D / level);
    }

    public static long reducedEnergyEut(long original, int level) {
        if (original <= 0L || level <= 0) return original;
        return Math.max(1L, Math.round(original * reductionFactor(level)));
    }
}
