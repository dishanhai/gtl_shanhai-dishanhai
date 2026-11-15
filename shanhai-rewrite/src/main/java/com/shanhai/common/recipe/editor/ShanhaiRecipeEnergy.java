package com.shanhai.common.recipe.editor;

/**
 * <b>「电压档 × 电流 = 耗能」这一小块算术的唯一实现</b>（纯 Java，无 MC / GT / LDLib 依赖）。
 *
 * <h2>为什么把它单独抽出来</h2>
 * 用户报的 bug 是「编辑电流之后反而时长增加了，耗能功率没有变」。要修的是
 * {@code 耗能 = 该档电压 × 电流} 这条口径，而 {@link ShanhaiRecipeEditorWorkspace} 那个类
 * （4881 行、依赖 MinecraftServer / LDLib / GT 的 {@code GTRecipe}）<b>没法脱机跑</b>。
 * 所以这条口径的算术搬到这里：它只用 {@code long} 和 {@code int}，可以
 * <b>用普通 javac 编一份、在离线探针里对真代码跑判据</b>（见 {@code temp/recipe-amp/probe}）。
 *
 * <h2>🔴 电流上限 = {@link Integer#MAX_VALUE}（不是 64，也不是 MAX-1）</h2>
 * 老代码是 {@code AMPERAGE_MAX = 64} —— 那是<b>业务上限</b>，用户明确要求去掉
 * （原话：「把那个上限64A的去掉」）。现在的上限就是 {@code int} 的边界本身，
 * 它的理由<b>只有一条</b>：电流在界面上是 {@code IntInputWidget}／{@code int} 字段，装不下更大的数。
 *
 * <p>它<b>不会</b>把 {@code 电压 × 电流} 撑破 {@code long}：
 * <pre>
 *   MAX 档电压 = 2147483647（{@link ShanhaiRecipeEditorWorkspace#TIER_VOLTAGE} 最后一档）
 *   电流上限   = 2147483647
 *   乘积       = 4611686014132420609  （≈ 4.61e18）
 *   Long.MAX   = 9223372036854775807  （≈ 9.22e18）
 *   ⇒ 4.61e18 &lt; 9.22e18，还剩约一半余量 ⇒ 不需要任何夹取，{@code long} 直接乘就行。
 * </pre>
 *
 * <h2>⚠️ 这里刻意<b>不</b>做的两件事</h2>
 * <ol>
 *   <li><b>不夹 EUt</b>：老代码把 {@code v × a} 夹进 {@code int}（{@code min(Integer.MAX_VALUE, …)}），
 *       那是一条静默的业务上限（MAX 档只要 &gt;1A 就被削平，读数看起来"没变"）。
 *       GT 侧 {@code RecipeHelper.getInputEUt(GTRecipe)} 的返回类型是 {@code long}，
 *       {@code Content.content} 是 {@code Object}（EU 那一条装的是 {@code Long}）⇒ 存得下。</li>
 *   <li><b>不猜档位</b>：{@link #tierOf} 用"档位电压 ≤ EUt 的最大一档"（与卡片的
 *       {@code amperageOf} 同一个口径），取不到就是 {@code -1}。要不要把它兜到 ULV 是
 *       调用方（工作区）的决定，不藏在这里。</li>
 * </ol>
 */
public final class ShanhaiRecipeEnergy {

    private ShanhaiRecipeEnergy() {}

    /**
     * 电流下限：1A（0A 的配方在 GT 里没有意义 —— 老口径就是"隐含 1A"）。
     */
    public static final int AMPERAGE_MIN = 1;

    /**
     * 电流上限 = {@code int} 上限 2147483647（<b>不是业务限制</b>，只因界面/字段是 {@code int}）。
     * <p>× MAX 档电压 2147483647 = 4.61e18 &lt; {@code Long.MAX} 9.22e18，仍有约一半余量，故取 int 上限。
     */
    public static final int AMPERAGE_MAX = Integer.MAX_VALUE;

    /**
     * 耗时下限 / 上限。
     * <p>🔴 <b>GT 侧就是 {@code int}</b>：{@code com.gregtechceu.gtceu.api.recipe.GTRecipe#duration}
     * 的字段类型是 {@code int}（{@code javap -p} 实测，2026-11）。所以这里能写到的最大值是
     * {@code Integer.MAX_VALUE} = 2147483647 tick（≈ 1242 天），<b>再大就会在 GT 侧溢出成负数</b>
     * ⇒ 不做"为了满足 long 而写一个会溢出的值"那种事。
     */
    public static final int DURATION_MIN = 1;
    public static final int DURATION_MAX = Integer.MAX_VALUE;

    /** 电流夹取（只有 int 边界这一个限制）。 */
    public static int clampAmperage(int amps) {
        return Math.max(AMPERAGE_MIN, Math.min(AMPERAGE_MAX, amps));
    }

    /** 耗时夹取（GT 侧是 int ⇒ 上限就是 int 上限，见 {@link #DURATION_MAX}）。 */
    public static int clampDuration(int duration) {
        return Math.max(DURATION_MIN, Math.min(DURATION_MAX, duration));
    }

