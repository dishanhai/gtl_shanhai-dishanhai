package com.dishanhai.gt_shanhai.client.renderer.machine;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import com.gtladd.gtladditions.client.RenderMode;
import com.gtladd.gtladditions.client.render.machine.antichrist.AntichristDeferredRenderer;
import com.gtladd.gtladditions.client.render.machine.antichrist.AntichristRenderProfile;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;

/**
 * 中子星渲染：不自绘球体，直接复用伪神之煅炉（{@code gtladditions:forge_of_the_antichrist}）的星体管线。
 * <p>
 * 入队 gtladditions 的 {@code AntichristDeferredRenderer} 后，由伪神锻自己的延迟批次在
 * {@code AFTER_TRANSLUCENT_BLOCKS} 阶段统一绘制 —— 三层球壳、星体着色器、Oculus 光影兼容全部沿用，
 * 不需要我们再维护一套。<b>2026-09-21 起按用户要求<b>点亮等离子体光束</b>（{@code beamAlpha = isWorking ? 1.0 : 0.0}，
 * 严格照上游"只在工作时亮"）—— 详见 {@link #BEAM_ALPHA} 的注释；球体尺寸本类不变。</b>
 *
 * <p>所有 gtladditions 调用都集中在本客户端渲染器，避免重复维护球壳与光束几何。
 */
final class PrimordialNeutronStarSphereRenderer {

    /**
     * 中子星冷白偏蓝色调 —— ⛔ <b>【已退役 · 2026-09-22】</b>，<b>保留声明仅为留档，已无任何调用点。</b>
     *
     * <p>沿革：2026-09-21 起它是「运行时间为 0 时的兜底色」；
     * 2026-09-22 用户裁定颜色改随模块等级后，{@code moduleBonus == 0} 有了唯一且明确的语义
     * （槽未生效 = 渐变起点橙红），<b>不再需要这个兜底色</b>（理由详见 {@link #colorOf(int)} 的 javadoc）。
     * <p>⚠️ <b>留着而不删</b>：按本工程「作废项保留原文」原则，
     * 它是"这里曾经有一套冷白偏蓝兜底规则"的凭据；将来若有人问"球为什么不再偏蓝"，答案就在这里。
     */
    private static final float COLOR_R = 0.72f;
    private static final float COLOR_G = 0.86f;
    private static final float COLOR_B = 1.00f;

    /** 与伪神锻同一基准半径，叠加轻微呼吸脉动（基准值取自 gtladditions，见网关注释）。 */
    private static final float BASE_RADIUS = AntichristRenderProfile.BASE_STAR_RADIUS;
    private static final float PULSE_AMPLITUDE = 0.035f;
    private static final float PULSE_PERIOD_TICKS = 18.0f;

    // ───────────────────── N7：星体尺寸随「主机物质模块专属槽」等级放大（2026-09-21） ─────────────────────

    /** 门控生效（专属槽满 64 个同种物质模块）时 Lv.17 的半径。 */
    private static final float MAX_BONUS_RADIUS = 35.1f;

    /** 物质模块等级上限（与 {@code PrimordialModuleMachine.MODULE_LEVELS} 的 17 项一致）。 */
    private static final int MAX_MODULE_LEVEL = 17;

    /**
     * 🔴 <b>硬上限 44.78</b>：上游 {@code getStartAngle} 用
     * {@code asin(starRadius / sqrt(44.5^2 + 5.0^2))}（分母 = √2005.25 = <b>44.7805…</b>）⇒ 半径超过它
     * asin 的参数就 &gt; 1，<b>返回 NaN 并把等离子体光束几何整个弄坏</b>。
     * 本上限与 {@link #BEAM_ALPHA} 注释里那条约束是同一个数。
     *
     * <p>🔴 <b>2026-09-23 这个数从"防御性注释"变成了"真事故"</b>：用户把面板半径点到 44 后客户端闪退，
     * 堆栈是 {@code AntichristBeamRenderer.packAlpha} 里 {@code roundToInt(NaN)} 抛
     * {@code IllegalArgumentException: Cannot round NaN value}（crash-2026-09-23_19.53.02-client.txt）。
     * ⇒ 这条上限<b>不是保险绳、是悬崖边</b>，{@code 44.78} 本身就在临界点上（{@code asin(1)} 只是恰好不 NaN）。
     * 所以真正用来钳位的是下面留了余量的 {@link #SAFE_MAX_STAR_RADIUS}，本常量从此<b>只作留档/解释</b>。
     */
    private static final float HARD_MAX_RADIUS = 44.78f;

