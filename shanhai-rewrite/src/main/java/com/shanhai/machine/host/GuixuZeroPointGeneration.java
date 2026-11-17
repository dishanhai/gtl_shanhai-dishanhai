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
        final long p = Math.max(1L, parallel);
        return new Readings(baseEut, p, 1L, p, product(baseEut, p, gateBonus), coefficient(gateBonus),
                gateBonus, SOURCE_PARALLEL_DATA);
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

        Readings(long baseEut, long realParallels, long batchSize, long parallel, BigInteger product,
                 BigDecimal coefficient, int gateBonus, @NotNull String source) {
            this.baseEut = baseEut;
            this.realParallels = realParallels;
            this.batchSize = batchSize;
            this.parallel = parallel;
            this.product = product;
            this.coefficient = coefficient;
            this.gateBonus = gateBonus;
            this.source = source;
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
     * </pre>
     */
    public static void main(String[] args) {
        final boolean wantHolder = has(args, "--holder");
        final boolean breakTypeGate = has(args, "--break-type-gate");
        final boolean breakHolder = has(args, "--break-holder");
        final boolean breakCoefficient = has(args, "--break-coefficient");

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
