package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

// Author: dishanhai

/**
 * 山海：歸墟彭羅斯黑洞遏制場的狀態機（純 Java，不依賴 MC）。
 *
 * <p>模組每秒呼叫一次 {@link #tickSecond(boolean)}；規則見
 * {@code docs/superpowers/specs/2026-10-25-guixu-penrose-containment-design.md} 第 4 節。
 * 數值沿用 {@code ShanhaiBlackHoleContainmentMachine}（GTNH 黑洞壓縮機移植）。
 */
public final class GuixuContainmentField {

    public enum Status { CLOSED, CONTAINED, UNSTABLE, HYPERSTABLE }

    public enum Event { NONE, OPENED, DESTABILIZED, COLLAPSED, SHUT_DOWN }

    public static final float MAX_STABILITY = 100.0F;
    public static final float COLLAPSE_STABILITY = -900.0F;
    public static final long BASE_SPACETIME_MB = 10_000L;
    public static final int CATALYSIS_PER_DOUBLING = 30;
    public static final long MAX_COST_MODIFIER = 1_000_000_000L;

    private Status status = Status.CLOSED;
    private float stability = MAX_STABILITY;
    private long costModifier = 1L;
    private int catalysisCounter;

    public Status status() { return status; }
    public float stability() { return stability; }
    public long costModifier() { return costModifier; }
    public int catalysisCounter() { return catalysisCounter; }
    public boolean isOpen() { return status != Status.CLOSED; }

    /** 投入種子：只有關閉狀態能開啟。 */
    public Event open(boolean hyperstable) {
        if (status != Status.CLOSED) return Event.NONE;
        status = hyperstable ? Status.HYPERSTABLE : Status.CONTAINED;
        stability = MAX_STABILITY;
        return Event.OPENED;
    }

    /** 投入坍縮器：任何開啟狀態都能安全關閉，消耗倍率一併重置。 */
    public Event shutDown() {
        if (status == Status.CLOSED) return Event.NONE;
        reset();
        return Event.SHUT_DOWN;
    }

    /** 失穩期間結構失效或被拆除：視同坍縮。 */
    public Event forceCollapse() {
        if (status != Status.UNSTABLE) return Event.NONE;
        reset();
        return Event.COLLAPSED;
    }

    /** 本秒需要的時空流體（mB）；只有遏制中才需要。 */
    public long requiredSpacetime() {
        if (status != Status.CONTAINED) return 0L;
        return costModifier > Long.MAX_VALUE / BASE_SPACETIME_MB ? Long.MAX_VALUE : BASE_SPACETIME_MB * costModifier;
    }

    public Event tickSecond(boolean spacetimeSupplied) {
        switch (status) {
            case CONTAINED -> {
                if (spacetimeSupplied) {
                    recordCatalysis();
                    return Event.NONE;
                }
                stability -= 1.0F;
                if (stability <= 0.0F) {
                    status = Status.UNSTABLE;
                    return Event.DESTABILIZED;
                }
                return Event.NONE;
            }
            case UNSTABLE -> {
                stability -= 1.0F;
                if (stability <= COLLAPSE_STABILITY) {
                    reset();
                    return Event.COLLAPSED;
                }
                return Event.NONE;
            }
            default -> {
                return Event.NONE;
            }
        }
    }

    /** 從存檔還原；狀態序號非法時收斂回關閉。 */
    public void restore(int statusOrdinal, float savedStability, long savedCostModifier, int savedCounter) {
        Status[] values = Status.values();
        if (statusOrdinal < 0 || statusOrdinal >= values.length) {
            reset();
            return;
        }
        status = values[statusOrdinal];
        stability = savedStability;
        costModifier = Math.max(1L, Math.min(MAX_COST_MODIFIER, savedCostModifier));
        catalysisCounter = Math.max(0, Math.min(CATALYSIS_PER_DOUBLING - 1, savedCounter));
    }

    private void recordCatalysis() {
        catalysisCounter++;
        if (catalysisCounter >= CATALYSIS_PER_DOUBLING) {
            catalysisCounter = 0;
            costModifier = Math.min(MAX_COST_MODIFIER, costModifier * 2L);
        }
    }

    private void reset() {
        status = Status.CLOSED;
        stability = MAX_STABILITY;
        costModifier = 1L;
        catalysisCounter = 0;
    }
}