    /**
     * 🔴 <b>实际钳位用的安全上限 44.6</b>（= 44.7805 留 0.18 余量）。
     *
     * <h2>为什么必须有它（2026-09-23 闪退根因）</h2>
     * <pre>
     *   asin 的域           : starRadius ≤ 44.7805
     *   面板手动半径上限     : 44（int）
     *   呼吸脉动             : r × (1 ± 0.035)   ← 乘在基数【之后】
     *   ⇒ 44 × 1.035 = 45.54 > 44.7805 ⇒ NaN ⇒ 光束顶点 NaN ⇒ packAlpha 的 roundToInt 抛异常 ⇒ 客户端崩
     *   43 × 1.035 = 44.505 < 44.7805 ⇒ 安全
     * </pre>
     * ⇒ <b>规则：钳位必须发生在"乘完脉动之后"</b>，否则任何"基数在域内"的判断都会被脉动悄悄推翻
     * （这正是本轮踩的坑：旧代码把 {@code min} 钳在脉动<b>之前</b>，而旧的最大基数 35.1 离域很远，
     * 所以那个错误一直没被发现）。
     *
     * <p>留 0.18 余量而不是贴着 44.7805：{@code sqrt} 与除法都是 float 运算，贴着边界可能算出
     * 1.0000001 ⇒ 又 NaN。余量把浮点舍入彻底排除在外。
     */
    private static final float SAFE_MAX_STAR_RADIUS = 44.6f;

    /** 非有限半径 WARN 的最小间隔（ms）—— 这是逐帧路径，不节流会把日志刷爆（见 {@link #sanitizeRadius}）。 */
    private static final long NON_FINITE_WARN_MIN_GAP_MS = 5000L;

    /**
     * 上一次「非有限半径」WARN 的时间戳（ms）。
     *
     * <p>静态可变状态是<b>安全的</b>：本类只在客户端渲染线程被调用（TESR 的
     * {@code renderSpecialEffects} → 本方法），单线程访问，没有并发问题。
     */
    private static long lastNonFiniteWarnMs;

    /**
     * 等离子体光束的开关值（2026-09-21 队长裁决：**点亮**，严格按上游"只在工作时亮"）。
     *
     * <h2>为什么改这一个常量就能点亮整道光束</h2>
     * 上游 gtladditions 的光束实现是 {@code AntichristBeamRenderer}（10 段 × 16 边形截面的变半径等离子管，
     * 经 3 个"透镜"收腰到半径 1.1，再在机器正面 -121.5 处张开到半径 13；soft + intense 两遍），
     * 它自带材质（{@code space_layer.png}）、自定义着色器（{@code gtladditions_antichrist_beam.*}）
     * 与 Iris 桥接 —— <b>全部由 gtladditions 在 MOD 总线上自行注册</b>。
     * 它的唯一门禁是 {@code AntichristRenderProfile.shouldRenderBeam = beamAlpha > 0.001F}，
     * 而 {@code beamAlpha} 正是我们从这里传进去的：
     * <pre>
     *   AntichristBeamRenderer.render():  if (profile.getShouldRenderBeam()) { … }  // 否则第一行 return
     * </pre>
     * ⇒ 传 0 就"整道光束消失"，传 1.0 就"整道光束出现"，**不需要我们自绘几何、不需要新增任何资源**。
     * （历史：原版私货 gt_shanhai 3.0.3 也是传 0 ⇒ 这是**继承来的设计**，点亮是"新增还原"而非"修回归"。）
     *
     * <h2>取值口径</h2>
     * 严格照上游：上游只在 {@code isWorking == true} 时才把星体+光束入队，{@code RenderMode.NORMAL} 下
     * {@code beamAlpha} 恒为 {@code 1.0F}（不淡化）⇒ 我们这里同样用 `isWorking ? 1.0F : 0.0F`。
     *
     * <h2>🔴 一条硬约束（给"把球做得更大"时用）</h2>
     * 上游 {@code getStartAngle} 用 {@code asin(starRadius / sqrt(44.5^2 + 5.0^2))} ⇒
     * <b>{@code starRadius > 44.78} 会返回 NaN，把光束几何整个弄坏</b>（上游最大 35.1，余量 9.7）。
     * ⇒ <b>球体半径上限 44.78，不能无限放大</b>（已写入客户端验收清单）。
     */
    private static final float BEAM_ALPHA = 1.0f;

