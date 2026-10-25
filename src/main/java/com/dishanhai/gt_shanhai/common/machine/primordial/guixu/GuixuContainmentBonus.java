package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

// Author: dishanhai

/**
 * 山海：遏制場對整台歸墟的增益快照（不可變）。
 *
 * <p>數值見 spec 第 5 節。{@link #NONE} 代表「沒有任何影響」，非歸墟主機與未安裝時一律拿到它，
 * 呼叫端以 {@link #affectsRecipes()} 判斷要不要走改寫路徑，確保舊行為逐位不變。
 */
public record GuixuContainmentBonus(long parallelMultiplier, double durationMultiplier, int extraOutput,
                                    double energyMultiplier, int matterCountMultiplier,
                                    double swallowChance, boolean locked) {

    public static final int EXTRA_OUTPUT = 5;
    public static final double ENERGY_MULTIPLIER = 0.5D;
    public static final int MATTER_COUNT_MULTIPLIER = 2;

    public static final GuixuContainmentBonus NONE = new GuixuContainmentBonus(1L, 1.0D, 0, 1.0D, 1, 0.0D, false);
    public static final GuixuContainmentBonus LOCKED = new GuixuContainmentBonus(1L, 1.0D, 0, 1.0D, 1, 0.0D, true);

    public static GuixuContainmentBonus of(GuixuContainmentField.Status status, float stability) {
        return switch (status) {
            case HYPERSTABLE -> active(4L, 0.5D);
            case CONTAINED -> stability >= 50.0F ? active(4L, 0.5D)
                    : stability >= 20.0F ? active(8L, 0.25D)
                    : active(16L, 0.125D);
            case UNSTABLE -> new GuixuContainmentBonus(1L, 1.0D, 0, 1.0D, 1, swallowChanceAt(stability), false);
            case CLOSED -> NONE;
        };
    }

    /** 失穩時的吞產出機率：0 → 0%，−900 → 100%，線性。 */
    public static double swallowChanceAt(float stability) {
        return Math.max(0.0D, Math.min(1.0D, -stability / 900.0D));
    }

    private static GuixuContainmentBonus active(long parallel, double duration) {
        return new GuixuContainmentBonus(parallel, duration, EXTRA_OUTPUT, ENERGY_MULTIPLIER,
                MATTER_COUNT_MULTIPLIER, 0.0D, false);
    }

    public boolean affectsRecipes() {
        return !NONE.equals(this);
    }

    public long scaleParallel(long base) {
        if (base <= 0L || parallelMultiplier <= 1L) return base;
        return base > Long.MAX_VALUE / parallelMultiplier ? Long.MAX_VALUE : base * parallelMultiplier;
    }

    /** 批次最終耗時：向下取整，下限 1 tick（spec 第 5 節：不以最短耗時為下限）。 */
    public int scaleDuration(int ticks) {
        if (durationMultiplier >= 1.0D) return ticks;
        return Math.max(1, (int) Math.floor(ticks * durationMultiplier));
    }

    /** 只縮耗電（正值）；發電或 0 原樣返回。 */
    public long scaleEnergy(long eut) {
        if (eut <= 0L || energyMultiplier >= 1.0D) return eut;
        return Math.max(1L, Math.round(eut * energyMultiplier));
    }

    public double energyFactor(boolean energyConsumer) {
        return energyConsumer ? energyMultiplier : 1.0D;
    }

    public boolean shouldSwallow(double roll) {
        return roll < swallowChance;
    }
}
