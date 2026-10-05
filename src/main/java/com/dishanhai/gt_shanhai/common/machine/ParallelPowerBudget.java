package com.dishanhai.gt_shanhai.common.machine;

import java.util.Locale;

/**
 * 🔴 <b>「按电力算并行」的纯计算核 —— 零 Minecraft 依赖（可被 javac 单独编译、离线跑判据）。</b>
 *
 * <h2>用户原话（逐字，这就是规格）</h2>
 * <blockquote>「好的，我的方案就是通过计算能源仓可以提供的总功率来确定并行数，
 * 注意机器是可以放2个能源仓的，还可以放2个不同的能源仓的，所以你需要仔细计算，
 * 我们通过总功率和此配方的功率来计算并行数，
 * （若是无线电网输入终端，或者创造能源仓则直接把并行拉到最大，这个就不需要我们算了）」</blockquote>
 * 以及后续两条补充（逐字）：
 * <blockquote>「经过我的测试，是需要算引擎的耗能乘数的」</blockquote>
 * <blockquote>「那两组测试数据我是没有开引擎耗电减免的」</blockquote>
 *
 * <h2>算式（三行，全在这里）</h2>
 * <pre>
 *   k  = 每并行耗电 = 配方每 tick 耗电 × 引擎耗能乘数 × N5 减免系数
 *        🔴 2026-10-02 第七轮起：k 以【毫 EU/t】的 long 参与运算（kMilli = k × 1000），
 *           <b>中途一律不许 round</b>；唯一一次取整发生在最后一步（由总功率算并行时向下取整）。
 *           实例：42 × 0.05 = 2.1 ⇒ 2100 毫（旧实现 round 成 2 ⇒ 并行偏大 5% ⇒「电力输入不足」）。
 *   Pe = 能源仓总功率 = Σ 各仓的【稳态每 tick 可付出 EU】               （见 HatchKind）
 *   p  = min( max(1, floor(Pe × 1000 ÷ k毫)), 原本上限 )                （Pe 无限 ⇒ 直接取原本上限）
 * </pre>
 *
 * <h2>🔴 各仓的「稳态每 tick 可付出」（字节码实证，出处见 EnergyHatchPower 的类注释）</h2>
 * <table border="1">
 *   <tr><th>仓</th><th>实现容器</th><th>稳态每 tick</th><th>为什么</th></tr>
 *   <tr><td>无线电网输入终端</td><td>{@code gtladditions NetworkEnergyContainer}</td>
 *       <td><b>∞</b></td><td>容量 {@code Long.MAX_VALUE}，{@code getEnergyStored()} 直接返回全队池余额，
 *           {@code handleRecipeInner} 永不报缺口 ⇒ 特例，拉满</td></tr>
 *   <tr><td>创造模式能源仓</td><td>{@code gtlcore InfinityEnergyContainer}</td>
 *       <td><b>∞</b></td><td>{@code getEnergyStored()} 硬编码返回容量（{@code Long.MAX_VALUE}），
 *           {@code handleRecipeInner} 恒返回 null ⇒ 特例，拉满</td></tr>
 *   <tr><td>电网能源仓</td><td>{@code gtmadvancedhatch NoConsumeNotifiabbleEnergyContainer}</td>
 *       <td><b>{@code V × 16 × A}</b></td>
 *       <td>{@code useEnergy()} 只要存量 ≠ 容量就把存量<b>直接置成容量</b>（不是 {@code stored + V×A}）
 *           ⇒ 稳态 = 容量 = {@code V×16×A}</td></tr>
 *   <tr><td>普通 / 无线能源仓</td><td>{@code gtceu NotifiableEnergyContainer}</td>
 *       <td><b>{@code V × A}</b></td>
 *       <td>{@code useEnergy()} 每 tick 最多补 {@code min(容量 − 存量, V×A)} ⇒ 容量 {@code V×16×A}
 *           只是 16 tick 的缓冲，稳态只能是 {@code V×A}</td></tr>
 * </table>
 *
 * <h3>⚠️ 为什么 ④无线 与 ⑤普通 合并成一档 {@code BUFFERED}</h3>
 * 两者<b>用的是同一个容器类</b> {@code com.gregtechceu.gtceu.api.machine.trait.NotifiableEnergyContainer}
 * （gtmthings 无线仓的 {@code createEnergyContainer} 走的就是 {@code NotifiableEnergyContainer.receiverContainer}），
 * 而两者的<b>稳态算式逐字相同</b>（都是 {@code V×A}）。规格里的"④/⑤ 分开判"是为了
 * <b>判定顺序必须从具体到一般</b>（因为电网仓 {@code extends} 无线仓）；在本类里，
 * 这个顺序由 {@link #knownKindByName(String)} 沿<b>超类链</b>从具体往一般走天然满足，
 * 而 ④/⑤ 在功率口径上确实<b>不可区分也不需区分</b> ⇒ 合并成一档才是诚实的写法
 * （分成两档会印出一个容器根本给不出的区别 —— 本工程红线：宁可缺，不可假）。
 *
 * <h2>⚠️ 不能"用容量反推电流"</h2>
 * 普通/无线仓的容量也是 {@code V×16×A}（同一个 {@code V×16} 因子）⇒ 拿容量当"可付出"会
 * <b>高估 16 倍</b>。本类一律走 {@code getInputVoltage() × getInputAmperage()} 这两个量。
 *
 * <h2>🔴 为什么"一个除法就够"（乘数不随并行 p 变化 —— 已逐条核到字段级）</h2>
 * <pre>
 *   getEuMultiplier() = (维护机 != null ? 维护机.getDurationMultiplier() : 1.0)
 *                       × reductionEUt × reductionDuration
 *   · reductionEUt / reductionDuration：{@code private final double}，{@code putfield} 只出现在
 *     MutableRecipesLogic 的<b>构造器</b>里，默认 {@code dconst_1, dconst_1} = 1.0/1.0；
 *     本工程两个 RecipeLogic 都走 {@code super(machine)} 单参路径 ⇒ 恒 1.0。
 *   · 维护机.getDurationMultiplier()：gtlcore {@code AutoConfigurationMaintenanceHatchPartMachine}
 *     偏移 300 是<b>纯 getter</b>（{@code getfield; freturn}），字段只被构造器（1.0f）、
 *     {@code setDurationMultiplier}（{@code Mth.clamp(v, 0.2f, 1.2f)}）、以及维护故障的
 *     inc/dec（±0.01）写过 ⇒ <b>配方与并行数都不是它的输入</b>。
 *   ⇒ 乘数在【一轮配方内】是常数 ⇒ 不需要解方程、不需要取最坏迭代，一次除法即可。
 * </pre>
 *
 * @see EnergyHatchPower 把机器上的仓读成 {@link #steadyPowerPerTick} 的实参（含 Minecraft 依赖的那一半）
 */
public final class ParallelPowerBudget {

    private ParallelPowerBudget() {}

    /**
     * 🔴 <b>「无限功率」哨兵</b>（终端 / 创造仓的总功率）。
     *
     * <p>取值 = {@code Long.MAX_VALUE}，与 {@code GTValues.VEX[30]}、以及两个无限容器的容量
     * <b>逐位相同</b> ⇒ 就算某天真有人拿它去做除法，结果也是"天文数字"而不是负数（不会反向）。
     */
    public static final long UNLIMITED = Long.MAX_VALUE;

    /** 电网仓的容量倍数（容量 = {@code V × 16 × A}）。 */
    public static final int NET_CAPACITY_FACTOR = 16;

    /**
     * 🔴 <b>「每并行耗电 k」的定点标度 —— k 一律以【毫 EU/t】的 long 参与运算
     * （{@code kMilli = k × 1000}）。</b>
     *
     * <p>引入理由（2026-10-02 第七轮，用户实机 bug）：开了 N5 耗电减免之后 k 是小数
     * （实例：{@code 42 × 0.05 = 2.1}），旧实现中途 {@code Math.round} 成整数 ⇒ 并行偏大 5%
     * ⇒ 整机耗电超 P ⇒「电力输入不足」。详见 {@link #perParallelMilliCost} 的 javadoc。
     */
    public static final long MILLI = 1000L;

    /**
     * <b>能源仓的供能口径</b>（判定顺序 = 从具体到一般，见类的 javadoc）。
     *
     * <p>{@link #order()} 是给日志用的：一行里要能看出"这一档是从哪条链上认出来的"。
     */
    public enum HatchKind {

        /** ⑥ 无线电网输入终端（gtladditions）：特例，拉满。 */
        NET_TERMINAL("无线电网输入终端", 0, true),

        /** ⑦ 创造模式能源仓（gtmthings + gtlcore 改写）：特例，拉满。 */
        CREATIVE("创造模式能源仓", 1, true),