    private PrimordialNeutronStarSphereRenderer() {}

    /**
     * @param continuousTick <b>必须是连续时钟</b>（{@code RenderUtil.getSmoothTick}）。
     *                       球体常驻可见，任何在工作状态翻转时归零的时钟都会被直接渲染成一次姿态突跳：
     *                       {@code AntichristStarRenderer} 把 tick 当累积角度用
     *                       （{@code base + tick*mult % 360000}），三层球壳无插值无状态，时钟一跳就是
     *                       整颗球猛地翻一下。
     * @param moduleBonus    主机「物质模块专属槽」的门控结果（{@code PrimordialOmegaEngineMachine
     *                       #moduleSlotBonus()}）：{@code 0} = 未生效，{@code 1..17} = 生效等级。
     *                       取值在调用点上完成（见 {@code PrimordialOmegaEngineRenderer}），
     *                       本类<b>不做</b>任何门控判断 —— 门控只有一处实现。
     * @param radiusOverride <b>手动半径覆盖</b>（{@code ≤0} = 无覆盖，走等级的 {@link #baseRadiusFor(int)}）。
     *                       调用点已经过「专属槽等级 ≥ 15 + 手动档」两道判断；本类只做数值钳制。
     * @param hueOverride    <b>手动色相覆盖</b>（{@code <0} = 无覆盖，走自动配色档）。
     *                       同上：档位判断在调用点，本类不做门控。
     * @param rainbowPalette <b>自动配色档</b>：{@code false} = 原光谱 7 档（<b>默认，用户裁决</b>）；
     *                       {@code true} = 彩虹 7 档（可选档）。只在没有 {@code hueOverride} 时有意义
     *                       （手动档的颜色是一根色相环，压根不看这个布尔）。
     * @param rainbowPeriodTicks <b>彩虹变化周期</b>（tick；{@code ≤0} = 不变化 = <b>默认</b>）。
     *                       只在 {@code rainbowPalette == true} 且有 {@code rainbowPeriodTicks > 0} 时起作用，
     *                       其余情况一律走静态路径 ⇒ <b>默认渲染与加这个参数之前逐位相同</b>。
     *                       时钟复用已经传进来的 {@code continuousTick}（<b>没有第二口时钟</b>）。
     * ⛔ <b>【已删除 · 2026-09-22】</b> 原本还有第 5 个参数
     * {@code long runningSecs}（主机累计运行秒数，只用来算颜色）。用户裁定
     * 「**不希望光束的颜色随着时间变化，而希望它随着物质模块的等级变化**」⇒
     * 该参数与 {@code PrimordialOmegaEngineMachine#getRunningSecs()} 及其 {@code @DescSynced} 链
     * **一并删除**（原文留档见 {@code PrimordialOmegaEngineMachine} 的
     * 「中子星的『运行时间』（🟡 已作废 · 原文留档）」整节）。
     */
    static void enqueue(BlockEntity blockEntity, Direction facing, float continuousTick, boolean isWorking,
                        int moduleBonus, int radiusOverride, int hueOverride, boolean rainbowPalette,
                        int rainbowPeriodTicks) {
        Vec3 starPos = PrimordialSphereAnchor.center(facing);
        // 🔴 颜色与半径【同源】：默认都是 moduleBonus（主机专属槽的模块等级）；手动档才由覆盖值接管。
        //    两个覆盖值都只可能来自主机（`PrimordialOmegaEngineMachine` 的 @DescSynced 字段），
        //    本类不自己读槽、不自己数 64 —— 门控与档位判断仍然只有一处实现。
        // 🔴 2026-09-23：颜色分支多加一个"彩虹相位"入参。它【只进颜色分支】——
        //    下面那行半径分支一个字符都没动（见类头 `SAFE_MAX_STAR_RADIUS` 那场 NaN 事故的教训）。
        float[] color = hueOverride >= 0
                ? PrimordialStarGradient.rgbFloatsFromHue(hueOverride)
                : colorOf(moduleBonus, rainbowPalette, continuousTick, rainbowPeriodTicks);
        float baseRadius = radiusOverride > 0
                ? Math.min(radiusOverride, SAFE_MAX_STAR_RADIUS)
                : baseRadiusFor(moduleBonus);

        float radius = sanitizeRadius(pulseRadius(baseRadius, continuousTick), moduleBonus);
        AntichristRenderProfile profile = new AntichristRenderProfile(
                continuousTick,
                isWorking,
                facing,
                RenderMode.NORMAL,
                starPos,
                color[0], color[1], color[2],
                radius,
                isWorking ? BEAM_ALPHA : 0.0f);
        AntichristDeferredRenderer.INSTANCE.enqueue(blockEntity, profile);
    }

