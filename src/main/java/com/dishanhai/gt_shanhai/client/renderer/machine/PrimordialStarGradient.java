package com.dishanhai.gt_shanhai.client.renderer.machine;

/**
 * 中子星颜色的<b>光谱渐变</b>（纯计算，无状态、无 MC 依赖）。
 *
 * <h2>🔴 2026-09-22 口径变更：驱动量由【运行时间】换成【物质模块等级】</h2>
 * 用户裁定（原话）：「<b>9的话我不希望光束的颜色随着时间变化，而希望它随着物质模块的等级变化</b>」
 * ⇒ <b>当时的入口</b> = {@link #rgbFloatsFromModuleLevel(int, int)} ← {@link #ratioFromModuleLevel(int, int)}，
 * 其中 {@code ratio = clamp01(level / maxLevel)} —— <b>与星体半径用的是同一个归一化</b>
 * （{@code PrimordialNeutronStarSphereRenderer#baseRadiusFor} 里的 {@code level / MAX_MODULE_LEVEL}）。
 * <p>⛔ 基于时间的 {@link #ratioFromRunningSecs(long)} / {@link #rgbFloatsFromRunningSecs(long)}
 * <b>已无任何调用点</b>（主机上的 {@code runningSecs} 字段与整条 {@code @DescSynced} 同步链已随裁定删除）
 * —— <b>保留原文仅为留档</b>：它们是"这条色彩管线原本由运行时间驱动"的凭据。
 * <p>✅ <b>没变的部分（一格都没动）</b>：七档停靠点、十进制色值、{@code lerpSRGB} 的
 * sRGB↔线性插值、以及"起点橙红、末段冷蓝"的整体观感。
 * <p>🔴 <b>它仍然只喂渲染</b>：产出倍率 / {@code duration} / {@code EUt} 一个字都不读它；
 * <b>等级也没有被新增到任何别的数值链</b>（它本来就在驱动 N3 与 N5）。
 *
 * <h2>🔴 2026-09-23 收口：默认【回到原光谱 7 档】，彩虹表降为可选档</h2>
 * 用户裁决原文：「<b>A · 默认用原来的 7 档（推荐）</b> —— 回到你之前定的那个
 * （Lv.1 橙红 → 中段近白 → Lv.17 冷蓝）；<b>彩虹只当手动模式里的一个选项，不抢默认位</b>」。
 * <pre>
 *   默认（跟随等级）  ⇒ {@link #rgbFloatsFromModuleLevel} ← {@link #COLORS}（原光谱：橙红 → 近白 → 冷蓝）
 *   可选的自动配色档  ⇒ {@link #rgbFloatsFromLevelRainbow} ← {@link #RAINBOW_COLORS}（玩家在面板上按按钮才启用）
 *   手动档            ⇒ {@link #rgbFloatsFromHue}（单色色相环，与上面两张表无关）
 * </pre>
 * ⚠️ <b>留档勘误（不许再犯）</b>：2026-09-22 本类头部曾写成「默认档换成彩虹七档」—— 那是<b>错的</b>：
 * 队长那条 C 项描述自相矛盾（原文「C · 自动彩虹（<b>就现在那 7 档</b>，只是允许手动覆盖）」），
 * 我按"自动彩虹"四个字实现了彩虹默认。用户随即裁决回到原光谱 ⇒ 本轮已收口。
 * ⇒ 现在 {@link #rgbFloatsFromModuleLevel} 的调用点<b>重新是 1</b>（不再是"零调用点留档"）。
 * <p>🔴 <b>三档全部仍由等级驱动</b>（{@code ratio = level / maxLevel}），
 * "颜色随等级、不随时间"这条 2026-09-22 的裁定一个字没动。
 *
 * <hr>
 * <p>—— 以下为原文（2026-09-21 撰写）——
 *
 * <p>中子星颜色随时间变化的<b>光谱渐变</b>（纯计算，无状态、无 MC 依赖）。
 *
 * <h2>来源（不是照抄转述，是反编译核对过的原文）</h2>
 * {@code [字节码]} 用 CFR 0.152 反编译 {@code libs/gtladditions-3.2.8Custom-fix1.jar}：
 * <pre>
 * // com.gtladd.gtladditions.utils.StarGradient
 * public final int getRGBFromTime(double ratio) {
 *     double clampedRatio = this.clamp01(ratio);
 *     int n = SPECTRAL_STOPS.length;
 *     for (int index = 1; index &lt; n; ++index) {
 *         Pair&lt;Double,Integer&gt; previousStop = SPECTRAL_STOPS[index-1];
 *         Pair&lt;Double,Integer&gt; currentStop  = SPECTRAL_STOPS[index];
 *         if (!(clampedRatio &lt;= currentStop.getFirst())) continue;
 *         double segmentRatio = (clampedRatio - previousStop.getFirst())
 *                             / (currentStop.getFirst() - previousStop.getFirst());
 *         return this.lerpSRGB(previousStop.getSecond(), currentStop.getSecond(), segmentRatio);
 *     }
 *     return ArraysKt.last(SPECTRAL_STOPS).getSecond();
 * }
 * static {
 *     SPECTRAL_STOPS = { 0.00 -&gt; 15755069, 0.16 -&gt; 16751437, 0.34 -&gt; 16765805,
 *                        0.50 -&gt; 16774872, 0.64 -&gt; 16777215, 0.80 -&gt; 13098495,
 *                        1.00 -&gt;  8760063 };
 * }
 * // lerpSRGB：R/G/B 各自先 sRGB→线性、再线性插值、再线性→sRGB、clamp01 后 round 到 0..255
 * sRGBToLinear(c) : c &lt;= 0.04045 ? c/12.92 : pow((c+0.055)/1.055, 2.4)
 * linearToSRGB(c) : c &lt;= 0.0031308 ? c*12.92 : 1.055*pow(c, 1/2.4) - 0.055
 * </pre>
 * 七档色值换算（本项目自己算的，不是抄别人的表）：
 * <pre>
 *   0.00 → 0xF0673D (橙红)    0.16 → 0xFF9B4D    0.34 → 0xFFD36D    0.50 → 0xFFF6D8
 *   0.64 → 0xFFFFFF (纯白)    0.80 → 0xC7DDFF    1.00 → 0x85AAFF (冷蓝)
 * </pre>
 * 即：<b>刚开机的橙红 → 中段白 → 长时间运行后的冷蓝</b>，与用户「原版伪神之锻炉的中子星颜色会变」一致。
 *
 * <h2>时间 → ratio 的映射也是上游原文</h2>
 * <pre>
 * // com.gtladd.gtladditions.common.machine.multiblock.controller.ForgeOfTheAntichrist
 * public final int getRgbFromTime() {
 *     return StarGradient.INSTANCE.getRGBFromTime(
 *             Math.max(0.0, Math.min(1.0, 1.0 - Math.exp(-(double) this.runningSecs / (double) 14400))));
 * }
 * public static final int MAX_EFFICIENCY_SEC = 14400;   // = 4 小时
 * </pre>
 * 常数 {@code 14400} = {@code MAX_EFFICIENCY_SEC}，就是原版那台机器的「满效率秒数」。
 *
 * <h2>🔴 本类只喂渲染</h2>
 * 它<b>不参与任何数值计算</b>：产出倍率、{@code duration}、{@code EUt} 一个字都不读它。
 * 本工程已经裁定<b>不做</b>原版那套"按运行时间给产出/耗能倍率"的机制（用户只要求视觉）。
 */