        /** ②④ 电网能源仓（gtmadvancedhatch）：稳态 = 容量 = {@code V×16×A}。 */
        NET("电网能源仓", 2, false),

        /** ①③ 普通 / 无线能源仓（gtceu / gtmthings）：稳态 = {@code V×A}。两者容器同型、算式同上。 */
        BUFFERED("普通/无线能源仓", 3, false),

        /** 既不认类名、容量也不是无限 ⇒ 按最保守的 {@code V×A} 兜底（与 ①③ 同口径）。 */
        UNKNOWN("未识别能源仓", 4, false);

        private final String displayName;
        private final int order;
        private final boolean unlimited;

        HatchKind(String displayName, int order, boolean unlimited) {
            this.displayName = displayName;
            this.order = order;
            this.unlimited = unlimited;
        }

        /** 中文名（日志 / 面板用）。 */
        public String displayName() {
            return displayName;
        }

        /** 判定顺序（0 = 最具体）。与规格里 ①→⑤ 的顺序逐条对应。 */
        public int order() {
            return order;
        }

        /** 是否属于"特例 ⇒ 不用算，直接拉满"。 */
        public boolean isUnlimited() {
            return unlimited;
        }
    }

    /** 总功率是不是"无限"（终端 / 创造仓命中过）。 */
    public static boolean isUnlimitedPower(long totalPowerPerTick) {
        return totalPowerPerTick == UNLIMITED;
    }

    /**
     * <b>按容器类名认仓型</b>——判定顺序从具体到一般（超类链天然如此）。
     *
     * <h2>为什么按<b>容器</b>而不是按<b>部件机器</b>认</h2>
     * <ul>
     *   <li>四个要区分的口径里，只有 ②④ 与 ⑥⑦ 需要"认出是谁"，而<b>三者各有自己的容器类</b>；
     *       ④无线 / ⑤普通同容器同算式（见类 javadoc），本来就不需要靠机器类区分。</li>
     *   <li>🔴 <b>GTMAdvancedHatch 的 jar 不在 {@code libs/}</b>（只在测试实例的 mods 里）
     *       ⇒ 对它的类<b>不能有编译期引用</b>，否则本工程直接编译失败。</li>
     *   <li>按容器认 ⇒ 与"这台机器上放的是谁家的仓"解耦，将来多一个 mod 的仓也走同一条路
     *       （认不出来就落到 {@link HatchKind#UNKNOWN} 的保守档，而不是崩）。</li>
     * </ul>
     *
     * @param className 正在检查的那个类（调用方沿 {@code getSuperclass()} 逐级上走）
     * @return 认得出来就返回仓型；<b>返回 {@code null} 表示"继续往父类找"</b>
     */
    public static HatchKind knownKindByName(String className) {
        if (className == null) {
            return null;
        }
        switch (className) {
            // 🔴 ⑦ 创造模式能源仓（gtlcore 把 gtmthings 的容器换成了它）—— 必须排在 NotifiableEnergyContainer 之前
            case "org.gtlcore.gtlcore.integration.gtmt.InfinityEnergyContainer":
                return HatchKind.CREATIVE;
            // 🔴 ⑥ 无线电网输入终端（gtladditions）—— 它【不是】 NotifiableEnergyContainer 的子类，顺序其实无关，
            //    仍按"从具体到一般"写，免得将来上游改了继承链
            case "com.gtladd.gtladditions.common.machine.trait.NetworkEnergyContainer":
                return HatchKind.NET_TERMINAL;
            // 🔴 ②④ 电网能源仓（GTMAdvancedHatch）—— 它 extends NotifiableEnergyContainer
            //    ⇒ 必须排在父类之前，否则会被认成"普通仓"而少算 16 倍
            case "com.xingmot.gtmadvancedhatch.api.NoConsumeNotifiabbleEnergyContainer":
                return HatchKind.NET;
            // ①③ 普通 / 无线能源仓（gtceu 本体 + gtmthings 无线仓共用这一个容器类）
            case "com.gregtechceu.gtceu.api.machine.trait.NotifiableEnergyContainer":
                return HatchKind.BUFFERED;
            default:
                return null;
        }
    }

    /**
     * <b>单个仓的稳态每 tick 可付出 EU</b>。
     *
     * @param kind     {@link HatchKind}
     * @param voltage  {@code IEnergyContainer.getInputVoltage()}（<b>不要</b>拿容量反推）
     * @param amperage {@code IEnergyContainer.getInputAmperage()}
     * @return {@link #UNLIMITED}（特例）或 {@code ≥ 0} 的 EU/t
     */
    public static long steadyPowerPerTick(HatchKind kind, long voltage, long amperage) {
        if (kind == null) {
            kind = HatchKind.UNKNOWN;
        }
        if (kind.isUnlimited()) {
            return UNLIMITED;
        }
        // 电压/电流读不到（还没成型、或者不是电气仓）⇒ 这个仓贡献 0，不参与求和。
        if (voltage <= 0L || amperage <= 0L) {
            return 0L;
        }
        if (kind == HatchKind.NET) {
            // 容量 = V × 16 × A，而电网仓的稳态【就是】容量
            return saturatingMultiply(saturatingMultiply(voltage, (long) NET_CAPACITY_FACTOR), amperage);
        }
        // BUFFERED / UNKNOWN：稳态 = V × A
        return saturatingMultiply(voltage, amperage);
    }

    /**
     * <b>把多个仓串起来求和</b>（饱和；只要有一个是无限 ⇒ 整体立刻无限）。
     *
     * <p>「多仓能否相加」的依据：{@code RecipeRunner.handleContentsInternal} 的非 distinct 支路把
     * 上一个 handler 的<b>剩余需求</b>写回 {@code content.content} 再喂给下一个 handler
     * ⇒ <b>各仓串联承担</b>，总上限 = Σ 各仓可交付。
     *
     * @param runningTotal 已经累起来的部分（初值 0）
     */
    public static long accumulate(long runningTotal, HatchKind kind, long voltage, long amperage) {
        if (isUnlimitedPower(runningTotal)) {
            return UNLIMITED;
        }
        final long add = steadyPowerPerTick(kind, voltage, amperage);
        if (isUnlimitedPower(add)) {
            return UNLIMITED;
        }
        return saturatingAdd(runningTotal, add);
    }

