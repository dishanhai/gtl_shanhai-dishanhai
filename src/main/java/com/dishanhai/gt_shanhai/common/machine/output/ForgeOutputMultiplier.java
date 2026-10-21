package com.dishanhai.gt_shanhai.common.machine.output;

/**
 * 伪神之锻炉（gtladditions ForgeOfTheAntichrist）产出倍率的纯数值规则，不依赖 Minecraft 类，可直接单测。
 * <p>
 * 字节码依据（gtladditions-3.3.1Custom / gtlcore-1.2.3.2-fix4）：
 * <ul>
 *   <li>{@code ForgeOfTheAntichrist.baseRecipeOutputMultiply()} = {@code 1 + 14 * (min(runningSecs, 14400) / 14400)^2}，
 *       是 1~15 的连续 double：工作时每秒 +1，闲置每秒 -16，只有封顶 15 才是静态值。</li>
 *   <li>{@code ContentModifier.apply} 返回 {@code 数量 * 倍率}（Double），{@code ItemRecipeCapabilityMixin} /
 *       {@code FluidRecipeCapabilityMixin} 再 {@code Number.longValue()} 截断，所以实际产出 = {@code floor(数量 × 倍率)}。</li>
 *   <li>{@code RecipeCalculationHelper.appendSpecificInput}：倍率 ≥ 2.0 时才给三条 stellar_forge 配方追加
 *       {@code 单位量 × (倍率 - 1)} 个空容器。</li>
 * </ul>
 * 样板声明量只能向下取整：高报会让 AE 永远等不到多出来的那一份，低报只会多出库存盈余。
 */
public final class ForgeOutputMultiplier {

    /** FOTCMixins 里模块脱离主机独立运行（host == null）时写死的 15.0D。 */
    public static final long STANDALONE_MODULE_MULTIPLIER = 15L;

    private ForgeOutputMultiplier() {}

    /** 把宿主返回的连续倍率量化成样板可用的整数倍率（向下取整，封顶 15.0 时恰为 15）。 */
    public static long quantize(double raw) {
        if (Double.isNaN(raw) || raw <= 1.0D) return 1L;
        if (raw >= (double) Long.MAX_VALUE) return Long.MAX_VALUE;
        // 不加容差：机器侧用同一个 double 做 amount * multiplier 再 longValue()，样板必须和它逐位一致
        return Math.max(1L, (long) Math.floor(raw));
    }

    /** {@code appendSpecificInput} 追加的空容器数量；倍率 &lt; 2 或单位量非正时不追加。 */
    public static long specialInputExtra(long unitAmount, long forgeMultiplier) {
        if (unitAmount <= 0L || forgeMultiplier < 2L) return 0L;
        try {
            return Math.multiplyExact(unitAmount, forgeMultiplier - 1L);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