final class PrimordialStarGradient {

    /** 上游 {@code MAX_EFFICIENCY_SEC}：4 小时 = 14400 秒，ratio 的时间常数。 */
    static final double MAX_EFFICIENCY_SEC = 14400.0D;

    /**
     * 手动色相档（档 A）固定的饱和度 / 明度。
     *
     * <p>面板<b>只</b>暴露一根色相环，这两个值由代码保证 —— 这样玩家怎么拧都不会把星体
     * 调成全黑/纯灰（那看起来像"渲染坏了"，而本项目忌讳"看起来坏了"的假象）。
     */
    static final double HUE_SATURATION = 0.85D;
    static final double HUE_VALUE = 1.0D;

    /** 光谱停靠点（与反编译原文逐字一致）。 */
    private static final double[] STOPS = {0.00D, 0.16D, 0.34D, 0.50D, 0.64D, 0.80D, 1.00D};
    /** 每个停靠点的 sRGB 色值（与反编译原文逐字一致，十进制原样保留便于对账）。 */
    private static final int[] COLORS = {15755069, 16751437, 16765805, 16774872, 16777215, 13098495, 8760063};

    /**
     * <b>彩虹档停靠点色值</b>（<b>可选档</b>：玩家在主机侧栏面板上按「配色：光谱 ⇄ 彩虹」才启用；
     * 停靠点位置与 {@link #STOPS} <b>完全一致</b>）。
     *
     * <p>由「色相 0/50/110/160/210/260/300°、S=0.85、V=1.0」经 {@link #hsvToRgb} 纯计算得出
     * （不是抄来的表，可复算）：
     * <pre>
     *   0.00 → 16721446 = 0xFF2626 红
     *   0.16 → 16767782 = 0xFFDB26 橙黄
     *   0.34 →  4914982 = 0x4AFF26 黄绿
     *   0.50 →  2555831 = 0x26FFB7 青绿
     *   0.64 →  2528255 = 0x2693FF 蓝
     *   0.80 →  7218943 = 0x6E26FF 紫
     *   1.00 → 16721663 = 0xFF26FF 品红
     * </pre>
     * ⇒ 与光谱档同构：等级 0=槽未生效走红端、Lv.17 走品红端，中间是标准七彩。
     * <p>🔴 <b>它不是默认档</b>（默认是 {@link #COLORS} 那套上游光谱）—— 见类头部「2026-09-23 收口」。
     */
    private static final int[] RAINBOW_COLORS = {16721446, 16767782, 4914982, 2555831, 2528255, 7218943, 16721663};