    /**
     * <b>每并行耗电 k</b> = 配方每 tick 耗电 × 引擎耗能乘数 × N5 减免系数（钳 {@code ≥ 1}）。
     *
     * <h2>为什么乘数必须算进去（用户实测确认）</h2>
     * 用户原话逐字：「经过我的测试，是需要算引擎的耗能乘数的」。
     * 不算的后果是 <b>k 偏小 ⇒ p 偏大 ⇒ 又跑不动</b>（正是他要解决的那个毛病）。
     *
     * <h2>三个因子的来源</h2>
     * <ul>
     *   <li><b>配方每 tick 耗电</b>：{@code RecipeHelper.getInputEUt(原配方)}（= {@code tickInputs} 里 EU 的求和）。
     *       引擎路径<b>不含超频</b>。</li>
     *   <li><b>引擎耗能乘数</b>：{@code MutableRecipesLogic.getEuMultiplier()}（维护机时长倍率 ×
     *       {@code reductionEUt} × {@code reductionDuration}）。<b>不随并行 p 变化</b>（见类 javadoc）。</li>
     *   <li><b>N5 减免系数</b>：{@code PrimordialRecipeEffects.reductionFactor(gateBonus)}，
     *       即主机专属槽门控的耗能减免；未生效（{@code gateBonus ≤ 0}）时恒 {@code 1.0}。</li>
     * </ul>
     *
     * <h2>⚠️ 对账口径（用户 2026-10-02 补充：「那两组测试数据我是没有开引擎耗电减免的」）</h2>
     * 那两组实测（{@code 48} / {@code 3}）是在<b>乘数 = 1</b> 的条件下取得的
     * ⇒ 拿它们对账时<b>必须把乘数当 1</b>，否则就是"拿关了减免的数据去验开了减免的公式"（口径不一致 = 假结论）。
     *
     * @param recipeEutPerTick  原配方的每 tick 耗电（{@code RecipeHelper.getInputEUt}）
     * @param engineMultiplier  引擎耗能乘数（{@code getEuMultiplier()}）
     * @param n5Factor          N5 减免系数（{@code reductionFactor(gateBonus)}，未生效 = 1.0）
     * <h2>🔴🔴 2026-10-02 第七轮：本方法改名为 {@code perParallelMilliCost}，返回值从「整数 EU/t」
     * 改成「<b>毫 EU/t</b>」（{@code k × 1000}）—— 用户实机报的「电力输入不足」的真凶就是旧的
     * {@code Math.round(raw)}。</h2>
     *
     * <h2>🔴 真凶与用户实机读数（逐位对上）</h2>
     * <pre>
     *   用户那台模块：16A HV 电网能源仓 131,072 ＋ 4A ZPM 无线能源仓 524,288
     *     ⇒ P = 655,360 EU/t（实机日志原文逐字：「总功率 655,360 EU/t（2 个仓：电网能源仓×1 普通/无线能源仓×1）」）
     *   配方 42 EU/t；主机插满 64 个创造模块 ⇒ 门控等级 17 ⇒ N5 减免 = 0.050000000000000044
     *     ⇒ 真实 k = 42 × 0.05 = 2.1 EU/t
     *   ⛔ 旧实现（{@code Math.round}）：k = round(2.1) = 2
     *      ⇒ 并行 = floor(655,360 ÷ 2) = 327,680
     *      ⇒ 整机耗电 = 327,680 × 2.1 = 688,128 EU/t &gt; P = 655,360 EU/t
     *      ⇒ 机器报「电力输入不足」；Jade 实机读数「耗能 688.13K EU/t」与 688,128 <b>一位不差</b>
     *   ✅ 本实现：k = 2100 毫 EU/t ⇒ 并行 = floor(655,360 × 1000 ÷ 2100) = <b>312,076</b>
     *      ⇒ 整机耗电 = 312,076 × 2.1 = 655,359.6 ≤ P ✓
     *      ⇒ 且 312,077 × 2.1 = 655,361.7 &gt; P ⇒ <b>边界紧</b>（没有保守过头）
     * </pre>
     * <p>⇒ <b>铁律：取整只允许发生在【最后一步】（由总功率算并行数时向下取整），中途一律不许 round。</b>
     * 「改成 ceil 也不对」—— 用户已点明：{@code ceil(2.1) = 3} ⇒ 并行 218,453 ⇒ 整机 458,751，
     * 白白少用 30% 的电力。
     *
     * <h2>为什么是「毫」（1000 倍）而不是 double</h2>
     * ① 后面那条除法 {@link #floorScaledDiv(long, long)} 全程 long ⇒ <b>精确、无浮点边界风险</b>；
     * ② 0.001 EU/t 的分辨率对最贵的配方（9e15 EU/t 量级）也远超需要；
     * ③ 本类要能被裸 {@code javac} 单独编译跑判据 ⇒ 不留 double 在算式里最省心。
     *
     * <h2>🔴 返回值的三档阶梯（2026-10-02 第八轮：把"极小乘数"这一档写成明文 —— 用户裁决 ① 点名要）</h2>
     * <pre>
     *   factor（清洗后）        raw = recipeEutPerTick × factor
     *   ─────────────────────────────────────────────────────────────────────────────────
     *   ①  raw ≤ 1.0          ⇒ 返回 <b>MILLI = 1000 毫（= 1 EU/t）</b>
     *                            ⚠️ 「毫」这一层【根本没轮到】—— 0.0004 EU/t 的毫表示是 0.4 毫，
     *                            它被这一档拦下，结果既不是 0 毫、也<b>不是</b> 1 毫。
     *   ②  1.0 &lt; raw &lt; 9e18   ⇒ <b>四舍五入到毫，且保证不低估</b>（见 conservativeMilliRound）
     *                            恒 ≥ 1001 毫（因为 raw &gt; 1.0 ⇒ raw×1000 &gt; 1000）
     *   ③  raw ≥ 9e18         ⇒ Long.MAX_VALUE（天文档，交给 floorScaledDiv 的上界守门兜底）
     * </pre>
     * <p>⇒ <b>返回值恒 ≥ 1000 毫</b>；「0 毫」与「1 毫」这两档在结构上<b>不可达</b>
     * （方法末尾那个 {@code Math.max(1L, …)} 是死的，保留它只为将来改动兜底 —— {@code selfTest} ⑦ 有断言）。
     *
     * <h2>🔴 「比 0.001 EU/t 更小」到底怎么办（用户 2026-10-02 裁决 ① 的原问题）</h2>
     * <pre>
     *   例：乘数 0.0004（配方 1 EU/t）⇒ k真 = 0.0004 EU/t ⇒ 毫表示 = 0.4 毫
     *   ⇒ 本方法返回 <b>1000 毫（1 EU/t）</b>：先在 ① 档被"每并行耗电 ≥ 1 EU/t"的地板接住。
     *   ⇒ 「会不会被截成 0」 —— 不会（0 不可达）。
     *   ⇒ 「会不会保底成 1 毫」—— 不会（1 毫同样不可达）；地板是 1000 毫。
     * </pre>
     * 为什么地板是 <b>1 EU/t</b> 而不是 1 毫：依据不是"保守一点"，而是<b>引擎侧本来就有这道地板</b> ——
     * {@code PrimordialRecipeEffects#scaleEnergyInPlace} 写的是
     * {@code Math.max(1L, Math.round(original × factor))} ⇒ 实机上每并行耗电<b>恒 ≥ 1 EU/t</b>。
     * 跟着它钳，方向是<b>「宁可保守」</b>（k 越大 ⇒ 并行越小 ⇒ 绝不会超功率）；
     * 同时排除了「k 被算成 0 ⇒ 并行爆成天文数字 ⇒ 整机耗电超 P」这条路。
     *
     * @param recipeEutPerTick  原配方的每 tick 耗电（{@code RecipeHelper.getInputEUt}）
     * @param engineMultiplier  引擎耗能乘数（{@code getEuMultiplier()}）
     * @param n5Factor          N5 减免系数（{@code reductionFactor(gateBonus)}，未生效 = 1.0）
     * @return {@code ≥ 1000}（= 1 EU/t）的毫 EU/t 定点值 k
     */
    public static long perParallelMilliCost(long recipeEutPerTick, double engineMultiplier, double n5Factor) {
        double factor = engineMultiplier * n5Factor;
        // 非有限 / 非正 ⇒ 当成"没有乘数"（1.0）。宁可退回旧口径，也不让一个 NaN 把并行算成 0。
        if (!Double.isFinite(factor) || !(factor > 0.0D)) {
            factor = 1.0D;
        }
        final double raw = (double) recipeEutPerTick * factor;
        if (!(raw > 1.0D)) {
            // ① 档：0 / 负数 / 极小（含 0.0004 那一档）⇒ 钳到 1 EU/t。
            //    ⚠️ 这一档**早于**毫级取整 ⇒ 0 毫 与 1 毫 都不可能被返回（见方法 javadoc 的阶梯表）。
            return MILLI;
        }
        if (raw >= 9.0E18D) {
            // ③ 档：天文数字 ⇒ 留给 floorScaledDiv 的 kMilli 上界守门（那里返回 0 ⇒ 调用方保底 1）。
            return Long.MAX_VALUE;
        }
        // ② 档：唯一的取整发生在这一行（四舍五入到【毫】，且保证不低估 —— 见 conservativeMilliRound）。
        //    对用户那台是 2100.0000000000018 ⇒ 2100（相对误差 1.8e-12），
        //    而旧实现（先 round 再除）对 2.1 的误差是 4.8% —— 正好就是用户看到的那个 5% 缺口。
        return Math.max(1L, conservativeMilliRound(raw * (double) MILLI));
    }

