package com.shanhai.common.recipe;

/**
 * 🔴 <b>「并行份数 × 每份数量」这一乘的<b>分档判据 + 饱和算术 + 证据行格式</b>，零 Minecraft / GT 依赖。</b>
 *
 * <h2>它治的病（一句话）</h2>
 * 上游 gtladditions 的 {@code RecipeCalculationHelper#multipleRecipe(GTRecipe, long)} 在把并行份数
 * 乘到内容数量上时做的是 <b>{@code l2d}</b>（{@code [字节码] multipleRecipe 偏移 26 lload_2 / 27 l2d}）
 * ⇒ {@code ContentModifier.multiplier(double)} ⇒ gtlcore {@code ContentModifierMixin#apply} 在
 * {@code useFraction = false} 时返回 <b>{@code Double}</b>（偏移 {@code 32/45}）
 * ⇒ <b>{@code p ≥ 2^53} 起数量被量化到 2 的倍数、超界饱和到 {@code Long.MAX}</b>
 * （而<b>份数</b>经 {@code setRealParallels(long)} 是精确的 —— 两者在那一行分叉）。
 *
 * <h2>分档表（本类唯一的决策，也是本类的全部价值）</h2>
 * <pre>
 *   p ≤ Integer.MAX_VALUE (2^31−1)      ⇒ {@link Tier#INT}     老路径（逐位不变）
 *   p ≤ 2^53 (9,007,199,254,740,992)    ⇒ {@link Tier#LONG}    维持现状（long 份数 + double 乘）
 *   p &gt; 2^53                          ⇒ {@link Tier#PRECISE} 精确长整数乘（{@code preciseMultiplier(long)}）
 * </pre>
 * 阈值取 <b>2^53</b> 而不是 2^62 的理由：double 的尾数是 53 位 ⇒ {@code (double)p} 在
 * {@code p ≥ 2^53} 时连<b>整数本身</b>都不再精确，乘积的量化是<b>结构性的</b>，不是"误差大一点"。
 * 2^53 以下 {@code (double)p} 精确，乘积只有最后一次舍入 ⇒ 与老行为一致，
 * 也就满足本工程「{@code p ≤ Integer.MAX_VALUE} 的原路径必须逐位不变」这条硬约束。
 *
 * <h2>🔴 为什么这段算术必须住在「纯 JDK」类里</h2>
 * 本项目纪律：「凡我自己写的检查脚本报出的结果（PASS 或 FAIL），采信前必须先证明脚本自己是对的」。
 * 若把判据/饱和写在带 Minecraft 依赖的类里，离线自证就只能<b>照抄一遍</b>算术 ——
 * 那"抄错的是脚本还是生产代码"就永远分不清（2026-09-20 同族撞了 7 次，全是<b>检查器自己</b>的假设错）。
 * ⇒ 本类只 import {@code java.*}，{@code javac --release 17} 可单独编译并离线驱动；
 * <b>游戏里跑的是同一份字节码</b>，不存在第二份判据。
 *
 * <h2>⚠️ 本类<b>不</b>负责真正去乘配方内容</h2>
 * 真正的乘仍走上游 {@code recipe.copy(ContentModifier, false)}（精确档传
 * {@code IAdvancedContentModifier.preciseMultiplier(long)}）—— 本工程<b>不自研</b>乘法实现，
 * 也不自己构造 {@code LongIngredient}（那会绕过 gtlcore 的
 * {@code ItemRecipeCapabilityMixin#copyWithModifier}，把 {@code SizedIngredient} 的 int 数量重新压回 int）。
 * 本类只做三件事：<b>判档</b>（{@link #tierOf}）、<b>预测结果</b>（{@link #multiply}，供日志与离线自证）、
 * <b>拼证据行</b>（{@link #line}）。
 */
public final class ShanhaiLongParallelMultiply {

    private ShanhaiLongParallelMultiply() {}