    private PrimordialStarGradient() {}

    /**
     * <b>物质模块等级 → {@code [0, 1]} 的渐变位置</b>（2026-09-22 现行口径）。
     *
     * <pre>
     *   ratio = clamp01(level / maxLevel)
     *   level = 0             ⇒ 0.000 ⇒ 0xF0673D 橙红 —— 语义 = 专属槽未生效（空槽/不满 64/非物质模块）
     *   level = 9             ⇒ 0.529 ⇒ ≈0xFFF6D8 近纯白
     *   level = maxLevel (17) ⇒ 1.000 ⇒ 0x85AAFF 冷蓝
     * </pre>
     * <p>🔴 <b>归一化与星体半径完全一致</b>（同为 {@code level / 17}）⇒
     * <b>颜色与尺寸同步推进</b>：球长到多大，就对应渐变走到哪一档；改映射只需改这一处，两者不会漂移。
     * <p>{@code maxLevel <= 0} 时返回 {@code 0}（退化保护：不做除法，也不会得到 {@code NaN}/{@code Infinity}）。
     *
     * @param level    专属槽等级（{@code 0} = 未生效，{@code 1..17} = 生效等级）
     * @param maxLevel 等级上限（本工程 = 17，来自 {@code PrimordialModuleMachine.MODULE_LEVELS} 的项数）
     */
    static double ratioFromModuleLevel(int level, int maxLevel) {
        if (maxLevel <= 0 || level <= 0) {
            return 0.0D;
        }
        return clamp01((double) Math.min(level, maxLevel) / (double) maxLevel);
    }