    /**
     * 🔴🔴 <b>候选集上的「取最贵」归约 —— 【全工程唯一一份】「候选多条时 k 用哪一条」的实现
     * （2026-10-02 第九轮 · 用户点名的那条隐患）。</b>
     *
     * <pre>
     *   worst = max( worst , perParallelMilliCost(本候选配方的每 tick 耗电, 引擎耗能乘数, N5 减免) )
     * </pre>
     *
     * <h2>用户原话（逐字，这就是规格）</h2>
     * <blockquote>「等一下，不同配方耗电是不同的，你不会取静态的数值了吧」</blockquote>
     *
     * <h2>🔴 为什么必须「取最贵」而不能「取候选集的头一条」</h2>
     * <pre>
     *   并行 = min( 本机上限 , floor(P ÷ (k × T)) )
     *   ⇒ k 取【大】了 ⇒ 并行只会变【小】 ⇒ 耗电只会变【少】 ⇒ 【保守安全】
     *   ⇒ k 取【小】了（= 拿便宜的那条当代表）⇒ 并行偏大 ⇒ 真跑起来贵的那条 ⇒ 电力又不够
     *        —— 正是用户 2026-10-02 实机报的「电力输入不足」那类症状
     * </pre>
     * 代价是"候选里混着贵配方时可能没吃满电力"（少用一点并行）—— 方向是保守，不会把机器跑坏。
     * <p>⚠️ 候选集是上游给的 {@code Set}（哈希序，**与配方优先级无关**，见
     * {@code PrimordialModuleRecipeLogic#lookupRecipeSet()} 的 javadoc）⇒ 「取头一条」在这里
     * 连"稳定的那条"都保证不了；本方法因此把顺序完全排除在语义之外（{@code selfTest} ⑧ 断言
     * 归约结果与遍历顺序无关）。
     *
     * <h2>🔴 「按原耗电比」与「按乘完减免比」等价 —— 这就是那条要证明的单调性</h2>
     * 一轮里<b>所有候选共用同两个乘数</b>（引擎耗能乘数、N5 减免；见本类 javadoc 的
     * 「乘数不随并行 p 变化」）⇒ {@code k = g(eut)} 是<b>同一个函数</b>，而
     * {@link #perParallelMilliCost} 的每一步都<b>单调非减</b>：
     * <pre>
     *   ① 乘一个【恒正】的因子（非正 / 非有限先被清洗成 1.0）
     *   ② 钳地板（<span>raw ≤ 1 ⇒ 1000 毫</span>：把小的都压到同一个值，仍然不减）
     *   ③ 取整（保守毫取整 = 四舍五入，且只<b>向上</b>补那被舍掉的一毫）
     * </pre>
     * ⇒ <b>argmax 相同</b>：先在原耗电上比大小、还是先在 k 上比大小，选出的是同一条配方。
     * <p>因此调用方<b>两种比法都不许各写一遍</b>：只在本方法里比一次；{@code selfTest} ⑨ 用
     * 值域扫描把这条等价性钉住（含地板档与极小乘数档）。
     *
     * @param runningWorstMilli  已经归约出来的最贵 k（毫 EU/t；<b>初值传 {@code 0}</b>）
     * @param recipeEutPerTick   这一条候选配方的每 tick 耗电（{@code RecipeHelper.getInputEUt}）
     * @param engineMultiplier   引擎耗能乘数（{@code getEuMultiplier()}）
     * @param n5Factor           N5 减免系数（{@code reductionFactor(gateBonus)}，未生效 = 1.0）
     * @return 新的最贵 k（毫 EU/t），恒 ≥ {@link #MILLI}
     */
    public static long worstPerParallelMilliCost(long runningWorstMilli, long recipeEutPerTick,
                                                 double engineMultiplier, double n5Factor) {
        return Math.max(runningWorstMilli,
                perParallelMilliCost(recipeEutPerTick, engineMultiplier, n5Factor));
    }

    /**
     * 🔴 <b>候选集指纹 —— 「同一轮两次取候选，拿到的是不是同一个集合」的<b>唯一一份</b>比较口径</b>
     * （2026-10-02 第十轮 · 用户裁决：「既然识别出来了 ⇒ 就钉住它」）。
     *
     * <pre>
     *   规范化 = 去重（集合语义） → 按字典序排序 → 用不可见分隔符连接 → 前缀条数
     * </pre>
     *
     * <h2>为什么要规范化，而不是直接比对象 / 比 {@code equals}</h2>
     * <ul>
     *   <li>候选集是 {@code Set} ⇒ 语义上<b>与遍历顺序无关</b>，而两次调用拿到的可能是
     *       两个不同的 {@code Set} 实例（上游每次都新建）⇒ <b>比 identity 必然假红</b>。</li>
     *   <li>排序后比较 ⇒ 「同样的成员换了个顺序」判<b>一致</b>；「成员真的变了」判<b>不一致</b>。
     *       ⚠️ 注意这里<b>不去</b>比迭代顺序：迭代顺序变了但成员没变，对 {@code k最贵} 没有影响
     *       （{@code worstPerParallelMilliCost} 对顺序无关，见其 javadoc 的 selfTest ⑧），
     *       把它算成"不一致"会造出与超功率无关的噪声告警。</li>
     *   <li>指纹只吃 id 字符串 ⇒ 与对象 identity 无关，且可以<b>离线</b>被正面/负面对照跑一遍
     *       （不依赖任何 Minecraft 类型）。</li>
     * </ul>
     *
     * <p>唯一消费者：{@link CandidateSetConsistency}（运行期探针）与其离线判据。
     *
     * @param recipeIds 候选集里每一条配方的 id 字符串；{@code null} / 空集合法
     * @return 形如 {@code "3:" + 三个 id 用不可见分隔符相连} 的规范化指纹（条数写在最前面）
     */
    public static String candidateSetSignature(java.util.Collection<String> recipeIds) {
        if (recipeIds == null || recipeIds.isEmpty()) {
            return "0:";
        }
        final java.util.TreeSet<String> sorted = new java.util.TreeSet<>();
        for (String id : recipeIds) {
            sorted.add(id == null ? "<null>" : id);
        }
        final StringBuilder sb = new StringBuilder();
        sb.append(sorted.size()).append(':');
        for (String id : sorted) {
            sb.append(id).append((char) 1);
        }
        return sb.toString();
    }

    /**
     * 🔴 <b>「毫」级取整 —— 四舍五入，但保证结果<span>不低于</span>真值</b>（2026-10-02 第八轮新增）。
     *
     * <pre>
     *   rounded = round(scaled)
     *   dropped = scaled − rounded
     *   若 dropped &gt; scaled × RELATIVE_TOLERANCE   ⇒ rounded + 1      ← 宁可保守，白丢 ≤ 1 毫
     *   否则                                       ⇒ rounded
     * </pre>
     *
     * <h2>为什么必须补这 1 毫（不是洁癖，是一个真的能超功率的口子）</h2>
     * 硬判据是 {@code k真 × 并行 × T ≤ P}。而并行是拿 <b>k用</b> 算的：
     * {@code 并行 = floor(P ÷ k用)}。若 {@code k用 &lt; k真}，则 {@code k真 × 并行} 会<b>超出 P</b>。
     * 取真实数字：{@code k真 = 2.1004}（毫表示 2100.4）⇒ 裸 {@code round} 给 2100，
     * {@code 并行 = floor(655360 ÷ 2.1000) = 312076} ⇒ {@code 2.1004 × 312076 = 655484.2 &gt; 655360}
     * ⇒ <b>正是用户报的那个"电力输入不足"</b>。补 1 毫 ⇒ {@code k用 = 2101} ⇒ 并行 311927
     * ⇒ {@code 2.1004 × 311927 = 655174.8 ≤ 655360} ✓。代价只是白丢 &lt; 0.05% 的并行。
     *
     * <h2>为什么还要那个相对容差（1e-9）—— 不加它会造出反向的错</h2>
     * {@code 2.1 × 1000} 在 double 里是 {@code 2100.0000000000018}（比 2100 大 1.8e-12）。
     * 若"只要舍掉了就 +1"，用户那一档会被补成 <b>2101</b> ⇒ 并行从 312,076 掉到 311,927
     * ——<b>白丢 149 份</b>，而且 {@code selfTest} 的「边界紧」判据会当场报红（那是反向的错）。
     * ⇒ 容差取<b>相对 1e-9</b>：double 噪声档不补，真被舍掉整档（如 2100.4 → 2100）才补。
     *
     * <h2>溢出</h2>
     * {@code scaled ≥ 9.223372036854775e18} 时直接返回 {@code Long.MAX_VALUE}
     * （否则 {@code Math.round} 已经饱和成 {@code Long.MAX_VALUE}，再 +1 会<b>回绕成负数</b>）。
     *
     * @param scaled {@code raw × 1000}（调用方已保证 {@code raw > 1.0}，故 {@code scaled > 1000}）
     * @return {@code ≥ 1001} 的毫值（除饱和档），且 {@code ≥ scaled − scaled×1e-9}
     */
    static long conservativeMilliRound(double scaled) {
        if (scaled >= 9.223372036854775E18D) {
            return Long.MAX_VALUE;
        }
        final long rounded = Math.round(scaled);
        if ((double) rounded < scaled && scaled - (double) rounded > scaled * RELATIVE_TOLERANCE) {
            return rounded + 1L;
        }
        return rounded;
    }

    /**
     * 「不低估」判据用的<b>相对容差</b>：正好吃掉 double 噪声（用户档 1.8e-12），
     * 又不吃掉真的被舍掉的整毫（0.4 毫 ≫ 2100×1e-9 = 2.1e-6）。
     */
    static final double RELATIVE_TOLERANCE = 1.0E-9D;