    /** 引擎链的真实 int 天花板（{@code Integer.MAX_VALUE} = 2,147,483,647）。 */
    public static final long INT_CEILING = 2_147_483_647L;

    /**
     * <b>double 的精确整数上界 = 2^53 = 9,007,199,254,740,992</b>。
     *
     * <p>{@code p ≤ 2^53} 时 {@code (double) p} 与 {@code p} 同值；{@code > 2^53} 起
     * <b>连 p 本身都被舍入</b>（例：{@code 2^53 + 1} 的 double 就是 {@code 2^53}）
     * ⇒ 那一档必须换精确长整数乘法。
     */
    public static final long TWO_POW_53 = 9_007_199_254_740_992L;

    /** 「没有样例 / 读不出数值」的哨兵（只在<b>日志</b>里用，不参与任何算术）。 */
    public static final long NO_SAMPLE = -1L;

    // ═══════════════════════════════ 分档 ═══════════════════════════════

    /**
     * 三档。<b>枚举名 = 日志里 {@code tier=} 的取值</b>（{@link #token()}），
     * 保证"判档"与"证据行"共用同一个来源，不会各写一份字符串。
     */
    public enum Tier {
        /** {@code p ≤ 2^31−1}：老路径，<b>逐位不变</b>。 */
        INT("int"),
        /** {@code (2^31−1, 2^53]}：维持现状（long 份数 + double 乘）。 */
        LONG("long"),
        /** {@code > 2^53}：精确长整数乘（{@code preciseMultiplier(long)}）。 */
        PRECISE("precise");

        private final String token;

        Tier(String token) {
            this.token = token;
        }

        /** 写进日志 {@code tier=} 的那个词。 */
        public String token() {
            return token;
        }
    }

    /**
     * 判档 —— <b>本任务的全部决策都收敛在这一处</b>。
     *
     * @param parallel 并行份数（调用方已由 {@code IParallelLogic.getMaxParallel} 钳过）
     * @return 该走哪条路；{@code parallel ≤ 0} 归 {@link Tier#INT}（上游对 {@code ≤ 1} 原样返回，不必精确）
     */
    public static Tier tierOf(long parallel) {
        if (parallel <= INT_CEILING) {
            return Tier.INT;
        }
        if (parallel <= TWO_POW_53) {
            return Tier.LONG;
        }
        return Tier.PRECISE;
    }

    /** 是否落在"必须换精确乘"的那一档（= {@code p > 2^53}）。 */
    public static boolean needsPrecise(long parallel) {
        return parallel > TWO_POW_53;
    }

    // ═══════════════════════════════ 算术（全部饱和） ═══════════════════════════════

    /**
     * <b>老路径的精确复刻</b>：{@code ContentModifier.multiplier((double) p).apply(Long.valueOf(per))}。
     *
     * <p>上游那一路是 {@code Double.valueOf((double) per * (double) p + 0.0)}，
     * 再被 {@code copyWithModifier} 用 {@code Number.longValue()}（= {@code (long) doubleValue()}）
     * 窄化 ⇒ <b>JLS 5.1.3 的 double→long：NaN → 0，越界饱和到 Long.MAX / Long.MIN</b>，
     * 静默无异常。本方法用<b>同一个 Java 转换</b>表达它，因此与老路径<b>逐位相同</b>
     * （不是"我用 BigDecimal 模拟了一下"）。
     */
    public static long legacyDoubleMultiply(long per, long parallel) {
        return (long) ((double) per * (double) parallel);
    }