    /**
     * ⛔ <b>【已作废 · 2026-09-22，已无调用点；保留原文仅作留档】</b>
     *
     * <p>旧的驱动量：运行秒数 → {@code [0, 1]} 的渐变位置（饱和曲线，4 小时后趋近 1 但永不到达）。
     * <p><b>作废原因</b>：见本类头部「2026-09-22 口径变更」—— 用户裁定颜色随<b>模块等级</b>、不随时间；
     * 主机上的 {@code runningSecs} 字段与整条 {@code @DescSynced} 同步链已一并删除。
     *
     * <p>{@code runningSecs <= 0} ⇒ 0（刚开机 = 橙红端），不会出现负数进 {@code exp}。
     */
    static double ratioFromRunningSecs(long runningSecs) {
        if (runningSecs <= 0L) {
            return 0.0D;
        }
        return clamp01(1.0D - Math.exp(-(double) runningSecs / MAX_EFFICIENCY_SEC));
    }

    /**
     * 渐变位置 → RGB（{@code 0xRRGGBB}，<b>不含 alpha</b>）。
     *
     * <p>逐字等价于上游 {@code StarGradient.getRGBFromTime}（含 sRGB↔线性插值）。
     */
    static int rgbFromRatio(double ratio) {
        final double r = clamp01(ratio);
        for (int index = 1; index < STOPS.length; index++) {
            if (r > STOPS[index]) {
                continue;
            }
            final double segment = (r - STOPS[index - 1]) / (STOPS[index] - STOPS[index - 1]);
            return lerpSRGB(COLORS[index - 1], COLORS[index], segment);
        }
        return COLORS[COLORS.length - 1];
    }

    /**
     * <b>便捷入口（现行）</b>：模块等级 → {@code {r, g, b}} 的 {@code 0..1} 浮点分量（渲染器要的形态）。
     *
     * <p>与 {@link #rgbFromRatio(double)} 是同一套算法 —— 本方法只是把
     * {@link #ratioFromModuleLevel(int, int)} 接上去，<b>不复制任何插值逻辑</b>。
     */
    static float[] rgbFloatsFromModuleLevel(int level, int maxLevel) {
        return toFloats(rgbFromRatio(ratioFromModuleLevel(level, maxLevel)));
    }

    /**
     * <b>便捷入口（可选档 · 彩虹）</b>：模块等级 → 彩虹档的 {@code {r, g, b}} 浮点分量。
     *
     * <p>🔴 <b>默认入口不是本方法</b>，而是 {@link #rgbFloatsFromModuleLevel(int, int)}（原光谱）。
     * 本方法只被主机 {@code starPalette == STAR_PALETTE_RAINBOW}（玩家按过面板上那个按钮）时调用。
     *
     * <p>归一化与 {@link #rgbFloatsFromModuleLevel(int,int)} <b>用的是同一个方法</b>
     * （{@link #ratioFromModuleLevel(int,int)}），差别只在查哪张表：
     * 本方法查 {@link #RAINBOW_COLORS}（彩虹），另一个查 {@link #COLORS}（上游光谱）。
     *
     * <pre>
     *   level = 0             ⇒ 0.000 ⇒ 0xFF2626 红    （语义 = 专属槽未生效）
     *   level = 9             ⇒ 0.529 ⇒ 约 0x26FFB7 青绿
     *   level = 17            ⇒ 1.000 ⇒ 0xFF26FF 品红
     * </pre>
     *
     * <p>🔴 <b>插值仍然走 {@link #lerpSRGB}</b>（sRGB↔线性），所以档与档之间是平滑过渡而不是硬切。
     */
    static float[] rgbFloatsFromLevelRainbow(int level, int maxLevel) {
        return toFloats(rgbFromStops(RAINBOW_COLORS, ratioFromModuleLevel(level, maxLevel)));
    }