    /**
     * ⛔⛔ <b>【本节已作废 · 2026-09-22 用户裁定，原文一字未改、仅作留档】</b>
     *
     * <p><b>作废原因（用户原话）</b>：「<b>9的话我不希望光束的颜色随着时间变化，
     * 而希望它随着物质模块的等级变化</b>」
     * ⇒ 色源从<b>运行时间</b>换成<b>专属槽里那个模块的等级</b>（与星体尺寸【同一个源】：
     * {@code moduleBonus}）。下面这段描述的是<b>旧的</b>时间口径。
     *
     * <p>⚠️ <b>下面这段原文里"免费"那一条为什么仍然成立</b>（所以它不算白写）：
     * 光束颜色照样是读<b>同一个 profile 的三通道</b>
     * （{@code AntichristBeamRenderer} 的 {@code BeamColor} uniform ← {@code profile.getColorR/G/B()}）
     * ⇒ <b>换色源之后，光束与星体依然自动同色，我们依然不需要为光束写任何额外代码。</b>
     * 变的只是"那三个通道由什么算出来"。
     *
     * <p>🔴 <b>下面"兜底"那一句已作废</b>：旧口径把 {@code runningSecs <= 0}（含"拿不到主机"）
     * 兜到冷白偏蓝；新口径下 {@code moduleBonus == 0} 就是<b>语义明确的"槽未生效"</b>，
     * 对应光谱渐变<b>起点（橙红）</b>，而<b>不再</b>兜到冷白偏蓝。
     *
     * <hr>
     * <p>—— 以下为原文（2026-09-21 撰写，作废于 2026-09-22）——
     *
     * <p><b>中子星颜色随运行时间变化</b>（2026-09-21 新增，用户要求「原版伪神之锻炉的中子星的颜色是会变的
     * （随着运行时间），以及光束也是会变的」）。
     *
     * <h2>颜色从哪来</h2>
     * {@link PrimordialStarGradient} —— 它逐字移植了 gtladditions
     * {@code com.gtladd.gtladditions.utils.StarGradient} 的七档光谱渐变
     * （{@code [字节码]} 反编译核对过，不是转述），时间映射也用上游原文
     * {@code 1 - exp(-runningSecs / 14400)}：
     * <pre>
     *   runningSecs = 0      → 0xF0673D 橙红
     *   约 4 小时后          → 0x85AAFF 冷蓝（饱和但永不到达，随 1-exp 逼近）
     * </pre>
     *
     * <h2>🔴 光束为什么"跟着变"是免费的</h2>
     * {@code [字节码]} {@code AntichristBeamRenderer} 的原文是
     * <pre>
     *   INSTANCE.renderCurrentSegments(poseStack, shader, profile.getTick(),
     *                                  profile.getColorR(), profile.getColorG(), profile.getColorB(),
     *                                  1.0f, 1.0f, 1.0f);
     *   … uniform "BeamColor" = (colorR, colorG, colorB)
     * </pre>
     * 也就是<b>光束的颜色直接读同一个 profile 的三通道</b>。所以只要把随时间变化的颜色传进 profile，
     * 星体与光束<b>同时</b>变色 —— 不需要为光束写任何额外代码、也不需要新增任何资源。
     *
     * <h2>兜底</h2>
     * 拿不到主机（理论上进不到这条分支）时 {@code runningSecs = 0} ⇒ 用原配色（冷白偏蓝）。
     * 这里刻意<b>不用</b> {@link #COLOR_R}/{@link #COLOR_G}/{@link #COLOR_B} 之外的常量，
     * 以免出现"两套颜色规则"。
     */
    /**
     * <b>中子星颜色随「物质模块专属槽」的等级变化</b>（2026-09-22 用户裁定，取代旧的"随时间"口径）。
     *
     * <h2>色源 = 与星体尺寸【同一个源】</h2>
     * 入参 {@code moduleBonus} 就是 {@link #pulseRadius(float, float)} /
     * {@link #baseRadiusFor(int)} 用的那一个数（{@code PrimordialOmegaEngineMachine#moduleSlotBonus()}）：
     * {@code 0} = 槽未生效（空槽 / 不满 64 / 非物质模块 / 非本类主机），{@code 1..17} = 生效等级。
     * <p>🔴 <b>没有新增第二条链路</b>：等级本来就在驱动 N3 产出倍率与 N5 耗电减免（既有设计），
     * 这里只是<b>颜色也读它</b>。调用点只读一次，本类不做任何门控判断 —— 门控只有一处实现。
     *
     * <h2>映射（与半径同一套归一化，便于肉眼对账）</h2>
     * <pre>
     *   ratio = clamp01(level / 17)            ← 与 baseRadiusFor 的 (level / 17) 是同一个归一化
     *   level =  0（槽未生效） ⇒ ratio 0.000 ⇒ 0xF0673D 橙红
     *   level =  9            ⇒ ratio 0.529 ⇒ ≈0xFFF6D8 近纯白
     *   level = 17            ⇒ ratio 1.000 ⇒ 0x85AAFF 冷蓝
     * </pre>
     * 渐变七档停靠点与 sRGB↔线性插值<b>完全沿用</b> {@link PrimordialStarGradient}（没改过一格）。
     *
     * <h2>为什么不像旧版那样"兜底成冷白偏蓝"</h2>
     * 旧口径下 {@code 0} 同时表示"拿不到主机"与"运行 0 秒"，分不清，所以兜了个固定色。
     * 新口径下 {@code 0} 的含义是明确且唯一的 —— <b>槽未生效 = 渐变起点</b>；
     * 而"拿不到主机"这条分支<b>根本进不来</b>（本类只被 {@code NEUTRON_STAR} 分支调用，
     * 而那条分支只在 {@code sphereStyleOf(machine)} 判定为本类主机时才走）。
     * ⇒ 不再需要第二套颜色规则，{@link #COLOR_R}/{@link #COLOR_G}/{@link #COLOR_B} 就此退役（见其声明处）。
     *
     * <p>🔴 <b>本方法只喂渲染</b>：产出、{@code duration}、{@code EUt} 一个字都不读它。
     *
     * <h2>🔴 2026-09-23 收口：默认回到【原光谱 7 档】，彩虹降为可选档</h2>
     * 用户裁决原文：「<b>A · 默认用原来的 7 档（推荐）</b> —— 回到你之前定的那个
     * （Lv.1 橙红 → 中段近白 → Lv.17 冷蓝）；<b>彩虹只当手动模式里的一个选项，不抢默认位</b>」。
     * ⇒ <b>默认路径（{@code rainbowPalette == false}）= {@code rgbFloatsFromModuleLevel}</b>
     * （与客户端验收清单第 ⑩ 条逐字一致）；彩虹只在主机 {@code starPalette == STAR_PALETTE_RAINBOW}
     * 且玩家在面板上按过那个按钮时才走（{@code true} 分支）。
     * <p>⚠️ <b>留档勘误</b>：2026-09-22 本项目曾把默认改成彩虹（队长那条 C 项描述自相矛盾所致，
     * 原话「C · 自动彩虹（就现在那 7 档，只是允许手动覆盖）」）。那一版默认观感是错的，
     * 本轮已收口 —— 上面「映射」一节里的 0xF0673D/0xFFF6D8/0x85AAFF <b>重新成为默认档的真实色值</b>。
     *
     * <h2>🔴 2026-09-23：彩虹档多一条"随时间推进"的可选相位</h2>
     * 用户拍板「<b>A · 只让【彩虹】档随时间动</b>」⇒ 本方法多收两个参数
     * （{@code tick} / {@code rainbowPeriodTicks}），但<b>只在彩虹分支上转发</b>：
     * <pre>
     *   rainbowPalette == false（默认/光谱档） ⇒ 仍然走 {@code rgbFloatsFromModuleLevel}，
     *                                           上面两个参数【根本不会被读】⇒ 逐位不变
     *   rainbowPalette == true 且 period ≤ 0  ⇒ {@code trianglePhase} 直接返回 base
     *                                           ⇒ 等于三参版彩虹 ⇒ 逐位不变
     *   rainbowPalette == true 且 period  &gt; 0 ⇒ 相位随 {@code continuousTick} 往返推进（新行为）
     * </pre>
     * <p>⚠️ <b>{@code tick} 用的是与呼吸脉动【同一个】连续时钟</b>（{@code RenderUtil.getSmoothTick}），
     * 不是新开的第二口钟 —— 之前那一版"随时间变色"之所以被裁定掉，是因为它是<b>默认</b>行为；
     * 这里它是玩家在面板上主动选的可选档，且默认周期就是"不变化"。
     *
     * @param moduleBonus        同 {@link #enqueue}（主机专属槽的门控结果）
     * @param rainbowPalette     是否彩虹档（{@code false} = 原光谱，默认）
     * @param tick               连续客户端时钟（tick），仅彩虹档 + 有周期时被读
     * @param rainbowPeriodTicks 彩虹往返周期（tick）；{@code ≤0} = 不变化（<b>默认</b>）
     */
    private static float[] colorOf(int moduleBonus, boolean rainbowPalette,
                                   float tick, int rainbowPeriodTicks) {
        final int level = Math.max(0, Math.min(moduleBonus, MAX_MODULE_LEVEL));
        return rainbowPalette
                ? PrimordialStarGradient.rgbFloatsFromLevelRainbow(level, MAX_MODULE_LEVEL,
                        tick, rainbowPeriodTicks)
                : PrimordialStarGradient.rgbFloatsFromModuleLevel(level, MAX_MODULE_LEVEL);
    }