    /**
     * <b>精确长整数乘 + 饱和</b>（{@code Long.MAX} / {@code Long.MIN}，绝不回绕）。
     *
     * <p>语义与 gtlcore {@code ContentModifierMixin#gtlcore$saturatedFraction(long)} <b>逐值一致</b>：
     * 那里是 {@code BigInteger(per).multiply(BigInteger(numerator)).divide(BigInteger(denominator))}
     * 再 {@code compareTo(Long.MAX)} / {@code compareTo(Long.MIN)} 后 {@code longValue()}；
     * {@code preciseMultiplier(long n)} 的 {@code denominator 恒为 1}（{@code setDivision(n, 1L)}）
     * ⇒ 除法不损失，结果 = "精确乘积，再饱和到 long 两端"。
     */
    public static long preciseMultiply(long per, long parallel) {
        try {
            return Math.multiplyExact(per, parallel);
        } catch (ArithmeticException overflow) {
            return (per < 0L) == (parallel < 0L) ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    /** 这次乘法会不会饱和（只用于统计 {@code sat=}，不做任何换算）。 */
    public static boolean wouldSaturate(long per, long parallel) {
        if (per == 0L || parallel == 0L) {
            return false;
        }
        // 精确判据：|per| > Long.MAX / |parallel|
        final long absPer = per == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(per);
        final long absMul = parallel == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(parallel);
        return absPer > Long.MAX_VALUE / absMul;
    }

    /**
     * <b>按档取结果</b>（生产代码的"预测器"，也是离线自证里被断言的那一份）。
     *
     * <p>⚠️ 生产路径<b>不</b>用它写配方（那是上游 {@code recipe.copy(ContentModifier, false)} 的事），
     * 它只用来回答两个问题：<b>这个条目会不会饱和</b>（{@code sat=}）、<b>乘完应该是多少</b>（{@code out=}）。
     * 对 {@link Tier#INT} / {@link Tier#LONG} 它与老路径<b>同一条表达式</b>；
     * 对 {@link Tier#PRECISE} 它与 gtlcore 的精确支<b>同值</b>（见 {@link #preciseMultiply}）。
     */
    public static long multiply(long per, long parallel) {
        return tierOf(parallel) == Tier.PRECISE
                ? preciseMultiply(per, parallel)
                : legacyDoubleMultiply(per, parallel);
    }

    // ═══════════════════════════════ 证据行的取样与拼装 ═══════════════════════════════

    /**
     * 一次乘法的<b>取样摘要</b>（喂给 {@code [SHANHAI-INFPAR]} 那三格 {@code per= / out= / sat=}）。
     *
     * <p>{@code sat} 的口径写死为：<b>对每一个"能量出数值"的内容条目，按精确乘积判断它是否会饱和，
     * 会计数</b>（不区分物品/流体/EU —— 只要那个条目能读出 long 就参与）。
     * 读不出数值的条目（例如自定 capability 的 Object）<b>不参与</b>，也不被当作"没饱和"。
     */
    public static final class Summary {

        /** 参与统计的条目数（能量出数值的）。 */
        public final int entries;
        /** 会饱和的条目数（{@code > 0} ⇒ 这一轮有数量被压到 {@code Long.MAX/MIN}）。 */
        public final int saturated;
        /** 样例的"每份数量"（= 乘前）；{@link #NO_SAMPLE} = 一个样例都取不到。 */
        public final long samplePer;
        /** 样例的"乘后数量"；{@link #NO_SAMPLE} = 取不到样例。 */
        public final long sampleOut;

        public Summary(int entries, int saturated, long samplePer, long sampleOut) {
            this.entries = entries;
            this.saturated = saturated;
            this.samplePer = samplePer;
            this.sampleOut = sampleOut;
        }

        public boolean hasSample() {
            return samplePer != NO_SAMPLE;
        }

        @Override
        public String toString() {
            return "Summary{entries=" + entries + ", saturated=" + saturated
                    + ", samplePer=" + samplePer + ", sampleOut=" + sampleOut + '}';
        }
    }

    /**
     * 对一批"每份数量"做取样统计。
     *
     * @param amounts  每个内容条目的"每份数量"（{@link #NO_SAMPLE} = 这条读不出来，跳过）
     * @param parallel 并行份数
     */
    public static Summary summarize(long[] amounts, long parallel) {
        if (amounts == null || amounts.length == 0) {
            return new Summary(0, 0, NO_SAMPLE, NO_SAMPLE);
        }
        int entries = 0;
        int saturated = 0;
        long samplePer = NO_SAMPLE;
        for (long per : amounts) {
            if (per == NO_SAMPLE) {
                continue;
            }
            entries++;
            if (wouldSaturate(per, parallel)) {
                saturated++;
            }
            // 样例取【第一个正数】（=0 的条目排不进"每份数量"的直觉，但也不丢：全都为 0 时退回第一个读数）
            if (samplePer == NO_SAMPLE || (samplePer == 0L && per > 0L)) {
                samplePer = per;
            }
        }
        if (samplePer == NO_SAMPLE) {
            return new Summary(entries, saturated, NO_SAMPLE, NO_SAMPLE);
        }
        return new Summary(entries, saturated, samplePer, multiply(samplePer, parallel));
    }

    /**
     * <b>拼一行证据（格式是本任务的验收判据，别改顺序、别加空格）</b>：
     * <pre>
     *   [SHANHAI-INFPAR] p=&lt;并行份数&gt; tier=long|precise|precise-unavailable per=&lt;每份数量样例&gt;
     *                    out=&lt;乘后数量样例&gt; sat=&lt;饱和条目数&gt; why=&lt;none|saturate|no-precise&gt;
     * </pre>
     * 实际落盘是<b>一行</b>（上面折行只为文档排版）。
     *
     * @param tier   {@code long} / {@code precise} / {@code precise-unavailable}
     * @param why    {@code none} / {@code saturate} / {@code no-precise}
     */
    public static String line(long parallel, String tier, String why, long per, long out, int sat) {
        return "[SHANHAI-INFPAR] p=" + parallel
                + " tier=" + tier
                + " per=" + (per == NO_SAMPLE ? "n/a" : Long.toString(per))
                + " out=" + (out == NO_SAMPLE ? "n/a" : Long.toString(out))
                + " sat=" + sat
                + " why=" + why;
    }

    /** {@code why} 的三个取值（同样收敛在一处，别在调用点写字符串字面量）。 */
    public static final String WHY_NONE = "none";
    public static final String WHY_SATURATE = "saturate";
    public static final String WHY_NO_PRECISE = "no-precise";

    /** {@code tier=} 里"精确通道不可用、已退回 double"的那个取值。 */
    public static final String TIER_PRECISE_UNAVAILABLE = "precise-unavailable";

    /**
     * 按"这一轮到底发生了什么"给出 {@code why=}。
     *
     * <pre>
     *   退回 double（preciseMultiplier 调不通） ⇒ no-precise   （优先级最高：这是降级，必须看得见）
     *   有条目饱和                            ⇒ saturate
     *   其余                                  ⇒ none
     * </pre>
     */
    public static String whyOf(boolean preciseUnavailable, int saturated) {
        if (preciseUnavailable) {
            return WHY_NO_PRECISE;
        }
        return saturated > 0 ? WHY_SATURATE : WHY_NONE;
    }

    // ═══════════════════════════════ 落盘判定（限流的唯一实现） ═══════════════════════════════

    /**
     * <b>「这一轮该不该落盘 / 该落哪一行 / 该用什么级别」的判定结果</b>。
     *
     * <p>{@code line} 已经拼好（格式见 {@link #line}），调用方只负责 {@code LOGGER.info/warn}。
     */
    public static final class Emit {

        /** 拼好的 {@code [SHANHAI-INFPAR]} 行。 */
        public final String line;
        /** {@code tier=} 的取值。 */
        public final String tier;
        /** {@code why=} 的取值。 */
        public final String why;
        /** true = 异常档（饱和 / 精确通道不可用）⇒ 调用方<b>必须</b>用 WARN，不许降成 INFO。 */
        public final boolean urgent;
        /** 自上次落盘以来被闸门挡下的次数（写进这一行尾 ⇒「静默了多少」仍然看得见）。 */
        public final long suppressed;

        Emit(String line, String tier, String why, boolean urgent, long suppressed) {
            this.line = line;
            this.tier = tier;
            this.why = why;
            this.urgent = urgent;
            this.suppressed = suppressed;
        }

        @Override
        public String toString() {
            return "Emit{urgent=" + urgent + ", suppressed=" + suppressed + ", line=" + line + '}';
        }
    }

    /** {@code [SHANHAI-INFPAR]} 的默认限流窗（毫秒）：同一形态最多这么久复述一次。 */
    public static final long DEFAULT_MIN_INTERVAL_MILLIS = 5_000L;

    /**
     * <b>决定这一次要不要打、打哪一行 —— 本工程 {@code [SHANHAI-INFPAR]} 的唯一判定实现。</b>
     *
     * <h2>为什么要抬进这个纯核（而不是写在 PrimordialRecipeEffects 里）</h2>
     * 这是本任务的<b>验收判据</b>（{@code tier=precise} 出现 / {@code sat>0} 能被 grep / 同一形态 5 秒最多 1 行）。
     * 判定若住在带 Minecraft 依赖的类里，离线自证只能照抄一遍 ⇒ 自查「绿」不构成证据。
     * 抬进来之后，<b>游戏里跑的就是这里被断言过的那份字节码</b>。
     *
     * <h2>判定表</h2>
     * <pre>
     *   p ≤ Integer.MAX_VALUE                ⇒ 返回 null（老路径：连判据都不进，一行都不打）
     *   形态（tier=…|why=…）已落过盘，非异常档 ⇒ 返回 null（只累计静默数）
     *   why = none（>2^53 走了精确路，无饱和）  ⇒ 该形态只打一次 INFO
     *   why = saturate（有条目被压到 Long.MAX） ⇒ 每 minInterval 最多复述 1 行，WARN
     *   why = no-precise（精确通道不可用）      ⇒ 每 minInterval 最多复述 1 行，WARN，tier=precise-unavailable
     * </pre>
     *
     * @param gate              闸门（生产侧是同一个静态闸门；测试可自己 new）
     * @param parallel          并行份数
     * @param tier              {@link #tierOf} 的判档（{@code preciseUnavailable} 为 true 时被覆盖成 unavailable）
     * @param preciseUnavailable 精确通道是否调用失败、已退回 double
     * @param summary           取样摘要
     * @param nowMillis         当前墙钟（由调用方给 ⇒ 可离线喂假时间）
     * @param minIntervalMillis 异常形态复述的最小间隔
     * @return 该打 ⇒ 结果；不该打 ⇒ {@code null}
     */
    public static Emit decide(com.shanhai.common.log.ShanhaiLogThrottle.Gate gate, long parallel, Tier tier,
                              boolean preciseUnavailable, Summary summary,
                              long nowMillis, long minIntervalMillis) {
        if (gate == null || summary == null || parallel <= INT_CEILING) {
            // 🔴 p ≤ int：老路径。这里【一行都不打】—— 不是"打了再吞掉"，是根本没走到。
            return null;
        }
        final String tierToken = preciseUnavailable ? TIER_PRECISE_UNAVAILABLE : tier.token();
        final String why = whyOf(preciseUnavailable, summary.saturated);
        final String shape = "tier=" + tierToken + "|why=" + why;
        // 异常档（饱和 / 精确通道不可用）按窗口复述；正常档同一形态只打一次。
        final boolean urgent = !WHY_NONE.equals(why);
        if (!gate.changed(shape, urgent, nowMillis, minIntervalMillis)) {
            return null;
        }
        return new Emit(line(parallel, tierToken, why,
                summary.hasSample() ? summary.samplePer : NO_SAMPLE,
                summary.hasSample() ? summary.sampleOut : NO_SAMPLE,
                summary.saturated), tierToken, why, urgent, gate.takeSuppressed());
    }
}