    /**
     * <b>由总功率求并行数</b>（本功能的主算式）。
     *
     * <pre>
     *   Pe 无限（终端 / 创造）        ⇒ 返回 ceiling（= 原本上限 ⇒ 「直接拉到最大」）
     *   ceiling ≤ 0                  ⇒ 返回 1（服务端兜底）
     *   Pe ≤ 0（没有仓 / 认不出仓）    ⇒ 返回 ceiling（【不做电力限制】= 改动前行为，见下）
     *   k ≤ 0（除零 / 负数）          ⇒ 返回 ceiling（同上）
     *   否则                          ⇒ clamp(min(floor(Pe × 1000 ÷ k毫), ceiling), 1, ceiling)
     * </pre>
     *
     * <h2>🔴 边界为什么这样定</h2>
     * <ul>
     *   <li><b>「没有能源仓 ⇒ ?」</b>：返回 ceiling 而<b>不是</b> 1。理由不是"这样更好看"，
     *       而是<b>认不出仓时不能把机器打死</b>：本判据靠容器类名识别，一旦上游换了容器类
     *       （或玩家用了第三方仓），"总功率 = 0" 与"真的没插仓"在数值上分不开。
     *       取 ceiling ⇒ 退化成改动前的行为（安全）；取 1 ⇒ 机器看起来像坏了（错误方向）。
     *       真的没插仓时机器本来也开不了工（{@code checkBeforeWorking} 要求
     *       {@code getOverclockVoltage() > 0}），这一档不会造成新行为。</li>
     *   <li><b>「每并行耗电为 0 ⇒ ?」</b>：同上，返回 ceiling —— 不耗电的配方没有电力上限。
     *       （{@link #perParallelMilliCost} 本身已经钳 ≥1 EU/t，这一支是给直接调用方的兜底。）</li>
     *   <li><b>「总功率极小 ⇒ ?」</b>：{@code Pe / k = 0} ⇒ 钳到 <b>1</b> —— 与"没有本功能时
     *       机器至少会尝试跑 1 份、跑不动就 WAITING"的既有行为一致。</li>
     * </ul>
     *
     * @param totalPowerPerTick 能源仓总功率（{@link #UNLIMITED} = 特例）
     * @param perParallelMilliEut 每并行耗电 k（<b>毫</b> EU/t，{@link #perParallelMilliCost}）
     * @param ceiling           原本的上限（模块 = 物质模块并行表值，主机 = {@code MAX_PARALLEL}）
     */
    public static long parallelFromPowerMilli(long totalPowerPerTick, long perParallelMilliEut, long ceiling) {
        final long cap = Math.max(1L, ceiling);
        if (isUnlimitedPower(totalPowerPerTick)) {
            return cap;
        }
        if (totalPowerPerTick <= 0L || perParallelMilliEut <= 0L) {
            return cap;
        }
        // 🔴 唯一的一次取整：向下取整。k 是【毫】⇒ floor(P × 1000 ÷ kMilli) 就是 floor(P ÷ k真实)，
        //    精确到 0.001 EU/t，不再有旧实现那个 round 出来的 5% 缺口。
        final long p = floorScaledDiv(totalPowerPerTick, perParallelMilliEut);
        if (p < 1L) {
            return 1L;
        }
        return Math.min(p, cap);
    }

    /**
     * 🔴 <b>{@code floor(totalPower × 1000 ÷ kMilli)} —— 全程 long、无溢出、精确。</b>
     *
     * <h2>为什么不用 double 除法</h2>
     * {@code (long) ((double) P / (double) k)} 在商接近整数时可能因为二进制表示误差
     * <b>多算 1 份并行</b>，而本工程的硬判据是 {@code k × 并行 × T ≤ P} —— 多 1 份就是超功率。
     * 这里把「商」与「余」分开算：{@code floor(P/k) = q + floor(r × 1000 / k)}，两步都在整数域完成。
     *
     * <h2>溢出为什么不可能（逐条）</h2>
     * <pre>
     *   ① kMilli &gt; Long.MAX/1000 ⇒ 直接返回 0（调用方按"连 1 份都养不起"钳成 1）。
     *      这一档要成立，k 得 ≥ 9.2e15 EU/t/份 —— 实机上不存在，但它把下面两条的前提钉死。
     *   ② 有了 ①，{@code r &lt; kMilli ≤ Long.MAX/1000} ⇒ {@code r × 1000} 严格不溢出。
     *   ③ {@code q × 1000} 单独判一次上界（{@code q &gt; Long.MAX/1000} ⇒ 返回 Long.MAX）；
     *      {@code base + frac} 那一句再兜一次（{@code frac &lt; 1000}）。
     * </pre>
     *
     * @param totalPowerPerTick   能源仓总功率（EU/t；{@link #UNLIMITED} 已由调用方处理）
     * @param perParallelMilliEut 每并行耗电 k（<b>毫</b> EU/t，{@link #perParallelMilliCost}）
     * @return {@code floor(P ÷ k)}（饱和到 {@code Long.MAX_VALUE}）；不保证 ≥ 1
     */
    static long floorScaledDiv(long totalPowerPerTick, long perParallelMilliEut) {
        if (perParallelMilliEut <= 0L) {
            return 0L;
        }
        if (perParallelMilliEut > Long.MAX_VALUE / MILLI) {
            // k ≥ 9.2e15 EU/t/份 ⇒ 连 1 份都养不起。
            return 0L;
        }
        final long q = totalPowerPerTick / perParallelMilliEut;
        final long r = totalPowerPerTick % perParallelMilliEut;
        if (q > Long.MAX_VALUE / MILLI) {
            return Long.MAX_VALUE;
        }
        final long base = q * MILLI;
        final long frac = (r * MILLI) / perParallelMilliEut;
        final long sum = base + frac;
        return sum < 0L ? Long.MAX_VALUE : sum;
    }