    /**
     * 星体半径 = <b>门控基数</b> × <b>呼吸脉动</b>（脉动保留，叠乘）。
     *
     * <pre>
     *   门控未生效（0）・空槽・不满 64・非物质模块 ⇒ radius = BASE_RADIUS = 13.0（= 上游基准，一点不动）
     *   门控生效（1..17）                          ⇒ radius = 13.0 + (35.1 - 13.0) × (等级 / 17)
     *                                                    Lv.1 ⇒ 14.30 ｜ Lv.9 ⇒ 24.70 ｜ Lv.17 ⇒ 35.10
     * </pre>
     * 即每个等级恰好 +1.3。半径<b>不按 isWorking 开关</b>（与脉动同理：那会让球在停机瞬间硬切一次），
     * 工作与否的反馈交给轨道环转速去表达。
     *
     * <p>⚠️ 只在<b>中子星</b>渲染模式下生效：本类整体只被 {@code NEUTRON_STAR} 那条分支调用，
     * 宇宙模式（{@code UNIVERSE}）走的是 {@code PrimordialUniverseSphereRenderer}，一行都没动。
     */
    private static float baseRadiusFor(int moduleBonus) {
        if (moduleBonus <= 0) {
            return BASE_RADIUS;
        }
        int level = Math.min(moduleBonus, MAX_MODULE_LEVEL);
        float radius = BASE_RADIUS + (MAX_BONUS_RADIUS - BASE_RADIUS) * (level / (float) MAX_MODULE_LEVEL);
        return Math.min(radius, SAFE_MAX_STAR_RADIUS);
    }