    /**
     * <b>耗能 = 该档电压 × 电流</b>（公式的唯一实现）。
     *
     * <p>不夹取、不溢出：最坏情况 2147483647L × 2147483647 = 4.61e18 &lt; {@code Long.MAX}（见类注释）。
     *
     * @param tierVoltage 该档电压（{@code <= 0} 视为 0，例如档位未定）
     * @param amps        电流（内部按 {@link #AMPERAGE_MIN} 夹一次，0/负数不会把耗能算成负的）
     */
    public static long eutOf(long tierVoltage, int amps) {
        final long v = Math.max(0L, tierVoltage);
        final long a = clampAmperage(amps);
        return v * a;
    }

    /**
     * 由 EUt 反查档位下标（找不到 = {@code -1}）。
     * <p>口径：<b>档位电压 ≤ EUt 的最大一档</b>（与 {@code ShanhaiRecipeCardWidget#ampsText} 的
     * {@code 电流 = 耗能 ÷ 该档电压} 互相自洽）。EUt &lt; 8 时返回 {@code -1}（GT 最低一档是 ULV=8）。
     */
    public static int tierOf(long[] tierVoltages, long eut) {
        if (tierVoltages == null) {
            return -1;
        }
        final long v = Math.abs(eut);
        int best = -1;
        for (int i = 0; i < tierVoltages.length; i++) {
            if (tierVoltages[i] <= v) {
                best = i;
            }
        }
        return best;
    }

    /**
     * 由 EUt 与档位反推电流（A）：{@code 电流 = 四舍五入(EUt / 该档电压)}。
     *
     * <p>🔴 这是<b>只读的反推</b>（面板/卡片显示用）。它<b>不是</b>编辑入口：用户在电流框里写的数
     * 会被 {@link ShanhaiRecipeEditorWorkspace#setAmperage(int)} 原样接住，再由
     * {@link #eutOf} 正着算出 EUt —— 反推只在"刚载入一条配方"那一拍用。
     *
     * <p>{@code Math.round(double)} 之后才转 {@code int}，且先夹到
     * {@code [AMPERAGE_MIN, AMPERAGE_MAX]} ⇒ {@code (int)} 这一步不可能溢出。
     */
    public static int amperageOf(long[] tierVoltages, long eut, int tier) {
        if (tierVoltages == null || tier < 0 || tier >= tierVoltages.length) {
            return AMPERAGE_MIN;
        }
        final long v = tierVoltages[tier];
        if (v <= 0L) {
            return AMPERAGE_MIN;
        }
        final long a = Math.round((double) Math.max(0L, eut) / (double) v);
        return (int) Math.max((long) AMPERAGE_MIN, Math.min((long) AMPERAGE_MAX, a));
    }

    // ------------------------------------------------------------------ save() 的两条判据

    /**
     * <b>保存这一拍该不该写 EUt</b> —— 判据是"用户有没有动过电流 / 电压档 / 发电-耗电那一侧"，
     * <b>不是</b> {@code pendingEut != 活配方的 EUt}。
     *
     * <h4>🔴 为什么必须是"动过"而不是"数值不同"（这就是用户报的那个 bug 的一半）</h4>
     * 老代码是 {@code eutDirty = pendingEut != euOf(live)}（值比较）。两个方向都会出事：
     * <ul>
     *   <li><b>该写的没写</b>：配方本来没有 EU（{@code euOf(live)==0}）或 EUt &lt; 8（档位反查成 -1）
     *       ⇒ {@code recomputeEut()} 把 {@code pendingEut} 算成 <b>0</b> ⇒ 与活配方那个 0 相等
     *       ⇒ {@code eutDirty=false} ⇒ <b>改电流一个字都没写</b>（用户看到的"耗能功率没有变"）；</li>
     *   <li><b>不该写的写了</b>：载入时 {@code amperage} 是四舍五入反推的（EUt=30、ULV ⇒ 4A），
     *       用户什么都没动就点保存，{@code 8×4=32 != 30} ⇒ 静默把 EUt 改了。</li>
     * </ul>
     */
    public static boolean shouldWriteEut(boolean amperageTouched, boolean tierTouched, boolean sideTouched) {
        return amperageTouched || tierTouched || sideTouched;
    }

    /**
     * <b>保存这一拍该不该写耗时</b> —— 判据同样是"用户有没有动过耗时框"。
     *
     * <h4>🔴 老判据为什么会把时效改长</h4>
     * 老代码：{@code durDirty = toLive(类型, pendingDuration) != live.duration}。
     * 左边是<b>源声明里的原始值</b>（{@code ShanhaiRecipeDuration#originalOf}），右边是
     * <b>gtlcore 乘过 durationMultiplier 之后</b>的实际值。只要某个类型被判成 UNFITTED
     * （样本不足 / 自相矛盾 ⇒ 不换算），这个比较就<b>恒为真</b> ⇒ 任何一次保存（包括只改电流）
     * 都会把原始值写进活配方：用户实例 {@code k=0.001} 下就是 <b>1 tick → 200 tick</b>。
     * 用户看到的就是"编辑电流之后时长增加了"。
     */
    public static boolean shouldWriteDuration(boolean durationTouched) {
        return durationTouched;
    }

    /** 判据行用的一句话（不含任何运算，只把上面那条证明写出来）。 */
    public static String overflowProof() {
        final long volt = 2147483647L;
        final long amps = (long) AMPERAGE_MAX;
        final long product = volt * amps;
        return "MAX电压 " + volt + " × 电流上限 " + amps + " = " + product
                + " < Long.MAX " + Long.MAX_VALUE
                + " (余量 " + (Long.MAX_VALUE - product) + ")"
                + " ⇒ 不溢出，无需夹取";
    }
}