    /**
     * <b>便捷入口（可选档 · 彩虹 + 随时间推进）</b>：模块等级 + 时钟 → 彩虹档的 {@code {r, g, b}} 浮点分量。
     *
     * <p>🔴 <b>2026-09-23 新增（用户拍板「A · 只让【彩虹】档随时间动」）。</b>
     * 与三参版的关系只有一处差别：查表前先把 {@code ratio} 过一遍 {@link #trianglePhase(double, double, double)}
     * —— <b>插值、色表、归一化一个字都没动</b>。
     *
     * <h2>默认路径与"加这个功能之前"逐位相同</h2>
     * <pre>
     *   phaseTicks &lt;= 0（含默认值 0 与 NaN） ⇒ trianglePhase 直接返回 base
     *                                        ⇒ 结果 == {@link #rgbFloatsFromLevelRainbow(int, int)} 逐位相同
     * </pre>
     * ⇒ 主机侧 {@code starRainbowPeriodTicks} 默认 {@code 0}，所以<b>不按那个按钮时颜色与以前一模一样</b>；
     * 而且本方法只在<b>彩虹档</b>被调用（光谱档走 {@link #rgbFloatsFromModuleLevel(int, int)}），
     * 2026-09-22 那条「颜色随等级、不随时间」的裁定对<b>默认档</b>依然完整成立。
     *
     * <h2>为什么 {@code base} 仍然带等级</h2>
     * 保留了本类既有的「颜色与尺寸同源」性质（{@code ratio = level / maxLevel}，与星体半径同一个归一化）：
     * 不同等级的球，同一时刻处在往返周期的<b>不同位置</b>，而不是全世界的球整齐划一地一起变色。
     *
     * <p>周期语义：{@code phaseTicks} = <b>红 → 品红 → 红</b> 一个完整往返所需 tick 数（20 tick = 1 秒）。
     *
     * @param level      专属槽等级（{@code 0} = 未生效，{@code 1..17} = 生效等级）
     * @param maxLevel   等级上限（本工程 = 17）
     * @param tick       连续客户端时钟（{@code RenderUtil.getSmoothTick}，与呼吸脉动同一个源）
     * @param phaseTicks 往返周期（tick）；{@code <=0} / 非有限 ⇒ 静止（回退到三参版的静态行为）
     */
    static float[] rgbFloatsFromLevelRainbow(int level, int maxLevel, double tick, double phaseTicks) {
        return toFloats(rgbFromStops(RAINBOW_COLORS,
                trianglePhase(ratioFromModuleLevel(level, maxLevel), tick, phaseTicks)));
    }