    /**
     * 呼吸脉动不按 isWorking 开关：那会让半径在停机瞬间从 13*(1±0.035) 硬切回 13.0。
     * 星体渲染器本身并不读 profile.isWorking，工作与否的反馈交给轨道环转速去表达。
     *
     * <p>🔴 <b>2026-09-22 签名改为收「已经算好的基准半径」</b>（原为 {@code pulseRadius(int moduleBonus, float)}）：
     * 手动档的覆盖半径不再来自 {@link #baseRadiusFor(int)}，若继续在方法内部取基数，
     * 就会变成"调用方选基数、本方法再覆盖一次"的两套规则。现在基数只有一处决定（见 {@link #enqueue}），
     * 本方法只负责叠脉动。
     *
     * <p>🔴🔴 <b>2026-09-23 闪退修复：脉动之后必须再钳一次</b>（这是本轮事故的唯一真正修点）。
     * 逐条理由见 {@link #SAFE_MAX_STAR_RADIUS}：{@code 44 × 1.035 = 45.54 > 44.7805} 会让上游
     * {@code asin} 返回 NaN，NaN 一路传到 {@code packAlpha} 的 {@code roundToInt} ⇒ 客户端每帧抛异常 ⇒ 闪退。
     * <p>⚠️ <b>本方法刻意【不】吞掉非有限值</b>：{@code Math.min} 挡不住 NaN（Java 里
     * {@code Math.min(NaN, x) == NaN}），所以 NaN 会原样传出去、由 {@link #sanitizeRadius} 在
     * <b>真正喂进 profile 的那一处</b>统一兜底并记 WARN —— 这样"哪里出了 NaN"永远留下痕迹，
     * 而不是在这里被静默吃掉（本次事故的教训就是"出事时日志里什么都没有"）。
     */
    private static float pulseRadius(float baseRadius, float continuousTick) {
        float pulse = (float) Math.sin(continuousTick / PULSE_PERIOD_TICKS);
        return Math.min(baseRadius * (1.0f + PULSE_AMPLITUDE * pulse), SAFE_MAX_STAR_RADIUS);
    }

