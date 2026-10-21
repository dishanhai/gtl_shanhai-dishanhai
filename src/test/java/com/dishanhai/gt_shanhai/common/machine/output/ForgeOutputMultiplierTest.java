package com.dishanhai.gt_shanhai.common.machine.output;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 伪神之锻炉倍率的纯数值规则。依据 gtladditions / gtlcore 字节码：
 * 倍率是 1~15 的连续 double，实际产出 = floor(数量 × 倍率)，所以样板只能按「向下取整」声明。
 */
class ForgeOutputMultiplierTest {

    @Test
    void fractionalHostMultiplierIsFlooredNeverRoundedUp() {
        // Math.round(7.6) = 8，但原版 floor(1 × 7.6) = 7：高报会让 AE 永远等不到第 8 个产物
        assertEquals(7L, ForgeOutputMultiplier.quantize(7.6D));
        assertEquals(7L, ForgeOutputMultiplier.quantize(7.0D));
        assertEquals(14L, ForgeOutputMultiplier.quantize(14.99D));
    }

    @Test
    void cappedHostMultiplierIsStaticFifteen() {
        assertEquals(15L, ForgeOutputMultiplier.quantize(15.0D));
        // 机器侧拿同一个 double 做 floor(1 x m)；样板不能比它多报哪怕 ulp 级的一格
        assertEquals(14L, ForgeOutputMultiplier.quantize(Math.nextDown(15.0D)));
    }

    @Test
    void rampUpFollowsTheRealEfficiencyCurve() {
        // 1 + 14 * (secs / 14400)^2，每小时一个采样点
        assertEquals(1L, ForgeOutputMultiplier.quantize(curve(0)));
        assertEquals(1L, ForgeOutputMultiplier.quantize(curve(3600)));   // 1.875
        assertEquals(4L, ForgeOutputMultiplier.quantize(curve(7200)));   // 4.5
        assertEquals(8L, ForgeOutputMultiplier.quantize(curve(10800)));  // 8.875
        assertEquals(15L, ForgeOutputMultiplier.quantize(curve(14400)));
        assertEquals(15L, ForgeOutputMultiplier.quantize(curve(114514L * 3600L)));
    }

    @Test
    void degenerateInputsFallBackToOne() {
        assertEquals(1L, ForgeOutputMultiplier.quantize(Double.NaN));
        assertEquals(1L, ForgeOutputMultiplier.quantize(Double.NEGATIVE_INFINITY));
        assertEquals(1L, ForgeOutputMultiplier.quantize(0.0D));
        assertEquals(1L, ForgeOutputMultiplier.quantize(1.0D));
        assertEquals(Long.MAX_VALUE, ForgeOutputMultiplier.quantize(Double.POSITIVE_INFINITY));
    }

    @Test
    void specialInputNeedsMultiplierAtLeastTwo() {
        // appendSpecificInput：modifier.getMultiplier() >= 2.0 才追加；追加量 = unit × (倍率 - 1)
        assertEquals(0L, ForgeOutputMultiplier.specialInputExtra(1L, 0L));
        assertEquals(0L, ForgeOutputMultiplier.specialInputExtra(1L, 1L));
        assertEquals(1L, ForgeOutputMultiplier.specialInputExtra(1L, 2L));
        assertEquals(14L, ForgeOutputMultiplier.specialInputExtra(1L, 15L));
        assertEquals(28L, ForgeOutputMultiplier.specialInputExtra(2L, 15L));
    }

    @Test
    void specialInputSaturatesInsteadOfOverflowing() {
        assertEquals(0L, ForgeOutputMultiplier.specialInputExtra(0L, 15L));
        assertEquals(0L, ForgeOutputMultiplier.specialInputExtra(-3L, 15L));
        assertEquals(Long.MAX_VALUE, ForgeOutputMultiplier.specialInputExtra(Long.MAX_VALUE, 15L));
    }

    private static double curve(long runningSecs) {
        return 1.0D + 14.0D * Math.pow(Math.min(runningSecs, 14400L) / 14400.0D, 2.0D);
    }
}