    /**
     * <b>三角波往返相位</b>：{@code tri(0)=0 → tri(0.5)=1 → tri(1)=0}，连续、无跳变。
     *
     * <h2>为什么是"往返"而不是"取模循环"</h2>
     * {@link #RAINBOW_COLORS} 的首档是红 {@code 0xFF2626}、末档是品红 {@code 0xFF26FF} ——
     * <b>它不是一个闭环</b>。若用 {@code frac} 循环，每轮都会在 {@code ratio 1.0 → 0.0} 处出现一次
     * <b>品红硬跳回红</b>的视觉断裂（本项目明令避免"看起来坏了"的形态）。
     * 三角波让相位在 {@code [0,1]} 之间往返 ⇒ 红→品红→红，连着走没有断口。
     *
     * <h2>🔴 第一行必须是那道除零/NaN 闸门（本设计自己新开的唯一 NaN 入口）</h2>
     * <pre>
     *   phaseTicks == 0  ⇒  tick / 0 == Infinity  ⇒  Infinity % 1.0 == NaN   （本机实测）
     *   ⇒ NaN 会一路穿过 clamp01（clamp01(NaN) 仍是 NaN，因为 x&lt;0 与 x&gt;1 对 NaN 都是 false）
     *   ⇒ Math.round((float) NaN) 在 Java 里返回 0（JDK 语义）
     *   ⇒ 球【静默变黑】，日志里什么都没有 —— 正是本项目最忌讳的形态
     * </pre>
     * ⇒ 所以这里用 {@code !(phaseTicks > 0.0D)} 这种<b>刻意</b>的写法：
     * {@code !(x > 0)} 对 {@code 0}、负数、{@code NaN} <b>全部</b>为真 ⇒ 三者都走进"回退到 {@code base}"那一句。
     * 写成 {@code phaseTicks <= 0} 就<b>挡不住 NaN</b>，这是本条不可省的唯一原因。
     * <p>同理挡掉非有限的 {@code tick} / {@code base}：半径链早就有 {@code sanitizeRadius} 兜底，
     * 颜色链此前一道都没有 —— 这里把两条链的防御层级对齐（都退到"安全且语义明确"的那一端）。
     *
     * @param base       静止时的渐变位置（本工程 = {@link #ratioFromModuleLevel(int, int)}）
     * @param tick       连续时钟（tick）
     * @param phaseTicks 一个完整往返所需 tick 数；{@code <=0} / NaN / 非有限 ⇒ 返回 {@code base}（静止）
     * @return 推进后的渐变位置，落在 {@code [0, 1]}
     */
    static double trianglePhase(double base, double tick, double phaseTicks) {
        if (!(phaseTicks > 0.0D) || !Double.isFinite(tick) || !Double.isFinite(base)) {
            return base;                                     // 🔴 除零/NaN 的唯一出口（默认值就是 0，这一句是必经之路）
        }
        final double u = (base + tick / phaseTicks) % 1.0D;
        final double f = u - Math.floor(u);
        return 1.0D - Math.abs(2.0D * f - 1.0D);
    }

    /**
     * <b>手动色相（手动档）</b>：{@code hue}（度，任意整数，自动取模）→ {@code {r, g, b}} 浮点分量。
     *
     * <p>固定 {@code S=0.85 / V=1.0}：不加这两个旋钮是刻意的 —— 面板只暴露「一根色相环」，
     * 饱和度/明度由代码保证，这样玩家怎么拧都不会把星体调成全黑或纯灰而看起来像"坏了"。
     *
     * <p>⚠️ 本方法<b>不走</b> {@link #lerpSRGB}：单色档没有"两个停靠点之间"这回事，
     * 色相→RGB 是直接转换（避免多一次线性/伽马往返把色相挪位）。
     */
    static float[] rgbFloatsFromHue(int hue) {
        return toFloats(hsvToRgb(((hue % 360) + 360) % 360, HUE_SATURATION, HUE_VALUE));
    }

    /**
     * <b>通用查表插值</b>（{@code ratio} → 表内色值），停靠点位置统一用 {@link #STOPS}。
     *
     * <p>与 {@link #rgbFromRatio(double)} 是同一套算法；之所以另开一个方法而不是把
     * {@code rgbFromRatio} 改成"接受一张表"，是因为后者的 javadoc 逐字标注了它与上游
     * {@code StarGradient.getRGBFromTime} 的等价性（留档价值），改它的签名会让那段留档失准。
     */
    private static int rgbFromStops(int[] colors, double ratio) {
        final double r = clamp01(ratio);
        for (int index = 1; index < STOPS.length; index++) {
            if (r > STOPS[index]) {
                continue;
            }
            final double segment = (r - STOPS[index - 1]) / (STOPS[index] - STOPS[index - 1]);
            return lerpSRGB(colors[index - 1], colors[index], segment);
        }
        return colors[colors.length - 1];
    }