    /**
     * <b>把「毫 EU/t」印成人看的数</b>（日志 / 面板用）。
     *
     * <pre>
     *   42000 ⇒ "42"        2100 ⇒ "2.1"       1000 ⇒ "1"      5250 ⇒ "5.25"
     * </pre>
     * <p>⚠️ 刻意<b>不</b>把小数印成整数：旧实现那一档在日志里长成「每并行 2 EU/t」，
     * 而真相是 2.1 —— 用户正是靠 Jade 的 688.13K 才把它反推出来的。日志不许再把这件事藏起来。
     */
    public static String formatMilli(long milliEut) {
        if (milliEut % MILLI == 0L) {
            return String.format(Locale.ROOT, "%,d", milliEut / MILLI);
        }
        String s = String.format(Locale.ROOT, "%,.3f", (double) milliEut / (double) MILLI);
        // 去掉尾随 0（以及可能剩下的那个小数点）：2100 ⇒ "2.1"、5250 ⇒ "5.25"
        while (s.endsWith("0")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** 饱和加法（{@code a + b} 溢出时返回 {@code Long.MAX_VALUE} 而不是回绕成负数）。 */
    public static long saturatingAdd(long a, long b) {
        if (a <= 0L) {
            return Math.max(0L, b);
        }
        if (b <= 0L) {
            return a;
        }
        final long sum = a + b;
        return sum < 0L ? Long.MAX_VALUE : sum;
    }

    /** 饱和乘法（任一侧 {@code ≤ 0} ⇒ 0；溢出 ⇒ {@code Long.MAX_VALUE}）。 */
    public static long saturatingMultiply(long a, long b) {
        if (a <= 0L || b <= 0L) {
            return 0L;
        }
        final long product = a * b;
        if (product < 0L || product / b != a) {
            return Long.MAX_VALUE;
        }
        return product;
    }

    /**
     * <b>一行可读的功率/并行读数</b>（日志与面板共用同一份措辞，防两处漂移）。
     *
     * <p>例：{@code 总功率 2048 EU/t（2 个仓：电网能源仓×2）· 每并行 42 EU/t · 电力上限 48 · 原本上限 262143}
     *
     * <p>🔴 2026-10-02 第七轮：{@code perParallelMilliEut} 改成<b>毫</b> EU/t ⇒ 开了 N5 减免时这一行会印
     * 「每并行 2.1 EU/t」而不再是四舍五入后的「2」。这不是美化：用户就是靠 Jade 的 688.13K
     * 反推出「真值 2.1、日志写着 2」的，<b>日志不许再把这件事藏起来</b>。
     */
    public static String describe(long totalPowerPerTick, int hatchCount, String kindSummary,
                                  long perParallelMilliEut, long energyParallel, long ceiling) {
        final String power = isUnlimitedPower(totalPowerPerTick)
                ? "∞（特例：拉满）"
                : String.format(Locale.ROOT, "%,d", totalPowerPerTick);
        return "总功率 " + power + " EU/t（" + hatchCount + " 个仓"
                + (kindSummary == null || kindSummary.isEmpty() ? "" : "：" + kindSummary) + "）"
                + " · 每并行 " + formatMilli(perParallelMilliEut) + " EU/t"
                + " · 电力上限 " + String.format(Locale.ROOT, "%,d", energyParallel)
                + " · 原本上限 " + String.format(Locale.ROOT, "%,d", ceiling);
    }

    /**
     * 🔴🔴 <b>加载期自检（正向对照 ＋ 回归锚 ＋ 边界紧 ＋ 负面对照），由
     * {@code PrimordialModuleMachine#assertParallelTablesConsistent()} → {@code ModuleRegistry#init()}
     * 在注册期调用 ⇒ 无头专服里必跑、日志可 grep。</b>
     *
     * <h2>它钉住的是哪一件事</h2>
     * 用户 2026-10-02 实机报的「电力自动算出来了、但整机耗电 688.13K &gt; 能源仓 655,360 ⇒ 电力输入不足」。
     * 真凶 = 旧的 {@code Math.round(2.1) = 2}。本方法把「<b>k 必须带小数参与除法</b>」变成
     * <b>加载期就会炸</b>的断言，而不是等玩家再抓一次 Jade。
     *
     * @return 一行可 grep 的读数（调用方直接 {@code LOGGER.info(...)}）
     * @throws IllegalStateException 任一条断言不成立（本项目风格：加载期 fail-fast，不静默）
     */
    public static String selfTest() {
        // ══════════ ① 用户实测档（2026-10-02 第七轮）：P=655,360 / 配方 42 / N5=5% ══════════
        final long userPower = 655_360L;              // 16A HV 电网仓 131,072 ＋ 4A ZPM 无线仓 524,288
        final long userEut = 42L;                     // 日志原文：「首条配方 42 EU/t」
        final double userN5 = 0.050000000000000044D;  // reductionFactor(17)（= 主机插满 64 个创造模块）
        final long kMilli = perParallelMilliCost(userEut, 1.0D, userN5);
        assertEq(kMilli, 2100L, "用户档 k 必须是 2.1 EU/t（= 2100 毫），不许被 round 成 2000（= 2 EU/t）");
        final long p = parallelFromPowerMilli(userPower, kMilli, Long.MAX_VALUE);
        assertEq(p, 312_076L, "用户档 P=655,360 / k=2.1 的并行必须是 312,076");

        // ══════════ ② 整机耗电红线 ＋ 边界紧（用户口径：批处理不参与） ══════════
        //    判据按【整机】写：k真实 × 并行 × T ≤ P。用户看到的就是整机那一行。
        final double kReal = (double) kMilli / (double) MILLI;
        final double draw = kReal * (double) p;
        if (!(draw <= (double) userPower)) {
            throw new IllegalStateException("[SHANHAI-POWER-PARALLEL-BUDGET] 自检失败（整机耗电红线）："
                    + "k=" + kReal + " × 并行=" + p + "（T=1）= " + draw
                    + " EU/t > 能源仓 " + userPower + " EU/t。");
        }
        final double drawNext = kReal * (double) (p + 1L);
        if (!(drawNext > (double) userPower)) {
            throw new IllegalStateException("[SHANHAI-POWER-PARALLEL-BUDGET] 自检失败（边界不紧）："
                    + "并行 +1 之后 " + drawNext + " 仍然 ≤ " + userPower
                    + " ⇒ 算出的并行偏保守，没吃满能源仓。");
        }
        // T > 1 的档位同样成立（整机 = k × 每线程并行 × T ≤ P）：
        // 每线程并行 = floor(电上限 ÷ T)（电上限本身就是 312,076），整数 T ⇒ 乘回必 ≤ 电上限。
        for (int t = 1; t <= 3; t++) {
            final long perThread = p / t;
            final double totalDraw = kReal * (double) perThread * (double) t;
            if (perThread < 1L || !(totalDraw <= (double) userPower)) {
                throw new IllegalStateException("[SHANHAI-POWER-PARALLEL-BUDGET] 自检失败（T=" + t + "）："
                        + "每线程并行=" + perThread + " ⇒ 整机耗电 " + totalDraw
                        + " EU/t > " + userPower + " EU/t。");
            }
        }

        // ══════════ ③ 负面对照：旧的「先 round 再除」口径必须超功率 ══════════
        //    否则判据对「忘了让 k 带小数」这件事没有分辨力（本工程血规：判据必须能报红）。
        final long oldK = Math.max(1L, Math.round((double) userEut * userN5));   // = 2（旧实现）
        final long oldP = userPower / oldK;                                     // = 327,680
        final double oldDraw = ((double) userEut * userN5) * (double) oldP;      // = 688,128
        assertEq(oldK, 2L, "负面对照的前提：旧口径 round(2.1) 必须 = 2");
        assertEq(oldP, 327_680L, "负面对照的前提：旧口径并行必须 = 327,680（= 用户实机读数）");
        if (!(oldDraw > (double) userPower)) {
            throw new IllegalStateException("[SHANHAI-POWER-PARALLEL-BUDGET] 自检的【负面对照】失败："
                    + "旧口径（先 round 再除）竟然没有超功率 ⇒ 本判据对真凶没有分辨力。");
        }

        // ══════════ ④ 回归锚：减免未生效（k = 42 整数）那两组必须逐位不变 ══════════
        final long kIntMilli = perParallelMilliCost(42L, 1.0D, 1.0D);
        assertEq(kIntMilli, 42_000L, "减免未生效时 k 必须仍是 42 EU/t");
        assertEq(parallelFromPowerMilli(2_048L, kIntMilli, Long.MAX_VALUE), 48L,
                "用户实测锚①：P=2048 / k=42 ⇒ 48（逐位不变）");
        assertEq(parallelFromPowerMilli(128L, kIntMilli, Long.MAX_VALUE), 3L,
                "用户实测锚②：P=128 / k=42 ⇒ 3（逐位不变）");
        // 边界（与旧实现同口径的整数档必须一位不差）：2048 / 42 = 48.76 ⇒ 48
        assertEq(parallelFromPowerMilli(2_057L, kIntMilli, Long.MAX_VALUE), 48L, "2057 / 42 = 48.98 ⇒ 48（向下取整）");
        assertEq(parallelFromPowerMilli(2_058L, kIntMilli, Long.MAX_VALUE), 49L, "2058 / 42 = 49.0 ⇒ 49");

        // ══════════ ⑤ 三条既有兜底不许被动摇 ══════════
        assertEq(parallelFromPowerMilli(UNLIMITED, kMilli, 999L), 999L, "无限功率 ⇒ 取原本上限");
        assertEq(parallelFromPowerMilli(0L, kMilli, 999L), 999L, "总功率 0 ⇒ 不做电力限制（取原本上限）");
        assertEq(parallelFromPowerMilli(100L, 0L, 999L), 999L, "k = 0 ⇒ 不做电力限制");
        assertEq(parallelFromPowerMilli(1L, 9_000_000L, Long.MAX_VALUE), 1L, "k 极大 ⇒ 保底 1");
        assertEq(parallelFromPowerMilli(userPower, kMilli, 1_000L), 1_000L, "原本上限更小 ⇒ 取原本上限");

        // ══════════ ⑥ 定点除法的溢出守门（不炸、不回绕成负数） ══════════
        if (floorScaledDiv(Long.MAX_VALUE, 1000L) < 0L) {
            throw new IllegalStateException("[SHANHAI-POWER-PARALLEL-BUDGET] 自检失败：floorScaledDiv 溢出成负数。");
        }
        assertEq(floorScaledDiv(Long.MAX_VALUE, Long.MAX_VALUE), 0L, "k 顶到 MAX ⇒ 0（调用方按 1 兜底）");
        assertEq(floorScaledDiv(0L, 1L), 0L, "功率 0 ⇒ 0");
        // 整数 k 时定点除法必须与朴素整数除法逐位相同（48 组）
        int divChecked = 0;
        final long[] powers = {0L, 1L, 41L, 42L, 48L, 128L, 2048L, 655_360L, 1L << 40};
        final long[] ks = {1000L, 2000L, 5250L, 42_000L, 1_000_000L};
        for (long pw : powers) {
            for (long kk : ks) {
                if (kk % MILLI != 0L) {
                    continue;
                }
                assertEq(floorScaledDiv(pw, kk), pw / (kk / MILLI),
                        "整数 k 档定点除法必须等于朴素整数除法（P=" + pw + " k=" + (kk / MILLI) + "）");
                divChecked++;
            }
        }

        // ══════════ ⑦ k &lt; 0.001 档 ＋ 「永不低估」全值域性质（2026-10-02 第八轮 · 用户裁决 ① 点名） ══════════
        //  用户原话（逐字）：「遇到比 0.001 更小的乘数时行为是什么（例：0.0004 ⇒ 毫之后是 0.4
        //  ⇒ 会被截成几？会不会保底成 1 毫？）⇒ 要给出明确行为 ＋ 一条判据（宁可保守，不能算不出）」
        //  ⇒ **明确行为**：先被「每并行耗电 ≥ 1 EU/t」的地板接住 ⇒ **恒返回 1000 毫（= 1 EU/t）**。
        //     既不是 0 毫、也不是 1 毫 —— 那两档在结构上不可达（进毫级取整的前提是 raw > 1.0）。
        assertEq(perParallelMilliCost(1L, 1.0D, 0.0004D), MILLI,
                "k真 = 0.0004 EU/t（毫表示 0.4 毫）⇒ 必须保住 1 EU/t（不是 0 毫、也不是 1 毫）");
        assertEq(perParallelMilliCost(42L, 1.0D, 1.0E-5D), MILLI, "42 × 1e-5 = 0.00042 EU/t ⇒ 1 EU/t");
        assertEq(perParallelMilliCost(1L, 1.0D, 0.001D), MILLI,
                "k真 = 0.001 EU/t（恰在毫的分辨率上）⇒ 仍是 1 EU/t（地板在毫级取整之前）");
        final double[] tinyFactors = {4.0E-4D, 1.0E-3D, 1.0E-4D, 1.0E-10D, 1.0E-300D, Double.MIN_VALUE};
        for (double tiny : tinyFactors) {
            final long m = perParallelMilliCost(42L, 1.0D, tiny);
            if (m != MILLI) {
                throw new IllegalStateException("[SHANHAI-POWER-PARALLEL-BUDGET] 自检失败（极小乘数档）："
                        + "42 EU/t × " + tiny + " ⇒ 必须落到 1 EU/t（1000 毫），实际 " + m + " 毫。");
            }
        }
        // 乘数清洗：非正 / 非有限 ⇒ 当成「没有乘数」（1.0），**不是**当成 0（否则并行会爆成天文数字）。
        final double[] badFactors = {0.0D, -1.0D, Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (double bad : badFactors) {
            assertEq(perParallelMilliCost(42L, 1.0D, bad), 42_000L,
                    "乘数 " + bad + " ⇒ 清洗成 1.0（退回旧口径），不是 0");
        }
        // 值域扫描：① 恒 ≥ 1000 毫；② 「1 毫」不可达；③ **永不低估真值**（相对容差 1e-9）。
        //    ③ 是「宁可保守」的机器可验证形态：k用 < k真 会让 k真 × 并行 有机会 > P。
        int milliSwept = 0;
        double worstDeficit = 0.0D;
        final long[] sweepEuts = {1L, 2L, 7L, 42L, 128L, 1_000L, 65_536L};
        final double[] sweepFactors = {1.0E-4D, 1.0E-3D, 5.0E-3D, 0.05D, 0.050000000000000044D,
                0.123456789D, 0.3333333333333333D, 0.5D, 1.0D, 1.0004D, 2.1004D, 7.0D};
        for (long eut : sweepEuts) {
            for (double f : sweepFactors) {
                final long m = perParallelMilliCost(eut, 1.0D, f);
                final double trueMilli = (double) eut * f * (double) MILLI;
                if (m <= 1L) {
                    throw new IllegalStateException("[SHANHAI-POWER-PARALLEL-BUDGET] 自检失败（1 毫不可达）："
                            + "42→" + eut + " EU/t × " + f + " ⇒ 算出 " + m + " 毫。");
                }
                final double allowed = trueMilli * (1.0D - RELATIVE_TOLERANCE);
                if ((double) m < allowed) {
                    throw new IllegalStateException("[SHANHAI-POWER-PARALLEL-BUDGET] 自检失败（低估）："
                            + eut + " EU/t × " + f + " ⇒ k用 = " + m + " 毫 < k真 = " + trueMilli
                            + " 毫 ⇒ 并行可能超出能源仓（正是用户报的那种「电力输入不足」）。");
                }
                worstDeficit = Math.max(worstDeficit, trueMilli - (double) m);
                milliSwept++;
            }
        }
        // 负面对照：**没有** conservativeMilliRound 的裸 round 在 2.1004 那一档会低估 ⇒ 这条判据有分辨力。
        //   真实后果：k用 = 2100 ⇒ 并行 = floor(655,360 ÷ 2.1000) = 312,076 ⇒ 整机 655,484.2 > 655,360（超功率）。
        assertEq(Math.round(2.1004D * (double) MILLI), 2100L,
                "负面对照的前提：裸 round(2100.4000000000001) 必须 = 2100（低估 0.4 毫）");
        assertEq(conservativeMilliRound(2.1004D * (double) MILLI), 2101L,
                "保守档必须把它补成 2101（否则 k用 < k真 ⇒ 可能超功率）");
        assertEq(conservativeMilliRound(2100.0000000000018D), 2100L,
                "用户档 2100.0000000000018 ⇒ 仍是 2100（double 噪声不算低估，补了反而白丢 149 份并行）");

        // ══════════ ⑧ 🔴 候选【多条】时 k 必须取【最贵】的那一条 ══════════
        //  用户原话（逐字）：「等一下，不同配方耗电是不同的，你不会取静态的数值了吧」
        //  ⇒ 三条断言：① 归约取的是 max；② 与遍历顺序无关；③ 负面对照「取头一条」必须【真超功率】。
        final long[] multiEuts = {42L, 100L};        // 例：便宜的 42 EU/t、贵的 100 EU/t
        long worstOfMulti = 0L;
        for (long e : multiEuts) {
            worstOfMulti = worstPerParallelMilliCost(worstOfMulti, e, 1.0D, 1.0D);
        }
        assertEq(worstOfMulti, 100_000L,
                "候选 {42, 100} ⇒ k 必须取【最贵的那条】100 EU/t（= 100,000 毫）");
        long worstReversed = 0L;
        for (int i = multiEuts.length - 1; i >= 0; i--) {
            worstReversed = worstPerParallelMilliCost(worstReversed, multiEuts[i], 1.0D, 1.0D);
        }
        assertEq(worstReversed, worstOfMulti,
                "归约必须与候选的遍历顺序无关（上游返回的是哈希集，顺序与配方优先级无关）");

        // 单条候选那两组实机读数（回归锚）在归约口径下必须逐位不变。
        assertEq(worstPerParallelMilliCost(0L, 42L, 1.0D, 1.0D), 42_000L,
                "单条候选 k=42 ⇒ 归约结果仍是 42 EU/t（= 42000 毫）");
        assertEq(worstPerParallelMilliCost(0L, userEut, 1.0D, userN5), kMilli,
                "单条候选（用户实机那台，N5=5%）⇒ 归约结果仍是 2.1 EU/t（= 2100 毫）");
        // 用户实机日志原文那一行：「· 每并行 42 EU/t · 电力上限 15,603」⇒ 必须逐位复现。
        assertEq(parallelFromPowerMilli(userPower, worstPerParallelMilliCost(0L, 42L, 1.0D, 1.0D),
                Long.MAX_VALUE), 15_603L,
                "用户实机日志原文「每并行 42 EU/t · 电力上限 15,603」必须逐位复现");

        // 同一条 P=655,360 上，取最贵 vs 取头一条 —— 两个上限与两种耗电，四个数全是实算：
        final long multiCap = parallelFromPowerMilli(userPower, worstOfMulti, Long.MAX_VALUE);          // 6553
        final long firstTakenCap = parallelFromPowerMilli(userPower,
                perParallelMilliCost(42L, 1.0D, 1.0D), Long.MAX_VALUE);                                // 15603
        assertEq(multiCap, 6_553L, "P=655,360 / k=100（最贵）⇒ 并行上限必须 6,553");
        assertEq(firstTakenCap, 15_603L, "负面对照的前提：取【头一条】42 ⇒ 并行上限 15,603");
        // 「取头一条」为什么危险：它在【便宜的假设下】看着完全正常，只有拿真跑的那条去乘才露馅。
        if (!(42.0D * (double) firstTakenCap <= (double) userPower)) {
            throw new IllegalStateException("[SHANHAI-POWER-PARALLEL-BUDGET] 自检失败（负面对照的前提不成立）："
                    + "取头一条算出的 15,603 份 × 42 EU/t = " + (42.0D * (double) firstTakenCap)
                    + "，竟然已经超了 " + userPower + " ⇒ 这个负面对照讲不出「看着没事、真跑就超」这件事。");
        }
        if (!(100.0D * (double) firstTakenCap > (double) userPower)) {
            throw new IllegalStateException("[SHANHAI-POWER-PARALLEL-BUDGET] 自检的【负面对照】失败："
                    + "取头一条算出的 " + firstTakenCap + " 份 × 真正会跑的那条 100 EU/t = "
                    + (100.0D * (double) firstTakenCap) + " 竟然没有超 " + userPower
                    + " ⇒ 本判据对「拿头一条当代表」这件事没有分辨力。");
        }
        if (!(100.0D * (double) multiCap <= (double) userPower)) {
            throw new IllegalStateException("[SHANHAI-POWER-PARALLEL-BUDGET] 自检失败（取最贵仍超功率）："
                    + multiCap + " × 100 = " + (100.0D * (double) multiCap) + " > " + userPower + "。");
        }
        if (!(100.0D * (double) (multiCap + 1L) > (double) userPower)) {
            throw new IllegalStateException("[SHANHAI-POWER-PARALLEL-BUDGET] 自检失败（取最贵后边界不紧）："
                    + "并行 +1 之后仍是 " + (100.0D * (double) (multiCap + 1L)) + " ≤ " + userPower
                    + " ⇒ 多候选这一档算得偏保守，没吃满能源仓。");
        }

        // ══════════ ⑨ 「按原耗电比」与「按乘完减免比」等价（单调性 · 用户点名要证明的那一条） ══════════
        //  理由见 worstPerParallelMilliCost 的 javadoc：一轮里所有候选共用同两个乘数 ⇒ k = g(eut) 单调非减
        //  ⇒ 先比原耗电再乘、与先乘再比，argmax 是同一条。本段把 g 的单调性扫出来（含地板档与极小乘数档）。
        int monoChecked = 0;
        final long[] monoEuts = {0L, 1L, 2L, 3L, 5L, 8L, 20L, 41L, 42L, 43L, 100L, 1_000L, 65_536L};
        final double[] monoFactors = {1.0E-4D, 5.0E-3D, 0.05D, 0.050000000000000044D, 0.5D, 1.0D, 2.1004D, 7.0D};
        for (double f : monoFactors) {
            for (int i = 1; i < monoEuts.length; i++) {
                final long left = perParallelMilliCost(monoEuts[i - 1], 1.0D, f);
                final long right = perParallelMilliCost(monoEuts[i], 1.0D, f);
                if (left > right) {
                    throw new IllegalStateException("[SHANHAI-POWER-PARALLEL-BUDGET] 自检失败（单调性被打破）："
                            + "乘数 " + f + " 时 耗电 " + monoEuts[i - 1] + " ⇒ " + left + " 毫，而更大的 "
                            + monoEuts[i] + " ⇒ " + right + " 毫（k 反而更小）"
                            + " ⇒ 「按原耗电比」与「按乘完比」会选出不同的配方。");
                }
                // 两种比法必须给出同一个结果：先把大的乘出来算，与两条都算完再取 max，逐位相同。
                assertEq(worstPerParallelMilliCost(worstPerParallelMilliCost(0L, monoEuts[i - 1], 1.0D, f),
                                monoEuts[i], 1.0D, f),
                        Math.max(left, right),
                        "乘数 " + f + "：归约结果必须等于对两条各算一次再取 max（耗电 "
                                + monoEuts[i - 1] + " / " + monoEuts[i] + "）");
                monoChecked++;
            }
        }

        // ══════════ ⑩ 🔴 「同一轮两次取候选是不是同一个集合」的比较口径（2026-10-02 第十轮） ══════════
        //  运行期探针 CandidateSetConsistency 就是拿本类这个纯函数做比较的 ⇒ 先把【尺子本身】钉住：
        //  ① 顺序无关（集合语义）② 去重 ③ 成员真的变了 ⇒ 指纹必须变 ④ 空集有确定的指纹。
        //  🔴 为什么「两次调用返回相同集合」这条只能落在【运行期】而不是这里：
        //     加载期造不出"两次调用"（没有机器、没有候选集、没有那台 RecipeIterator）；
        //     而且 GTRecipe 对象在离线 JVM 里根本 new 不出来（第八轮 §3.5 的
        //     ExceptionInInitializerError / ModList.get()==null 栈在案）⇒ 离线这一层只能证明
        //     "尺子对"，"两次调用一致"只能由运行期探针观察。用户点名的位置选择即此。
        final String sigAbc = candidateSetSignature(java.util.Arrays.asList("shanhai:a", "shanhai:b", "shanhai:c"));
        final String sigCba = candidateSetSignature(java.util.Arrays.asList("shanhai:c", "shanhai:b", "shanhai:a"));
        if (!sigAbc.equals(sigCba)) {
            throw new IllegalStateException("[SHANHAI-POWER-PARALLEL-BUDGET] 自检失败（候选集指纹与顺序有关）："
                    + "同样的三条配方换个遍历顺序就得到了不同的指纹 ⇒ 运行期探针会报出与超功率无关的假告警。"
                    + " 正序=" + escapeForLog(sigAbc) + " 反序=" + escapeForLog(sigCba));
        }
        final String sigAbcDup = candidateSetSignature(
                java.util.Arrays.asList("shanhai:b", "shanhai:a", "shanhai:c", "shanhai:a"));
        if (!sigAbc.equals(sigAbcDup)) {
            throw new IllegalStateException("[SHANHAI-POWER-PARALLEL-BUDGET] 自检失败（候选集指纹不去重）："
                    + "同一个 id 出现两次就变了指纹 ⇒ 上游给的是 Set，这一档本不该出现。");
        }
        if (sigAbc.equals(candidateSetSignature(java.util.Arrays.asList("shanhai:a", "shanhai:b")))) {
            throw new IllegalStateException("[SHANHAI-POWER-PARALLEL-BUDGET] 自检失败（候选集指纹分不开）："
                    + "少一条候选，指纹竟然还相等 ⇒ 探针对「两次取候选不一致」没有分辨力。");
        }
        if (!"0:".equals(candidateSetSignature(java.util.Collections.emptyList()))
                || !"0:".equals(candidateSetSignature(null))) {
            throw new IllegalStateException("[SHANHAI-POWER-PARALLEL-BUDGET] 自检失败（空集指纹）："
                    + "空集 / null 必须给同一个确定指纹 \"0:\"，否则「两次都是空的」会被误报成不一致。");
        }
        int sigChecked = 4;

        return "[SHANHAI-POWER-PARALLEL-BUDGET] 每并行耗电定点核自检通过（用户档 P=655,360 / k=2.1 ⇒ 并行 "
                + p + "，整机耗电 " + draw + " ≤ " + userPower + "（并行 +1 则 " + drawNext
                + " > " + userPower + " ⇒ 边界紧）；T=1..3 整机红线全过；"
                + "负面对照：旧 round 口径并行 " + oldP + " ⇒ 整机 " + oldDraw + " > " + userPower
                + "（判据有分辨力）；回归锚 k=42 ⇒ 2048→48 / 128→3 逐位不变；"
                + "定点除法与朴素整数除法对账 " + divChecked + " 组逐位相同；格式 "
                + formatMilli(42_000L) + " / " + formatMilli(2100L) + " / " + formatMilli(5250L)
                + "；极小乘数档（k真 < 0.001 EU/t）恒钳 1 EU/t（= 1000 毫，0 毫 / 1 毫均不可达）；"
                + "全值域 " + milliSwept + " 组无低估（最大亏损 " + worstDeficit
                + " 毫，恒 ≤ 该档 1e-9 相对容差）；"
                + "【多候选】候选 {42,100} ⇒ k 取【最贵】「" + formatMilli(worstOfMulti) + " EU/t」"
                + "（P=655,360 时并行上限 " + multiCap + "，边界紧；单条候选那两组仍逐位不变）；"
                + "负面对照「取头一条」：" + firstTakenCap + " 份 × 真跑那条 100 EU/t = "
                + (100.0D * (double) firstTakenCap) + " > " + userPower + "（判据有分辨力）；"
                + "单调性「按原耗电比 ≡ 按乘完减免比」" + monoChecked + " 组全过，归约与遍历顺序无关；"
                + "候选集指纹（运行期「同一轮两次取候选是否同集合」探针的比较口径）" + sigChecked
                + " 条自证全过：顺序无关 / 去重 / 少一条必变指纹 / 空集与 null 同为 \"0:\"。";
    }

    /** 把指纹里的不可见分隔符换成可见写法，只为日志可读（控制字符直接进日志会毁掉整行）。 */
    private static String escapeForLog(String s) {
        return s == null ? "null" : s.replace((char) 1, '|');
    }

    private static void assertEq(long actual, long expected, String what) {
        if (actual != expected) {
            throw new IllegalStateException("[SHANHAI-POWER-PARALLEL-BUDGET] 自检失败：" + what
                    + "，实际 " + actual + "，应为 " + expected + "。");
        }
    }
}
