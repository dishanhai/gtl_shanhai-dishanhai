package com.dishanhai.gt_shanhai.common.item.generator;

/** 样板数量倍率。输入按 scale，输出再乘 outputScale。溢出直接失败，不截断。 */
public final class PatternGeneratorScale {
    public static final int MAX_FACTOR = 64;

    private PatternGeneratorScale() {}

    public static int clamp(int value) {
        if (value < 1) return 1;
        return Math.min(MAX_FACTOR, value);
    }

    public static int outputFactor(int scale, int outputScale) {
        return clamp(scale) * clamp(outputScale);
    }

    public static long apply(long amount, int factor) {
        if (amount <= 0) throw new ArithmeticException("non-positive pattern amount");
        int safe = Math.max(1, factor);
        if (safe == 1) return amount;
        if (amount > Long.MAX_VALUE / safe) throw new ArithmeticException("pattern amount overflow");
        return amount * safe;
    }
}