    /**
     * 色相环（HSV）→ {@code 0xRRGGBB}。<b>纯算术，不引 MC 的 {@code Mth.hsvToRgb}</b>：
     * 本类被 {@code PrimordialStarGradient} 头部声明为「纯计算、无 MC 依赖」，加一个
     * {@code net.minecraft} import 就破坏了这个性质（而本类只在客户端跑，没有别的理由去碰 MC）。
     *
     * @param hue        色相（度，调用方已取模到 {@code [0,360)}）
     * @param saturation 饱和度 {@code [0,1]}，本工程固定 {@link #HUE_SATURATION}
     * @param value      明度 {@code [0,1]}，本工程固定 {@link #HUE_VALUE}
     */
    private static int hsvToRgb(double hue, double saturation, double value) {
        final double h = (((hue % 360.0D) + 360.0D) % 360.0D) / 60.0D;
        final int sector = (int) Math.floor(h);
        final double f = h - sector;
        final double p = value * (1.0D - saturation);
        final double q = value * (1.0D - saturation * f);
        final double t = value * (1.0D - saturation * (1.0D - f));
        final double[] rgb = switch (sector % 6) {
            case 0 -> new double[] {value, t, p};
            case 1 -> new double[] {q, value, p};
            case 2 -> new double[] {p, value, t};
            case 3 -> new double[] {p, q, value};
            case 4 -> new double[] {t, p, value};
            default -> new double[] {value, p, q};
        };
        return (channel(rgb[0]) << 16) | (channel(rgb[1]) << 8) | channel(rgb[2]);
    }

    private static int channel(double v) {
        return Math.round((float) (clamp01(v) * 255.0D));
    }

    /**
     * ⛔ <b>【已作废 · 2026-09-22，已无调用点；保留原文仅作留档】</b>
     * 旧的便捷入口：运行秒数 → {@code {r, g, b}} 的 {@code 0..1} 浮点分量。
     */
    static float[] rgbFloatsFromRunningSecs(long runningSecs) {
        return toFloats(rgbFromRatio(ratioFromRunningSecs(runningSecs)));
    }

    /** {@code 0xRRGGBB} → {@code {r, g, b}} 的 {@code 0..1} 浮点分量（两个入口共用，避免复制）。 */
    private static float[] toFloats(int rgb) {
        return new float[] {
                ((rgb >> 16) & 0xFF) / 255.0F,
                ((rgb >> 8) & 0xFF) / 255.0F,
                (rgb & 0xFF) / 255.0F,
        };
    }

    /** 上游 {@code lerpSRGB}：把两端的 sRGB 分量转线性、各分量线性插值、再转回 sRGB 并四舍五入。 */
    private static int lerpSRGB(int colorA, int colorB, double t) {
        final int rA = (colorA >> 16) & 0xFF;
        final int gA = (colorA >> 8) & 0xFF;
        final int bA = colorA & 0xFF;
        final int rB = (colorB >> 16) & 0xFF;
        final int gB = (colorB >> 8) & 0xFF;
        final int bB = colorB & 0xFF;
        final double lr = sRGBToLinear(rA / 255.0D) + (sRGBToLinear(rB / 255.0D) - sRGBToLinear(rA / 255.0D)) * t;
        final double lg = sRGBToLinear(gA / 255.0D) + (sRGBToLinear(gB / 255.0D) - sRGBToLinear(gA / 255.0D)) * t;
        final double lb = sRGBToLinear(bA / 255.0D) + (sRGBToLinear(bB / 255.0D) - sRGBToLinear(bA / 255.0D)) * t;
        final int r = Math.round((float) (clamp01(linearToSRGB(lr)) * 255.0D));
        final int g = Math.round((float) (clamp01(linearToSRGB(lg)) * 255.0D));
        final int b = Math.round((float) (clamp01(linearToSRGB(lb)) * 255.0D));
        return r << 16 | g << 8 | b;
    }

    private static double sRGBToLinear(double c) {
        return c <= 0.04045D ? c / 12.92D : Math.pow((c + 0.055D) / 1.055D, 2.4D);
    }

    private static double linearToSRGB(double c) {
        return c <= 0.0031308D ? c * 12.92D : 1.055D * Math.pow(c, 1.0D / 2.4D) - 0.055D;
    }

    private static double clamp01(double x) {
        return x < 0.0D ? 0.0D : (x > 1.0D ? 1.0D : x);
    }
}