    /**
     * 🔴 <b>纵深防御：喂进 profile 之前的最后一道闸</b>（2026-09-23 队长追加要求）。
     *
     * <h2>为什么需要它（而不是"我已经钳过两次了"）</h2>
     * 上游 {@code asin(starRadius / 44.780018)} 的<b>域是别人的代码</b>：我们只能保证自己算出的
     * 半径在域内，<b>不该假设自己永远算不出 NaN</b>。真出现 NaN 时，代价不是"半径不对"，
     * 而是<b>每帧在渲染线程抛异常、把游戏崩掉</b>（本次事故形态）。所以这里有第二层：
     * <pre>
     *   非有限（NaN / ±Inf）⇒ 退回 baseRadiusFor(level)；若它也不是有限正数 ⇒ 退回 BASE_RADIUS(13.0)
     *                        + 打一行 WARN（节流，见 {@link #NON_FINITE_WARN_MIN_GAP_MS}）
     *   有限               ⇒ 仍然钳到 SAFE_MAX_STAR_RADIUS
     * </pre>
     *
     * <h2>🔴 为什么 WARN 必须节流</h2>
     * 这是<b>逐帧</b>调用的路径：真出现持续 NaN、又不节流，日志会被秒级刷爆（本项目已有一条
     * 同类教训：逐帧日志必须节流，避免把客户端日志刷爆）。
     * 节流只压"重复次数"，不压"这件事发生过" —— 5000 ms 一行，足够复盘。
     *
     * <h2>为什么兜底 + WARN 两条都要</h2>
     * 只兜底不告警 ⇒ 掩盖真 bug（本项目最忌讳）；只告警不兜底 ⇒ 用户直接闪退。
     * 两条一起才是"最坏是半径不对，不是游戏崩掉"。
     */
    private static float sanitizeRadius(float radius, int moduleBonus) {
        if (Float.isFinite(radius)) {
            return Math.min(radius, SAFE_MAX_STAR_RADIUS);
        }
        float fallback = baseRadiusFor(moduleBonus);
        if (!Float.isFinite(fallback) || fallback <= 0.0f) {
            fallback = BASE_RADIUS;
        }
        final float safeFallback = Math.min(fallback, SAFE_MAX_STAR_RADIUS);
        final long now = System.currentTimeMillis();
        if (now - lastNonFiniteWarnMs >= NON_FINITE_WARN_MIN_GAP_MS) {
            lastNonFiniteWarnMs = now;
            GTDishanhaiMod.LOGGER.warn(
                    "[SHANHAI-SPEC] star_radius_NON_FINITE bad={} level={} fallback={} (上游 asin 域=44.780018；"
                            + "已退回安全值，游戏不会崩；本行最多每 {} ms 一行)",
                    radius, moduleBonus, safeFallback, NON_FINITE_WARN_MIN_GAP_MS);
        }
        return safeFallback;
    }
}
