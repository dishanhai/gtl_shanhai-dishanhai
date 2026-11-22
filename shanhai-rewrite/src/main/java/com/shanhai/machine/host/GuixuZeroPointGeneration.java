package com.shanhai.machine.host;

import com.lowdragmc.lowdraglib.syncdata.field.ManagedKey;
import com.shanhai.machine.module.PrimordialGeneratorProduction;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 归墟视界枢核 · <b>「零点能发生器」那套产电逻辑</b>（只认 {@code gtceu:vision_energy_extraction}）。
 *
 * <h2>用户原话（本次需求，逐字）</h2>
 * <blockquote>「那我们当归墟视界枢核运行视界能流抽取的配方时执行零点能发生器的逻辑」</blockquote>
 * ⇒ <b>不动 {@code isGenerator} 标志</b>、<b>不动该配方类型的 EUIO</b>，只在归墟自己这一侧：
 * 跑到该类型<b>且配方输出电 &gt; 0</b> 的配方时，把那度电<b>从配方上拿走</b>（{@code copy()} 后
 * {@code setOutputEUt(0)}）并<b>按配方定义算出的产量送进无线池</b>。
 *
 * <h2>为什么这样写而不是"给机器加 isGenerator"</h2>
 * {@code getDefinition().isGenerator()} 是<b>机器定义</b>上的一个静态标志：归墟同时跑几十种配方，
 * 其中绝大多数是<b>耗电</b>型。把它标成发电机 ⇒ 整个多配方引擎的电学语义会跟着变（不只是这一条配方）。
 * 而「只看这一条配方是不是发电型」在运行期就能判（配方自带 {@code tickOutputs.eu &gt; 0}）⇒ 判据比标志更准，
 * 而且<b>只影响这一条</b>。
 *
 * <h2>🔴 本类只做纯计算（零游戏对象、零日志、零静态状态）</h2>
 * 类型判断 / 并行口径 / BigInteger 产量 / 判据自检全在这里，<b>不做任何副作用</b>：
 * <ul>
 *   <li>机型侧（{@link GuixuVisionCoreMachine}）只负责「读配方 → 调本类 → 入池」；</li>
 *   <li>配方逻辑侧（{@link GuixuVisionCoreRecipeLogic}）只负责「调本类判一下 → copy + 清零」；</li>
 *   <li>⇒ 这样 {@link #selfTest()} / {@link #main} 能<b>在没有游戏的情况下</b>把判据跑一遍
 *       （本项目纪律：检查器先证明自己对，再相信它的绿）。</li>
 * </ul>
 *
 * <h2>产量口径（2026-11-14 用户两次改判的最终版）</h2>
 * <pre>
 *   产量 = baseEut × 实际并行 p ÷ 主机耗能系数 f    ← 🔴 乘用 BigInteger，除用 HALF_UP 取整
 *   系数 f = {@link #coefficient(int)} = 零点能模块同一个 generationDivisor(门控份数)
 *            （域外 ⇒ 1.0；域内低于 0.05 ⇒ 抬到 0.05；量化 4 位小数）
 *   p     = ParallelData#getParallels[i]（引擎路径）/ realParallels ÷ batchSize（原生链口径）
 *   {@link #FORCE_SINGLE_PARALLEL} = false ⇒ 吃并行；改 true ⇒ 强制 p=1（"1A 恒定"只需翻这一个开关）
 * </pre>
 * <b>为什么必须 BigInteger</b>：本条配方自带 {@code 2^47}（= 140,737,488,355,328 EU/t，见
 * {@code kubejs/server_scripts/[server_scripts]shanhai_blackhole_recipes.js} 的 {@code .EUt(-140737488355328)}），
 * 乘 p=64 就是 {@code 2^53}；p 再大就撞 {@code Long.MAX_VALUE = 9.22e18}（{@code 2^63}）——
 * 而 <b>double→long 的窄化在 JLS 里是静默饱和</b>（不抛异常），所以"用 long 乘"在现场只会得到一个
 * 假的大数（{@link #selfTest()} 的负对照里把这条墙变成了断言）。
 *
 * <h2>记号</h2>
 * 沿用 {@code [SHANHAI-GEN-LIMIT]}（与发电模块同一个前缀，队长口径："用户只需 grep 一个记号就能同时看到
 * 墙和入池"）。本类不打印任何日志，前缀给机型侧用。
 */
public final class GuixuZeroPointGeneration {

    /** 日志前缀（与发电模块同一个记号族；见 {@code PrimordialGeneratorProduction.TAG}）。 */
    public static final String TAG = "[SHANHAI-GEN-LIMIT]";

    /** 唯一被接管的配方类型（{@code ShanhaiRecipeTypes.VISION_ENERGY_EXTRACTION} 的注册名）。 */
    public static final String TYPE_ID = "gtceu:vision_energy_extraction";

    /**
     * 🔴 <b>并行开关</b>（用户 2026-11-14：「为什么不能吃并行」⇒ 改成能吃）。
     *
     * <p>{@code false} = <b>产量 = baseEut × 实际并行 p</b>（现行）；
     * {@code true} = <b>强制 p = 1</b>（等于旧口径「1A 恒定」）。
     * <p>⇒ 将来要回到"1A 恒定"，<b>只改这一行</b>；改完 {@link #selfTest()} 会自动跟着换成
     * {@link #EFFECTIVE_PARALLEL_WHEN_FORCED} 的期望值（判据不跟着变，改完就会红 —— 这是故意的）。
     */
    private static final boolean FORCE_SINGLE_PARALLEL = false;

    /** {@link #FORCE_SINGLE_PARALLEL} 为 true 时 p 的取值。 */
    public static final long EFFECTIVE_PARALLEL_WHEN_FORCED = 1L;

    /**
     * <b>产量系数 = 主机耗能系数 f</b>（2026-11-14 用户定案：<b>吃它</b>）。
     *
     * <h2>🔴 一处实现都不新写 —— 直接调零点能发生器那一份</h2>
     * <pre>
     *   {@code PrimordialGeneratorProduction.generationDivisor(int gateBonus)}
     *     = divisorOf(reductionFactor(gateBonus))
     *   {@code PrimordialRecipeEffects.reductionFactor(level)}:  f = 1 − 0.95^(17/level)，level ≤ 0 ⇒ 1.0
     *   {@code divisorOf(raw)} 的域规则（**同一个函数，不复制**）：
     *       域外（≤0 / &gt;1 / NaN / ∞）⇒ 1.0（不放大；坏输入的安全侧）
     *       域内但 &lt; 0.05       ⇒ 抬到 0.05（用户原话「系数最小是 0.05」⇒ 最多 ×20）
     *       最后量化到 4 位小数（HALF_UP）：0.050000000000000044 ⇒ 0.0500
     * </pre>
     * 为什么必须共用同一个函数而不是抄一份：本工程既有裁决 —— 「与模块同一个纯函数」，
     * 抄一份的下场就是两边某天悄悄分叉（这里差 1e-16 就会让 ×20 变成 ×19）。
     *
     * <p>⚠️ 已知档位（与零点能模块逐位一致，见 {@link #selfTest()}）：
     * 门控 0 ⇒ 1.0 ／ 1 ⇒ 0.5819 ／ 5 ⇒ 0.1600 ／ 17 ⇒ 0.0500。
     */
    @NotNull
    public static BigDecimal coefficient(int gateBonus) {
        return PrimordialGeneratorProduction.generationDivisor(gateBonus);
    }

    /** 本条配方自带的发电量（{@code 2^47}）= 用户口中的「MAX+8 1A」。 <b>只用于自检的已知样本</b>。 */
    public static final long RECIPE_BASE_EUT = 140_737_488_355_328L;

    /** 非目标类型的两个真实样本（用于「不碰已验收行为」的正向对照）。 */
    public static final String OTHER_TYPE_MATTER_RECOMBINATION = "gtceu:primordial_matter_recombination";
    /** 非目标类型的第二个真实样本（解压）。 */
    public static final String OTHER_TYPE_MATTER_DECOMPRESSION = "gtceu:vision_matter_decompression";

    private GuixuZeroPointGeneration() {}

    // ═════════════════════════════ ① 类型判断（只认一个类型） ═════════════════════════════

    /** 这个配方类型 id 是不是本次要接管的那一个（{@code null} / 别的类型一律 false）。 */
    public static boolean isTargetType(@Nullable String typeId) {
        return typeId != null && TYPE_ID.equals(typeId);
    }

    /**
     * <b>这条配方要不要被"清零 + 入池"</b> —— 唯一的判定入口（两个条件都要满足）。
     *
     * <ol>
     *   <li>配方类型 == {@link #TYPE_ID}；</li>
     *   <li>该配方<b>输出电 &gt; 0</b>（{@code RecipeHelper.getOutputEUt(原配方)}）。</li>
     * </ol>
     * 🔴 第二个条件不是多余的：同类型下也可能出现"不发电"的条目（EUt 写成 0 或反过来耗电），
     * 那时<b>一个字节都不该动</b>（否则就是把耗电配方也当发电机）。
     *
     * @param typeGateEnabled <b>只给自检用</b>：{@code false} 模拟"把类型判断去掉"这条负对照
     *                        （生产代码<b>永远</b>传 {@code true}，见两个调用点的字面量）。
     */
    public static boolean shouldConvert(@Nullable String typeId, long outputEut, boolean typeGateEnabled) {
        if (outputEut <= 0L) {
            return false;                       // 不发电 ⇒ 与改动前逐位相同（这条任何开关都不受影响）
        }
        return typeGateEnabled ? isTargetType(typeId) : true;   // 负对照：不看类型 ⇒ 所有类型都会被清零
    }

    /** 清零后的读数（{@code convert == true} ⇒ 0；否则<b>原值</b>，逐位不变）。 */
    public static long zeroedOutputEut(long originalOutputEut, boolean convert) {
        return convert ? 0L : originalOutputEut;
    }

    // ═════════════════════════════ ② 并行与产量（BigInteger） ═════════════════════════════

    /**
     * 本 tick 的实际并行 p —— <b>照 {@code PrimordialGeneratorProduction} 的口径</b>：
     * {@code IGTRecipe.getRealParallels() ÷ getBatchSize()}。
     *
     * <p>为什么必须除 {@code batchSize}：{@code getRealParallels()} 是<b>整批总份数</b>
     * （gtlcore {@code BatchProcessing.scaleRecipe} 把 {@code realParallels × batchSize} 写回去），
     * 不除就会在批处理下每 tick 多投 {@code batchSize} 倍的电。
     * <p>为什么 {@code realParallels <= 0} 取 1：读不到并行（非引擎路径 / 刚装配）时按 1 份算 ——
     * 与"配方至少跑了 1 份"一致，且不会除出 0 产量。
     * <p>{@code FORCE_SINGLE_PARALLEL} 为 true 时直接返回 {@link #EFFECTIVE_PARALLEL_WHEN_FORCED}。
     */
    public static long effectiveParallel(long realParallels, long batchSize) {
        return effectiveParallel(realParallels, batchSize, FORCE_SINGLE_PARALLEL);
    }

    /** {@link #effectiveParallel(long, long)} 的显式开关版（自检要把两个分支都跑一遍）。 */
    public static long effectiveParallel(long realParallels, long batchSize, boolean forceSingleParallel) {
        if (forceSingleParallel) {
            return EFFECTIVE_PARALLEL_WHEN_FORCED;
        }
        final long batch = Math.max(1L, batchSize);
        if (realParallels <= 0L) {
            return 1L;
        }
        return Math.max(1L, realParallels / batch);
    }

    /**
     * 本 tick 要送进无线池的量 = <b>baseEut × p ÷ 系数</b>（🔴 乘法走 BigInteger，不会溢出/饱和）。
     *
     * @param baseEut   <b>配方定义实例</b>上的输出 EU（{@code RecipeHelper.getOutputEUt(原配方)}）
     * @param parallel  {@link #effectiveParallel} 给出的实际并行
     * @param gateBonus 主机门控份数（= 零点能模块读的同一个 {@code moduleSlotBonus()}）
     * @return 任一因子非法（&le;0）⇒ {@link BigInteger#ZERO}（= 不产、也不误产）
     */
    public static BigInteger product(long baseEut, long parallel, int gateBonus) {
        return productForTest(baseEut, parallel, gateBonus, false);
    }

    /**
     * {@link #product(long, long, int)} 的<b>可注入版</b>（只给自检的负对照用：把系数强制成 1.0）。
     *
     * @param forceCoefficientOne {@code true} = 假装"系数 = 1.0"（这一支就是负对照要打红的那条口径）
     */
    static BigInteger productForTest(long baseEut, long parallel, int gateBonus, boolean forceCoefficientOne) {
        if (baseEut <= 0L || parallel <= 0L) {
            return BigInteger.ZERO;
        }
        BigInteger value = BigInteger.valueOf(baseEut).multiply(BigInteger.valueOf(parallel));
        final BigDecimal divisor = forceCoefficientOne ? BigDecimal.ONE : coefficient(gateBonus);
        if (divisor.compareTo(BigDecimal.ONE) != 0) {
            // HALF_UP：除不尽只是系数精度造成的亚整数级差，四舍五入才是"÷ 这个系数"的本义
            value = new BigDecimal(value).divide(divisor, 0, RoundingMode.HALF_UP).toBigIntegerExact();
        }
        return value;
    }

    /** 一次性拿到"产量 + 三个读数"（机型侧只调这一个，避免两处各算一遍）。 */
    @NotNull
    public static Readings readings(long baseEut, long realParallels, long batchSize, int gateBonus) {
        return readings(baseEut, realParallels, batchSize, gateBonus, SOURCE_LAST_RECIPE);
    }

    /** 同上，但标明并行是从哪个读数来的（日志里必须能看出"这个 p 是哪来的"）。 */
    @NotNull
    public static Readings readings(long baseEut, long realParallels, long batchSize, int gateBonus,
                                    @NotNull String source) {
        final long parallel = effectiveParallel(realParallels, batchSize);
        return new Readings(baseEut, realParallels, batchSize, parallel,
                product(baseEut, parallel, gateBonus), coefficient(gateBonus), gateBonus, source);
    }

    /**
     * 多配方引擎的<b>首选</b>口径：{@code ParallelData#getParallels()[i]} 就是"引擎这一轮认为第 i 条配方要跑几份"。
     *
     * <p>用它而不是 {@code IGTRecipe#getRealParallels()} 的原因（本工程实证，不是偏好）：
     * {@code realParallels} 记的是<b>合并后那一整张配方</b>的总份数（跨配方种类并行时会跨好几种类型），
     * 而池子是按"这一条发电配方"入账的 ⇒ 用总值会多产电。
     */
    @NotNull
    public static Readings readingsFromParallelData(long baseEut, long parallel, int gateBonus) {
        return readingsFromParallelData(baseEut, parallel, gateBonus, 0);
    }

    /**
     * 同上，并带上<b>发电配方定义实例自己的时长</b>（{@code ParallelData#getOriginRecipeList()[i].duration}）。
     *
     * <p>🔴 这个时长是<b>未经面板地板、也未经批次拉长</b>的原值 —— 摊平口径的分子用它：
     * {@code 本批计划总量 = 乘积 × 自身时长}（见 {@link #amortize}）。
     *
     * @param ownDuration 配方定义实例的 {@code duration}；{@code <=0} = 拿不到 ⇒ 摊平退回旧口径
     */
    @NotNull
    public static Readings readingsFromParallelData(long baseEut, long parallel, int gateBonus, int ownDuration) {
        final long p = Math.max(1L, parallel);
        return new Readings(baseEut, p, 1L, p, product(baseEut, p, gateBonus), coefficient(gateBonus),
                gateBonus, SOURCE_PARALLEL_DATA, ownDuration);
    }

    /** 并行读数来源①：多配方引擎的 {@code ParallelData#getParallels()}。 */
    public static final String SOURCE_PARALLEL_DATA = "ParallelData#getParallels";
    /** 并行读数来源②：工作配方上的 {@code IGTRecipe#getRealParallels() ÷ getBatchSize()}（原生链口径）。 */
    public static final String SOURCE_LAST_RECIPE = "IGTRecipe#getRealParallels÷batch";

    /**
     * 一次发电读数的<b>不可变快照</b>（日志行与自检共用同一份字段名，防止"日志里的数"和"算的数"分叉）。
     */
    public static final class Readings {

        /** 配方定义实例自带的每 tick 发电（{@code 2^47}）。 */
        public final long baseEut;
        /** 引擎给的原始 {@code realParallels}（整批总份数）。 */
        public final long realParallels;
        /** 对应配方的批大小（非批处理 = 1）。 */
        public final long batchSize;
        /** 实际用的并行 p（= realParallels ÷ batchSize，或强制 1）。 */
        public final long parallel;
        /** 产量（BigInteger 全文，绝不截断）。 */
        public final BigInteger product;
        /** 耗能系数 f（= 零点能模块同一个 {@code generationDivisor}，已量化 4 位小数）。 */
        public final BigDecimal coefficient;
        /** 这个系数对应的主机门控份数（{@code moduleSlotBonus()}；0 = 未生效 ⇒ 1.0）。 */
        public final int gateBonus;
        /** 并行是从哪个读数来的（{@link #SOURCE_PARALLEL_DATA} / {@link #SOURCE_LAST_RECIPE}）。 */
        public final String source;
        /** 发电配方<b>定义实例</b>的时长（未经面板地板、未经批次拉长）；{@code <=0} = 拿不到。 */
        public final int ownDuration;

        Readings(long baseEut, long realParallels, long batchSize, long parallel, BigInteger product,
                 BigDecimal coefficient, int gateBonus, @NotNull String source) {
            this(baseEut, realParallels, batchSize, parallel, product, coefficient, gateBonus, source, 0);
        }

        Readings(long baseEut, long realParallels, long batchSize, long parallel, BigInteger product,
                 BigDecimal coefficient, int gateBonus, @NotNull String source, int ownDuration) {
            this.baseEut = baseEut;
            this.realParallels = realParallels;
            this.batchSize = batchSize;
            this.parallel = parallel;
            this.product = product;
            this.coefficient = coefficient;
            this.gateBonus = gateBonus;
            this.source = source;
            this.ownDuration = ownDuration;
        }

        /** 系数按<b>4 位小数</b>的显示形式（0.05 ⇒ {@code 0.0500}；1.0 ⇒ {@code 1.0000}）。 */
        @NotNull
        public String coefficientText() {
            return coefficient.setScale(4, RoundingMode.HALF_UP).toPlainString();
        }

        /**
         * 日志行正文（**四个读数全在**：baseEut / parallel / product（全文）/ 系数（4 位小数））。
         * <p>{@code extra} 由调用方拼（池子前后读数 / 位置等）。
         */
        public String line(@Nullable String recipeId, @Nullable String extra) {
            return TAG + " 归墟零点能发电"
                    + " 配方=" + (recipeId == null ? "?" : recipeId)
                    + " 基础EUt=" + baseEut
                    + " 并行=" + parallel + "（realParallels=" + realParallels + " ÷ batch=" + batchSize + "）"
                    + " 乘积=" + product
                    + " 系数=" + coefficientText() + "（门控=" + gateBonus + "，÷系数 = ×"
                    + String.format(java.util.Locale.ROOT, "%.2f",
                    BigDecimal.ONE.divide(coefficient, 10, RoundingMode.HALF_UP).doubleValue()) + "）"
                    + " 并行来源=" + source
                    + (extra == null ? "" : " " + extra);
        }
    }

    // ═════════════════════════════ ②'' 分料：**耗电先分料、发电按耗电份数和封顶** ═════════════════════════════
    //
    //  <h2>🔴 2026-11-21 第一版（发电先分料）被【实机证伪】，原文留档</h2>
    //  第一版假设"发电配方每份要吃 32 mB 流体 ⇒ 份数上限 ≤ Long.MAX/32 ⇒ 它吃不干预算"。
    //  <b>这个前提在现场不成立</b>：预算是机器给的（现场 1024），而发电配方的份数上限远大于预算
    //  ⇒ 份数 = 预算本身 ⇒ 预算归零 ⇒ 循环 break ⇒ <b>排在它后面的耗电配方一条都没被问过</b>。
    //  用户现场日志（原文）：
    //  <pre>
    //    归墟分料顺序 … 新序=[…blackhole_engine_power, …decompression/new_recipe_1]
    //    混合轮读数 入批=true 批次=[…blackhole_engine_power×1024] 并行=1024
    //    ⇒ 用户：「现在归墟只会跑发电配方了，正常配方不跑了」
    //  </pre>
    //  ⇒ 结论：**只改顺序解决不了饥饿 —— 谁排第一谁吃干预算。** 顺序换向只是把饥饿换个方向。
    //
    //  <h2>✅ 第二版（现行）：顺序回到"耗电在前"，再给发电一条**按候选**的份数上限</h2>
    //  <pre>
    //    耗电份数和 ΣA = 本轮已被分掉的预算（= 预算 − 轮到发电时的 remain）
    //    发电份数上限 = ΣA &lt;= 0 ? 不设限 : max(1, ΣA)      ← "你抽出来多少能量，我才发多少电"
    //    ⇒ 发电永远拿不到"超过耗电份数和"的份数，且至少 1 份 ⇒ 两头都不会饿死；
    //    ⇒ ΣA = 0（纯发电轮）⇒ 不设限 ⇒ 纯发电轮行为照旧（不变量 1）。
    //  </pre>
    //  上限的落点是<b>按候选</b>的份数出口（机型侧覆写 {@code calculateParallel} 的返回值），
    //  不是机器级 {@code getMaxParallel}（那是全局的、会连坐纯耗电轮）。
    //
    //  <h2>③ 为什么要"耗电在前"（这条顺序不是为了抢料，是为了让上限可算）</h2>
    //  ΣA 只能在"轮到发电时"从 {@code remain} 读出来 ⇒ 发电必须排在耗电之后。
    //  现场自然顺序本来就是这条（{@code 原序=[…decompression, …energy_power]}），
    //  所以这一步只是**把候选序保持成上游原样**（稳定分区：非发电型按原序在前，发电型按原序在后）。
    //  纯发电轮 / 纯耗电轮 ⇒ 分区是恒等序 ⇒ 一个字节都不改。

    /**
     * <b>这条候选是不是"发电型视界能流抽取"</b> —— {@code 0} = 是（分料时排到最后并受上限约束），
     * {@code 1} = 其它（按原序先分料）。
     *
     * <p>判据与 {@link #shouldConvert} <b>同一份</b>（类型 + 输出电 &gt; 0）：
     * "要接管的"、"要加份数上限的"、"要排到最后的"必须是同一批配方，三处各写一份迟早分叉。
     */
    public static int allocationRank(@Nullable String typeId, long outputEut) {
        return shouldConvert(typeId, outputEut, true) ? 0 : 1;
    }

    /**
     * <b>发电配方的份数上限</b>（用户 2026-11-22 第二次拍板：**保预算**，不再无条件给到 ΣA）。
     *
     * <pre>
     *   ΣA &lt;= 0（纯发电轮 / 本轮没有耗电配方被分到料）⇒ Long.MAX_VALUE = 不设限（照旧，一个字不改）
     *   ΣA &gt;  0 ⇒ clamp( min(ΣA, max(0, 预算 − ΣA)), 1, ΣA )
     *              ├─ 预算够（预算 ≥ 2×ΣA）⇒ ΣA      （两边都不挤，按"抽多少发多少"给满）
     *              ├─ 预算被吃干（预算 − ΣA = 0）⇒ 1 （最多只超预算 1 份，发电始终活着）
     *              └─ 半个预算（0 &lt; 预算−ΣA &lt; ΣA）⇒ 预算−ΣA
     *   预算未知（&lt;=0，读不到）⇒ 不当成压力 ⇒ 也给 ΣA
     * </pre>
     *
     * <p>为什么从"无条件 ΣA"改成这个（用户原话：「保预算，不保发电=耗电份数和」）：
     * 预算是<b>电</b>，超 2 倍会让能源仓吃紧甚至整轮不动；只超 1 份可忽略，且发电始终"活着"。
     *
     * <p>🔴 纯发电轮必须**不设限**：若这里返回 0，纯发电轮会退化成"一份都不跑"（判据 ⑧ 负对照 C）。
     *
     * @param consumerParallelSum 同批耗电份数之和 ΣA
     * @param budget              本轮并行预算（相 1 真实用掉的那个；{@code <=0} = 读不到 ⇒ 不钳）
     */
    public static long generationParallelCap(long consumerParallelSum, long budget) {
        if (consumerParallelSum <= 0L) {
            return Long.MAX_VALUE;                       // 纯发电轮：不设限
        }
        final long room = budget <= 0L ? Long.MAX_VALUE : Math.max(0L, budget - consumerParallelSum);
        final long byRule = Math.min(consumerParallelSum, room);       // 能放下就 ΣA
        final long floored = Math.max(1L, byRule);                     // 至少 1 份（发电始终活着）
        return Math.min(consumerParallelSum, floored);                 // 且不许超过 ΣA
    }

    /**
     * <b>稳定分区（耗电在前、发电在后）</b>：返回"新顺序里第 i 位应该取原序列的哪一个下标"。
     *
     * <p>为什么不是"发电在前"：见本节开头那段落档 —— 顺序只决定谁先吃预算，方向反过来就是镜像饥饿；
     * 这里把发电放最后，是为了让 {@code 预算 − 轮到它时的 remain} 能直接读出"耗电已分掉的份数和"。
     *
     * @param ranks 每条候选的 {@link #allocationRank}（0 = 发电型）
     */
    @NotNull
    public static int[] allocationOrderConsumerFirst(@NotNull int[] ranks) {
        return allocationOrderConsumerFirstForTest(ranks, true);
    }

    /**
     * {@link #allocationOrderConsumerFirst(int[])} 的<b>可注入版</b>（只给自检的负对照用）。
     *
     * @param consumerFirst {@code false} = <b>上一版</b>（发电在前）—— 那一支就是判据 ⑧ 负对照 A 要打红的形态
     */
    static int[] allocationOrderConsumerFirstForTest(@NotNull int[] ranks, boolean consumerFirst) {
        if (ranks == null) {
            throw new IllegalArgumentException("ranks 不能为 null");
        }
        final int n = ranks.length;
        final int[] order = new int[n];
        int at = 0;
        if (consumerFirst) {
            for (int i = 0; i < n; i++) {
                if (ranks[i] != 0) {
                    order[at++] = i;
                }
            }
            for (int i = 0; i < n; i++) {
                if (ranks[i] == 0) {
                    order[at++] = i;
                }
            }
        } else {
            // 上一版：发电在前（= 已被实机证伪的形态；保留只为负对照能复现那个坏现象）
            for (int i = 0; i < n; i++) {
                if (ranks[i] == 0) {
                    order[at++] = i;
                }
            }
            for (int i = 0; i < n; i++) {
                if (ranks[i] != 0) {
                    order[at++] = i;
                }
            }
        }
        return order;
    }

    /** 这个顺序是不是"原样"（= 排序没改任何东西 ⇒ 调用方必须返回原集合本身，不做无谓的重建）。 */
    public static boolean isIdentityOrder(@NotNull int[] order) {
        for (int i = 0; i < order.length; i++) {
            if (order[i] != i) {
                return false;
            }
        }
        return true;
    }

    // ── ⑧ 分料结算的口径（自检据此复现每一个坏现象） ──

    /** 生产口径：耗电先分料（贪心），发电再按 {@link #generationParallelCap} 封顶（含预算钳制）。 */
    public static final int ALLOC_TWO_PASS = 0;
    /** 负对照 A：<b>上一版</b>（发电先分料、不设限）⇒ 复现「只跑发电」。 */
    public static final int ALLOC_GEN_FIRST = 1;
    /** 负对照 B：<b>改动前</b>（耗电先分料、不设限）⇒ 复现「发电不跑」。 */
    public static final int ALLOC_CONSUMER_FIRST_NO_CAP = 2;
    /** 负对照 C：发电上限 = {@code ΣA}（**不论预算**，= 用户拍板前的旧写法）⇒ 复现「总份数超预算 2×」。 */
    public static final int ALLOC_TWO_PASS_OLD_CAP = 3;
    /** 负对照 D：上限里去掉「至少 1 份」地板（{@code cap = max(0, min(ΣA, 剩余预算))}）⇒ 复现「预算吃干时发电=0」。 */
    public static final int ALLOC_TWO_PASS_NO_FLOOR = 4;

    /**
     * <b>分料结算的纯模型</b>：返回 {@code {耗电份数和, 发电份数}}。
     *
     * <p>用什么模拟"上游贪心"：<b>直接调本工程现成的那份对照实现</b>
     * {@code ShanhaiFairAllocation#greedyShares}（逐句照抄上游循环骨架，含 {@code remain <= 0 就 break}，
     * 生产代码从不调它、只给离线判据用）⇒ 不自造第二份模拟器。
     *
     * @param budget      本轮并行预算（现场实测 1024）
     * @param consumerCap 耗电候选自己的份数上限（现场 = 无上限 ⇒ {@code Long.MAX_VALUE}）
     * @param genCap      发电候选自己的份数上限（现场 = 无上限 ⇒ {@code Long.MAX_VALUE}）
     * @param mode        {@code ALLOC_*} 之一
     */
    @NotNull
    public static long[] simulatedSplit(long budget, long consumerCap, long genCap, int mode) {
        if (budget <= 0L) {
            return new long[]{0L, 0L};
        }
        switch (mode) {
            case ALLOC_GEN_FIRST: {
                final long[] shares = com.shanhai.common.thread.ShanhaiFairAllocation
                        .greedyShares(new long[]{genCap, consumerCap}, budget);
                return new long[]{shares[1], shares[0]};
            }
            case ALLOC_CONSUMER_FIRST_NO_CAP: {
                final long[] shares = com.shanhai.common.thread.ShanhaiFairAllocation
                        .greedyShares(new long[]{consumerCap, genCap}, budget);
                return new long[]{shares[0], shares[1]};
            }
            case ALLOC_TWO_PASS_OLD_CAP:
            case ALLOC_TWO_PASS_NO_FLOOR:
            case ALLOC_TWO_PASS:
            default: {
                // 相 1：耗电先分料（同上游贪心）⇒ ΣA
                final long[] cons = com.shanhai.common.thread.ShanhaiFairAllocation
                        .greedyShares(new long[]{consumerCap}, budget);
                final long consumerSum = cons[0];
                // 相 2：发电按上限再分一次（上限 = 生产口径 2 参版 generationParallelCap）
                final long cap;
                switch (mode) {
                    case ALLOC_TWO_PASS_OLD_CAP:
                        cap = Math.max(1L, consumerSum);                       // 旧写法：不看预算
                        break;
                    case ALLOC_TWO_PASS_NO_FLOOR:
                        cap = Math.max(0L, Math.min(consumerSum,
                                budget <= 0L ? Long.MAX_VALUE : Math.max(0L, budget - consumerSum)));
                        break;
                    case ALLOC_TWO_PASS:
                    default:
                        cap = generationParallelCap(consumerSum, budget);       // 生产口径
                        break;
                }
                final long[] gen = com.shanhai.common.thread.ShanhaiFairAllocation
                        .greedyShares(new long[]{genCap}, cap);
                return new long[]{consumerSum, gen[0]};
            }
        }
    }

    /**
     * <b>判据 ⑧</b>：分料必须"两头都能跑"**且守住预算**。
     *
     * <p>同一份代码跑各口径 ⇒ 生产口径必须<b>空表</b>，每个负对照必须<b>非空</b>。三条场景：
     * <pre>
     *   A 预算充足（预算 4096、耗电无上限 ⇒ ΣA=1024）⇒ 发电 = 1024（= ΣA，能放下就给满）
     *   B 预算吃干（预算 1024、ΣA=1024）              ⇒ 发电 = 1，且 总份数 ≤ 预算+1
     *   C 半个预算（预算 1024、ΣA=600）               ⇒ 发电 = min(600, 424) = 424，总份数 = 预算
     * </pre>
     *
     * @param mode 口径（见 {@code ALLOC_*}）
     */
    @NotNull
    public static List<String> splitAllocationProblems(int mode) {
        final List<String> problems = new ArrayList<>();
        final long inf = Long.MAX_VALUE;           // 两边都「没有消耗型输入上限」（现场形状）
        final String tag = "mode=" + mode;

        // ── 场景 A：预算充足 ⇒ 发电 = ΣA ──
        final long[] ample = simulatedSplit(4096L, 1024L, inf, mode);
        check(problems, ample[0] == 1024L && ample[1] == 1024L,
                "⑧【场景A·预算充足】预算 4096、ΣA=1024 ⇒ 发电必须=1024，实际 "
                        + java.util.Arrays.toString(ample) + "（" + tag + "）");

        // ── 场景 B：预算被耗电吃干 ⇒ 发电 = 1 份，且总份数只许超预算 1 ──
        final long[] squeezed = simulatedSplit(1024L, inf, inf, mode);
        check(problems, squeezed[0] >= 1L,
                "⑧【场景B】耗电份数必须 ≥1（否则复现「只跑发电」），实际耗电=" + squeezed[0] + "（" + tag + "）");
        check(problems, squeezed[1] == 1L,
                "⑧【场景B·预算吃干】预算 1024、ΣA=1024 ⇒ 发电必须=1（否则超预算 2× / 或发电=0），实际 "
                        + java.util.Arrays.toString(squeezed) + "（" + tag + "）");
        check(problems, squeezed[0] + squeezed[1] <= 1024L + 1L,
                "⑧【场景B·总份数】必须 ≤ 预算+1（=1025），实际 " + (squeezed[0] + squeezed[1]) + "（" + tag + "）");
        check(problems, generationParallelCap(1024L, 1024L) <= 1L,
                "⑧【场景B·生产入口】cap(ΣA=1024, 预算=1024) 必须 ≤1，实际 "
                        + generationParallelCap(1024L, 1024L));

        // ── 场景 C：半个预算 ⇒ 发电 = 剩余预算（424） ──
        final long[] half = simulatedSplit(1024L, 600L, inf, mode);
        check(problems, half[0] == 600L && half[1] == 424L,
                "⑧【场景C·半个预算】预算 1024、ΣA=600 ⇒ 发电必须=424，实际 "
                        + java.util.Arrays.toString(half) + "（" + tag + "）");
        check(problems, half[0] + half[1] <= 1024L,
                "⑧【场景C·总份数】必须 ≤ 预算 1024，实际 " + (half[0] + half[1]) + "（" + tag + "）");

        // ── 场景 D：纯发电轮 ⇒ 不设限（照旧跑满） ──
        final long[] pure = simulatedSplit(1024L, 0L, 1024L, mode);
        check(problems, pure[0] == 0L && pure[1] == 1024L,
                "⑧【场景D·纯发电轮】必须照旧跑满（期望 0/1024，实际 " + java.util.Arrays.toString(pure)
                        + "；" + tag + "）");

        // ── 场景 E：发电需求小于上限 ⇒ 原样（不许被放大） ──
        final long[] small = simulatedSplit(4096L, 1024L, 7L, mode);
        check(problems, small[0] == 1024L && small[1] == 7L,
                "⑧【场景E】发电自身只要 7 份 ⇒ 必须原样 7（不许放大），实际 "
                        + java.util.Arrays.toString(small) + "（" + tag + "）");
        return problems;
    }

    /**
     * <b>判据 ⑧ 打在生产入口上</b>：{@link #generationParallelCap} 本体 + 生产口径的模拟。
     *
     * <p>🔴 上一轮踩过的坑：只打"注入口径"的自检对生产代码没有分辨力（把生产入口改回旧写法它照样全绿）。
     * 这里必须再打一遍，而且断言的是<b>函数本体</b>的返回值。
     */
    @NotNull
    public static List<String> productionSplitProblems() {
        final List<String> problems = new ArrayList<>();
        check(problems, generationParallelCap(0L, 1024L) == Long.MAX_VALUE,
                "⑧【生产入口】ΣA=0（纯发电轮）⇒ 上限必须 = Long.MAX_VALUE（不设限），实际 "
                        + generationParallelCap(0L, 1024L));
        check(problems, generationParallelCap(-5L, 1024L) == Long.MAX_VALUE,
                "⑧【生产入口】ΣA<0 ⇒ 上限必须 = Long.MAX_VALUE，实际 " + generationParallelCap(-5L, 1024L));
        // 三条现场档位（用户拍板时点名的三组数）
        check(problems, generationParallelCap(1024L, 4096L) == 1024L,
                "⑧【生产入口】预算充足(4096)、ΣA=1024 ⇒ 上限必须=1024，实际 "
                        + generationParallelCap(1024L, 4096L));
        check(problems, generationParallelCap(1024L, 1024L) == 1L,
                "⑧【生产入口】预算吃干(1024)、ΣA=1024 ⇒ 上限必须=1，实际 "
                        + generationParallelCap(1024L, 1024L));
        check(problems, generationParallelCap(600L, 1024L) == 424L,
                "⑧【生产入口】半个预算(1024)、ΣA=600 ⇒ 上限必须=424，实际 "
                        + generationParallelCap(600L, 1024L));
        // 边界
        check(problems, generationParallelCap(1L, 1024L) == 1L,
                "⑧【生产入口】ΣA=1 ⇒ 上限必须=1，实际 " + generationParallelCap(1L, 1024L));
        check(problems, generationParallelCap(7L, 0L) == 7L,
                "⑧【生产入口】预算读不到(0) ⇒ 不当成压力、给 ΣA=7，实际 " + generationParallelCap(7L, 0L));
        check(problems, generationParallelCap(7L, -3L) == 7L,
                "⑧【生产入口】预算<0 ⇒ 不当成压力、给 ΣA=7，实际 " + generationParallelCap(7L, -3L));
        final long[] split = simulatedSplit(1024L, Long.MAX_VALUE, Long.MAX_VALUE, ALLOC_TWO_PASS);
        check(problems, split[0] >= 1L && split[1] >= 1L,
                "⑧【生产入口】现场形状（预算 1024）必须两头都能跑，实际 " + java.util.Arrays.toString(split));
        check(problems, split[0] + split[1] <= 1024L + 1L,
                "⑧【生产入口】现场形状总份数必须 ≤ 预算+1，实际 " + (split[0] + split[1]));
        return problems;
    }


    // ═════════════════════════════ ②''' 结算口径：每批总量守恒的摊平 ═════════════════════════════
    //
    //  <h2>🔴 2026-11-22（用户选 ①「摊平，每批总量守恒」）</h2>
    //  上一版的口径是"每 tick 入池 = 乘积"，批次一长就整批多发：
    //  实测混合轮批次 3199 tick、发电配方自身 20 tick ⇒ 同一批燃料**多付 159.95 倍**。
    //  用户定案：<b>每批总量 = 乘积 × 自身时长</b>，把它<b>摊到本批时长</b>上逐 tick 发，
    //  余数在<b>最后一 tick</b> 补齐 ⇒ 总量严格守恒（不许多发也不许少发）。
    //
    //  <h2>三条硬不变量（离线自检 ⑦ 逐条断言，且有两条负对照）</h2>
    //  <ol>
    //    <li><b>本批时长 == 自身时长 ⇒ 逐 tick 与改动前逐位相同</b>
    //        （{@code perTick = 乘积×自身 ÷ 自身 = 乘积}，余数恒 0）⇒ 纯发电轮零变化；</li>
    //    <li>混合轮（本批 3199、自身 20）的<b>每批总量 == 纯发电轮每批总量</b>
    //        （都是 {@code 乘积×20}），只是摊到更多 tick ⇒ 不再是 159.95 倍；</li>
    //    <li>批次提前结束 ⇒ <b>余额不发</b>（只有"最后一 tick 补余数"这一条补发路径，
    //        超出 {@code 本批时长} 的 tick 一律返回 0）。</li>
    //  </ol>
    //
    //  <h2>⛔ 边界：本批时长 &lt;= 0 或 自身时长 &lt;= 0 ⇒ 退回改动前的每 tick 口径</h2>
    //  拿不到时长就不许假装摊平（那会把总量算错），宁可退回旧行为并在日志里 WARN 说明原因。

    /** 生产口径：摊平 + 最后一 tick 补余数。 */
    public static final int AMORTIZE_NORMAL = 0;
    /** 负对照口径：不摊平（= 改动前"每 tick 乘积"）⇒ 长批次必然多发 {@code 本批时长÷自身时长} 倍。 */
    public static final int AMORTIZE_LEGACY = 1;
    /** 负对照口径：摊平但**余数丢掉**（不补最后一 tick）⇒ 每批总量必然少 {@code 余数}。 */
    public static final int AMORTIZE_NO_REMAINDER = 2;

    /**
     * 一次"按批摊平"的<b>不可变计划</b>（机型侧只读它；所有数都能离线复算）。
     *
     * <h2>字段口径</h2>
     * <pre>
     *   product       = readings.product（= baseEut × p ÷ 系数），本批的"每 tick 上限"；
     *                   本批时长 == 自身时长时它就是每 tick 实发（= 不变量 1）
     *   ownDuration   = 发电配方**定义实例**的 duration（未经面板地板、未经批次拉长）
     *   batchDuration = 本批真正倒计时的 duration（{@code RecipeLogic#duration}，
     *                   = 引擎尾链算出的时长再经面板地板 {@code max(engine, panel)}）
     *   total         = product × ownDuration            ← 本批计划总量（守恒的那个数）
     *   perTick       = total ÷ batchDuration（整除）
     *   remainder     = total − perTick × batchDuration  ← 只在最后一 tick 补
     *   fallback      = true ⇒ 退回改动前口径（每 tick 发 product，无上限）
     * </pre>
     */
    public static final class Amortized {

        /** 计划每 tick 上限（= {@link Readings#product}）。 */
        public final BigInteger product;
        /** 发电配方自身时长。 */
        public final long ownDuration;
        /** 本批真实倒计时时长（{@code RecipeLogic#duration}）。 */
        public final long batchDuration;
        /** 本批计划总量 = {@code product × ownDuration}。 */
        public final BigInteger total;
        /** 每 tick 实发 = {@code total ÷ batchDuration}（整除）。 */
        public final BigInteger perTick;
        /** 余数 = {@code total − perTick × batchDuration}（最后一 tick 补；{@code fallback} 时恒 0）。 */
        public final BigInteger remainder;
        /** {@code true} = 退回改动前口径（每 tick 发 {@link #product}，无上限、无余数）。 */
        public final boolean fallback;
        /** {@code true} = 最后一 tick 补 {@link #remainder}（生产恒 true；负对照用 false）。 */
        public final boolean payRemainder;
        /** 退回原因（{@code fallback == false} 时是空串）。 */
        public final String reason;

        Amortized(BigInteger product, long ownDuration, long batchDuration, BigInteger total,
                  BigInteger perTick, BigInteger remainder, boolean fallback, boolean payRemainder,
                  @NotNull String reason) {
            this.product = product;
            this.ownDuration = ownDuration;
            this.batchDuration = batchDuration;
            this.total = total;
            this.perTick = perTick;
            this.remainder = remainder;
            this.fallback = fallback;
            this.payRemainder = payRemainder;
            this.reason = reason;
        }

        /** 本批还剩多少 tick 没发（{@code <=0} = 批次已走完；提前结束也可能 &gt;0 —— 那时余额不发）。 */
        public long ticksLeft(long paidTicks) {
            return batchDuration - paidTicks;
        }

        /** 一行读数（日志/自检共用同一份字段名）。 */
        @NotNull
        public String line(long paidTicks) {
            return line(paidTicks, null);
        }

        /**
         * 一行读数（日志/自检共用同一份字段名）。
         *
         * @param paidTicks 本批已走过的 tick 数
         * @param paidTotal 本批已入池合计（{@code null} = 调用方没记 ⇒ 打印 {@code ?}；绝不拿计划量冒充）
         */
        @NotNull
        public String line(long paidTicks, @Nullable BigInteger paidTotal) {
            return TAG + " 归墟零点能摊平"
                    + " 自身时长=" + ownDuration
                    + " 本批时长=" + batchDuration
                    + " 每tick=" + perTick
                    + " 余数=" + remainder
                    + " 本批计划总量=" + total
                    + " 本批已发=" + paidTicks + "/" + (batchDuration < 0L ? "?" : String.valueOf(batchDuration))
                    + " tick"
                    + " 本批已发合计=" + (paidTotal == null ? "?" : paidTotal)
                    + (fallback ? " 口径=退回旧口径（" + reason + "）" : " 口径=摊平+最后一tick补余数");
        }
    }

    /**
     * 生成"按批摊平"的计划（生产入口，恒 {@link #AMORTIZE_NORMAL}）。
     *
     * @param product       本次读数 {@link Readings#product}（{@code ≤0} ⇒ 返回一个全零计划）
     * @param ownDuration   发电配方定义实例的 duration
     * @param batchDuration 本批真实倒计时时长（{@code RecipeLogic#duration}）
     */
    @NotNull
    public static Amortized amortize(@Nullable BigInteger product, int ownDuration, long batchDuration) {
        return amortizeForTest(product, ownDuration, batchDuration, AMORTIZE_NORMAL);
    }

    /**
     * {@link #amortize} 的<b>可注入版</b>（只给自检的负对照用）。
     *
     * @param mode {@link #AMORTIZE_NORMAL}（生产）/ {@link #AMORTIZE_LEGACY} / {@link #AMORTIZE_NO_REMAINDER}
     */
    @NotNull
    public static Amortized amortizeForTest(@Nullable BigInteger product, int ownDuration, long batchDuration,
                                            int mode) {
        final BigInteger p = product == null || product.signum() <= 0 ? BigInteger.ZERO : product;
        if (p.signum() <= 0) {
            return new Amortized(BigInteger.ZERO, ownDuration, batchDuration, BigInteger.ZERO,
                    BigInteger.ZERO, BigInteger.ZERO, true, false, "乘积<=0（不产，也不误产）");
        }
        if (ownDuration <= 0) {
            // 拿不到自身时长 ⇒ 不许假装摊平（会把总量算错）⇒ 退回改动前行为
            return new Amortized(p, ownDuration, batchDuration, p.multiply(BigInteger.valueOf(Math.max(1L, batchDuration))),
                    p, BigInteger.ZERO, true, false, "自身时长<=0（读不到配方定义时长）");
        }
        if (batchDuration <= 0L) {
            return new Amortized(p, ownDuration, batchDuration, p.multiply(BigInteger.valueOf(ownDuration)),
                    p, BigInteger.ZERO, true, false, "本批时长<=0（读不到 RecipeLogic#duration）");
        }
        if (mode == AMORTIZE_LEGACY) {
            // 负对照：不摊平（= 改动前"每 tick 乘积"）⇒ 长批次会按 tick 数多发
            return new Amortized(p, ownDuration, batchDuration,
                    p.multiply(BigInteger.valueOf(batchDuration)), p, BigInteger.ZERO, true, false,
                    "负对照：摊平被关掉（每 tick 都发乘积）");
        }
        final BigInteger total = p.multiply(BigInteger.valueOf(ownDuration));
        final BigInteger perTick = total.divide(BigInteger.valueOf(batchDuration));
        final BigInteger remainder = total.subtract(perTick.multiply(BigInteger.valueOf(batchDuration)));
        if (mode == AMORTIZE_NO_REMAINDER) {
            // 负对照：余数不补 ⇒ 每批总量少 remainder
            return new Amortized(p, ownDuration, batchDuration, total, perTick, remainder, false, false,
                    "");
        }
        return new Amortized(p, ownDuration, batchDuration, total, perTick, remainder, false, true, "");
    }

    /**
     * <b>本批第 {@code tickIndex} 个 tick（0-based）该发多少</b> —— 唯一的分发算式。
     *
     * <ul>
     *   <li>{@code fallback} ⇒ 每 tick 都发 {@code product}（= 改动前行为，不看下标）；</li>
     *   <li>{@code tickIndex ∈ [0, 本批时长)} ⇒ 中间 tick 发 {@code perTick}，
     *       <b>最后一 tick</b> 发 {@code perTick + 余数}（{@code payRemainder == false} 时不补）；</li>
     *   <li>{@code tickIndex >= 本批时长} 或 {@code < 0} ⇒ <b>0</b>
     *       （🔴 批次提前结束时余额就是不发 —— 这是"不许有第二条补发路径"的落点）。</li>
     * </ul>
     */
    @NotNull
    public static BigInteger payoutAt(@Nullable Amortized a, long tickIndex) {
        if (a == null || a.product.signum() <= 0) {
            return BigInteger.ZERO;
        }
        if (a.fallback) {
            return a.product;
        }
        if (tickIndex < 0L || tickIndex >= a.batchDuration) {
            return BigInteger.ZERO;                     // ← 超出批次：不发（不补发余额）
        }
        if (a.payRemainder && tickIndex == a.batchDuration - 1L) {
            return a.perTick.add(a.remainder);          // ← 唯一的补余数点
        }
        return a.perTick;
    }

    /** 这个下标是不是"本批最后一 tick"（日志用它决定要不要再打一行收尾读数）。 */
    public static boolean isLastTick(@Nullable Amortized a, long tickIndex) {
        return a != null && !a.fallback && a.batchDuration > 0L && tickIndex == a.batchDuration - 1L;
    }

    /**
     * <b>判据 ⑦ 的同一份代码</b>（正向 = 生产口径，负向 = 两种错口径），形制照抄
     * {@link #otherTypeProblems(boolean)} / {@link #splitAllocationProblems(int)}。
     *
     * <p>三条不变量逐条断言；{@code mode != AMORTIZE_NORMAL} 时<b>必须</b>报红 ⇒ 证明这三条有分辨力。
     */
    @NotNull
    public static List<String> amortizationProblems(int mode) {
        return amortizationProblems(mode, false);
    }

    /**
     * <b>判据 ⑦ 打在【生产入口】上</b>（{@link #amortize} 本体，不注入任何 mode）。
     *
     * <p>🔴 为什么必须另有一条：{@link #amortizationProblems(int)} 是"把 mode 当参数传进去"的自检，
     * 它对 {@link #amortize} 本体<b>没有分辨力</b> —— 把生产入口改回旧口径，它照样全绿（本轮实踩：
     * 负对照跑了仍报"全绿"）。所以生产口径必须有一条断言<b>直接</b>打在生产入口上。
     */
    @NotNull
    public static List<String> productionAmortizationProblems() {
        return amortizationProblems(AMORTIZE_NORMAL, true);
    }

    /**
     * @param mode             注入口径（只对 {@code useProductionEntry == false} 有效）
     * @param useProductionEntry {@code true} = 走生产入口 {@link #amortize}（不看 {@code mode}）
     */
    @NotNull
    private static List<String> amortizationProblems(int mode, boolean useProductionEntry) {
        final List<String> problems = new ArrayList<>();
        // 用已知样本：2^53（= 2^47 × 64），自身时长 20（现场那条配方就是 20）
        final BigInteger p = BigInteger.valueOf(9_007_199_254_740_992L);
        final int own = 20;
        final String tag = useProductionEntry ? "生产入口" : ("mode=" + mode);

        // ── 不变量 1：本批时长 == 自身时长 ⇒ 每 tick 与改动前逐位相同 ──
        final Amortized same = pick(p, own, own, mode, useProductionEntry);
        check(problems, !same.fallback, "⑦ 本批时长==自身时长 时不许退回旧口径（" + tag + "）");
        check(problems, same.perTick.equals(p),
                "⑦【不变量1】本批时长==自身时长 ⇒ 每tick 必须逐位 = 乘积 " + p + "，实际 " + same.perTick
                        + "（" + tag + "）");
        check(problems, same.remainder.signum() == 0, "⑦【不变量1】等长批次的余数必须为 0，实际 " + same.remainder);
        boolean everyTickSame = true;
        BigInteger sumSame = BigInteger.ZERO;
        for (long i = 0L; i < own; i++) {
            final BigInteger paid = payoutAt(same, i);
            if (!paid.equals(p)) {
                everyTickSame = false;
            }
            sumSame = sumSame.add(paid);
        }
        check(problems, everyTickSame, "⑦【不变量1】等长批次里每个 tick 都必须发 " + p + "（逐位相同）");
        check(problems, sumSame.equals(p.multiply(BigInteger.valueOf(own))),
                "⑦【不变量1】等长批次总量必须 = 乘积×自身时长，实际 " + sumSame);

        // ── 不变量 2：混合轮（3199）每批总量 == 纯发电轮（20）每批总量 ──
        final long longBatch = 3199L;
        final Amortized mixed = pick(p, own, longBatch, mode, useProductionEntry);
        BigInteger sumMixed = BigInteger.ZERO;
        for (long i = 0L; i < longBatch; i++) {
            sumMixed = sumMixed.add(payoutAt(mixed, i));
        }
        check(problems, sumMixed.equals(sumSame),
                "⑦【不变量2】长批次(" + longBatch + ")每批总量必须 = 纯发电轮每批总量 " + sumSame
                        + "，实际 " + sumMixed + "（" + tag + "）");
        check(problems, mixed.remainder.compareTo(BigInteger.valueOf(longBatch)) < 0,
                "⑦【不变量2】余数必须 &lt; 本批时长（否则说明摊平算式写错了），实际 " + mixed.remainder);

        // ── 不变量 3：批次提前结束 ⇒ 余额不发；超出批次一律 0 ──
        final BigInteger atEnd = payoutAt(mixed, longBatch);
        check(problems, atEnd.signum() == 0,
                "⑦【不变量3】超出本批时长的 tick 必须发 0（不许补发余额），实际 " + atEnd);
        check(problems, payoutAt(mixed, longBatch + 5L).signum() == 0,
                "⑦【不变量3】批次结束后再调也不许发（不许第二条补发路径）");
        BigInteger sumHalf = BigInteger.ZERO;
        for (long i = 0L; i < longBatch / 2L; i++) {
            sumHalf = sumHalf.add(payoutAt(mixed, i));
        }
        check(problems, sumHalf.compareTo(sumMixed) < 0,
                "⑦【不变量3】只跑一半时必须【少发】（= 余额不发），实际 " + sumHalf + " vs 完整 " + sumMixed);

        // ── 边界：时长拿不到 ⇒ 退回旧口径（每 tick 乘积），且必须带原因 ──
        final Amortized badOwn = pick(p, 0, longBatch, mode, useProductionEntry);
        check(problems, badOwn.fallback && !badOwn.reason.isEmpty(),
                "⑦ 边界：自身时长<=0 必须退回旧口径并给原因");
        check(problems, payoutAt(badOwn, 0L).equals(p),
                "⑦ 边界：退回旧口径时每 tick 必须发乘积");
        final Amortized badBatch = pick(p, own, 0L, mode, useProductionEntry);
        check(problems, badBatch.fallback && !badBatch.reason.isEmpty(),
                "⑦ 边界：本批时长<=0 必须退回旧口径并给原因");
        return problems;
    }

    /**
     * 取一个摊平计划：生产入口（{@link #amortize}）或注入口径（{@link #amortizeForTest}）。
     * <p>🔴 判据 ⑦ 必须能在<b>两条入口</b>上跑：只打注入口径 = 对生产代码没有分辨力。
     */
    @NotNull
    private static Amortized pick(@NotNull BigInteger product, int ownDuration, long batchDuration,
                                  int mode, boolean useProductionEntry) {
        return useProductionEntry
                ? amortize(product, ownDuration, batchDuration)
                : amortizeForTest(product, ownDuration, batchDuration, mode);
    }

    // ═════════════════════════════ ③ 判据自检（离线可跑） ═════════════════════════════

    /**
     * <b>「其它类型逐位不变」这一组判据</b>（判据 ③ 的实现，也是负对照 ④A 的同一份代码）。
     *
     * @param typeGateEnabled {@code true} = 生产口径（必须<b>空表</b> = 绿）；
     *                        {@code false} = 模拟"把类型判断去掉"⇒ 必须<b>非空</b>（= ③ 报红）
     */
    @NotNull
    public static List<String> otherTypeProblems(boolean typeGateEnabled) {
        final List<String> problems = new ArrayList<>();
        for (final String other : new String[]{OTHER_TYPE_MATTER_RECOMBINATION,
                OTHER_TYPE_MATTER_DECOMPRESSION}) {
            final boolean conv = shouldConvert(other, RECIPE_BASE_EUT, typeGateEnabled);
            final long kept = zeroedOutputEut(RECIPE_BASE_EUT, conv);
            check(problems, !conv, "③ 类型 " + other + " 不许被转换（typeGate=" + typeGateEnabled + "）");
            check(problems, kept == RECIPE_BASE_EUT,
                    "③ 类型 " + other + " 的 EU 读数必须逐位不变（" + RECIPE_BASE_EUT + "），实际 " + kept
                            + "（typeGate=" + typeGateEnabled + "）");
        }
        return problems;
    }

    /**
     * <b>离线自检</b>：正向对照 + 三条负对照（任何一条不成立 ⇒ 抛异常，加载期 fail-fast）。
     *
     * <pre>
     *   ① 目标配方真的被清零（EU 读数 = 0）
     *   ② 产量逐位正确：p=1 ⇒ 2^47 ；p=64 ⇒ 2^53 （两组读数）
     *   ③ 其它类型逐位不变（两个真实样本）
     *   ④A 负对照：把类型判断去掉 ⇒ ③ 必须【红】（不允许仍然全绿）
     *   ④B 并行开关：把 FORCE_SINGLE_PARALLEL 打开 ⇒ 产量回到 2^47（一行开关的语义证明）
     *   ⑤ BigInteger 判据：p = Long.MAX_VALUE 也【不许】出现负数 / 9.22e18 饱和值（含 long 路饱和的负对照）
     *   ⑥ 分料顺序：混合轮里发电配方必须被提到第 1 位（含负对照：改回旧写法 ⇒ 必红）
     *   ⑦ 结算口径：每批总量 = 乘积×自身时长，摊到本批时长、余数最后一 tick 补
     *      （含三条不变量 + 两条负对照：摊平关掉 ⇒ 长批次 159.95 倍必红；余数不补 ⇒ 总量少必红）
     * </pre>
     */
    @NotNull
    public static String selfTest() {
        final StringBuilder log = new StringBuilder();
        final List<String> problems = new ArrayList<>();

        // ── ① 目标配方：清零 ──
        final boolean convert = shouldConvert(TYPE_ID, RECIPE_BASE_EUT, true);
        final long afterZero = zeroedOutputEut(RECIPE_BASE_EUT, convert);
        check(problems, convert, "① shouldConvert(目标类型, 2^47) 必须为 true");
        check(problems, afterZero == 0L, "① 目标配方清零后 EU 读数必须是 0，实际 " + afterZero);
        check(problems, !shouldConvert(TYPE_ID, 0L, true), "① 同类型但输出电 = 0 时不许转换");
        check(problems, !shouldConvert(TYPE_ID, -7L, true), "① 同类型但输出电 < 0 时不许转换");
        check(problems, !shouldConvert(null, RECIPE_BASE_EUT, true), "① 类型为 null 时不许转换");
        log.append("① 清零：目标类型 2^47 ⇒ 0（原值 ").append(RECIPE_BASE_EUT)
                .append("）；同类型但 EUt<=0 ⇒ 不动 ✔\n");

        // ── ③ 其它类型：逐位不变 ──
        final List<String> otherProblems = otherTypeProblems(true);
        problems.addAll(otherProblems);
        log.append("③ 不变：").append(OTHER_TYPE_MATTER_RECOMBINATION).append(" / ")
                .append(OTHER_TYPE_MATTER_DECOMPRESSION).append(" 的 EU 读数逐位 = 原值 ")
                .append(RECIPE_BASE_EUT).append(" ✔\n");

        // ── ② 产量逐位（p = 1 与 p = 64 两组；门控 0 ⇒ 系数 1.0000 ⇒ 与旧口径逐位相同） ──
        final int gate0 = 0;
        final BigInteger one = product(RECIPE_BASE_EUT, effectiveParallel(1L, 1L), gate0);
        final BigInteger p64 = product(RECIPE_BASE_EUT, effectiveParallel(64L, 1L), gate0);
        check(problems, "140737488355328".equals(one.toString()),
                "② p=1、门控 0（系数 1）产量必须逐位 = 140737488355328，实际 " + one);
        check(problems, "9007199254740992".equals(p64.toString()),
                "② p=64、门控 0 产量必须逐位 = 2^53 = 9007199254740992，实际 " + p64);
        check(problems, p64.equals(one.shiftLeft(6)), "② p=64 必须逐位 = p=1 × 64");
        // 引擎路径真正用的那个入口（ParallelData 口径）必须与上面逐位相同
        final Readings viaParallelData = readingsFromParallelData(RECIPE_BASE_EUT, 64L, gate0);
        check(problems, viaParallelData.product.equals(p64),
                "② ParallelData 口径（引擎路径的现行来源）与 realParallels 口径必须逐位相同，实际 "
                        + viaParallelData.product);
        check(problems, SOURCE_PARALLEL_DATA.equals(viaParallelData.source),
                "② 并行来源必须标成 " + SOURCE_PARALLEL_DATA + "（日志里要能看出这个 p 是哪来的）");
        check(problems, "1.0000".equals(viaParallelData.coefficientText()),
                "② 门控 0 的系数显示必须 = 1.0000（4 位小数），实际 " + viaParallelData.coefficientText());
        log.append("② 产量（门控 0 ⇒ 系数 1.0000）：baseEut=").append(RECIPE_BASE_EUT).append(" × p=1  ⇒ ")
                .append(one)
                .append("\n").append("         baseEut=").append(RECIPE_BASE_EUT).append(" × p=64 ⇒ ")
                .append(p64).append("（= 2^53）✔").append("\n")
                .append("         ParallelData 口径（引擎路径现行来源）p=64 ⇒ ").append(viaParallelData.product)
                .append("（与上一行逐位相同 ✔，来源标记 = ").append(viaParallelData.source).append("）\n");

        // ── ①（新）系数真参与：门控 0 / 1 / 5 / 17 四档，与零点能模块逐档一致 ──
        //    期望值 = 零点能模块的同一组读数（1.0 / 0.5819 / 0.1600 / 0.0500）。
        final int[] gates = {0, 1, 5, 17};
        final String[] expectedCoefficients = {"1", "0.5819", "0.16", "0.05"};
        final List<String> coefficientLines = new ArrayList<>();
        for (int i = 0; i < gates.length; i++) {
            final int gate = gates[i];
            final BigDecimal c = coefficient(gate);
            final String shown = c.stripTrailingZeros().toPlainString();
            check(problems, expectedCoefficients[i].equals(shown),
                    "① 门控 " + gate + " 的系数必须 = " + expectedCoefficients[i] + "，实际 " + shown);
            // 同一个函数：与零点能模块逐档逐值相同（不是"看起来差不多"）
            check(problems, c.compareTo(PrimordialGeneratorProduction.generationDivisor(gate)) == 0,
                    "① 门控 " + gate + " 的系数必须与零点能模块的 generationDivisor 逐值相同");
            // 系数真的参与：产量必须 = floor(base × p ÷ 系数)（HALF_UP），而不是 base × p
            final BigInteger withC = product(RECIPE_BASE_EUT, 1L, gate);
            final BigInteger expectWithC = new BigDecimal(
                    BigInteger.valueOf(RECIPE_BASE_EUT).multiply(BigInteger.ONE))
                    .divide(c, 0, RoundingMode.HALF_UP).toBigIntegerExact();
            check(problems, withC.equals(expectWithC),
                    "① 门控 " + gate + " 的产量必须 = 2^47 ÷ 系数 = " + expectWithC + "，实际 " + withC);
            final BigInteger withoutC = productForTest(RECIPE_BASE_EUT, 1L, gate, true);
            if (gate > 0) {
                check(problems, !withC.equals(withoutC),
                        "① 门控 " + gate + " 的产量必须【不等于】「系数强制 1.0」的产量（否则系数没参与）");
            } else {
                check(problems, withC.equals(withoutC), "① 门控 0 时两种口径必须逐位相同（系数真为 1.0）");
            }
            coefficientLines.add("门控 " + gate + " ⇒ 系数 " + c.setScale(4, RoundingMode.HALF_UP)
                    + " ⇒ 2^47÷系数 = " + withC + "（÷系数 = ×"
                    + String.format(java.util.Locale.ROOT, "%.2f",
                    BigDecimal.ONE.divide(c, 10, RoundingMode.HALF_UP).doubleValue()) + "）");
        }
        log.append("① 系数（= 零点能模块同一个 generationDivisor）：\n");
        for (final String line : coefficientLines) {
            log.append("         ").append(line).append("\n");
        }
        // 域规则照抄：地板 0.05、域外 ⇒ 1.0（调的是模块那一个函数，不是我另写一份）
        check(problems, PrimordialGeneratorProduction.divisorOf(0.0123D)
                .compareTo(new BigDecimal("0.0500")) == 0, "① 域内低于地板 ⇒ 必须抬到 0.0500");
        check(problems, PrimordialGeneratorProduction.divisorOf(Double.NaN)
                .compareTo(BigDecimal.ONE) == 0, "① NaN（域外）⇒ 必须 1.0（不放大）");
        check(problems, PrimordialGeneratorProduction.divisorOf(1.7D)
                .compareTo(BigDecimal.ONE) == 0, "① &gt;1（域外）⇒ 必须 1.0");
        check(problems, PrimordialGeneratorProduction.divisorOf(0.050000000000000044D)
                .compareTo(new BigDecimal("0.0500")) == 0, "① 0.050000000000000044 ⇒ 量化成 0.0500");
        log.append("         域规则（模块同一个函数）：0.0123 ⇒ 0.0500（地板）；NaN / 1.7 ⇒ 1.0（域外不放大）；"
                        + "0.050000000000000044 ⇒ 0.0500 ✔\n");

        // ── ① 的负对照：把系数强制成 1.0 ⇒ 上面那组必须报红 ──
        int coefficientRed = 0;
        for (final int gate : new int[]{1, 5, 17}) {
            final BigInteger real = product(RECIPE_BASE_EUT, 1L, gate);
            final BigInteger fake = productForTest(RECIPE_BASE_EUT, 1L, gate, true);
            if (!real.equals(fake)) {
                coefficientRed++;
            }
        }
        check(problems, coefficientRed == 3,
                "① 负对照失败：系数强制 1.0 后有档位仍与真值相同 ⇒ 「系数真参与」这条判据没有分辨力（红 "
                        + coefficientRed + "/3）");
        log.append("① 负对照：系数强制 1.0 ⇒ 3/3 档产量都与真值不同（判据①会报红）✔\n");

        // ── ②' 并行口径（realParallels ÷ batchSize，与模块同一条算式） ──
        check(problems, effectiveParallel(64L, 1L) == 64L, "②' 非批处理：p 必须 = realParallels");
        check(problems, effectiveParallel(128L, 2L) == 64L, "②' 批处理：p 必须 = realParallels ÷ batchSize");
        check(problems, effectiveParallel(0L, 1L) == 1L, "②' 读不到并行时必须取 1（不许产出 0）");
        check(problems, effectiveParallel(-5L, 1L) == 1L, "②' 并行 &lt; 0 时必须取 1");
        log.append("②' 并行：64/1 ⇒ 64 ；128/2 ⇒ 64 ；0/1 ⇒ 1 ✔\n");

        // ── ⑧ 分料结算：**耗电先分料 + 发电按「ΣA 与剩余预算」封顶**（预算优先，用户 2026-11-22 拍板）──
        final List<String> splitProblemsProd = productionSplitProblems();
        problems.addAll(splitProblemsProd);
        final List<String> splitProblems = splitAllocationProblems(ALLOC_TWO_PASS);
        problems.addAll(splitProblems);
        // 顺序（耗电在前、发电在后）：现场自然顺序就是它 ⇒ 分区是恒等序（不改老路径）
        final int[] liveRanks = {allocationRank(OTHER_TYPE_MATTER_DECOMPRESSION, 2_147_483_648L),
                allocationRank(TYPE_ID, RECIPE_BASE_EUT)};
        final int[] liveOrder = allocationOrderConsumerFirst(liveRanks);
        check(problems, isIdentityOrder(liveOrder),
                "⑧ 现场自然顺序（耗电在前）下分区必须是恒等序（老路径一个字节都不改），实际 "
                        + java.util.Arrays.toString(liveOrder));
        check(problems, isIdentityOrder(allocationOrderConsumerFirst(new int[]{0})), "⑧ 只有发电一条候选 ⇒ 恒等序");
        check(problems, isIdentityOrder(allocationOrderConsumerFirst(new int[]{1, 1})), "⑧ 全是耗电 ⇒ 恒等序");
        check(problems, isIdentityOrder(allocationOrderConsumerFirst(new int[0])), "⑧ 空候选 ⇒ 恒等序");
        // 发电被排在前面时（= 上一版的形态）必须真的换了向 —— 这是负对照 A 的前提
        final int[] genFirstOrder = allocationOrderConsumerFirstForTest(liveRanks, false);
        check(problems, !isIdentityOrder(genFirstOrder),
                "⑧ 负对照前提不成立：\"发电在前\"的分区竟然与自然顺序相同 ⇒ 负对照 A 复现不了那个坏现象");
        // 三条场景读数（用户点名的三组数）
        final long[] scAmple = simulatedSplit(4096L, 1024L, Long.MAX_VALUE, ALLOC_TWO_PASS);
        final long[] scSqueezed = simulatedSplit(1024L, Long.MAX_VALUE, Long.MAX_VALUE, ALLOC_TWO_PASS);
        final long[] scHalf = simulatedSplit(1024L, 600L, Long.MAX_VALUE, ALLOC_TWO_PASS);
        final long[] scPure = simulatedSplit(1024L, 0L, 1024L, ALLOC_TWO_PASS);
        log.append("⑧ 分料结算（生产口径；cap = clamp(min(ΣA, max(0,预算−ΣA)),1,ΣA)）：\n")
                .append("         A 预算充足 预算=4096 ΣA=1024 ⇒ 发电=").append(scAmple[1])
                .append("（应 =1024 =ΣA；总份数=").append(scAmple[0] + scAmple[1]).append(" ≤ 预算）")
                .append(scAmple[1] == 1024L ? " ✔" : " ✗").append("\n")
                .append("         B 预算吃干 预算=1024 ΣA=1024 ⇒ 发电=").append(scSqueezed[1])
                .append("（应 =1；总份数=").append(scSqueezed[0] + scSqueezed[1])
                .append(" = 预算+").append(scSqueezed[0] + scSqueezed[1] - 1024L).append("）")
                .append(scSqueezed[1] == 1L && scSqueezed[0] + scSqueezed[1] <= 1025L ? " ✔" : " ✗").append("\n")
                .append("         C 半个预算 预算=1024 ΣA=600 ⇒ 发电=").append(scHalf[1])
                .append("（应 =min(600,424)=424；总份数=").append(scHalf[0] + scHalf[1]).append(" = 预算）")
                .append(scHalf[1] == 424L && scHalf[0] + scHalf[1] <= 1024L ? " ✔" : " ✗").append("\n")
                .append("         D 纯发电轮 ΣA=0 ⇒ 发电=").append(scPure[1]).append("（应 =1024，不设限）")
                .append(scPure[1] == 1024L ? " ✔" : " ✗").append("\n");
        // ⑧ 负对照 A：上一版（发电先分料、不设限）⇒ 必须复现「只跑发电」
        final List<String> redGenFirst = splitAllocationProblems(ALLOC_GEN_FIRST);
        check(problems, !redGenFirst.isEmpty(),
                "⑧ 负对照A失败：上一版口径（发电先分料）竟然全绿 ⇒ 检查器没有分辨力");
        // ⑧ 负对照 B：改动前（耗电先、不设限）⇒ 必须复现「发电不跑」
        final List<String> redNoCap = splitAllocationProblems(ALLOC_CONSUMER_FIRST_NO_CAP);
        check(problems, !redNoCap.isEmpty(),
                "⑧ 负对照B失败：改动前口径（耗电先、不设限）竟然全绿 ⇒ 检查器没有分辨力");
        // ⑧ 负对照 C：上限 = ΣA（不看预算，= 拍板前旧写法）⇒ 必须报「总份数超预算 2×」
        final List<String> redOldCap = splitAllocationProblems(ALLOC_TWO_PASS_OLD_CAP);
        check(problems, !redOldCap.isEmpty(),
                "⑧ 负对照C失败：把上限改回「不看预算的 ΣA」后仍然全绿 ⇒ 检查器没有分辨力");
        // ⑧ 负对照 D：去掉「至少 1 份」地板 ⇒ 预算吃干时发电=0（复现只跑耗电）
        final List<String> redNoFloor = splitAllocationProblems(ALLOC_TWO_PASS_NO_FLOOR);
        check(problems, !redNoFloor.isEmpty(),
                "⑧ 负对照D失败：去掉「至少 1 份」地板后仍然全绿 ⇒ 检查器没有分辨力");
        final long[] oldCapSplit = simulatedSplit(1024L, Long.MAX_VALUE, Long.MAX_VALUE,
                ALLOC_TWO_PASS_OLD_CAP);
        final long[] noFloorSplit = simulatedSplit(1024L, Long.MAX_VALUE, Long.MAX_VALUE,
                ALLOC_TWO_PASS_NO_FLOOR);
        log.append("⑧ 负对照：C 旧写法(上限=ΣA、不看预算) ⇒ 总份数=")
                .append(oldCapSplit[0] + oldCapSplit[1]).append(" = 预算的 ")
                .append(String.format(java.util.Locale.ROOT, "%.2f",
                        (double) (oldCapSplit[0] + oldCapSplit[1]) / 1024.0D))
                .append(" 倍 ⇒ 报红 ").append(redOldCap.size()).append(" 条（复现「超预算 2×」）\n")
                .append("           D 去掉「至少 1 份」 ⇒ 预算吃干时 发电份数=").append(noFloorSplit[1])
                .append(" ⇒ 报红 ").append(redNoFloor.size()).append(" 条（复现「只跑耗电」）\n")
                .append("           A/B 另两条负对照报红条数=").append(redGenFirst.size()).append("/")
                .append(redNoCap.size()).append("\n");
        // ── ⑦ 结算口径（每批总量守恒的摊平）：三条硬不变量 + 两条负对照 ──
        // 🔴 先打【生产入口】（amortize 本体）：把生产代码改回旧口径必须在这里报红
        //    （本轮实踩：只打注入口径的自检对生产代码没有分辨力 —— 生产入口改回旧口径它照样全绿）
        final List<String> amProduction = productionAmortizationProblems();
        problems.addAll(amProduction);
        final List<String> amProblems = amortizationProblems(AMORTIZE_NORMAL);
        problems.addAll(amProblems);
        final BigInteger amP = BigInteger.valueOf(9_007_199_254_740_992L);   // 2^53（= 2^47 × 64）
        final Amortized amSame = amortize(amP, 20, 20);
        final Amortized amMixed = amortize(amP, 20, 3199);
        BigInteger amPureTotal = BigInteger.ZERO;
        for (long i = 0L; i < 20L; i++) {
            amPureTotal = amPureTotal.add(payoutAt(amSame, i));
        }
        BigInteger amMixedTotal = BigInteger.ZERO;
        for (long i = 0L; i < 3199L; i++) {
            amMixedTotal = amMixedTotal.add(payoutAt(amMixed, i));
        }
        // 不变量 1（直接打在生产入口上）：等长批次每 tick 逐位 = 乘积、余数 0
        final boolean inv1 = amSame.perTick.equals(amP) && amSame.remainder.signum() == 0;
        check(problems, inv1, "⑦【不变量1·生产入口】本批时长==自身时长 ⇒ 每tick 必须逐位 = 乘积 " + amP
                + " 且余数 0，实际 每tick=" + amSame.perTick + " 余数=" + amSame.remainder);
        // 不变量 2（直接打在生产入口上）：长批次总量 == 纯发电轮总量
        final boolean inv2 = amMixedTotal.equals(amPureTotal);
        check(problems, inv2, "⑦【不变量2·生产入口】长批次每批总量必须 = 纯发电轮每批总量 " + amPureTotal
                + "，实际 " + amMixedTotal + "（差 " + amMixedTotal.subtract(amPureTotal) + "）");
        // 不变量 3（直接打在生产入口上）：超出批次一律 0
        final boolean inv3 = payoutAt(amMixed, 3199L).signum() == 0 && payoutAt(amMixed, 5000L).signum() == 0;
        check(problems, inv3, "⑦【不变量3·生产入口】超出/结束后的 tick 必须发 0（不许第二条补发路径）");
        final BigInteger amLegacyMixed = amP.multiply(BigInteger.valueOf(3199L));
        log.append("⑦ 摊平（每批总量守恒）：乘积=").append(amP).append("\n")
                .append("         纯发电轮（本批 20 = 自身 20）：每tick=").append(amSame.perTick)
                .append(" 余数=").append(amSame.remainder).append(" 总量=").append(amPureTotal)
                .append("（与改动前逐位相同 ").append(inv1 ? "✔" : "✗").append("）\n")
                .append("         混合轮（本批 3199、自身 20）：每tick=").append(amMixed.perTick)
                .append(" 余数=").append(amMixed.remainder).append(" 总量=").append(amMixedTotal)
                .append("（= 纯发电轮总量 ").append(inv2 ? "✔" : "✗ 差 " + amMixedTotal.subtract(amPureTotal))
                .append("；超出批次一率 0 ").append(inv3 ? "✔" : "✗").append("）\n")
                .append("         旧口径同批总量=").append(amLegacyMixed)
                .append("（= 混合轮总量的 ").append(String.format(java.util.Locale.ROOT, "%.2f",
                        (double) 3199L / 20.0D)).append(" 倍 ⇒ 这就是本轮的负对照 A）\n");
        // ⑦ 负对照 A：把摊平关掉（旧口径）⇒ 混合轮总量必须变成 159.95 倍 ⇒ 判据必须报红
        final List<String> redLegacy = amortizationProblems(AMORTIZE_LEGACY);
        check(problems, !redLegacy.isEmpty(),
                "⑦ 负对照A失败：把摊平关掉后 ⑦ 仍然全绿 ⇒ 检查器没有分辨力");
        // ⑦ 负对照 B：摊平但余数不补 ⇒ 每批总量少 余数 ⇒ 判据必须报红
        final List<String> redNoRemainder = amortizationProblems(AMORTIZE_NO_REMAINDER);
        check(problems, !redNoRemainder.isEmpty(),
                "⑦ 负对照B失败：把\"最后一 tick 补余数\"去掉后 ⑦ 仍然全绿 ⇒ 检查器没有分辨力");
        log.append("⑦ 负对照：摊平关掉 ⇒ ⑦ 报红 ").append(redLegacy.size())
                .append(" 条（第一条：").append(redLegacy.isEmpty() ? "空=坏了" : redLegacy.get(0)).append("）\n")
                .append("           余数不补 ⇒ ⑦ 报红 ").append(redNoRemainder.size())
                .append(" 条（第一条：").append(redNoRemainder.isEmpty() ? "空=坏了" : redNoRemainder.get(0))
                .append("）✔\n");

        // ── ④B 并行开关（一行开关的语义；门控 0 时系数 1 ⇒ 与 p=1 那一档逐位相同） ──
        final long forced = effectiveParallel(64L, 1L, true);
        final BigInteger forcedProduct = product(RECIPE_BASE_EUT, forced, gate0);
        check(problems, forced == EFFECTIVE_PARALLEL_WHEN_FORCED, "④B 开关打开时 p 必须 = "
                + EFFECTIVE_PARALLEL_WHEN_FORCED + "，实际 " + forced);
        check(problems, forcedProduct.equals(one), "④B 开关打开时产量必须回到 p=1 那一档（" + one + "）");
        log.append("④B 并行开关：FORCE_SINGLE_PARALLEL=").append(FORCE_SINGLE_PARALLEL)
                .append(" ⇒ 现行 p=").append(effectiveParallel(64L, 1L))
                .append("；打开后 p=").append(forced).append(" ⇒ 产量 ").append(forcedProduct)
                .append("（= p=1 那一档）✔\n");

        // ── ⑤ BigInteger：p = Long.MAX_VALUE 也不许负数/饱和（门控 0 ⇒ 不被系数整除，便于逐位断言） ──
        final BigInteger huge = product(RECIPE_BASE_EUT, Long.MAX_VALUE, gate0);
        final BigInteger hugeSmall = product(1L << 31, Long.MAX_VALUE, gate0);
        check(problems, huge.signum() > 0, "⑤ p=Long.MAX 时产量必须 &gt; 0，实际 " + huge);
        check(problems, huge.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0,
                "⑤ p=Long.MAX 时产量必须 &gt; Long.MAX_VALUE（否则说明走了 long 链）");
        check(problems, huge.mod(BigInteger.valueOf(Long.MAX_VALUE)).signum() == 0,
                "⑤ p=Long.MAX 时产量必须能被 p 整除（逐位无损）");
        check(problems, huge.divide(BigInteger.valueOf(Long.MAX_VALUE))
                        .equals(BigInteger.valueOf(RECIPE_BASE_EUT)),
                "⑤ 产量 ÷ p 必须逐位回到 baseEut");
        check(problems, "19807040628566084396238503936".equals(hugeSmall.toString()),
                "⑤ 2^31 × Long.MAX_VALUE 必须逐位 = 2^94 − 2^31 = 19807040628566084396238503936，实际 " + hugeSmall);
        // 负对照：同一条算式走 double→long 会【静默饱和】到 Long.MAX_VALUE（JLS 5.1.3）
        final long viaLong = (long) ((double) RECIPE_BASE_EUT * (double) Long.MAX_VALUE);
        check(problems, viaLong == Long.MAX_VALUE,
                "⑤ 负对照：long 路必须饱和成 Long.MAX_VALUE（= 证明「必须 BigInteger」这条前提是真的），实际 " + viaLong);
        log.append("⑤ BigInteger：2^47 × Long.MAX = ").append(huge).append("\n")
                .append("             2^31 × Long.MAX = ").append(hugeSmall).append("\n")
                .append("             （同一算式走 long ⇒ 饱和成 ").append(viaLong).append(" = Long.MAX_VALUE ✔）\n");

        // ── ④A 负对照：把类型判断去掉 ⇒ ③ 必须报红 ──
        final List<String> redWhenTypeGateOff = otherTypeProblems(false);
        check(problems, !redWhenTypeGateOff.isEmpty(),
                "④A 负对照失败：把类型判断去掉后 ③ 仍然全绿 ⇒ 检查器没有分辨力（它说的绿没有任何信息量）");
        log.append("④A 负对照：去掉类型判断 ⇒ ③ 报红 ").append(redWhenTypeGateOff.size())
                .append(" 条（证明 ③ 的绿是真的）✔ 第一条原文：")
                .append(redWhenTypeGateOff.isEmpty() ? "(空 —— 那就是坏了)" : redWhenTypeGateOff.get(0))
                .append("\n");

        if (!problems.isEmpty()) {
            throw new IllegalStateException(TAG + " 归墟零点能自检失败：" + problems);
        }
        return log.toString();
    }

    /**
     * <b>字段 holder 自检</b>（判据 ④B 的第二条负对照）—— 需要游戏 classpath。
     *
     * <p>做法与 {@code GuixuVisionCoreMachine#assertFieldHolderComplete()} <b>同一个实现</b>
     * （本方法调的就是它内部那个 {@code holderProblems}），所以这里绿 ⇒ 那条运行期闸门也是绿：
     * <pre>
     *   正向：真实 holder 的字段名 ⇒ 没有问题（绿）
     *   负向：把 {@code shanhai$visionPoolUuid} 从字段集合里拿掉 ⇒ 必须报红
     *        （模拟"@Persisted 没被登记"这个静默失败；不模拟就永远不知道那道闸门有没有用）
     * </pre>
     */
    /** 空表 = 正向（绿）：真实 holder 的字段名一个不缺。 */
    @NotNull
    static List<String> holderPositiveProblems() {
        return GuixuVisionCoreMachine.shanhai$holderProblems(GuixuVisionCoreMachine.shanhai$managedFieldKeys());
    }

    /**
     * <b>负对照</b>：把 {@link GuixuVisionCoreMachine#FIELD_VISION_POOL_UUID} 从"实际登记的字段集合"里拿掉
     * ⇒ {@code assertFieldHolderComplete()} 必须<b>真的抛异常</b>（这里把它抛出来的原文接住）。
     *
     * <p>为什么不是"返回问题列表"：要证明的是<b>那道运行期闸门</b>会响，而不是"我另写了一个判断"。
     * 这里调的就是 {@code assertFieldHolderComplete()} 本体的可注入版（同一段代码、同一个异常文本）。
     *
     * @return 抛出来的异常文本；{@code null} = 没有抛（= 闸门对这条字段无效，负对照失败）
     * @throws IllegalStateException 字段<b>本来就</b>没登记（那不是负对照失败，是功能根本没接上）
     */
    @Nullable
    static String holderRedExceptionMessage() {
        final ManagedKey[] real = GuixuVisionCoreMachine.shanhai$managedFieldKeys();
        final String uuidField = GuixuVisionCoreMachine.FIELD_VISION_POOL_UUID;
        final Set<String> without = new LinkedHashSet<>();
        boolean removed = false;
        for (final ManagedKey key : real) {
            if (uuidField.equals(key.getName())) {
                removed = true;
                continue;
            }
            without.add(key.getName());
        }
        if (!removed) {
            throw new IllegalStateException(TAG + " 字段 holder：字段 " + uuidField
                    + " 根本没有登记进 holder ⇒ 它不会落盘/同步（这正是本自检要抓的那件事）。");
        }
        try {
            GuixuVisionCoreMachine.shanhai$assertFieldHolderComplete(without);
            return null;                        // 没响 ⇒ 负对照失败
        } catch (IllegalStateException expected) {
            return expected.getMessage();
        }
    }

    /**
     * <b>字段 holder 自检</b>（判据 ④ 的第二条负对照）—— 需要游戏 classpath。
     *
     * <p>做法与 {@code GuixuVisionCoreMachine#assertFieldHolderComplete()} <b>同一个实现</b>
     * （本方法调的就是它本体），所以这里绿 ⇒ 那条运行期闸门也是绿：
     * <pre>
     *   正向：真实 holder 的字段名 ⇒ 没有问题（绿）
     *   负向：把 {@code shanhai$visionPoolUuid} 从字段集合里拿掉 ⇒ 必须报红
     *        （模拟"@Persisted 没被登记"这个静默失败；不模拟就永远不知道那道闸门有没有用）
     * </pre>
     */
    @NotNull
    public static String holderSelfTest() {
        final List<String> green = holderPositiveProblems();
        if (!green.isEmpty()) {
            throw new IllegalStateException(TAG + " 字段 holder 自检失败（正向）：真实 holder 就缺字段 " + green
                    + " ⇒ 运行期 assertFieldHolderComplete() 会抛异常，先修 holder 再谈发电。");
        }
        final String red = holderRedExceptionMessage();
        if (red == null) {
            throw new IllegalStateException(TAG + " 字段 holder 自检的【负对照】失败：把 "
                    + GuixuVisionCoreMachine.FIELD_VISION_POOL_UUID
                    + " 从 holder 里拿掉后 assertFieldHolderComplete() 仍然通过 ⇒ 那道闸门对这条字段无效。");
        }
        return "④B holder：真实 holder ⇒ 绿（" + GuixuVisionCoreMachine.shanhai$managedFieldKeys().length
                + " 个字段，其中 " + GuixuVisionCoreMachine.FIELD_VISION_POOL_UUID + " 的 isPersist=true）；"
                + "拿掉它 ⇒ assertFieldHolderComplete() 抛：" + red;
    }

    private static void check(@NotNull List<String> problems, boolean ok, @NotNull String what) {
        if (!ok) {
            problems.add(what);
        }
    }

    // ═════════════════════════════ ④ 离线入口（不连游戏） ═════════════════════════════

    /**
     * 离线复算入口（不需要启动游戏）：
     * <pre>
     *   java -cp &lt;temp/zpg-out&gt;                            com.shanhai.machine.host.GuixuZeroPointGeneration
     *        ⇒ 只跑纯数学判据（①②③④A⑤），classpath 里不需要任何游戏 jar
     *   java -cp &lt;temp/zpg-out;libs/*;...&gt; …               --holder        ⇒ 追加字段 holder 的两条对照
     *                                                       --break-type-gate ⇒ 负对照：去掉类型判断后 ③ 的原文
     *                                                       --break-holder    ⇒ 负对照：从 holder 拿掉 uuid 后的原文
     *                                                       --break-order     ⇒ 负对照：分料顺序改回旧写法后 ⑥ 的原文
     *                                                       --break-amortize-legacy     ⇒ 负对照：摊平关掉（每 tick 乘积）后 ⑦ 的原文
     *                                                       --break-amortize-remainder  ⇒ 负对照：余数不补后 ⑦ 的原文
     * </pre>
     */
    public static void main(String[] args) {
        final boolean wantHolder = has(args, "--holder");
        final boolean breakTypeGate = has(args, "--break-type-gate");
        final boolean breakHolder = has(args, "--break-holder");
        final boolean breakCoefficient = has(args, "--break-coefficient");
        final boolean breakOrder = has(args, "--break-order");
        final boolean breakAmortizeLegacy = has(args, "--break-amortize-legacy");
        final boolean breakAmortizeRemainder = has(args, "--break-amortize-remainder");

        System.out.println("==== " + TAG + " 归墟·零点能发电 离线自检 ====");
        System.out.println("类型 = " + TYPE_ID + "；并行开关 FORCE_SINGLE_PARALLEL = " + FORCE_SINGLE_PARALLEL
                + "；系数 = 零点能模块同一个 generationDivisor（门控 0/1/5/17 ⇒ 1 / 0.5819 / 0.16 / 0.05）");
        System.out.println();

        if (breakCoefficient) {
            // 🔴 负对照（判据 ① 第二条）：把系数强制成 1.0 ⇒ 判据①必须报红。这里把红的那几行【原文】打出来。
            System.out.println("---- 负对照 C：把系数强制成 1.0（不除系数）----");
            int red = 0;
            for (final int gate : new int[]{1, 5, 17}) {
                final BigInteger real = product(RECIPE_BASE_EUT, 1L, gate);
                final BigInteger fake = productForTest(RECIPE_BASE_EUT, 1L, gate, true);
                final boolean different = !real.equals(fake);
                if (different) {
                    red++;
                }
                System.out.println("   " + (different ? "✗ 报红（真值≠假值，说明系数真在参与）" : "✔ 没差别（= 检查器坏了）")
                        + " 门控=" + gate + " 真值=" + real + " 强制系数1.0=" + fake
                        + " 系数=" + coefficient(gate).setScale(4, RoundingMode.HALF_UP));
            }
            System.out.println("③ 判据①的判定结果（期望：3/3 报红）= " + red + "/3");
            System.out.println();
            System.out.println("==== 判定：负对照生效（把系数改成 1.0 ⇒ 判据①报红）====");
            return;
        }

        if (breakAmortizeLegacy || breakAmortizeRemainder) {
            // 🔴 负对照（判据 ⑦ 的两条）：摊平关掉 / 余数不补 ⇒ 判据 ⑦ 必须报红。红的那几行【原文】打出来。
            final int mode = breakAmortizeLegacy ? AMORTIZE_LEGACY : AMORTIZE_NO_REMAINDER;
            final String name = breakAmortizeLegacy
                    ? "A：把摊平关掉（每 tick 都发乘积 = 改动前口径）"
                    : "B：摊平但余数不补（去掉最后一 tick 补齐）";
            System.out.println("---- 负对照 E" + name + " ----");
            final List<String> red = amortizationProblems(mode);
            System.out.println("⑦ 的判定结果（期望：非空，即报红）= "
                    + (red.isEmpty() ? "空（= 检查器坏了）" : red.size() + " 条"));
            for (final String line : red) {
                System.out.println("   ✗ " + line);
            }
            final BigInteger p = BigInteger.valueOf(9_007_199_254_740_992L);
            final Amortized a = amortizeForTest(p, 20, 3199L, mode);
            BigInteger sum = BigInteger.ZERO;
            for (long i = 0L; i < 3199L; i++) {
                sum = sum.add(payoutAt(a, i));
            }
            System.out.println("   mode=" + mode + " 每tick=" + a.perTick + " 余数=" + a.remainder
                    + " 计划总量=" + a.total + " 实发合计(3199 tick)=" + sum
                    + " ；纯发电轮总量=" + p.multiply(BigInteger.valueOf(20L)));
            System.out.println();
            System.out.println("==== 判定：负对照生效（⑦ 变红）====");
            return;
        }

        if (breakOrder) {
            // 🔴 负对照（判据 ⑧）：四种错口径必须各自复现一个坏现象（只跑发电 / 发电不跑 / 超预算2× / 吃干时0）。
            System.out.println("---- 负对照 D：分料结算的四种错口径（判据 ⑧）----");
            final String[] names = {
                    "A 上一版（发电先分料、不设限）⇒ 只跑发电",
                    "B 改动前（耗电先、不设限）⇒ 发电不跑",
                    "C 上限=ΣA（不看预算，拍板前写法）⇒ 超预算 2×",
                    "D 去掉「至少 1 份」地板 ⇒ 预算吃干时发电=0"};
            final int[] modes = {ALLOC_GEN_FIRST, ALLOC_CONSUMER_FIRST_NO_CAP,
                    ALLOC_TWO_PASS_OLD_CAP, ALLOC_TWO_PASS_NO_FLOOR};
            for (int i = 0; i < modes.length; i++) {
                final List<String> red = splitAllocationProblems(modes[i]);
                final long[] split = simulatedSplit(1024L, Long.MAX_VALUE, Long.MAX_VALUE, modes[i]);
                System.out.println("   " + names[i] + " ⇒ 耗电=" + split[0] + " 发电=" + split[1]
                        + " 总份数=" + (split[0] + split[1])
                        + " ；⑧ 报红 " + (red.isEmpty() ? "0 条（= 检查器坏了）" : red.size() + " 条"));
                for (final String line : red) {
                    System.out.println("      ✗ " + line);
                }
            }
            final List<String> redProd = productionSplitProblems();
            System.out.println("   生产入口（应 0 条）= " + (redProd.isEmpty() ? "0 条 ✔" : redProd.size() + " 条 ✗"));
            System.out.println("   生产口径三档 cap：4096/ΣA1024 ⇒ " + generationParallelCap(1024L, 4096L)
                    + " ；1024/ΣA1024 ⇒ " + generationParallelCap(1024L, 1024L)
                    + " ；1024/ΣA600 ⇒ " + generationParallelCap(600L, 1024L)
                    + " ；纯轮 ΣA0 ⇒ " + generationParallelCap(0L, 1024L));
            final int[] ranks = {allocationRank(OTHER_TYPE_MATTER_DECOMPRESSION, 2_147_483_648L),
                    allocationRank(TYPE_ID, RECIPE_BASE_EUT)};
            System.out.println("   生产口径 顺序 = " + java.util.Arrays.toString(allocationOrderConsumerFirst(ranks))
                    + " ；上一版 顺序 = " + java.util.Arrays.toString(
                    allocationOrderConsumerFirstForTest(ranks, false)));
            System.out.println();
            System.out.println("==== 判定：负对照生效（⑧ 变红）====");
            return;
        }

        if (breakTypeGate) {
            // 🔴 负对照（判据 ④ 第一条）：去掉类型判断 ⇒ ③ 必须报红。这里把红的那几行【原文】打出来。
            final List<String> red = otherTypeProblems(false);
            System.out.println("---- 负对照 A：把类型判断去掉（对所有类型清零 EU）----");
            System.out.println("③ 的判定结果（期望：非空，即报红）= " + (red.isEmpty() ? "空（= 检查器坏了）" : red.size() + " 条"));
            for (final String line : red) {
                System.out.println("   ✗ " + line);
            }
            System.out.println();
            System.out.println("==== 判定：负对照生效（③ 变红）====");
            return;
        }
        if (breakHolder) {
            // 🔴 负对照（判据 ④ 第二条）：从 field holder 里拿掉 UUID ⇒ 必须报红。
            System.out.println("---- 负对照 B：把 " + GuixuVisionCoreMachine.FIELD_VISION_POOL_UUID
                    + " 从 field holder 里拿掉 ----");
            System.out.println("真实 holder（期望绿）= " + (holderPositiveProblems().isEmpty() ? "绿（无问题）"
                    : "红：" + holderPositiveProblems()));
            final String red = holderRedExceptionMessage();
            System.out.println("拿掉之后（期望红）= " + (red == null ? "空（= 检查器坏了）" : "抛异常：" + red));
            System.out.println();
            System.out.println("==== 判定：负对照生效（assertFieldHolderComplete 会抛异常）====");
            return;
        }

        System.out.print(selfTest());
        if (wantHolder) {
            System.out.println();
            System.out.println(holderSelfTest());
        } else {
            System.out.println();
            System.out.println("（跳过字段 holder 自检：加 --holder 并把 libs/*.jar 放进 classpath 才跑）");
        }
        System.out.println();
        System.out.println("==== 判定：全绿 ====");
    }

    private static boolean has(@Nullable String[] args, @NotNull String flag) {
        if (args == null) {
            return false;
        }
        for (final String a : args) {
            if (flag.equals(a)) {
                return true;
            }
        }
        return false;
    }
}
