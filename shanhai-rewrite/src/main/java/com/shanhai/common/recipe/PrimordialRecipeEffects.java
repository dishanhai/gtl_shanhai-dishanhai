package com.shanhai.common.recipe;

import com.gregtechceu.gtceu.api.capability.recipe.EURecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gregtechceu.gtceu.api.recipe.chance.logic.ChanceLogic;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.content.ContentModifier;
import com.gregtechceu.gtceu.api.recipe.modifier.ParallelLogic;

import com.gtladd.gtladditions.common.data.ParallelData;
import com.gtladd.gtladditions.utils.RecipeCalculationHelper;

import com.mojang.logging.LogUtils;

import com.shanhai.common.log.ShanhaiLogThrottle;
import com.shanhai.common.thread.ShanhaiBatchPlan;
import com.shanhai.common.thread.ShanhaiDurationFloor;
import com.shanhai.common.thread.ShanhaiFairAllocation;
import com.shanhai.common.thread.ShanhaiParallelBudget;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLongPair;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import org.gtlcore.gtlcore.api.recipe.BatchProcessing;
import org.gtlcore.gtlcore.api.recipe.IAdvancedContentModifier;
import org.gtlcore.gtlcore.api.recipe.IGTRecipe;
import org.gtlcore.gtlcore.api.recipe.IParallelLogic;
import org.gtlcore.gtlcore.api.recipe.RecipeRunnerHelper;
import org.gtlcore.gtlcore.config.ConfigHolder;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 青铜神锻（原始终焉引擎）体系里 <b>N3 产出倍率 / N5 减免 / N6 最短耗时</b> 的<b>唯一一份算术实现</b>。
 *
 * <h2>为什么要单独一个类</h2>
 * 这三条效果主机与 24 个模块都要用。{@code RecipeModifier} 是<b>单函数接口</b>
 * （{@code javap} 只有一个抽象方法 {@code apply}），框架<b>没有 chain 契约</b>，
 * 所以「先并行 → 后倍率 → 下限」的组合必须自己写；算术本身也只允许存在一处，
 * 否则主机与模块的公式迟早漂移（本项目最忌讳的静默失败）。
 *
 * <h2>本类的硬约束（红线）</h2>
 * <ul>
 *   <li><b>只用 {@code recipe.copy()}（无参）做深拷贝</b>，<b>绝不用</b>
 *       {@code recipe.copy(ContentModifier)}。理由由字节码确认：</li>
 * </ul>
 * <pre>
 * GTRecipe.copy(ContentModifier, boolean)  :
 *     130: iload_2            // 第二个参数
 *     131: ifeq 152           // false 就跳过
 *     137: getfield duration:I
 *     143: ContentModifier.apply(Number)   ← 这里会把 duration 也乘掉
 *     149: putfield duration:I
 * GTRecipe.copy()  :  119: getfield duration:I → 直接进构造器，**没有任何 ContentModifier.apply**
 * </pre>
 * ⇒ 用 {@code copy()} 之后 {@code duration} 与输入在<b>结构上</b>不可能被改动。
 * 但本类仍然保留 {@link #guardedCopy(GTRecipe, String)} 这道德意志绊线（断言 + 响亮日志 + 强制还原），
 * 因为「上游将来改了 copy 的契约」这件事只能靠运行期喊出来，不能靠注释承诺。
 *
 * <h2>N3 产出倍率</h2>
 * <b>口径（用户原话）</b>：「额外产出指的是消耗一份原料，产出 2 份-18 份成品，就和原版伪神之锻炉一样，
 * 就是需要和并行叠成，例如配方 A 是输入 1 铁产生 1 齿轮，机器是 100 并行，3 倍产出倍率，
 * 然后我输入了 100 个铁，然后通过一轮加工，产出了 300 个齿轮」
 * <pre>
 *   M = (门控等级 == 0) ? 1 : 1 + 门控等级        // ×1（未生效）或 ×2 … ×18
 *   100 并行 × 3 倍  ⇒  输入 100 铁  ⇒  产出 300 齿轮
 * </pre>
 * <ul>
 *   <li><b>倍率源唯一</b>：主机专属槽的 {@code moduleSlotBonus()}。主机自己算一次、
 *       主机下的每台模块各算一次（<b>同一系数，不叠乘</b>）。</li>
 *   <li><b>与并行叠乘且并行在前</b>：并行先定「跑几份」，倍率再定「每份出多少」。</li>
 *   <li><b>只动产出</b>：{@code duration} 原样、输入消耗原样。</li>
 *   <li><b>2026-09 队长裁决：输出概率拉满</b>（照旧私货 {@code PrimordialRecipeOutputAmplifier}
 *       的语义，见 {@link #multiplyOutputs} 的逐字引文）——{@code outputs} 与 {@code tickOutputs}
 *       两张表<b>都</b>在缩放前把 {@code chance}/{@code maxChance} 写成满值。</li>
 * </ul>
 *
 * <h2>N5 减免 —— 🔴 2026-09-21 用户点破方向反了，公式已改（见本节末尾的作废留档）</h2>
 * 用户给的 {@code 0.95^(17/等级)} 是<b>「减免比例 R」</b>，不是<b>「成本系数」</b>。
 * 本工程此前把 R 直接当成成本系数乘到 EUt/duration 上 ⇒ <b>等级越高反而越费电</b>，方向整个反了。
 * 现在分成两个量（等级 = 主机门控等级 1..17）：
 * <pre>
 *   减免比例 R(等级) = 0.95 ^ (17.0 / 等级)          ← 用户本意，公式不动
 *   成本系数 f(等级) = 1 - R(等级) = 1 - 0.95^(17/等级)   ← 实际乘到 EUt / duration 上的那个
 * </pre>
 * <b>用户原话（逐字）</b>：「他这个减免不应该是物质模块等级越高它减免越高吗，为什么低等级的物质模块减免
 * 比高等级的要高啊，<b>那个减免95%应该是原耗电*0.05，而不是*0.95</b>」。
 * 🔴 <b>等级 ≤ 0 必须先短路成 f = 1.0（不减免）</b>：否则 {@code 17 / 0} 在 double 下是 {@code Infinity}，
 * {@code 0.95 ^ Infinity} 收敛到 {@code 0} ⇒ {@code f = 1 - 0 = 1}（这半边反而"安全"），
 * 但 {@code R} 会变成 0、且"等级 0"在本工程里语义是<b>未生效</b>，
 * 必须显式走「不减免」这条分支，不许靠浮点行为兜。
 * <p>数值表（{@code Math.pow} 实算，用于对账；R 与 f 一一对应）：
 * <pre>
 *   Lv1  R=0.4181 f=0.5819(省 41.8%)   Lv2  R=0.6466 f=0.3534   Lv3  R=0.7478 f=0.2522
 *   Lv5  R=0.8400 f=0.1600(省 84.0%)   Lv10 R=0.9165 f=0.0835   Lv17 R=0.9500 f=0.0500(省 95.0%)
 * </pre>
 * ⇒ <b>Lv.17 正好 ×0.05</b>（与用户原话逐字吻合），且<b>单调：等级越高减免越高</b>。
 * <p><b>f 恒 {@code ∈ (0, 1]}</b> ⇒ <b>永远不会零耗能</b>，也永远不会把配方改大。
 * <p><b>两个独立旋钮</b>：耗时（先乘 f）与耗能（后乘 f），互不影响；
 * 耗能侧必须钳 {@code ≥ 1 EU/t}（见 {@link #reduceEnergy}）。
 *
 * <h3>🔴 作废项留档（旧公式原文，只留档、不生效）</h3>
 * <pre>
 *  —— 以下为 2026-09-21 之前写在代码里的原文，现已作废 ——
 *   f(等级) = 0.95 ^ (17.0 / 等级)        等级 ≤ 0 ⇒ 必须先短路成 1.0
 *   Lv1  = 0.4181 (省 58.2%)   Lv2  = 0.6466   Lv3  = 0.7478
 *   Lv5  = 0.8400             Lv10 = 0.9165   Lv17 = 0.9500 (省 5%)
 * </pre>
 * <b>作废原因：把「减免比例」当成了「成本系数」，方向反了，2026-09-21 用户点破后改为
 * {@code f = 1 - R}</b>。旧文字里那句「Lv1 省 58.2% / Lv17 省 5%」正是方向反了的现场证据
 * —— 它描述的是"低等级省得多"，与用户的要求完全相反。
 * 旧档位数字（0.4181 / 0.6466 / 0.7478 / 0.8400 / 0.9165 / 0.9500）在新口径下<b>是 R 值</b>，
 * 仍有意义，只是不许再当成本系数用。
 *
 * <h2>N6 最短耗时 —— ⛔【旧口径 · 已作废，原文照留】「恒等」语义（2026-09 队长裁决；已被 2026-09-22 的 A 口径取代：主机 T = max(1, dx, limitedDuration)，见 {@link #durationFloorTarget(int, int)}）</h2>
 * 队长取证并改判：原版算式链里其实有<b>两条</b>分支，此前把「照原版」只写成下限（地板）
 * <b>是标错的</b>。<b>有线 = 地板 / 无线 ≡ 恒等</b>，而旧私货与伪神走的是<b>无线</b>，
 * 用户要的「工作速度无限」也只有恒等能达成。
 *
 * <h3>取证原文（反编译 gtladditions 3.2.8Custom-fix1，{@code [字节码]}）</h3>
 * <pre>
 *   // com.gtladd.gtladditions.utils.RecipeCalculationHelper.buildNormalRecipe  ——「有线」
 *   double dx = totalEu / maxEUt;
 *   long eut = dx > minDuration ? maxEUt : (long)(totalEu / minDuration);
 *   recipe.duration = MathKt.roundToInt(Math.max(dx, (double)minDuration));      ← 地板
 *
 *   // com.gtladd.gtladditions.utils.RecipeCalculationHelper.buildWirelessRecipe ——「无线」
 *   BigInteger eut = totalEu.divide(BigInteger.valueOf(duration)).negate();
 *   … .duration(duration).setWirelessEut(eut)                                    ← 恒等（duration 原样传入）
 *
 *   // 无线的 duration 从哪来 —— com.gtladd.gtladditions.api.machine.logic.GTLAddMultipleRecipesLogic
 *   return RecipeCalculationHelper.buildWirelessRecipe$default(
 *          …, this.limited.getLimitedDuration(), totalEu, null, 16, null);
 * </pre>
 * ⇒ 「无线」分支：{@code duration ≡ getLimitedDuration()}（<b>与配方原时长、并行数完全无关</b>），
 * 而 {@code EUt = totalEU / limitedDuration}。
 *
 * <h3>🔴 上面这段取证属于【另一个类】，别套到本主机现在用的引擎上（2026-09-21 追加澄清）</h3>
 * 上面 {@code GTLAddMultipleRecipesLogic} 是<b>旧私货那条路</b>的引擎类，它<b>确实</b>把机器的
 * {@code getLimitedDuration()} 传进去当 duration。而本主机换引擎后走的是
 * <b>{@code MutableRecipesLogic}</b> —— <b>那个类里没有这件事</b>：
 * <pre>
 *   {@code [字节码]} MutableRecipesLogic.buildFinalNormalRecipe：
 *       457: bipush 20     ← 硬编码字面量
 *       459: invokevirtual RecipeCalculationHelper.buildNormalRecipe:(…DJI)…
 *   且 MutableRecipesLogic 的整个常量池里 getLimitedDuration / LimitedDuration /
 *   IGTLAddMultiRecipeMachine 命中【全为 0】
 *   ⇒ 引擎不认侧栏那个旋钮；引擎自己算出的 duration 下限是【字面 20】。
 * </pre>
 * <b>后果（当时只记录、未修；**其后已按队长裁决选「甲」修好**，见下）：</b>{@code EUt_引擎 × duration_引擎 = totalEu} 恒成立，
 * 但当时的 N6（恒等）只改 duration、不改 EUt ⇒ 只有 {@code limitedDuration == duration_引擎} 时总能量守恒；
 * 常见分支（{@code dx ≤ 20}）下 {@code duration_引擎 = 20} ⇒ <b>侧栏 ≠ 20 就不守恒，比值 = 侧栏/20</b>。
 * 详见 {@code PrimordialEngineRecipeLogic} 类注释的残余风险 ③。
 *
 * <h3>🔴 主机与模块的不对称（队长要求显式写清，两边都不许漂移）</h3>
 * <b>2026-09 最终口径（用户原话「主机耗电吃N5减免」）</b>：
 * <p>🔴 <b>【2026-09-22 订正 → 同日再改判 A 口径 · 下列各版原文均照留】</b>
 * 下面 <b>N6 那两行</b>写的是 <b>恒等 / 纯下限</b> 的旧口径；主机其后改成带<b>天花板</b>的形状、再改成
 * <b>A 口径的下限</b> —— <b>原文保留不改，正确值见紧随其后的「现行」那一行</b>。
 * <pre>
 *   主机（生产路径 = {@code PrimordialEngineRecipeLogic#buildFinalNormalRecipe}；下表「现行」即 A 口径）：
 *       ⛔ 旧（作废）：duration = max(1, getLimitedDuration())        ← 恒等（原 applyIdentityDuration）
 *       ✅ 现行      ：duration = max(1, dx, getLimitedDuration())   ← 下限（durationFloorTarget；旧 min 天花板口径两版均已作废）
 *       🔴 不吃 N5【时长】减免（时长由 N6 的 A 下限决定，与 f 无关）
 *       🔴 吃   N5【耗电】减免：EUt ×= f（本类的 reduceEnergy，钳 ≥1 EUt）
 *       主机修饰链顺序：并行(N4) → 产出倍率(N3) → 时长下限(N6，A 口径) → 耗电减免(N5)
 *
 *   模块（{@code ModuleRegistry#applyModuleRecipeModifier}）：
 *       ⛔ 旧（作废）：duration = max(下限, round(duration × f))       ← 纯下限（原 applyDurationFloorForModule）
 *       ⛔ 次旧（作废）：duration = min(原时长, max(下限, round(原时长 × f)))  ← 下限 + 天花板
 *       ✅ 现行（2026-09-26 用户裁决「连功能一起删」）：duration = min(原时长, max(1, round(原时长 × f)))
 *          ⇒ <b>下限项已删</b>，模块耗时 = 原耗时 × 减免系数；只剩「≥1 tick」保底与「永不变长」天花板
 *       吃 N5 两个旋钮：耗时 ×f 与耗能 ×f（耗能钳 ≥1 EUt）
 *
 *   ⇒ 两侧<b>已不再</b>共用一条规则：主机 = A 口径<b>下限</b>（{@link #durationFloorTarget(int, int)}，可把短配方拖长）；「时长永不变长」现只对<b>模块</b>成立（{@link #durationTarget(int, int)}）。
 * </pre>
 * <b>{@code f} 的来源两侧完全相同</b>：<b>主机专属槽</b>里那个模块的等级
 * ⇒ {@code f = 1 - 0.95^(17/等级)}（= 1 − 减免比例）；等级 ≤ 0 先短路成 {@code 1.0}（见
 * {@link #reductionFactor(int)} 的除零说明）。作用在<b>主机与其所有模块</b>上。
 *
 * <p>为什么模块侧的「下限」已被删除（2026-09-26 用户裁决）：旧理由是「否则 N5 的耗时旋钮会被
 * 抹平、玩家那个『配方最短耗时』旋钮在模块上失效」，而那个旋钮 2026-09-22 就从模块侧摘掉了
 * ⇒ 被托住的一头不存在。两处 javadoc 各写一句对照，改动任一侧都必须同步改另一侧的注释。
 *
 * <h3>可实机对账的判据（写给下一轮验收）</h3>
 * <pre>
 *   同一台主机 + 同一配方，专属槽【空】vs【满 64 个同种模块】⇒ 耗电应差 f 倍
 *       例：Lv.1 ⇒ R = 0.95^17 ≈ 0.4181（减免 41.8%）⇒ f = 1 − R ≈ 0.5819
 *           ⇒ 槽满后 EUt 约为原来的 0.5819 倍（Lv.17 ⇒ f = 0.05，即 ×0.05）
 *   耗时两栏都不变（主机时长 = N6 的 A 目标 {@code max(1, dx, 下限)}，与 f 无关）
 * </pre>
 *
 * <h3>旧实现（已被本次裁决作废，仅留作考古）</h3>
 * <pre>
 *   d              = totalEU / maxEUt
 *   duration_final = round(max(d, limitedDuration))
 *   EUt_final      = d > limitedDuration ? maxEUt : totalEU / limitedDuration
 * </pre>
 * 那正是「有线」分支的形状；本工程主机 2026-09-22 选 A 之后走的<b>就是这一半</b>（T = max(1, dx, 下限)）。
 */
public final class PrimordialRecipeEffects {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 配方最短耗时的默认值（与 gtladditions {@code IGTLAddMultiRecipeMachine.getLimitedDuration()} 的默认值一致，也是原版的字面 20）。
     *
     * <p>🔴 <b>2026-09-26 用途收窄（不是删除）</b>：用户裁决「连功能一起删」删掉的是<b>模块侧</b>的
     * 下限（{@code PrimordialModuleMachine} 那个字段与 {@link #applyModuleDuration} 的 floor 项）。
     * 本常量<b>仍然被主机侧使用</b>：{@code PrimordialOmegaEngineMachine#limitedDuration} 的初值，
     * 而那台主机的侧栏控件<b>仍然是活的</b>（{@code panel.attachConfigurators(
     * new LimitedDurationConfigurator(this))}）⇒ <b>删掉本常量会让主机侧编译不过</b>。
     * ⇒ 本常量与 {@link #clampLimitedDuration(int)} 一起<b>保留</b>，属于「有别的用途，不一刀切」。
     */
    public static final int DEFAULT_LIMITED_DURATION = 20;
    /** GUI 允许的下限范围（照抄 gtladditions {@code LimitedDurationConfigurator} 的 {@code setMin(10).setMax(200)}）。 */
    public static final int MIN_LIMITED_DURATION = 10;
    /** GUI 允许的上限范围。 */
    public static final int MAX_LIMITED_DURATION = 200;

    /**
     * 🔴 <b>「并行进入 long 档之后的配方时长下限」= 10 tick</b>（用户 2026-09-30 拍板）。
     *
     * <p>用户原话（逐字）：<i>「对了，这个配方加到 long 之后可以加一个最小配方时长为 10tick，
     * 然后 jade 写一下提示，这样不会引起误解」</i>
     *
     * <p>⚠️ <b>本常量只是别名，算术本体在纯核 {@link ShanhaiDurationFloor}（只 import {@code java.*}）</b>
     * —— 与 {@link ShanhaiParallelBudget} 那一次同款理由：纯核能单独 javac 驱动做三段自证、
     * 也能挂在加载期自检上在无头专服里真跑。全工程<b>只有那一份算术</b>，
     * 判据 / 公式 / 与「时长永不变长」的调和全部写在那边的类注释里。
     *
     * <p>取值为什么是 10：与主机侧 GUI 允许的最小下限 {@link #MIN_LIMITED_DURATION} 同值
     * （那个值本身照抄 gtladditions {@code LimitedDurationConfigurator} 的 {@code setMin(10)}），
     * 加载期自检断言两者相等。
     */
    public static final int LONG_SCALE_MIN_DURATION = ShanhaiDurationFloor.LONG_SCALE_MIN_DURATION;

    /**
     * 本台机器当前的并行预算<b>是不是"进了 long 档"</b>（{@code > 2147483647}，严格大于）。
     *
     * <p>这就是「加到 long 之后」的可判定定义 —— 详见 {@link ShanhaiDurationFloor#isLongScaleParallel(long)}。
     * <b>本方法只是别名</b>，算术与边界断言（含"天花板自身不触发"那条负面对照）都在纯核里。
     */
    public static boolean isLongScaleParallel(long parallelBudget) {
        return ShanhaiDurationFloor.isLongScaleParallel(parallelBudget);
    }

    /**
     * {@link #LONG_SCALE_MIN_DURATION} 的<b>实际生效值</b> = <b>绝对 10 tick</b> ⇒ 别名，
     * 见 {@link ShanhaiDurationFloor#floorTargetFor(int)}。
     *
     * <p>⛔ 历史（作废，要点留档）：本方法曾经返回 {@code min(10, 配方原时长)}，
     * 为的是不撞「任何情况下时长都不许超过定义时长」那条旧红线。用户 2026-09-30 拍板
     * <b>「B. 破一次红线，让那 25 台也抬到 10」</b> ⇒ 现在<b>与原时长无关，恒为 10</b>。
     * 新红线措辞逐字见 {@link ShanhaiDurationFloor} 的类注释。
     */
    public static int longScaleMinDurationFor(int originalDuration) {
        return ShanhaiDurationFloor.floorTargetFor(originalDuration);
    }

    /**
     * <b>「并行进 long 档 ⇒ 配方时长下限 10 tick」在配方对象上的唯一落点</b>。
     *
     * <pre>
     *   并行预算 &lt;= 2147483647（未进 long 档）  ⇒  逐值不变（返回入参实例，零分配）
     *   并行预算 &gt;  2147483647（已进 long 档）  ⇒  duration = max(duration, 10)   ← 绝对下限
     * </pre>
     *
     * <p>🔴 <b>2026-09-30 同日第四轮（用户拍板 B）</b>：公式里的 {@code min(10, 原时长)} 已换成
     * <b>绝对 10</b> ⇒ 原时长 1 tick 的配方（引擎链 25 台的实际情形）**也会**被抬到 10。
     * 代价 = 那些配方比原版慢（最坏 1→10，即 10 倍），用户明知并接受。
     * 新红线措辞逐字见 {@link ShanhaiDurationFloor} 的类注释；<b>只抬不砍</b>（幂等那一支仍在）。
     *
     * <p>算术本体在 {@link ShanhaiDurationFloor#apply(int, int, long)}；本方法只负责
     * 「要不要改 → 不改就原样返回入参 → 要改才 {@link #guardedCopy} 拿副本再写 duration」，
     * 与链上其它步骤（N3/N5/N6）同纪律。
     *
     * <p>⚠️ <b>恒等那一支是硬保证</b>：本工程所有"并行 ≤ 21 亿"的档位走这一支 ⇒ 一个字都不变
     * （含「永恒物质模块 + 线程 1」那个恰好等于天花板的边界）。
     *
     * @param originalDuration 配方定义的原时长（<b>只用于日志与探针</b>；现行公式不读它）
     * @param parallelBudget   并行预算（{@code totalParallelLimitFor(...)} 的输出）
     */
    /**
     * 「并行 long 档时长下限」那一行（生效行）的闸门。
     *
     * <p>🔴 2026-10-01（用户点单「日志的问题」）：实测日志里这一行打了 <b>1270 次</b>，
     * 而 1270 次的取值<b>完全相同</b>（预算 = Long.MAX、1 → 10、下限 10、原时长 1）。
     * ⇒ 改成「<b>形态第一次出现时打一次</b>」：稳态一行都不落盘，换档 / 退出仍然看得见。
     * 判定本体在 {@link ShanhaiLogThrottle#decideDurationFloor}（纯 JDK；离线可单跑自检）。
     */
    private static final ShanhaiLogThrottle.Gate shanhai$FLOOR_GATE = new ShanhaiLogThrottle.Gate();

    public static GTRecipe applyLongScaleDurationFloor(GTRecipe recipe, int originalDuration, long parallelBudget) {
        if (recipe == null) {
            return recipe;
        }
        if (!ShanhaiDurationFloor.isLongScaleParallel(parallelBudget)) {
            // ── 恒等支（本工程所有 ≤ 21 亿 的档位都走它：一个数都不改、一个 .copy() 都不做）──
            // 🔴 2026-10-01：本支**默认一行都不打**。只有「本闸门打过 long 档形态」**且**
            //    「long 档调用已静默 ≥ 30 秒」时才兑现一行「已退出 long 档」。
            //    为什么必须靠静默超时：本方法是**无机器身份的静态工具**，而 25 台模块里大量档位
            //    的预算 ≤ 21 亿（例：空槽 64）⇒「看到一次非 long 调用」根本不等于「刚那台退出了」
            //    （照字面实现会打出一堆**假退出**）。判据与理由见
            //    {@link ShanhaiLogThrottle#decideDurationFloorExit}。
            final ShanhaiLogThrottle.Verdict exit = ShanhaiLogThrottle.decideDurationFloorExit(
                    shanhai$FLOOR_GATE, parallelBudget,
                    ShanhaiLogThrottle.DEFAULT_LONG_EXIT_QUIET_MILLIS, System.currentTimeMillis());
            if (exit.level != ShanhaiLogThrottle.Level.NONE) {
                LOGGER.info("[SHANHAI-DURATION-FLOOR] 并行已退出 long 档：long 档调用已静默 ≥{} 秒"
                                + "（当前预算 {}）⇒ 时长下限不再适用，配方时长回到原值。"
                                + "自上次落盘以来同形重复 {} 次已静默。",
                        ShanhaiLogThrottle.DEFAULT_LONG_EXIT_QUIET_MILLIS / 1000L, parallelBudget, exit.suppressed);
            }
            return recipe;
        }
        // 🔴 静默心跳：记「long 档调用还在继续」。缺了它，「退出」那一行会立刻误判成「已静默 30 秒」。
        shanhai$FLOOR_GATE.touch(System.currentTimeMillis());
        final int target = ShanhaiDurationFloor.apply(recipe.duration, originalDuration, parallelBudget);
        if (target == recipe.duration) {
            return recipe;
        }
        final GTRecipe copy = guardedCopy(recipe, "N6-模块-long下限");
        copy.duration = target;
        // 🔴 2026-10-01：原来这里**每次**都打（实测 1270 行、全部同形）⇒ 改成「形态第一次出现时打一次」。
        final ShanhaiLogThrottle.Verdict floor = ShanhaiLogThrottle.decideDurationFloor(
                shanhai$FLOOR_GATE, parallelBudget, recipe.duration, target, LONG_SCALE_MIN_DURATION,
                originalDuration, System.currentTimeMillis());
        if (floor.level != ShanhaiLogThrottle.Level.NONE) {
            LOGGER.info("[SHANHAI-DURATION-FLOOR] 并行已进 long 档（预算 {}），配方时长 {} → {} tick"
                            + "（下限 {}，原时长 {}）；未进 long 档的档位本行恒不触发。"
                            + "【本行已改为「同一形态只打一次」：自上次落盘以来同形重复 {} 次已静默】",
                    parallelBudget, recipe.duration, target, LONG_SCALE_MIN_DURATION, originalDuration,
                    floor.suppressed);
        }
        return copy;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  2026-09-30（同日第五轮）：**配方对象级别**的加载期判据
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /**
     * <b>真对象判据</b>：拿一个<b>真实的 {@code GTRecipe} 实例</b>喂进本类的生产落点
     * {@link #applyLongScaleDurationFloor(GTRecipe, int, long)}，再从<b>产出的那个配方对象上读
     * {@code duration}</b>。
     *
     * <h2>为什么必须有这一条（用户 2026-09-30 明确要求）</h2>
     * <blockquote>「自证里至少一条是"读实际 duration"…… 别只断言"函数被调用了"」
     * —— 上一轮就是"函数调了但没生效"（{@code min(10, 1) = 1} ⇒ 目标 == 现值 ⇒ 静默返回）。</blockquote>
     * 纯核 {@code ShanhaiDurationFloor.selfTest()} 查的是<b>算出来的数</b>；本方法查的是
     * <b>「这个数有没有真的落到配方对象的字段上」</b> —— 两件事，都会坏，且坏法不同
     * （对象侧漏写 ⇒ 算术全对、游戏里没用）。所以这一条不是重复。
     *
     * <h2>它断言的五件事</h2>
     * <ol>
     *   <li>探针配方初值 {@code duration == 1}（引擎链 25 台的实际情形）；</li>
     *   <li><b>判据成立 ⇒ 从产出对象上读到 {@code duration == 10}</b>（本轮的核心读数）；</li>
     *   <li>产出是<b>新副本</b>（{@code floored != probe}），且<b>入参对象没被就地改写</b>；</li>
     *   <li>判据不成立 ⇒ 返回的<b>就是入参实例本身</b>（{@code same == probe}）⇒ 恒等那一支零分配；</li>
     *   <li><b>只抬不砍</b>：{@code duration = 60} 的配方读回来仍是 60。</li>
     * </ol>
     *
     * <p>任一条不成立 ⇒ 抛 {@link IllegalStateException}（与其余加载期自检同纪律：宁可加载失败，
     * 也不许「静默不生效」）。
     *
     * @return 给调用方写日志用的一整行
     */
    public static String selfTestDurationFloorOnRealRecipe() {
        final com.gregtechceu.gtceu.api.recipe.GTRecipeType type = ShanhaiRecipeTypes.PRIMORDIAL_POWER_GENERATOR;
        if (type == null) {
            return "[SHANHAI-DURATION-FLOOR] 真对象判据【跳过】：配方类型 primordial_power_generator 此刻还没注册"
                    + "（此时只有纯核判据，没有对象级读数）。";
        }
        // 探针配方：输入/产出/条件全空、data 空、duration = 1、非燃料 —— 只为拿一个真实的 GTRecipe 实例。
        final GTRecipe probe = new GTRecipe(type,
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                java.util.List.of(), java.util.List.of(), new net.minecraft.nbt.CompoundTag(), 1, false);
        if (probe.duration != 1) {
            throw new IllegalStateException("[SHANHAI-DURATION-FLOOR] 真对象判据前置失败：探针配方初值应为 1，实为 "
                    + probe.duration);
        }
        // ① 判据成立 ⇒ 读产出对象上的 duration
        final GTRecipe floored = applyLongScaleDurationFloor(probe, 1, Long.MAX_VALUE);
        if (floored.duration != LONG_SCALE_MIN_DURATION) {
            throw new IllegalStateException("[SHANHAI-DURATION-FLOOR] 🔴 真对象判据失败：判据成立时，"
                    + "产出配方对象上读到的 duration 应为 " + LONG_SCALE_MIN_DURATION + "，实为 " + floored.duration
                    + "（这正是「函数被调用了但没生效」那一类病）");
        }
        if (floored == probe) {
            throw new IllegalStateException("[SHANHAI-DURATION-FLOOR] 真对象判据失败：判据成立时应返回新副本，"
                    + "实际返回了入参实例本身");
        }
        if (probe.duration != 1) {
            throw new IllegalStateException("[SHANHAI-DURATION-FLOOR] 真对象判据失败：入参配方对象被就地改写了（duration="
                    + probe.duration + "，应为 1）");
        }
        // ② 判据不成立 ⇒ 返回入参实例本身（恒等支零分配），且读回来逐值不变
        final GTRecipe same = applyLongScaleDurationFloor(
                probe, 1, com.shanhai.common.thread.ShanhaiParallelBudget.NATIVE_INT_CEILING);
        if (same != probe || same.duration != 1) {
            throw new IllegalStateException("[SHANHAI-DURATION-FLOOR] 真对象判据失败：预算 = 天花板自身时"
                    + "应原样返回入参实例、duration 不变，实际 same==probe? " + (same == probe)
                    + "，duration=" + same.duration);
        }
        // ③ 只抬不砍：长配方读回来仍是原值
        final GTRecipe longOne = new GTRecipe(type,
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                java.util.List.of(), java.util.List.of(), new net.minecraft.nbt.CompoundTag(), 60, false);
        final int longAfter = applyLongScaleDurationFloor(longOne, 60, Long.MAX_VALUE).duration;
        if (longAfter != 60) {
            throw new IllegalStateException("[SHANHAI-DURATION-FLOOR] 真对象判据失败：60 tick 的配方被砍到 "
                    + longAfter + "（本步只抬不砍）");
        }
        return "[SHANHAI-DURATION-FLOOR] 真对象判据通过（读的是产出配方对象的 duration 字段，不是「函数被调用」）："
                + "探针 duration 1 ⇒ 判据成立（预算 Long.MAX）⇒ 读回 " + floored.duration + " tick；"
                + "判据不成立（预算 = 2,147,483,647）⇒ 原样返回入参实例、读回 " + same.duration + " tick；"
                + "60 tick 的配方读回 " + longAfter + "（只抬不砍）；入参对象未被就地改写。";
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  2026-09-30（同日第三轮）：现场探针 —— 治「抬头说 10 tick、配方还是 1 tick」
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /**
     * 已打点次数（只打前 {@value #shanhai$FLOOR_PROBE_LIMIT} 次，避免每 tick 刷屏）。
     *
     * <h2>为什么必须有这一条</h2>
     * 用户 2026-09-30 报「它没有到 10 tick」。而 {@link #applyLongScaleDurationFloor} 只在
     * <b>真的改写了</b>时才打 INFO ⇒ 没有这行日志时，有两种完全不同的病长得一模一样：
     * <ol>
     *   <li><b>判据没过</b>（{@code parallelBudget} 没进 long 档 ⇒ 用户看到的那行 Jade 提示是假的）；</li>
     *   <li><b>目标值 == 现值</b>（{@code max(当前, 10) == 当前} ⇒ 当前已 ≥ 10，本来就不该改，不是 bug）。</li>
     * </ol>
     * 本方法把<b>这两个数连同它们的来源一起打出来</b>，一次运行即可分辨。
     *
     * @param isGenerator       只有发电那台走原生链；其余 25 台进不了本方法（调用方已过滤）
     * @param parallelBudget    判据用的预算（与提示行、与并行那一步同一个数）
     * @param durationAtEntry   链上第 ② 步之后读到的时长（= 进本链时的原时长；**诊断值**）
     * @param floorBase         配方定义实例的原时长（**诊断值**；现行公式不读它，只用来比对"链上值有没有被污染"）
     * @param durationNow       第 ③④ 步交出来的当前时长
     */
    public static void shanhai$probeDurationFloor(boolean isGenerator, long parallelBudget,
                                                  int durationAtEntry, int floorBase, int durationNow) {
        if (!isGenerator || shanhai$floorProbeCount >= shanhai$FLOOR_PROBE_LIMIT) {
            return;
        }
        shanhai$floorProbeCount++;
        LOGGER.info("[SHANHAI-DURATION-FLOOR] 原生链现场探针（{}/{} 次）：预算={}（判据 >{} ⇒ long 档={}）"
                        + "／链上原时长={}／基准原时长={}／第 ③④ 步之后 duration={}／本步应改成={}"
                        + "（若「应改成 == duration」⇒ 按设计不改写，也不会打印生效行）",
                shanhai$floorProbeCount, shanhai$FLOOR_PROBE_LIMIT, parallelBudget,
                com.shanhai.common.thread.ShanhaiParallelBudget.NATIVE_INT_CEILING,
                ShanhaiDurationFloor.isLongScaleParallel(parallelBudget),
                durationAtEntry, floorBase, durationNow,
                ShanhaiDurationFloor.apply(durationNow, floorBase, parallelBudget));
    }

    /** 探针最多打几次。 */
    private static final int shanhai$FLOOR_PROBE_LIMIT = 3;

    /** 探针计数（静态；只为"打几次"服务，不参与任何判定）。 */
    private static int shanhai$floorProbeCount = 0;

    /**
     * 减免公式的底数：{@code R = 0.95 ^ (17 / 等级)}（<b>R 是减免比例，不是成本系数</b>；
     * 成本系数 {@code f = 1 - R}，见 {@link #reductionFactor(int)}）。
     */
    public static final double REDUCTION_BASE = 0.95D;
    /** 减免公式的固定分子 17（= 最高模块等级）。 */
    public static final double REDUCTION_EXPONENT_NUMERATOR = 17.0D;

    private PrimordialRecipeEffects() {}

    // ═══════════════════════════════════ N3 产出倍率 ═══════════════════════════════════

    /**
     * N3 的倍率：{@code 门控等级 0 ⇒ 1（未生效）}；{@code 1..17 ⇒ 2..18}。
     *
     * @param gateBonus 主机专属槽的门控结果（{@code PrimordialOmegaEngineMachine#moduleSlotBonus()}）
     */
    public static int outputMultiplier(int gateBonus) {
        return gateBonus <= 0 ? 1 : 1 + gateBonus;
    }

    /**
     * 🔴 <b>N3 的【实际】产出倍率 = {@link #outputMultiplier(int)} + 宿主给的额外量</b>
     * —— 全工程<b>唯一</b>一份算式（2026-10-23 新增）。
     *
     * <h2>为什么必须收成一个出口（这条不是洁癖，是一次真实事故）</h2>
     * "N3 倍率"在工程里有<b>四个</b>读者：
     * <pre>
     *   ① 主机原生修饰链  GuixuVisionCoreMachine#applyGuixuRecipeModifier
     *   ② 主机引擎尾链    PrimordialEngineRecipeLogic#buildFinalNormalRecipe
     *   ③ 模块引擎尾链    PrimordialModuleRecipeLogic#buildFinalNormalRecipe   ← 本次的落点
     *   ④ 显示            主机面板 + 模块面板（{@code addSharedEffectDisplayText}）
     * </pre>
     * ① 与 ② 当时就按"额外 +5"改了，③ 与 ④ 没有 ⇒ 同一台归墟主机下
     * <b>主机面板写 ×23（1+17+5）、模块面板写 ×18，而模块的产出<b>真的</b>只有 ×18</b>
     * （模块走多配方引擎，引擎路径<b>不经过</b> {@code RecipeModifierList.apply}
     *  ⇒ ① 那条链对模块是死代码；字节码实证：gtladditions {@code MutableRecipesLogic}
     *  与 {@code RecipeCalculationHelper} 的常量池里没有任何 {@code RecipeModifier} 引用）。
     * 用户报的原话就是「<b>模块的额外产出少了 +5</b>」。
     * <p>⇒ 额外量的<b>唯一来源</b>是 {@code PrimordialOmegaEngineMachine#getHostProductionBonusExtra()}
     * （归墟覆写成"门控生效时 5"），算式只有本方法这一处 —— 谁都不许再写第二个 {@code +5}。
     *
     * <h2>🔴 两条不变式（任一条破掉就是回归）</h2>
     * <pre>
     *   extra &le; 0 ⇒ 结果 == outputMultiplier(gateBonus)    （老主机 extra 恒 0 ⇒ 逐位不变）
     *   extra &gt; 0 ⇒ 结果 == outputMultiplier(gateBonus) + extra
     *              ⇒ (0, +5) = 6    (17, +5) = 23    (17, 0) = 18    (0, 0) = 1
     * </pre>
     *
     * <h2>🔴 2026-10-25 用户裁决：额外量【恒】参与 —— 删掉了旧的「{@code base > 1} 才加」那道闸</h2>
     * 用户原话（逐字）：「<b>2通过（但是专属槽清空 应该是×6）</b>」。
     * <p>旧实现写的是 {@code base > 1 ? base + Math.max(0, extra) : base}，于是
     * <b>专属槽一清空</b> ⇒ {@code gateBonus == 0} ⇒ {@code base == 1} ⇒ 那道闸把 {@code extra}
     * 也一起挡掉了 ⇒ 显示 <b>×1</b>；用户要的是 <b>×6</b>（= 1 + 额外 5）。
     * 旧注释把这写成「空槽白得 ×6 产出不是『沿用那套 +5』」—— <b>那条口径已被用户推翻</b>，
     * 归墟视界枢核的 +5 是<b>主机自带的常驻增产</b>，与专属槽满不满无关
     * （专属槽只管那 0..17 级的门控部分）。原文留档在本条下面。
     * <p>⛔ <b>作废口径（只留档、不生效）</b>：「{@code gateBonus ≤ 0 ⇒ 结果 == 1}」／
     * 「额外量只加在『门控已生效』之上」。
     *
     * <p>⚠️ <b>本方法不知道"这台机器是不是发电机"</b>：发电那台吃的 {@code tickOutputs} 是
     * <b>输出语义的 EU</b>（放大 N3 = 凭空多发电），本倍率对它不成立
     * ⇒ <b>调用方必须自己判 {@code isGenerator()} 再决定要不要走本方法</b>
     * （模块侧已经收在 {@code PrimordialModuleMachine#n3ProductionMultiplierOf} 里，
     * 发电那一支在**调用本方法之前**就短路成 {@code outputMultiplier(gateBonus)}）。
     *
     * @param gateBonus 门控等级（{@code moduleSlotBonus()}；调用方<b>只读一次</b>，与 N5 同源）
     * @param extra     宿主给的额外倍率（{@code getHostProductionBonusExtra()}；&le;0 = 不加）
     */
    public static int productionMultiplierOf(int gateBonus, int extra) {
        // 🔴 2026-10-25：`base > 1 ? … : base` 那道闸已删 —— extra 恒参与（用户："专属槽清空 应该是×6"）。
        final int base = outputMultiplier(gateBonus);
        return base + Math.max(0, extra);
    }

    /**
     * <b>只放大产出</b>：缩放产出数量，并把产出概率拉满。
     *
     * <h2>🔴 2026-09 队长裁决：改用<b>旧私货语义</b>（不再做阉割版）</h2>
     * 口径依据是用户原话「<b>就和原版伪神之锻炉一样</b>」+「消耗一份原料，产出 2-18 份成品」；
     * 出现「任务书字面 vs 旧私货」分歧时<b>以旧私货为准</b>。
     *
     * <h3>旧私货原文（逐字引用 {@code originals/upstream/.../primordial/PrimordialRecipeOutputAmplifier.java}，
     * {@code [源码原文]}；已用反编译复核 {@code ChanceLogic.getMaxChancedValue()} 的常量值）</h3>
     * <pre>
     *   GTRecipe copy = recipe.copy();
     *   copy.parallels = recipe.parallels;
     *   copy.ocTier = recipe.ocTier;
     *   ContentModifier modifier = ContentModifier.multiplier(multiplier);
     *   int fullChance = ChanceLogic.getMaxChancedValue();
     *   ContentScaler scaler = (capability, content, contentModifier) -&gt;
     *           content.copy(capability, contentModifier);
     *   amplifyAndForceFullChance(copy.outputs, fullChance, modifier, scaler);
     *   amplifyAndForceFullChance(copy.tickOutputs, fullChance, modifier, scaler);
     *   return copy;
     *
     *   static void amplifyAndForceFullChance(
     *           Map&lt;RecipeCapability&lt;?&gt;, List&lt;Content&gt;&gt; contentsMap,
     *           int fullChance, ContentModifier modifier, ContentScaler scaler) {
     *       for (Map.Entry&lt;RecipeCapability&lt;?&gt;, List&lt;Content&gt;&gt; entry : contentsMap.entrySet()) {
     *           List&lt;Content&gt; contents = entry.getValue();
     *           for (int i = 0; i &lt; contents.size(); i++) {
     *               Content content = contents.get(i);
     *               content.chance = fullChance;
     *               content.maxChance = fullChance;
     *               contents.set(i, scaler.scale(entry.getKey(), content, modifier));
     *           }
     *       }
     *   }
     * </pre>
     * 常量名与取值已复核：{@code ChanceLogic.getMaxChancedValue()} 的反编译原文是
     * {@code public static int getMaxChancedValue() { return 10000; }} ⇒ 满概率 = <b>10000</b>。
     *
     * <h3>本实现与旧私货的差异（逐条，不藏）</h3>
     * <ol>
     *   <li><b>逐字等价</b>：缩放发生在 {@code copy.outputs} / {@code copy.tickOutputs} 上，
     *       {@code chance}/{@code maxChance} 在调用 {@code content.copy(cap, modifier)} <b>之前</b>
     *       就被写成满值 —— 顺序与旧私货完全一致（顺序不能换：{@code Content.copy} 是把
     *       {@code this.chance} 带进新实例的，先缩放再改概率就白改了）。</li>
     *   <li><b>不再单独遍历 {@code recipe.outputs}</b>：旧实现改的是「把 probability 拉满的副本」，
     *       本实现直接改 {@code copy} 自己那张表。这同时消除了一个隐患 ——
     *       {@code GTRecipe.copy()} 的 {@code copyContents(..., null)} 会为每条内容
     *       {@code new Content(...)}（不是共享同一 {@code Content} 实例），所以旧私货的原地写
     *       {@code content.chance} 没有污染原配方；本实现沿用同一前提，但位置更明确。</li>
     *   <li><b>补上了旧私货的 {@code parallels}/{@code ocTier} 还原</b>，落在
     *       {@link #guardedCopy(GTRecipe, String)} 里（原因见那个方法的 javadoc：
     *       {@code GTRecipe.copy()} 的构造器<b>根本不接收</b>这两个字段）。</li>
     *   <li><b>额外还原 {@code realParallels}</b>（gtlcore 的 {@code @Unique} 字段）：旧私货没做，
     *       因为那个字段来自 gtlcore 的 {@code GTRecipeMixin}；本工程并行真的走 gtlcore 的
     *       {@code ParallelLogicMixin}，丢了它会让「运行了几个并行」记成 1。</li>
     *   <li><b>不复制 {@code ContentScaler} 那个函数式接口</b>：只有一处调用点，直接内联。</li>
     * </ol>
     *
     * <h3>保持不变的</h3>
     * 只放大产出；{@code duration} 不变（{@link #guardedCopy} 的绊线仍在）；
     * 输入消耗不变；与并行叠乘且<b>并行在前</b>。
     *
     * <h3>⚠️ 一处<b>已被本裁决消掉</b>的旧副作用（如实记录）</h3>
     * 旧实现在注释里写过「{@code chance == 0} 的产出内容不会被缩放」（那是
     * {@code Content.copy} 的 {@code modifier != null && this.chance != 0} 分支的后果）。
     * 现在<b>先</b>把 {@code chance} 写成 10000 再缩放，等于把那个分支恒定为真
     * ⇒ <b>该副作用不再存在</b>：{@code chance == 0} 的产出也会被正常放大。
     * 这正是旧私货的行为（它同样先写 chance 再 scale），所以是与旧私货对齐，不是新引入的偏差。
     *
     * @return {@code multiplier <= 1} 时<b>原样返回入参实例</b>（零分配）；否则返回一个新的 {@link GTRecipe}
     */
    public static GTRecipe multiplyOutputs(GTRecipe recipe, int multiplier) {
        if (recipe == null || multiplier <= 1) {
            return recipe;
        }
        final GTRecipe copy = guardedCopy(recipe, "N3");
        final ContentModifier modifier = ContentModifier.multiplier(multiplier);
        final int fullChance = ChanceLogic.getMaxChancedValue();
        amplifyAndForceFullChance(copy.outputs, fullChance, modifier);
        amplifyAndForceFullChance(copy.tickOutputs, fullChance, modifier);
        return copy;
    }

    /**
     * 把一张内容表的每条内容<b>先拉满概率、再按倍率缩放</b>（旧私货 {@code amplifyAndForceFullChance}
     * 的等价实现，逐字对照见 {@link #multiplyOutputs}）。
     *
     * <p>入参必须是<b>副本自己的</b>可变表：{@code GTRecipe.copy()} 的 {@code copyContents}
     * 产出的是 {@code new HashMap} + {@code new ArrayList}，所以 {@code contents.set(i, …)} 合法。
     * 空表会被上游跳过（{@code copyContents} 对 {@code null}/empty 直接不放进新表），
     * 所以这里的 {@code null} 判空只是防御。
     */
    private static void amplifyAndForceFullChance(Map<RecipeCapability<?>, List<Content>> contentsMap,
                                                  int fullChance, ContentModifier modifier) {
        for (Map.Entry<RecipeCapability<?>, List<Content>> entry : contentsMap.entrySet()) {
            final List<Content> contents = entry.getValue();
            if (contents == null) {
                continue;
            }
            for (int i = 0; i < contents.size(); i++) {
                final Content content = contents.get(i);
                content.chance = fullChance;
                content.maxChance = fullChance;
                contents.set(i, content.copy(entry.getKey(), modifier));
            }
        }
    }

    // ═══════════════════════════════════ N5 减免 ═══════════════════════════════════

    /**
     * N5 的<b>成本系数</b> {@code f}（乘到 EUt / duration 上的那个）。
     *
     * <h2>🔴 2026-09-21 改正：从"减免比例"改成"成本系数"</h2>
     * 用户给的 {@code 0.95^(17/等级)} 是<b>减免比例 R</b>，本方法此前把它<b>原样返回</b>当成本系数用，
     * 于是 Lv.1 只花 0.4181 倍电（减免 58%）、Lv.17 反而要花 0.95 倍电（减免 5%）
     * —— <b>等级越高越费电，方向整个反了</b>。用户原话逐字见类注释 N5 一节。
     * <pre>
     *   R(等级) = 0.95 ^ (17.0 / 等级)          ← 用户公式，不动
     *   f(等级) = 1 - R(等级)                    ← 本方法的返回值（2026-09-21 起）
     *   Lv17 ⇒ R = 0.95 ⇒ f = 0.05              ← 与用户原话「原耗电*0.05」逐字吻合
     * </pre>
     *
     * <p>🔴 {@code level <= 0} <b>必须先短路成 1.0（不减免）</b>：见类注释的除零说明，
     * 且"等级 0"在本工程里的语义就是<b>门控未生效</b>。
     * 本方法对 {@code level > 17} 也不做钳制（{@code R} 在 17 以上继续缓慢趋近 1、
     * {@code f} 趋近 0 但恒 {@code > 0}，而全工程的等级表 {@code MODULE_LEVELS} 只有 1..17，
     * 多出来的值不可能是本工程产生的）。
     *
     * @return {@code [0, 1]} 区间的成本系数；{@code level <= 0} 时恒为 {@code 1.0}
     */
    public static double reductionFactor(int level) {
        if (level <= 0) {
            return 1.0D;
        }
        return 1.0D - Math.pow(REDUCTION_BASE, REDUCTION_EXPONENT_NUMERATOR / level);
    }

    /**
     * N5 的<b>耗时旋钮（仅模块侧）</b>：{@code newDur = max(1, round(duration × factor))}。
     *
     * <p>🔴 <b>主机侧不调用本方法</b>（主机时长恒等，见 {@code applyIdentityDuration(GTRecipe, int)}）。
     * ⛔ **【2026-09-22 订正 · 旧句照留】** 括号里「主机时长恒等」是<b>旧口径</b>，已被用户裁定
     * 「**照上游：下限 + 总能量守恒**」（A 口径）取代 ⇒ **主机后来连 {@code applyHostDurationCap} 也不用了：
     * 主机现行口径 = {@link #durationFloorTarget(int, int)}（下限）。**
     * 唯一调用点是 {@code ModuleRegistry#applyModuleRecipeModifier} 第 ③ 步。
     *
     * <p>这里只做乘法，<b>不夹下限</b> —— 下限由 {@code applyDurationFloorForModule(GTRecipe, int)}
     * ⛔ **【2026-09-22 订正 · 旧句照留】** 该方法已改名为
     * {@link #applyModuleDuration(GTRecipe, int)}（**次旧 = 下限 + 天花板；2026-09-26 起下限已删，
     * 只剩保底 + 天花板**）；改名原因见其 javadoc：
     * {@code max} 只保证"不比下限快"、**不保证"不比原来慢"** ⇒ 1 tick 的配方会被下限拖到 20 tick。
     * 在第 ④ 步单独施加，因为「N5 乘法与 N6 下限的先后」是写死的口径：
     * {@code max(下限, round(duration × f))}。
     * ⛔ **【2026-09-22 订正 · 旧式照留】** 上一行的旧式现行读作
     * **{@code min(原时长, max(下限, round(原时长 × f)))}** —— 即在外层再夹一次「永不变长」的天花板。
     * ✅ **【2026-09-26 第三版 · 用户裁决「连功能一起删」】** 下限项整条删除 ⇒ 现行读作
     * **{@code min(原时长, max(1, round(原时长 × f)))}**（只剩「≥1 tick」保底与天花板）。
     *
     * @return {@code factor >= 1} 时原样返回入参（未生效则零分配）
     */
    public static GTRecipe applyDurationReduction(GTRecipe recipe, double factor) {
        if (recipe == null || !(factor > 0.0D) || factor >= 1.0D) {
            return recipe;
        }
        final int reduced = saturateMillisToTicks(recipe.duration * factor);
        if (reduced == recipe.duration) {
            return recipe;
        }
        final GTRecipe copy = guardedCopy(recipe, "N5-耗时");
        copy.duration = reduced;
        return copy;
    }

    /**
     * N5 的<b>耗能旋钮（主机与模块都吃）</b>：把配方的 EU 内容乘 {@code factor}，并钳到 {@code ≥ 1 EU/t}。
     *
     * <p>🔴 <b>2026-09 队长改判（用户原话「主机耗电吃N5减免」）</b>：本方法在<b>两侧</b>都调用 ——
     * 主机链第 ④ 步与模块链第 ⑤ 步。<b>主机只吃「耗电」这半边，不吃「耗时」那半边</b>
     * （主机时长恒等，见 {@code applyIdentityDuration(GTRecipe, int)}）。
     * ⛔ **【2026-09-22 订正 · 旧句照留】** 括号里「主机时长恒等」是旧口径；**现行 = A 口径下限**
     * ⇒ 主机时长由 {@link #durationFloorTarget(int, int)} 的 {@code max(1, dx, 下限)} 决定
     * （{@code durationTarget} 那套 min 天花板现在只服务模块侧）。**"主机只吃耗电这半边"这条本身没有变。**
     *
     * <h2>为什么必须钳 ≥ 1</h2>
     * {@link ContentModifier#multiplier(double)} 会向下取整，
     * {@code 1 EU/t × 0.05(Lv.17 的 f) = 0.05 ⇒ 0}；<b>零耗能的配方在 GTCEu 里是"永动机"</b>，
     * 而且 {@code EURecipeCapability} 的匹配语义会随之改变。所以这里显式地板到 1。
     * （旧注释里的例子写的是 {@code 1 EU/t × 0.4181}，那是作废的旧口径——0.4181 现在是 Lv.1 的
     * <b>减免比例 R</b>，不再是成本系数；留档见类注释 N5 一节。）
     *
     * <h2>为什么改 {@code tickInputs}（也顺带处理 {@code inputs}）</h2>
     * {@code RecipeHelper.getInputEUt(recipe)} 的原文是
     * {@code recipe.getTickInputContents(EURecipeCapability.CAP)...sum()} ⇒ EU 住在前者；
     * {@code inputs} 只是历史写法，一起处理是防御。
     *
     * <h2>为什么不是 {@code OCResult.setEut(long)}</h2>
     * 那个候选<b>其实可行</b>（已找到正向对照：gtlcore 的 {@code RecipeModifierListMixin} 覆写了
     * {@code RecipeModifierList.apply}，在收尾处 {@code if (result.getDuration() != 0)} 时才把
     * {@code result.getEut()} 写回 {@code tickInputs}），但它有两个额外代价：
     * ① 必须以 {@code result.setDuration(...)} 不等于 0 为闸门，等于把「耗能」和「耗时」两个旋钮绑死；
     * ② 收尾回写会把 {@code duration} 与 EU 一起覆盖，正好会盖掉 N6 写下的时长值。
     * 所以选①（直接改 GTRecipe 的 content）。详见交付报告。
     *
     * @return {@code factor >= 1} 或无 EU 内容时原样返回入参
     */
    public static GTRecipe reduceEnergy(GTRecipe recipe, double factor) {
        if (recipe == null || !(factor > 0.0D) || factor >= 1.0D) {
            return recipe;
        }
        final boolean hasTickEu = !recipe.getTickInputContents(EURecipeCapability.CAP).isEmpty();
        final boolean hasPlainEu = !recipe.getInputContents(EURecipeCapability.CAP).isEmpty();
        if (!hasTickEu && !hasPlainEu) {
            return recipe;
        }
        final GTRecipe copy = guardedCopy(recipe, "N5-耗能");
        boolean changed = scaleEnergyInPlace(copy.getTickInputContents(EURecipeCapability.CAP), factor);
        changed |= scaleEnergyInPlace(copy.getInputContents(EURecipeCapability.CAP), factor);
        return changed ? copy : recipe;
    }

    /** 就地缩放一组 EU 内容；{@code list} 是<b>副本自己的</b>可变列表（{@code Collections.emptyList()} 时循环体不会执行）。 */
    private static boolean scaleEnergyInPlace(List<Content> contents, double factor) {
        boolean changed = false;
        for (Content content : contents) {
            final Long raw = EURecipeCapability.CAP.of(content.content);
            if (raw == null) {
                continue;
            }
            final long original = raw;
            final long reduced = Math.max(1L, Math.round(original * factor));
            if (reduced != original) {
                content.content = reduced;
                changed = true;
            }
        }
        return changed;
    }

    // ═══════════ EUt 精确覆盖（引擎路径的最后一步：用原生算式的结果覆盖引擎取整值） ═══════════

    /**
     * 🔴 <b>把每 tick EUt <u>精确设定</u>为给定值</b>（不是"乘一个系数"—— 与
     * {@link #reduceEnergy(GTRecipe, double)} / {@link #rescaleEnergyForDuration(GTRecipe, int, int)}
     * 是同一族，写法也照抄它们的 {@code scaleEnergyInPlace} 路径，只把"缩"换成"设"）。
     *
     * <h2>为什么需要它（2026-09-26 用户实机报的真 bug）</h2>
     * 引擎给 EUt 的方式是<b>除回来</b>：{@code RecipeCalculationHelper.buildNormalRecipe} 里
     * {@code EUt = totalEu / maxEUt} 再按 {@code duration = max(round(...), minDuration)} 分摊
     * ⇒ 只要 `totalEu` 不是 `maxEUt` 的整数倍，就<b>必然丢余数</b>。用户实机抓到的原文（满配物质模块）：
     * <pre>
     *   引擎路径 EUt = 3221225470 ；原生链重放 EUt = 3221225471（p = 2147483647，d0 = 1）
     * </pre>
     * ⇒ 差 <b>1</b>。而原生链的 EUt 是 {@code baseEUt × p}（乘法，精确）。
     * ⇒ 引擎路径最后一步必须<b>用原生算式的结果覆盖掉引擎那个近似值</b>，否则"值=1 逐字一致"永远差 1。
     *
     * <h2>口径</h2>
     * 本原语假设该配方的 EU 内容<b>只有一份</b>（引擎 {@code buildNormalRecipe} 的成品即如此：
     * 一张 {@code tickInputs[eu]} 列表、一个元素）。多份时逐份都设为 {@code euPerTick}，
     * 那时 {@code RecipeHelper.getInputEUt} 的含义是"求和" ⇒ 调用方要自己保证只在单份场景用它。
     *
     * @param euPerTick 目标每 tick EUt；{@code <= 0} 时原样返回入参（不做任何事）
     * @return 已设定的配方；<b>无需改动时原样返回入参实例</b>（零分配）
     */
    public static GTRecipe setEUtPerTick(GTRecipe recipe, long euPerTick) {
        if (recipe == null || euPerTick <= 0L) {
            return recipe;
        }
        final boolean hasTickEu = !recipe.getTickInputContents(EURecipeCapability.CAP).isEmpty();
        final boolean hasPlainEu = !recipe.getInputContents(EURecipeCapability.CAP).isEmpty();
        if (!hasTickEu && !hasPlainEu) {
            return recipe;
        }
        final GTRecipe copy = guardedCopy(recipe, "EUt-精确覆盖");
        boolean changed = setEnergyInPlace(copy.getTickInputContents(EURecipeCapability.CAP), euPerTick);
        changed |= setEnergyInPlace(copy.getInputContents(EURecipeCapability.CAP), euPerTick);
        return changed ? copy : recipe;
    }

    /** {@link #setEUtPerTick(GTRecipe, long)} 的就地写值（与 {@code scaleEnergyInPlace} 同一套 EU 内容写法）。 */
    private static boolean setEnergyInPlace(List<Content> contents, long value) {
        boolean changed = false;
        for (Content content : contents) {
            final Long raw = EURecipeCapability.CAP.of(content.content);
            if (raw == null) {
                continue;
            }
            if (raw != value) {
                content.content = value;
                changed = true;
            }
        }
        return changed;
    }

    // ═══════════ 守恒反缩放：把「引擎定的时长」换成「我们的时长」而不丢总能量 ═══════════

    /**
     * 🔴 <b>守恒反缩放（队长 2026-09-21 裁决选（甲），<u>只给多配方引擎路径用</u>）</b>：
     * {@code EUt ← max(1, round(EUt × duration_引擎 / max(1, targetDuration)))}（{@code targetDuration} = 参数 T）。
     *
     * <h2>为什么需要它（问题陈述）</h2>
     * 引擎 {@code buildNormalRecipe} 的 {@code minDuration} 是<b>它自己硬编码的字面量 20</b>
     * （{@code [字节码]} {@code MutableRecipesLogic.buildFinalNormalRecipe} 的 {@code bipush 20}；
     * 该类常量池对 {@code getLimitedDuration / IGTLAddMultiRecipeMachine} 命中 <b>0</b>），
     * 它保证的是
     * <pre>
     *   EUt_引擎 × duration_引擎 ≈ totalEu        （duration_引擎 = round(max(totalEu/maxEUt, 20))）
     * </pre>
     * 而主机 N6〔旧口径=恒等；现行 A = {@code max(1, dx, limitedDuration)}〕⇒ 我们随后把 {@code duration} 改成 N6 目标 T，
     * <b>却不动 EUt</b> ⇒
     * <pre>
     *   EUt_引擎 × T ≈ totalEu × (T / duration_引擎)
     *   ⇒ 只有 T == duration_引擎 时才守恒（T = N6 之后的真实时长，A 口径）
     * </pre>
     * <b>用户选的锚点是「和伪神一样」</b>，而伪神/旧私货那两条路都守恒（无线分支
     * {@code EUt = totalEU/getLimitedDuration()}；旧私货引擎类把真 {@code getLimitedDuration()} 传进
     * {@code buildNormalRecipe}）⇒ <b>不守恒的是我们这一支，属于我们的偏离，本方法把它补回来。</b>
     *
     * <h2>🔴 只在【引擎路径】调用（原生 {@code recipeModifier} 链<b>不许</b>调）</h2>
     * 原生路径<b>没有"引擎时长"这个概念</b>（那条路上 duration 一直是配方原值，直到 N6 才被改成
     * N6 目标 T），在那里反缩放<b>是错的</b>。
     * ⇒ 本方法只有一个调用点：{@code PrimordialEngineRecipeLogic#buildFinalNormalRecipe}。
     * <b>共用方法 {@link #applyHostTailEffects} 当时一个字节都没改</b>（见下面「顺序」一节），
     * 所以原生路径的行为当时<b>逐字不变</b>；<b>但 2026-09-22 A 口径起本类已改走 {@link #applyHostTailEffectsA}</b>。
     *
     * <h2>🔴 {@code engineDuration} 必须在 N6 改写 duration <u>之前</u> 取</h2>
     * 调用方必须传「引擎装配出来那一刻的 {@code built.duration}」。
     * 传 N6 之后的值会让因子恒为 1 ⇒ 静默变成 no-op（本方法的 early-return 分支）。
     *
     * <h2>调用顺序（钳 ≥1 必须落在最后）</h2>
     * 调用方把它放在尾链入口（现为 {@link #applyHostTailEffectsA}）<b>之前</b>：
     * <pre>
     *   引擎装配(⇒ duration_引擎, EUt_引擎) → 【本方法：反缩放】 → N3 → N6(定时长) → N5(×f，<b>自带 clamp ≥1，它是最后一次乘法</b>)
     * </pre>
     * 反缩放与 N5 的 {@code ×f} 都是<b>纯乘</b>（数学上可交换），但只有这个顺序能保证
     * 「<b>最后一次乘法之后紧跟钳制</b>」；若把反缩放放到 N5 <b>之后</b>，
     * N5 那个中途的 clamp 会把底数钳坏（例：EUt_引擎 10、f 0.05、反缩放 ×2 ⇒
     * 正确值 = 1，先 N5 后反缩放会得到 2）。
     *
     * <h2>🔴 {@code EUt} 仍钳 {@code ≥ 1}</h2>
     * 缩放走的是与 {@link #reduceEnergy} <b>同一个</b> {@code scaleEnergyInPlace}
     * ⇒ 每一档都 {@code Math.max(1L, …)}，<b>不可能出现 0 / 负耗能（永动机）</b>。
     * 另外这里的钳制还兼一份职责：{@code T > duration_引擎}（N6 目标比引擎算出的时长还长）时因子 &lt; 1，
     * 若不做钳制，{@code EUt = 1} 会被舍入成 0。
     *
     * <h2>🟡 精度的诚实边界（不是我引入的误差）</h2>
     * 引擎自己那一步就有<b>取整</b>：{@code EUt_引擎 = (long)(totalEu / 20)} 是<b>截断</b>，
     * {@code duration_引擎 = round(dx)} 是<b>四舍五入</b>。
     * ⇒ 本方法恢复的守恒是「{@code EUt × 我们的时长} == {@code EUt_引擎 × duration_引擎}」，
     * 而后者本身与 {@code totalEu} 相差一个<b>引擎自带</b>的取整量
     * （{@code dx ≤ 20} 支路：误差 &lt; 20 EU 总量；{@code dx &gt; 20} 支路：误差 ≤ {@code maxEUt/2}）。
     * 报告里必须写成这个形式，<b>不许写"精确等于 totalEu"</b>。
     * <p>还有一处边界：若钳制真的生效（缩放后 &lt; 1 而被抬到 1），守恒在该点上不再成立。
     * 这是可接受的边界，但同样不许隐瞒。
     *
     * <p>🔴 <b>2026-09-22 口径细化（A 口径定稿，请连上面那段一起读）</b>：
     * 上面正文里凡是写 {@code L} / "我们的 N6 恒等目标" 的地方，<b>一律读作 {@code T}</b>
     * —— {@code T = max(1, max(1, dx), max(1, limitedDuration))}（A 下限）是 N6 改写【之后】的真实时长。<b>原文保留不改</b>，此处加注。
     *
     * @param recipe         引擎装配出的配方（此时 duration 仍是 <b>引擎的</b>）
     * @param engineDuration <b>N6 改写之前</b>的 {@code recipe.duration}（引擎时长）；{@code ≤ 0} 时直接跳过
     * @param targetDuration 🔴 <b>N6 改写【之后】的时长 T</b> —— <b>不是</b>侧栏那个限定值 L。
     *                       <p>守恒要的是 {@code EUt_终 × T == EUt_引擎 × D} ⇒ 因子必须是 {@code D/T}。
     *                       旧写法传 {@code L}，只在 {@code T == L} 时正确；A 口径下
     *                       {@code dx > L ⇒ T > L}（短配方被拖长），若仍以 {@code L} 为分母，
     *                       <b>守恒会静默破掉（总能量差 T/L 倍）</b> —— 正是本项目最忌讳的失败模式。
     *                       <p>{@code T == D} 时本方法原样返回入参（零分配；最常见的一档）。
     * @return 缩放后的配方；<b>不缩放时原样返回入参实例</b>（零分配、零改动）
     */
    public static GTRecipe rescaleEnergyForDuration(GTRecipe recipe, int engineDuration,
                                                    int targetDuration) {
        if (recipe == null || engineDuration <= 0) {
            // 🔴 不许除零；也不许在"拿不到引擎时长"时乱缩（跳过 = 保持现状，保守）。
            return recipe;
        }
        final int target = Math.max(1, targetDuration);
        if (engineDuration == target) {
            // 恒等：**逐值一致且不产生新实例**。
            // 🔴 最常见的一档（dx ≤ 20 支路下 duration_引擎 = 20，而原配方也不短于 20）
            //    ⇒ 本方法的净效果 = 什么都没发生（连副本都不建）。
            return recipe;
        }
        final boolean hasTickEu = !recipe.getTickInputContents(EURecipeCapability.CAP).isEmpty();
        final boolean hasPlainEu = !recipe.getInputContents(EURecipeCapability.CAP).isEmpty();
        if (!hasTickEu && !hasPlainEu) {
            return recipe;
        }
        final double factor = energyRescaleFactor(engineDuration, targetDuration);
        final GTRecipe copy = guardedCopy(recipe, "守恒-反缩放");
        boolean changed = scaleEnergyInPlace(copy.getTickInputContents(EURecipeCapability.CAP), factor);
        changed |= scaleEnergyInPlace(copy.getInputContents(EURecipeCapability.CAP), factor);
        return changed ? copy : recipe;
    }

    /**
     * 🔴 <b>守恒反缩放的因子本身</b>：{@code engineDuration / max(1, targetDuration)}（记 {@code D/T}）。
     *
     * <p>⚠️ <b>2026-09-22：第二个形参的语义由 {@code L}（侧栏限定值）改成 {@code T}（N6 之后的真实时长）</b>
     * —— N6 从"恒等"改到 A 口径 {@code max(1, dx, L)} 之后，{@code T ≠ L} 会成为常态（dx &gt; L 的短配方）。
     * 本节正文照旧写作 {@code D/L}，<b>一律读作 {@code D/T}</b>（原文保留 + 此处加注）。
     *
     * <h2>🔴 它<b>不再</b>被抬头（Jade）使用 —— 别把两者混起来</h2>
     * 队长 2026-09-21 <b>改判 (Q)</b> 之后，抬头「耗能倍率」的分母改用<b>原配方时长 {@code d0}</b>
     * （{@code f × d0/T}），因为那是"相对原配方"的基线、能让 `耗能 × 耗时 = f` 恒成立。
     * <b>⇒ 本因子只有两个用途，都在"真实生效"这一侧：</b>
     * <ol>
     *   <li>{@link #rescaleEnergyForDuration} —— <b>真的乘进 EUt</b>（守恒反缩放本身）；</li>
     *   <li>{@code PrimordialEngineRecipeLogic#logEngineEvidence} —— 证据日志里印出来核对。</li>
     * </ol>
     * 🔴🔴 <b>本因子的分母是"我们的真实时长 T"（由引擎时长 D 推出），抬头那个的分母是"原配方时长 d0"</b>
     * —— 两者<b>数值一般不等</b>（默认档 D = 20、d0 = 200 ⇒ 差 10 倍）。
     * <b>若把抬头那套 {@code d0/T} 用到真反缩放上，守恒立刻被破坏</b>（总能量会差 {@code d0/D} 倍）；
     * 反之把本因子用到显示上，就是被改判掉的 (P)。⇒ 两处用不同表达式 + 不同名字隔离，不许互串。
     *
     * <p>{@code engineDuration <= 0}（拿不到引擎时长）时返回 {@code 1.0} ——
     * 与 {@link #rescaleEnergyForDuration}「跳过缩放」的行为一致。
     */
    public static double energyRescaleFactor(int engineDuration, int targetDuration) {
        if (engineDuration <= 0) {
            return 1.0D;
        }
        return (double) engineDuration / (double) Math.max(1, targetDuration);
    }

    // ═════════════ N6 时长：主机【下限 A 口径】 / 模块【保底 + 天花板，下限已删】 ═════════════

    /**
     * <b>本方法 = {@code min} 口径</b>（{@code duration = min(原时长, max(1, limitedDuration))}）；⛔ <b>主机侧已不再走本方法：主机生产路径 = {@link #durationFloorTarget(int, int)} 的 A 下限。</b>
     *
     * <h2>🔴 2026-09-22 语义变更（队长裁定）：从「恒等」改成「min」（该 min 口径现只服务模块侧与已停用的旧主机链）</h2>
     * <pre>
     *   旧（作废）：duration ≡ max(1, limitedDuration)             —— 【恒等】：无条件等于限定值
     *   本口径（非主机）：duration = min(原时长, max(1, limitedDuration)) —— 【天花板】：永不把配方拖慢
     * </pre>
     * <b>作废原因（用户实测点出）</b>：恒等语义下<b>比限定值更短的配方会被【拉长】</b> ——
     * 一条<b>原本 1 tick</b> 的配方被强行钉到 10 tick ⇒ 拉长 10 倍 ⇒ 抬头「总耗时倍率」显示
     * <b>1000%</b>（用户截图：我们的 1000% vs 仿神版的 100%）。
     * <p><b>⇒「工作速度无限」的正确含义是"不超过下限"，不是"恰好等于下限"。</b>
     *
     * <h2>🔴 为什么"原时长"必须由调用方传进来</h2>
     * 两条路径的入口配方<b>不是同一件事</b>：
     * <ul>
     *   <li>原生路径：入口配方就是配方定义原实例 ⇒ 链入口快照就是原时长；</li>
     *   <li><b>多配方引擎路径</b>：入口配方是引擎<b>已经装配好</b>的成品，它的 {@code duration} 是引擎
     *       自己算的 D（{@code round(max(totalEu/maxEUt, 20))}，<b>自带硬编码 20 下限</b>）⇒
     *       <b>D 不是原时长</b>，原时长要从 {@code ParallelData.getOriginRecipeList()} 取（d0）。</li>
     * </ul>
     * 若在引擎路径拿 D 当"原时长"，则 1 tick 的配方仍会被 D 的 20 下限兜住 ⇒ <b>判据①不成立</b>。
     *
     * <p>{@code max(1, …)} 里的 1 是<b>服务端兜底</b>：{@code limitedDuration} 正常路径上已被
     * {@link #clampLimitedDuration(int)} 收进 {@code [10, 200]}，但字段是 {@code @Persisted}，
     * 万一落盘数据被改坏成 0/负数，{@code duration = 0} 会让 GTCEu 的配方逻辑除零。
     *
     * <p>🔴 <b>对照</b>：<b>模块侧不走本方法</b>，走 {@link #applyModuleDuration(GTRecipe, int)}。
     * （旧方法名 {@code applyIdentityDuration} 已按新语义改名 —— 名字里的"恒等"正是被删掉的那件事。）
     * ⚠️ <b>主机（A 口径）用的 {@code max(1, dx, limitedDuration)} 里的 {@code limitedDuration} 是【主机自己的】
     * 那一个</b>（主机侧栏控件仍然是活的：{@code PrimordialOmegaEngineMachine:2104}）——
     * 用户 2026-09-26 的裁决只删了<b>模块侧</b>的下限，主机侧这个值一个字都没动。
     *
     * @param originalDuration 配方定义的原时长（= 天花板；{@code <= 0} 表示未知 ⇒ 退化成只夹上限）
     * @return {@code duration} 已等于目标值时原样返回入参（零分配）
     */
    public static GTRecipe applyHostDurationCap(GTRecipe recipe, int limitedDuration, int originalDuration) {
        if (recipe == null) {
            return recipe;
        }
        final int target = durationTarget(originalDuration, limitedDuration);
        if (recipe.duration == target) {
            return recipe;
        }
        final GTRecipe copy = guardedCopy(recipe, "N6");
        copy.duration = target;
        return copy;
    }

    /**
     * <b>模块侧时长的统一规则</b>：{@code min(原时长, cap)} —— <b>模块时长永不变长</b>（⚠️ 主机不再走这里，见 {@link #durationFloorTarget(int, int)}）。
     *
     * <pre>
     *   主机（⛔ 已作废，改走 A 下限）：cap = max(1, limitedDuration)     ⇒ min(原时长, 限定值)
     *   模块（现行）：cap = 当前时长（N5 先乘过 f 的值）                  ⇒ min(原时长, max(1, round(原时长×f)))
     * </pre>
     * <p>{@code originalDuration <= 0}（未知）⇒ 不做天花板，只夹 {@code cap}。
     */
    public static int durationTarget(int originalDuration, int cap) {
        final int ceiling = Math.max(1, cap);
        if (originalDuration <= 0) {
            return ceiling;
        }
        return Math.min(originalDuration, ceiling);
    }

    /**
     * <b>主机侧 N6 的新口径 A1（用户 2026-09-22 拍板选 A）</b>：{@code T = max(dx, 下限)}。
     *
     * <h2>⛔ 旧口径（作废，原文照留）</h2>
     * <pre>
     *   旧： duration = min(原时长, max(1, limitedDuration))        —— 【上限】："不许更慢"
     * </pre>
     * <b>作废原因</b>：用户要的是【下限】——「不许更快 + **总能量守恒**」。
     * 🔴 <b>连带作废</b>：主机侧「**时长永不变长**」这条红线（用户选 A 时**明知**小批量会变慢、
     * EUt 同步降低）⇒ 「1 tick 配方仍是 1 tick」**不再是判据**。
     *
     * <h2>✅ 新口径（= 上游语义）</h2>
     * <pre>
     *   上游 RecipeCalculationHelper.buildNormalRecipe（javap -c 逐句核对）：
     *       d   = totalEu / maxEUt
     *       T   = roundToInt(max(d, minDuration))        // ← 上游的 minDuration 是硬编码 20
     *       EUt = d > minDuration ? maxEUt : (long)(totalEu / minDuration)
     *   ⇒ 两分支的 EUt × T 都等于 totalEu（守恒）。
     *   本工程把那个硬编码的 20 换成玩家的 limitedDuration ⇒ T = max(dx, 下限)。
     * </pre>
     *
     * @param dx              引擎口径下的"整批总能量 ÷ 最大电压"
     * @param limitedDuration 配方最短耗时（下限）
     * @return {@code max(max(1, dx), max(1, limitedDuration))}
     */
    public static int durationFloorTarget(int dx, int limitedDuration) {
        final int floor = Math.max(1, limitedDuration);
        return Math.max(Math.max(1, dx), floor);
    }

    /**
     * <b>模块侧 N6（2026-09-26 起 = 「保底 ≥1」+ 天花板；<u>下限项已按用户裁决整体删除</u>）</b>：
     * {@code duration = min(原时长, max(1, 当前时长))}，其中当前时长 = N5 乘过 f 的值。
     *
     * <h2>⛔ 旧的两种口径（作废，原文与作废理由一并留档）</h2>
     * <pre>
     *   最旧（作废）：duration = max(下限, round(d × f))              —— 只保证"不比下限快"
     *   次旧（作废）：duration = min(原时长, max(下限, round(d × f)))  —— 另加"不比原来慢"
     * </pre>
     * <b>🔴 作废原因 —— 用户 2026-09-26 裁决，原话逐字：「连功能一起删」</b>。
     * <p>本方法 javadoc 里原来那句「模块侧保留（最小区间）下限的理由」写的是：
     * <i>「模块吃 N5 的耗时减免（先乘 f），减免后的值必须被『配方最短耗时』托住，否则玩家在侧栏调的
     * 那个旋钮在模块上完全没有意义」</i>。本条裁决把那条理由<b>整条抽掉了</b>：
     * <pre>
     *   ① 那个「侧栏旋钮」（{@code LimitedDurationConfigurator}）2026-09-22 就已按用户裁决
     *      从模块上摘掉（{@code PrimordialModuleMachine#attachConfigurators} 现在只剩 super）；
     *   ② 2026-09-26 用户追加确认原话：「我们当时确实把模块的最小配方耗时给去除了」；
     *   ⇒ 被「托住」的那一头（玩家可调的旋钮）<b>已经不存在</b> ⇒ 下限项失去存在理由。
     * </pre>
     *
     * <h2>✅ 现行公式（用户 2026-09-26 口径）</h2>
     * <pre>
     *   模块耗时 = 原耗时 × 减免系数   （<b>不再有 20 tick 下限</b>）
     * </pre>
     *
     * <h2>🔴 保底 {@code ≥ 1 tick} 是【另一件事】，必须留（不是"下限"的残留）</h2>
     * 用户删的是「下限 20」，<b>不是</b>「至少 1 tick」。两者容易混，这里写清为什么留：
     * <pre>
     *   {@code 原时长 = 1、f = 0.16}  ⇒ {@code round(1 × 0.16) = 0}  ⇒ 算出 0
     * </pre>
     * {@code duration = 0} 不是"瞬间完成"而是一个<b>上游从未设计过的退化值</b>：
     * GTCEu 的配方进度是按 {@code duration} 归一化推进/绘制的，0 会让它落进"除零 / 永不完成"
     * 这一类未定义行为（同一结论在 {@link #applyHostDurationCap(GTRecipe, int, int)} 的 javadoc
     * 里对主机侧已写过一次，是同一个理由）。⇒ 保底 {@code Math.max(1, …)} 由
     * {@link #durationTarget(int, int)} 承担，本方法不再自己夹。
     *
     * <p>⚠️ 本次改动<b>只删下限项、只删那个参数</b>：外层 `min(原时长, …)` 的
     * 「时长永不变长」天花板<b>保留</b>（{@code f ≤ 1} 时它是恒等操作、零成本，
     * 但它是模块侧唯一的"绝不把配方拖慢"防线，也是
     * {@code PrimordialModuleRecipeLogic} 那条审计断言的前提）。
     *
     * @param originalDuration 配方定义的原时长（天花板；{@code <= 0} ⇒ 未知，退化成只保底）
     * @return 目标值等于当前值时原样返回入参（零分配）
     */
    public static GTRecipe applyModuleDuration(GTRecipe recipe, int originalDuration) {
        if (recipe == null) {
            return recipe;
        }
        // 🔴 2026-09-26：下限项已按用户裁决（「连功能一起删」）删除，参数 `limitedDuration` 一并去掉。
        //    剩下的 `max(1, …)` 保底在 durationTarget 内部 —— 见本方法 javadoc「保底是另一件事」。
        final int target = durationTarget(originalDuration, recipe.duration);
        if (target == recipe.duration) {
            return recipe;
        }
        final GTRecipe copy = guardedCopy(recipe, "N6-模块");
        copy.duration = target;
        return copy;
    }

    /**
     * <b>duration 运行期绊线（"没有别的步骤偷偷改 duration"）</b>：期望值与实得值不一致 ⇒
     * <b>响亮地</b>喊出来并把副本改回期望值。
     *
     * <h2>为什么重要</h2>
     * 主机链会<b>无条件直接写 {@code duration}</b>（N6）；于是「有没有别的步骤偷偷改 duration」
     * 这件事必须<b>显式证明</b>。主机链在 ① 并行、② 倍率 之后各插一次本方法，N6 之后再终检 ——
     * 一旦上游（GTCEu / gtlcore）改了 {@code copy()} 或 {@code ParallelLogic} 的契约，
     * 日志里会立刻出现 {@code [SHANHAI-…]} 错误行，而不是静默地把时长改掉。
     *
     * <h2>🔴 2026-09-22 口径改写（本方法中性；N6 前后换过两版口径，本方法都用同一种断言）</h2>
     * 错误文案早先写过「本工程 N6 的口径是【恒等】」、后写过「min(原时长, 上限)」—— <b>两版都已被取代</b>：
     * 主机现行 = A 口径 {@code max(1, dx, max(1, limitedDuration))}（见 {@link #durationFloorTarget(int, int)}）。
     * <b>本方法只做"实得 == 调用方给的期望值"这一个断言</b>，与 N6 是哪一版口径无关 ⇒ 逻辑<b>未改</b>，只改了文案。
     * <p><b>{@code auditDurationWithin} 才是 min 口径专用的那一套</b>：
     * 它额外断言「≤ 原时长」与「≤ 上限」两条不等式 —— <b>A 口径下这两条必然误报，所以 A 路径只用本方法</b>。
     * <p>⚠️ <b>【2026-09-22 追加 · 别让两套绊线互相误导】</b>
     * <b>本方法是<u>通用</u>的（只比"实得 vs 期望值"，两版口径都适用）；只有 {@code auditDurationWithin} 的三条不变式是<u>旧 min 口径</u>
     * ——"时长永不变长"——专用。</b>**A 口径请走
     * {@link #applyHostTailEffectsA(GTRecipe, int, int, int, String)} + {@link #auditDuration}**，
     * <b>不要用 {@code auditDurationWithin}</b>：A 的下限 {@code max(dx, L)} 可以把短配方<b>拖长</b>，
     * 「≤ 原时长」那条断言必然误报。
     *
     * <h2>为什么先复制再改</h2>
     * 绊线可能在 {@code recipe} 还是<b>配方缓存里的原实例</b>时触发（例如并行没生效、
     * 倍率也没生效，链上没产生任何副本）。就地改会污染缓存里的配方定义，
     * 所以这里走 {@link #guardedCopy(GTRecipe, String)} 拿一个副本再改。
     *
     * @return 一致时原样返回入参；不一致时返回「已改回期望值」的新副本
     */
    public static GTRecipe auditDuration(GTRecipe recipe, int expected, String step) {
        if (recipe == null || recipe.duration == expected) {
            return recipe;
        }
        LOGGER.error("[SHANHAI-{}] duration 绊线触发：期望 {} tick，实得 {} tick。"
                        + "本工程 N6 只许由 N6 那一步改写（主机 = A 下限 max(1, dx, 下限)；模块 = min 天花板）——链上任何步骤都不许改 duration。"
                        + "上游契约可能已变（ParallelLogic / GTRecipe.copy），已按期望值强制还原并返回副本。",
                step, expected, recipe.duration);
        final GTRecipe fixed = guardedCopy(recipe, step);
        fixed.duration = expected;
        return fixed;
    }

    /**
     * <b>N6 专用绊线（2026-09-22 新增）</b>：一次断言三条不变式，失败时逐条报出是哪一条破了。
     *
     * <pre>
     *   ① duration == expected（expected = min(原时长, 上限)）  ⇒ 结果正确
     *   ② duration &lt;= originalDuration                        ⇒ 【时长永不变长】（本轮新增的核心红线）
     *   ③ duration &lt;= cap                                     ⇒ 【不越过上限】
     * </pre>
     * <b>为什么要拆成三条而不是只断言 ①</b>：只断言等式时，若 {@code expected} 自己算错
     * （例如把引擎时长 D 当成原时长 d0），<b>绊线会安静地通过</b> —— 因为它比的是"实得 vs 我算的"。
     * 加上 ② 之后，「短配方被拖慢」这一类回归<b>无论 expected 怎么算错都拦得住</b>。
     *
     * <p>三条都过 ⇒ 原样返回入参；任一条破 ⇒ 打 ERROR（逐条点名）并按 {@code expected} 还原。
     *
     * @param originalDuration 配方定义的原时长（天花板）；{@code <= 0} ⇒ 跳过第 ② 条（未知不硬判）
     * @param cap              本链施加的上限（唯一调用点 = 已停用的旧主机尾链 {@code applyHostTailEffects}：{@code max(1, limitedDuration)}）
     */
    public static GTRecipe auditDurationWithin(GTRecipe recipe, int expected,
                                               int originalDuration, int cap, String step) {
        if (recipe == null) {
            return recipe;
        }
        final int actual = recipe.duration;
        boolean ok = actual == expected;
        if (originalDuration > 0 && actual > originalDuration) {
            ok = false;
            LOGGER.error("[SHANHAI-{}] duration 绊线②（时长永不变长）触发：实得 {} tick > 原时长 {} tick。"
                    + "本链（N6 = min 天花板）不许把配方拖慢；主机生产路径的 N6 是 A 下限 max(1, dx, 下限)，该路径不用本绊线。", step, actual, originalDuration);
        }
        if (actual > Math.max(1, cap)) {
            ok = false;
            LOGGER.error("[SHANHAI-{}] duration 绊线③（不越过上限）触发：实得 {} tick > 上限 {} tick。",
                    step, actual, Math.max(1, cap));
        }
        if (ok) {
            return recipe;
        }
        if (actual != expected) {
            LOGGER.error("[SHANHAI-{}] duration 绊线①（结果正确）触发：期望 {} tick，实得 {} tick。"
                            + "已按期望值强制还原并返回副本。", step, expected, actual);
        }
        final GTRecipe fixed = guardedCopy(recipe, step);
        fixed.duration = expected;
        return fixed;
    }

    /**
     * 把外部传进来的最短耗时收进 GUI 允许的 {@code [10, 200]}（GUI 控件自己也会钳，这里是服务端兜底）。
     *
     * <p>🔴 <b>2026-09-26 用途收窄（不是删除）</b>：模块侧的那个 setter 已随下限功能一起删除，
     * 本方法现在<b>只剩主机侧一个调用点</b>{@code PrimordialOmegaEngineMachine#setLimitedDuration(int)}
     * —— 那台主机的侧栏控件仍然是活的 ⇒ <b>删掉本方法会让主机侧编译不过</b>。保留理由同上。
     */
    public static int clampLimitedDuration(int limitedDuration) {
        return Math.max(MIN_LIMITED_DURATION, Math.min(MAX_LIMITED_DURATION, limitedDuration));
    }

    // ═══════════════════════════════════ 并行 ═══════════════════════════════════

    /**
     * GTCEu 原生并行入口（{@code ParallelLogic.applyParallel}）。
     *
     * <h2>运行期实际执行的是 gtlcore 的版本（已逐条查证，不是猜的）</h2>
     * <ol>
     *   <li>{@code gtlcore.mixin.json} 的 {@code mixins} 列表里有
     *       {@code "gtm.api.recipe.ParallelLogicMixin"}（且 {@code "required": true}）—— 它生效；</li>
     *   <li>那个 mixin 用 {@code @Overwrite} 换掉了 {@code getMaxRecipeMultiplier} /
     *       {@code limitByOutputMerging} / {@code doParallelRecipes} 三个静态方法：
     *       <pre>
     *         getMaxRecipeMultiplier → Ints.saturatedCast(Math.min((long) Integer.MAX_VALUE,
     *                                     IParallelLogic.getMaxParallel(holder, recipe, parallelAmount)))
     *         doParallelRecipes      → … setRealParallels(limitByOutput * 当前 realParallels)
     *                                  … IParallelLogic.getRecipeOutputChance(machine, multiRecipe)   // 掷概率
     *                                  … 只在 limitByOutput &gt; 1 时才真的 copy 放大
     *       </pre></li>
     *   <li>{@code ItemRecipeCapability.getMaxParallelRatio} 也被
     *       {@code gtm.api.capability.ItemRecipeCapabilityMixin} @Overwrite 成了
     *       {@code IParallelLogic.getInputItemParallel(...)}；</li>
     *   <li><b>🔴 这里是最容易被误判的一环</b>：{@code IParallelLogic.getInputItemParallel} 的原文是
     *       <pre>
     *         } else if (holder instanceof IRecipeCapabilityMachine machine) {
     *             … 按真实的 RecipeHandlePart 统计可用量，算出真正的可行并行 …
     *         } else {
     *             return 1L;          // ← 不是 gtlcore 体系的机器，并行恒为 1
     *         }
     *       </pre>
     *       「本工程的主机是 {@code WorkableElectricMultiblockMachine}，所以不是
     *       {@code IRecipeCapabilityMachine}，并行因此恒为 1」—— 这个推论<b>是错的</b>。
     *       真相是：{@code gtm.api.machine.WorkableMultiblockMachineMixin} 声明了
     *       <pre>
     *         &#64;Mixin(WorkableMultiblockMachine.class)
     *         public abstract class WorkableMultiblockMachineMixin extends MultiblockControllerMachine
     *                 implements IRecipeCapabilityMachine, IRecipeLogicMachine {
     *       </pre>
     *       —— Mixin 在运行期把 {@code IRecipeCapabilityMachine} <b>织进</b>了
     *       {@code WorkableMultiblockMachine}，而它是本主机与 24 个模块的<b>共同基类</b>。
     *       ⇒ {@code holder instanceof IRecipeCapabilityMachine} <b>为真</b>，
     *       走的是「按 RecipeHandlePart 统计真实可用量」那条路，<b>并行是活的</b>。</li>
     * </ol>
     * ⚠️ <b>残余不确定（只能实机验证）</b>：那条路依赖 {@code upDate()} 在建好结构后
     * 重建 {@code normalCapabilities / sharedInputRecipeHandlePart}（mixin 在
     * {@code onStructureFormed} 的 TAIL 里用 {@code level.getServer().execute(this::upDate)} 触发）。
     * 本任务<b>禁止启动游戏</b>，所以「结构成型后 handle part 一定非空」这条我只到「源码如此」为止，
     * 没有实机确认。
     *
     * <p>{@code modifyDuration = false}：并行<b>不改时长</b>（时长由 N6 管）。
     *
     * @return 并行后的配方；{@code limit <= 1} 或机器不吃并行时原样返回入参
     */
    public static GTRecipe applyParallel(GTRecipe recipe, MetaMachine machine, int limit) {
        if (recipe == null || machine == null || limit <= 1) {
            return recipe;
        }
        final com.mojang.datafixers.util.Pair<GTRecipe, Integer> applied =
                ParallelLogic.applyParallel(machine, recipe, limit, false);
        shanhai$probeGeneratorEnergyWall(machine, recipe, applied.getFirst(), applied.getSecond());
        return applied.getFirst();
    }

    /**
     * 🔴 <b>2026-09-26：{@link #applyParallel(GTRecipe, MetaMachine, int)} 的 <u>long</u> 形态
     * —— 这是模块并行表末三档（4.6e18 / 6.9e18 / Long.MAX）在<b>原生修饰链</b>上的出口。</b>
     *
     * <h2>为什么必须新开一个重载（卡点陈述）</h2>
     * {@code ModuleRegistry} 那条模块配方修饰链原来调的是 int 版（{@code module.parallelCap()}），
     * 而 {@code ParallelLogic.applyParallel}/{@code doParallelRecipes} 的签名
     * <b>三个参数全是 int</b>（gtceu 原版 API；gtlcore 的 {@code ParallelLogicMixin} 只是
     * {@code @Overwrite} 了实现，<b>没有</b>改签名）⇒ 值 &gt; 2^31−1 时<b>物理上塞不进去</b>。
     *
     * <h2>分流规则（这条是本方法唯一的纪律）</h2>
     * <pre>
     *   limit &lt;= Integer.MAX_VALUE  ⇒  原样转交 int 版（<b>逐字走上游原路</b>：
     *                                  canVoid 谓词、BatchProcessing 判定、realParallels 累乘全不变）
     *   limit &gt;  Integer.MAX_VALUE  ⇒  走下面的 long 分支（自建，逐句对齐 gtlcore 的 doParallelRecipes）
     * </pre>
     * ⇒ <b>反向对照</b>：所有 ≤ 21 亿 的取值（含并行表前 14 档、含空槽 64）走的是<b>同一条
     * 字节码路径</b>，行为不可能变。
     *
     * <h2>long 分支对齐了 gtlcore {@code ParallelLogicMixin.doParallelRecipes} 的哪几句</h2>
     * <pre>
     *   [源码原文] {getMaxRecipeMultiplier}  → IParallelLogic.getMaxParallel(holder, recipe, parallelAmount)
     *   [源码原文] {limitByOutputMerging}    → IParallelLogic.getMinParallel(holder, recipe, parallelAmount)
     *   [源码原文] {copy 放大}               → RecipeCalculationHelper.multipleRecipe(recipe, p)
     *                                          （= recipe.copy(ContentModifier.multiplier(p), false)
     *                                             + RecipeExtensionCopier.copy + setRealParallels(p)）
     *   [源码原文] {概率}                    → if (!BatchProcessing.isEnabled(machine)) getRecipeOutputChance(...)
     * </pre>
     * <p>⚠️ <b>两处刻意的差异（如实写出来，别当成等价）</b>：
     * <ol>
     *   <li><b>{@code canVoidRecipeOutputs} 谓词在 long 分支里没有对应物</b>：
     *       {@code limitByOutputMerging} 会对"可销毁输出的 cap"跳过输出空间限制，
     *       而 {@code IParallelLogic.getMinParallel}（long API）没有这个谓词
     *       ⇒ long 分支是按输出空间<b>硬钳</b>。只影响 &gt; 21 亿 的档位，且方向是"更保守"（不会多跑）。</li>
     *   <li><b>{@code realParallels} 是"覆盖"而不是"累乘"</b>：上游写的是
     *       {@code setRealParallels(limitByOutput * 原值)}，而 {@code multipleRecipe} 写的是
     *       {@code setRealParallels(p)}。原生链的入参是<b>配方定义原实例</b>（{@code realParallels} 恒为 1）
     *       ⇒ 两者同值。离开这个前提（例如把已缩放过的配方再喂进来）就不等价。</li>
     * </ol>
     */
    public static GTRecipe applyParallel(GTRecipe recipe, MetaMachine machine, long limit) {
        if (recipe == null || machine == null || limit <= 1L) {
            return recipe;
        }
        if (limit <= (long) Integer.MAX_VALUE) {
            // ≤ int 范围 ⇒ 上游原路（与改动前逐字同一条字节码）。
            return applyParallel(recipe, machine, (int) limit);
        }
        return shanhai$applyParallelLong(recipe, machine, limit);
    }

    /** {@link #applyParallel(GTRecipe, MetaMachine, long)} 的 {@code > Integer.MAX_VALUE} 分支（自建 long 版）。 */
    private static GTRecipe shanhai$applyParallelLong(GTRecipe recipe, MetaMachine machine, long limit) {
        if (!(machine instanceof IRecipeLogicMachine rlm)) {
            // 防御：不是配方逻辑机器就没有"并行"这个概念（上游 int 版内部也是这么强转的）。
            return recipe;
        }
        // ① 输入量上限（long）
        final long byInput = IParallelLogic.getMaxParallel(rlm, recipe, limit);
        if (byInput <= 1L) {
            return recipe;
        }
        // ② 输出空间上限（long）
        final long byOutput = IParallelLogic.getMinParallel(rlm, recipe, byInput);
        if (byOutput <= 1L) {
            // 与上游同形：limitByOutput <= 1 时返回【原配方】，不做任何放大。
            return recipe;
        }
        // ③ 放大（long，含 setRealParallels）
        GTRecipe multi = RecipeCalculationHelper.INSTANCE.multipleRecipe(recipe, byOutput);
        // ④ 概率掷骰（与上游同款闸门）
        if (!BatchProcessing.isEnabled(machine)) {
            multi = IParallelLogic.getRecipeOutputChance(rlm, multi);
        }
        shanhai$probeGeneratorEnergyWall(machine, recipe, multi, byOutput);
        return multi;
    }

    // ═══════════ 批处理（BatchProcessing）在【多配方引擎路径】上的唯一落点 ═══════════

    /**
     * 🔴 <b>批处理的时间窗份数 N —— 照抄 gtlcore {@code BatchProcessing.getBatchSize} 的第一段。</b>
     *
     * <pre>
     *   [字节码] BatchProcessing.apply：
     *       22: iconst_1 / 23: ConfigHolder.INSTANCE.batchProcessingTimeLimitTicks / 29: Math.max  → window
     *       34–57: (duration &gt; 0 &amp;&amp; duration &lt;= window / 2) ⇒ Eligibility.TIME_WINDOW
     *              （⇒ 时长 &gt; 时间窗一半的配方【根本不进批处理】）
     *   [字节码] BatchProcessing.getBatchSize：
     *       21: window  22: recipe.duration  26: idiv   ⇒ cycles = 时间窗 / 配方时长
     *       …再被 limitByLongAmounts 与 getParallelAmountWithoutEU 按<b>输入量</b>收紧
     *   ⇒ 本方法取 N = max(1, batchProcessingTimeLimitTicks / 原配方时长)
     * </pre>
     * ⚠️ <b>与上游的差异（如实写清，不藏）</b>：上游那个 {@code duration} 是<b>修饰链之后</b>的配方时长
     * （模块侧 N5 会把时长乘 f），本方法用<b>配方定义原值</b>（{@code originRecipe.duration}）。
     * 取原值的理由：这是玩家在侧栏看到的那个时长，也是"连跑几次"最直观的口径；
     * 而且原值 ≥ 修饰后值 ⇒ 本方法的 N <b>偏小</b>（更保守），不会放大过头。
     *
     * @return {@code >= 1}；<b>机器没开批处理 / 不支持 / 时长非正 ⇒ 恒为 1</b>（= 逐字退化为改动前的行为）
     */
    public static int batchTimeWindowCycles(IRecipeLogicMachine machine, GTRecipe originRecipe) {
        if (machine == null || originRecipe == null) {
            return 1;
        }
        final MetaMachine self = machine.self();
        if (self == null || !BatchProcessing.isEnabled(self)) {
            // isEnabled = 该机器 implements IBatchMachine && supportsBatchProcessing() && isBatchEnabled()
            // 主机侧三道覆写都返回 false ⇒ 主机恒走这一支（用户裁决「主机的批处理是要删去的」）。
            return 1;
        }
        final int originDuration = originRecipe.duration;
        if (originDuration <= 0) {
            return 1;
        }
        final int window = Math.max(1, ConfigHolder.INSTANCE.batchProcessingTimeLimitTicks);
        return Math.max(1, window / originDuration);
    }

    /**
     * 🔴 <b>批处理（batch）= 连跑 N 次 —— 施加在"已经并好、已经扣过料"的引擎成品上。</b>
     *
     * <h2>两条语义的正面对照（用户 2026-09-27 规格原话）</h2>
     * <pre>
     *   用户原话：「批处理应该是不影响机器的耗电的，而且批处理应该是延长机器运行时间的」
     *   并行（parallel）＝ 同时跑 N 份 ⇒ 输入 ×N、输出 ×N、<b>耗时不变</b>、<b>EU/t ×N</b>
     *   批处理（batch）   ＝ 连跑 N 次   ⇒ 输入 ×N、输出 ×N、<b>耗时 ×N</b>、<b>EU/t 不变</b>   ← 本方法
     * </pre>
     * ⇒ 本方法只做<b>三件事</b>：补扣 {@code (N-1)×p} 份原料（③）、产出 ×N、耗时 ×N（④）。
     * <b>{@code tickInputs}（= EU/t）与 {@code tickOutputs} 原样写回 ⇒ EU/t 一文不变</b>；
     * 「总耗电量变成 N 倍」是<b>跑得久了</b>（duration ×N）自然的结果，不是把 EU/t 乘上去。
     *
     * <h2>为什么补扣原料必须由我们自己调 {@code handleRecipeInput}</h2>
     * <pre>
     *   [字节码] RecipeCalculationHelper.calculateParallelsWithGreedyAllocation（gtladditions 3.2.8）
     *      130: invokevirtual RecipeCalculationHelper.multipleRecipe:(GTRecipe;J)GTRecipe   ← inputs/outputs 各 ×p
     *      146: invokestatic  RecipeRunnerHelper.handleRecipeInput:(IRecipeLogicMachine;GTRecipe)Z
     *           ⇒ ★材料在【装配期】就真扣了，且只扣这一处
     *   [字节码] RecipeCalculationHelper.buildNormalRecipe：只 put outputs / tickInputs(EU) / duration
     *           ⇒ 引擎成品的 {@code inputs} 恒为【空】，并被打上 setBatchProcessed(true)
     *   [字节码] MutableRecipesLogic.onMultipleRecipeFinish：
     *       26: invokestatic RecipeRunnerHelper.handleRecipeOutput   ← 收尾【只发产出，从不碰 inputs】
     *   ⇒ 引擎路径【没有任何一处】会在运行期消费 {@code recipe.inputs}
     *     ⇒ 输入 ×N 只能由本方法在装配期补扣（把 inputs 塞回成品配方是没用的：没人会扣它）。
     * </pre>
     *
     * <h2>③ 的原子性（先模拟、后真扣）</h2>
     * <pre>
     *   [字节码] RecipeRunnerHelper.matchRecipeInput  → handleRecipe(…, isTick=false, isSimulate=<b>true</b>)  ← 模拟
     *   [字节码] RecipeRunnerHelper.handleRecipeInput → handleRecipe(…, isTick=true,  isSimulate=<b>false</b>) ← 真扣
     * </pre>
     * 份数已由 ① 用引擎自己用的同一个 API（{@code IParallelLogic.getMaxParallel}）钳过，③ 再模拟一次
     * ⇒ 预检不过就<b>整单放弃</b>（不扣料、不改配方、原样返回入参实例）。
     *
     * <h2>④ 逐句照抄 gtlcore {@code BatchProcessing.scaleRecipe}（唯一权威来源）</h2>
     * <pre>
     *   [字节码] BatchProcessing.scaleRecipe(GTRecipe, int)：
     *        3: IAdvancedContentModifier.preciseMultiplier((long)N)
     *        7: GTRecipe.copy(ContentModifier, false)                ← inputs/outputs/tickInputs/tickOutputs 四张表全 ×N
     *       17: tickInputs.clear()  + 21: putAll(源.tickInputs)      ← ★EU/t 还原成原值（不变）
     *       39: tickOutputs.clear() + 43: putAll(源.tickOutputs)     ← 同上
     *       61: putfield duration = 源.duration * N                  ← ★耗时 ×N
     * </pre>
     * ⚠️ <b>刻意不调 gtlcore 的 {@code BatchProcessing.apply}</b>：它的第一闸是
     * {@code isBatchProcessed()}，而引擎成品恒为 {@code true}（"引擎已按批装好"的信号）；
     * 把标记洗成 false 再喂它 = 对一张 inputs 为空的配方乘 outputs ⇒ <b>白送</b>
     * （正是 2026-09-27 用户实机报的「1 个铁锭出 100 个铁板」）。
     * <h2>⑤ {@code realParallels} ← 源值 × N（独立掷骰 · 照抄 gtlcore）</h2>
     * <pre>
     *   [源码] org.gtlcore.gtlcore.api.recipe.BatchProcessing#scaleRecipe(GTRecipe recipe, int batchSize) 末行：
     *          IGTRecipe.of(batched).setRealParallels(IGTRecipe.of(recipe).getRealParallels() * (long) batchSize);
     * </pre>
     * 概率副产必须<b>掷 N 次独立的骰子</b>，不能"只掷 1 次、把那一注结果放大 N 倍"——
     * 后者的方差 ≈ N 倍（例：p=1、副产率 30%、N=10 ⇒ 要么 0 份、要么 10 份；
     * 正确形态是期望 3 份、取值 0~10 的二项分布）。
     * 用 {@link #saturatingMultiply} 而不是裸 {@code *}：本工程红线是并行/份数算术一律饱和、不许回绕成负数。
     * <p>⚠️ <b>副作用（已核对字节码，不是猜测）</b>：gtlcore {@code GTRecipeMixin#setRealParallels} 的实现体是
     * <pre>
     *   this.realParallels = realParallel;
     *   this.parallels     = Ints.saturatedCast(realParallel);   // ← 同一个 setter 顺带写 parallels
     * </pre>
     * ⇒ 上面那行 {@code scaled.parallels = finalRecipe.parallels;}（本方法 ④ 里）<b>会被这一句覆盖</b>成
     * {@code saturatedCast(p × N)}。<b>这与 gtlcore 逐值一致</b>：gtlcore 的 {@code batched} 的 {@code parallels}
     * 初值只来自 {@code GTRecipe.copy()}（= 1），{@code RecipeExtensionCopier} 只按 getter/setter 配对搬运、
     * 而 {@code parallels} 是裸 public 字段（无 {@code getParallels}/{@code setParallels}）⇒ 搬不动，
     * 所以 gtlcore 的 {@code batched.parallels} <b>也只由这一句 setRealParallels 写定</b>。
     * 它同时是"掷骰次数真的变成 p×N"的机制（{@code com.gregtechceu.gtceu.api.recipe.RecipeRunner#fillContent}
     * 的两处都是按 {@code this.recipe.parallels} 定掷骰次数与每注摊薄量）。
     * <p>📝 <b>2026-09-27 订正</b>：本段原写作「⚠️ 刻意不写 {@code realParallels}」（称"保持批处理只动三件事"），
     * 与紧邻下方的代码（确实写了 {@code setRealParallels} 与 {@code setSubTickParallelized}）<b>直接矛盾</b>；
     * 现按代码订正为上面的表述。
     *
     * @param parallel 本轮并行份数 {@code p}（= 引擎贪心分配出来的份数，材料已按它扣过）
     * @return 连跑 N 次之后的成品；<b>N ≤ 1 ／ 料不够 ／ 装不下 ／ 机器没开批处理 ⇒ 原样返回 {@code finalRecipe}</b>
     */
    public static GTRecipe applyBatchProcessing(IRecipeLogicMachine machine, GTRecipe originRecipe,
                                                GTRecipe finalRecipe, long parallel) {
        if (machine == null || originRecipe == null || finalRecipe == null || parallel <= 0L) {
            return finalRecipe;
        }
        final MetaMachine self = machine.self();
        if (self == null || !BatchProcessing.isEnabled(self)) {
            // isEnabled = 该机器 implements IBatchMachine && supportsBatchProcessing() && isBatchEnabled()
            // 主机侧三道覆写都返回 false ⇒ 主机恒走这一支（用户裁决「主机的批处理是要删去的」）。
            return finalRecipe;
        }
        final int wanted = batchTimeWindowCycles(machine, originRecipe);
        if (wanted <= 1) {
            // 时长 > 时间窗一半的配方，上游也不会触发批处理 ⇒ 零分配、零改动。
            return finalRecipe;
        }

        // ── ① 输入量上限（份数）：装配期已经按 p 扣过一份 ⇒「本轮总共能跑几份」= 剩余份数 + p ──
        final long remaining;
        try {
            remaining = IParallelLogic.getMaxParallel(machine, originRecipe, Long.MAX_VALUE);
        } catch (Throwable t) {
            LOGGER.warn("[SHANHAI-BATCH] 输入量上限读取失败，放弃本次批处理（退化为未开批处理）：{}", t.toString());
            return finalRecipe;
        }
        long cycles = remaining <= 0L ? 0L : saturatingAdd(remaining, parallel) / parallel;

        // ── ② 输出空间上限（份数）：产出尚未发放，整块输出空间都可用 ──
        try {
            final long hintWanted = saturatingMultiply(parallel, wanted);
            final int hint = hintWanted > (long) Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) hintWanted;
            final int outputLimited = ParallelLogic.limitByOutputMerging(
                    originRecipe, (IRecipeCapabilityHolder) machine, hint, machine::canVoidRecipeOutputs);
            if (outputLimited >= 0) {
                cycles = Math.min(cycles, (long) outputLimited / parallel);
            }
        } catch (Throwable t) {
            LOGGER.warn("[SHANHAI-BATCH] 输出空间钳制失败，放弃本次批处理（退化为未开批处理）：{}", t.toString());
            return finalRecipe;
        }

        final long appliedLong = Math.min((long) wanted, cycles);
        if (appliedLong <= 1L) {
            shanhai$logBatchSkippedOnce(machine, originRecipe, parallel, wanted);
            return finalRecipe;
        }
        final int applied = (int) appliedLong;

        // ── ③ 真扣 (N-1)×p 份额外原料（先模拟、后真扣）──
        final long extraMultiple = saturatingMultiply(parallel, (long) applied - 1L);
        if (extraMultiple <= 0L || extraMultiple == Long.MAX_VALUE
                || extraMultiple / parallel != (long) applied - 1L) {
            LOGGER.warn("[SHANHAI-BATCH] 额外投料量溢出（p={} ×(N-1)={}），放弃本次批处理（退化为未开批处理）。",
                    parallel, applied - 1);
            return finalRecipe;
        }
        final GTRecipe extraRecipe;
        try {
            extraRecipe = RecipeCalculationHelper.INSTANCE.multipleRecipe(originRecipe, extraMultiple);
        } catch (Throwable t) {
            LOGGER.warn("[SHANHAI-BATCH] 构造额外投料配方失败，放弃本次批处理：{}", t.toString());
            return finalRecipe;
        }
        if (!RecipeRunnerHelper.matchRecipeInput(machine, extraRecipe)) {
            LOGGER.warn("[SHANHAI-BATCH] 额外投料【预检不过】（p={} ×(N-1)={}）⇒ 放弃本次批处理："
                            + "不扣任何料、配方原样交回（退化为未开批处理）。",
                    parallel, applied - 1);
            return finalRecipe;
        }
        if (!RecipeRunnerHelper.handleRecipeInput(machine, extraRecipe)) {
            LOGGER.error("[SHANHAI-BATCH] 🔴 额外投料【真扣失败】（预检已通过 ⇒ 理论上到不了这里；p={} ×(N-1)={}）"
                            + "⇒ 配方原样交回，本行原文请交回验收。",
                    parallel, applied - 1);
            return finalRecipe;
        }

        // ── ④ 连跑 N 次：产出 ×N、耗时 ×N、EU/t 一文不变 ──
        final GTRecipe scaled = finalRecipe.copy(
                IAdvancedContentModifier.preciseMultiplier((long) applied), false);
        scaled.parallels = finalRecipe.parallels;
        scaled.ocTier = finalRecipe.ocTier;
        scaled.tickInputs.clear();
        scaled.tickInputs.putAll(finalRecipe.tickInputs);
        scaled.tickOutputs.clear();
        scaled.tickOutputs.putAll(finalRecipe.tickOutputs);
        scaled.duration = saturatingDuration(finalRecipe.duration, applied);
        final long euBefore = RecipeHelper.getInputEUt(finalRecipe);
        final long euAfter = RecipeHelper.getInputEUt(scaled);
        if (scaled instanceof IGTRecipe dst) {
            if (finalRecipe instanceof IGTRecipe src) {
                // 对齐 gtlcore BatchProcessing.scaleRecipe 的末行：realParallels ← 源值 × N（独立掷骰）。
                dst.setRealParallels(saturatingMultiply(src.getRealParallels(), (long) applied));
                if (src.isSubTickParallelized()) {
                    dst.setSubTickParallelized(true);
                }
            }
            dst.setBatchSize(applied);
            // 我们已经【自己】按批装配完了 ⇒ 打上标记，杜绝 gtlcore 的 applyInPlace 再乘一次。
            dst.setBatchProcessed(true);
        }
        shanhai$logBatchAppliedOnce(originRecipe, finalRecipe, scaled, parallel, wanted, applied, extraMultiple,
                euBefore, euAfter);
        return scaled;
    }

    // ═════════ 批处理在【多配方聚合】（跨配方并行 ≥ 2 条）上的正确形态（2026-09-30） ═════════

    /**
     * 🔴 <b>多配方聚合档的批处理 = 对【每一条】origin 各补各的 {@code (N-1)×p_i}，且全部预检通过才真扣。</b>
     *
     * <h2>它替换掉的是什么（上一轮 §3.4 的"安全选择"）</h2>
     * 上一轮发现：{@link #applyBatchProcessing} 的额外投料只按<b>第一条</b> origin 与第一条的份数补
     * （调用点传的是 {@code shanhai$originRecipeOf} / {@code shanhai$parallelOf}），
     * 却把<b>聚合后的全部产出</b>乘 N ⇒ 少扣料 = <b>白送材料</b>（本工程红线）。
     * 当时的处置是"多配方下干脆【不施加】批处理"（打一行 WARN）。
     * <b>本方法就是那个"下一轮的正确形态"</b>：决策与原子性全在
     * {@link ShanhaiBatchPlan}（纯算术核，可用 {@code javac} 离线驱动），
     * 本方法只负责"采数"与"把结果装配成配方"。
     *
     * <h2>N 取哪个 —— 一句话</h2>
     * {@code N = min( 时间窗 ÷ max(各条时长) , 逐条输入量上界 , 聚合体输出空间 )}；
     * 时长取 <b>max</b> 的依据（同口径 / 保住上游时间窗不变式 / 保住上游资格闸门 / 每条各算 N_i 不可行）
     * 逐条写在 {@link ShanhaiBatchPlan} 的类注释里，不在这里重复。
     *
     * <h2>🔴 为什么本方法把 {@link #applyBatchProcessing} 的 ④ 段【照抄】而不是抽公共函数</h2>
     * 抽公共函数会让"单条候选"那条路径的执行体<b>跟着动</b>，而本工程对它的硬要求是
     * <b>「候选 ≤ 1 条时逐值不变」</b>。照抄之后，单条路径的字节码<b>一个字节都不变</b>
     * （判据：{@code javap -c -p} 新旧 jar 的 {@code applyBatchProcessing} 逐行相同），
     * 于是那条要求从"我论证过"变成"机器比出来的"。代价是两处 20 行的重复 —— 已知并接受。
     *
     * @param machine    机器（批处理开关由它的 {@code IBatchMachine} 三道覆写决定）
     * @param origins    {@code ParallelData#getOriginRecipeList()}（与 {@code parallels} 一一对应）
     * @param parallels  {@code ParallelData#getParallels()}（材料已按它扣过一轮）
     * @param finalRecipe 引擎装配 + N3/N5/N6 + 原生权威之后的成品（本方法只做"再连跑 N-1 次"）
     * @return 施加成功 = 连跑 N 次之后的成品；<b>被拒 / 装不下 / 开关没开 ⇒ 原样返回 {@code finalRecipe}</b>
     */
    public static GTRecipe applyBatchProcessingMulti(IRecipeLogicMachine machine, List<GTRecipe> origins,
                                                     long[] parallels, GTRecipe finalRecipe) {
        if (machine == null || finalRecipe == null || origins == null || parallels == null) {
            return finalRecipe;
        }
        final int originCount = origins.size();
        if (originCount <= 0) {
            return finalRecipe;
        }
        if (originCount == 1) {
            // 防御性兜底：单条时【原路交回单条版】⇒「单条候选时逐值不变」在任何入口都成立。
            return applyBatchProcessing(machine, origins.get(0), finalRecipe, Math.max(0L, parallels[0]));
        }
        if (parallels.length < originCount) {
            // 份数数组短于候选条数 ⇒ 对应关系不可信 ⇒ 算不出该补多少（血规矩：宁可跳过，不许猜）。
            LOGGER.warn("[SHANHAI-BATCH] 多配方档：候选 {} 条，而份数数组只有 {} 项（对应关系不可信）"
                            + "⇒ 算不出该补多少 ⇒ 【本轮不施加批处理】（不扣任何料、配方原样交回）。",
                    originCount, parallels.length);
            return finalRecipe;
        }
        final MetaMachine self = machine.self();
        if (self == null || !BatchProcessing.isEnabled(self)) {
            // isEnabled = 该机器 implements IBatchMachine && supportsBatchProcessing() && isBatchEnabled()
            return finalRecipe;
        }
        final long[] durations = new long[originCount];
        for (int i = 0; i < originCount; i++) {
            final GTRecipe origin = origins.get(i);
            if (origin == null) {
                LOGGER.warn("[SHANHAI-BATCH] 多配方档：第 {} 条 origin 为 null ⇒ 算不出该补多少"
                                + " ⇒ 【本轮不施加批处理】（不扣任何料、配方原样交回）。", i);
                return finalRecipe;
            }
            durations[i] = origin.duration;
        }
        final long[] ps = parallels.length == originCount
                ? parallels : java.util.Arrays.copyOf(parallels, originCount);
        final long window = Math.max(1, ConfigHolder.INSTANCE.batchProcessingTimeLimitTicks);
        final ShanhaiBatchPlan.Outcome outcome = ShanhaiBatchPlan.run(window, durations, ps,
                i -> shanhai$batchInputBoundOf(machine, origins.get(i)),
                wanted -> shanhai$batchOutputBoundOf(machine, finalRecipe, wanted),
                new ShanhaiBatchPlan.Steps() {

                    @Override
                    public Object build(int index, long extra) {
                        return shanhai$batchBuildExtraInput(origins.get(index), extra);
                    }

                    @Override
                    public boolean precheck(int index, Object payload) {
                        return RecipeRunnerHelper.matchRecipeInput(machine, (GTRecipe) payload);
                    }

                    @Override
                    public boolean deduct(int index, Object payload) {
                        return RecipeRunnerHelper.handleRecipeInput(machine, (GTRecipe) payload);
                    }
                });
        if (outcome.kind != ShanhaiBatchPlan.Kind.APPLIED) {
            shanhai$logBatchMultiNotAppliedOnce(machine, originCount, ps, outcome);
            return finalRecipe;
        }

        // ── ④ 连跑 N 次：产出 ×N、耗时 ×N、EU/t 一文不变 ──
        //    ⚠️ 本段与 applyBatchProcessing 的 ④【逐字相同】，刻意不共用（见方法注释）。
        final int applied = (int) outcome.cycles;
        final GTRecipe scaled = finalRecipe.copy(
                IAdvancedContentModifier.preciseMultiplier((long) applied), false);
        scaled.parallels = finalRecipe.parallels;
        scaled.ocTier = finalRecipe.ocTier;
        scaled.tickInputs.clear();
        scaled.tickInputs.putAll(finalRecipe.tickInputs);
        scaled.tickOutputs.clear();
        scaled.tickOutputs.putAll(finalRecipe.tickOutputs);
        scaled.duration = saturatingDuration(finalRecipe.duration, applied);
        final long euBefore = RecipeHelper.getInputEUt(finalRecipe);
        final long euAfter = RecipeHelper.getInputEUt(scaled);
        if (scaled instanceof IGTRecipe dst) {
            if (finalRecipe instanceof IGTRecipe src) {
                dst.setRealParallels(saturatingMultiply(src.getRealParallels(), (long) applied));
                if (src.isSubTickParallelized()) {
                    dst.setSubTickParallelized(true);
                }
            }
            dst.setBatchSize(applied);
            dst.setBatchProcessed(true);
        }
        shanhai$logBatchMultiAppliedOnce(originCount, ps, outcome, finalRecipe, scaled, euBefore, euAfter);
        return scaled;
    }

    /** 第 {@code i} 条 origin【单独看】当前还能跑几份；{@code < 0} = 问不出来（⇒ 降级，不猜）。 */
    private static long shanhai$batchInputBoundOf(IRecipeLogicMachine machine, GTRecipe origin) {
        try {
            return IParallelLogic.getMaxParallel(machine, origin, Long.MAX_VALUE);
        } catch (Throwable t) {
            LOGGER.warn("[SHANHAI-BATCH] 多配方档：输入量上限读取失败（origin={}）⇒ 问不出就【不猜】：{}",
                    origin, t.toString());
            return -1L;
        }
    }

    /**
     * 输出空间能装下几份【聚合体】（{@code wanted} = 我们最多想要几份）。
     *
     * <p>为什么拿 {@code finalRecipe}（聚合体）而不是某一条 origin 去问：
     * 被 ×N 的就是这一整块聚合产出（它已经把全部 origin 的产出都含在里面），
     * 所以"这块还能装下几份"就是 N，而且它天然把"两条 origin 争同一个输出口"算进去了。
     * <p>{@code [字节码] ParallelLogic.getMaxRecipeMultiplier} 的用法证实返回值是<b>份数（乘数）</b>：
     * {@code limitByOutputMerging(recipe, holder, limit, canVoid)} 之后是
     * {@code recipe.copy(ContentModifier.multiplier(那个返回值))} ⇒ 与 {@code recipe.parallels} 无关。
     *
     * @return 份数；{@code < 0} = 问不出来（⇒ 降级，不猜）
     */
    private static long shanhai$batchOutputBoundOf(IRecipeLogicMachine machine, GTRecipe aggregate, int wanted) {
        try {
            return ParallelLogic.limitByOutputMerging(aggregate, (IRecipeCapabilityHolder) machine, wanted,
                    machine::canVoidRecipeOutputs);
        } catch (Throwable t) {
            LOGGER.warn("[SHANHAI-BATCH] 多配方档：输出空间钳制失败（聚合体={}）⇒ 问不出就【不猜】：{}",
                    aggregate, t.toString());
            return -1L;
        }
    }

    /** 造第 {@code i} 条的额外投料配方；失败返回 {@code null}（⇒ 核心会整单放弃）。 */
    private static Object shanhai$batchBuildExtraInput(GTRecipe origin, long extra) {
        try {
            return RecipeCalculationHelper.INSTANCE.multipleRecipe(origin, extra);
        } catch (Throwable t) {
            LOGGER.warn("[SHANHAI-BATCH] 多配方档：构造额外投料配方失败（origin={}，extra={}）：{}",
                    origin, extra, t.toString());
            return null;
        }
    }

    /** 「多配方批处理已施加」的证据行一次性闸门（每个不同的 (N, p 数组) 组合只打一行，不刷屏）。 */
    private static final java.util.Set<String> shanhai$batchMultiAppliedLogged =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 「多配方批处理未施加」的证据行一次性闸门。 */
    private static final java.util.Set<String> shanhai$batchMultiNotAppliedLogged =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * <b>「多配方档批处理真的施加了」的可 grep 证据行（一次性）。</b>
     *
     * <p>一行里同时给出用户抬头能对照的四件事：<b>N / 逐条 p 与额外料 / 产出倍率 / duration 前后 / EU/t 前后</b>，
     * 以及 N 的三条上界各是多少 —— 这样"N 为什么是 100 而不是 50"在日志里是<b>读出来的</b>，不是猜的。
     */
    private static void shanhai$logBatchMultiAppliedOnce(int originCount, long[] parallels,
                                                         ShanhaiBatchPlan.Outcome outcome,
                                                         GTRecipe before, GTRecipe after,
                                                         long euBefore, long euAfter) {
        final String key = outcome.cycles + "/" + java.util.Arrays.toString(parallels);
        if (shanhai$batchMultiAppliedLogged.size() > 256 || !shanhai$batchMultiAppliedLogged.add(key)) {
            return;
        }
        LOGGER.info("[SHANHAI-BATCH] 🔴 多配方档批处理【已施加】：候选 {} 条同时在跑。"
                        + "聚合时长基数 d0* = {} tick（= 各条时长取【最大】，理由见 ShanhaiBatchPlan 类注释）。"
                        + "N = min(时间窗 {}÷d0* = {}, 输入量 {}, 输出空间 {}) = {}。"
                        + "⇒ 逐条补扣 (N-1)×p_i：p = {} ⇒ 额外料 = {}（{}{} 条）"
                        + "【每一条各补各的，且全部预检通过之后才真扣】。"
                        + "产出：{} 张 → {} 张（×N）；duration：{} → {}（×N）；"
                        + "★EUt：{} → {}（必须相等 ⇒ 「批处理不影响机器的耗电」）。",
                originCount, outcome.d0Max,
                Math.max(1, ConfigHolder.INSTANCE.batchProcessingTimeLimitTicks), outcome.nTime,
                outcome.nInput, outcome.nOutput, outcome.cycles,
                java.util.Arrays.toString(parallels), outcome.extrasText(),
                outcome.zeroParallelCount == 0 ? "全部" : ("除 " + outcome.zeroParallelCount + " 条份额≤0 的之外，共 "),
                outcome.extras.length - outcome.zeroParallelCount,
                before.outputs.size(), after.outputs.size(), before.duration, after.duration,
                euBefore, euAfter);
    }

    /**
     * <b>「多配方档批处理本轮没施加」的可 grep 证据行（一次性）。</b>
     *
     * <p>三种归宿各有措辞，且与"漏做"必须能分开：
     * <ul>
     *   <li>{@code SKIPPED} ⇒ 正常跳过（N ≤ 1，多半是配方时长超过时间窗一半）—— <b>不是错</b>；</li>
     *   <li>{@code REJECTED} ⇒ <b>算不出来</b>或<b>某条预检不过</b> ⇒ 降级，<b>不扣任何料</b>；</li>
     *   <li>{@code DEDUCT_FAILED} ⇒ 预检全过却真扣失败（理论上到不了）⇒ 打 ERROR。</li>
     * </ul>
     */
    private static void shanhai$logBatchMultiNotAppliedOnce(IRecipeLogicMachine machine, int originCount,
                                                            long[] parallels,
                                                            ShanhaiBatchPlan.Outcome outcome) {
        final String key = outcome.kind + "/" + outcome.cycles + "/" + java.util.Arrays.toString(parallels);
        if (shanhai$batchMultiNotAppliedLogged.size() > 256 || !shanhai$batchMultiNotAppliedLogged.add(key)) {
            return;
        }
        final String head = "候选 " + originCount + " 条 / p = " + java.util.Arrays.toString(parallels)
                + " / 聚合时长基数 d0* = " + outcome.d0Max + " tick / N 的三条上界 = (时间窗 "
                + outcome.nTime + ", 输入量 " + outcome.nInput + ", 输出空间 " + outcome.nOutput + ")";
        if (outcome.kind == ShanhaiBatchPlan.Kind.DEDUCT_FAILED) {
            LOGGER.error("[SHANHAI-BATCH] 🔴 多配方档批处理【真扣失败】（预检已全部通过 ⇒ 理论上到不了这里）：{}"
                            + " ⇒ 配方原样交回（退化为未开批处理），本行原文请交回验收。",
                    head + "；" + outcome.reason);
            return;
        }
        LOGGER.warn("[SHANHAI-BATCH] 多配方档批处理【本轮不施加】：{} ⇒ {}。"
                        + "⚠️ 这是【降级】不是漏做：一粒额外料都没有扣，配方原样交回（从「省事」退回到「少功能」）。"
                        + "机器={}",
                outcome.kind == ShanhaiBatchPlan.Kind.SKIPPED ? "正常跳过" : "算不出 / 预检不过",
                head + "；原因：" + outcome.reason,
                machine == null ? null : machine.self());
    }

    /** 「批处理已施加」的证据行的一次性闸门（每个不同的 (N, p) 组合只打一行，不刷屏）。 */
    private static final java.util.Set<String> shanhai$batchAppliedLogged =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 「批处理被跳过」的证据行的一次性闸门。 */
    private static final java.util.Set<String> shanhai$batchSkippedLogged =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * <b>「批处理真的按『连跑 N 次』施加了」的可 grep 证据行（一次性 · 正负对照自证）</b>。
     *
     * <p>一行里同时给出四个数：<b>产出倍率 / duration 前后 / EU/t 前后 / 额外扣料量</b> ——
     * 判据 ②（耗时 ×N）与判据 ③（EU/t 不变）就是靠这一行在<b>运行期</b>读出来的，
     * 不必等实机；{@code EUt 前 == EUt 后} 是"不影响耗电"的<b>现场读数</b>，不是承诺。
     */
    private static void shanhai$logBatchAppliedOnce(GTRecipe originRecipe, GTRecipe before, GTRecipe after,
                                                    long parallel, int wanted, int applied, long extra,
                                                    long euBefore, long euAfter) {
        final String key = applied + "/" + parallel;
        if (shanhai$batchAppliedLogged.size() > 256 || !shanhai$batchAppliedLogged.add(key)) {
            return;
        }
        LOGGER.info("[SHANHAI-BATCH] 批处理已施加（「连跑 N 次」语义 · 一次性）："
                        + "时间窗={} tick ／ 原配方时长={} tick ⇒ 时间窗份数={}；并行 p={}；实际连跑 N={}"
                        + "（已被输入量/输出空间钳过）⇒ 额外补扣原料 = (N-1)×p = {} 份。"
                        + "产出：{} 张 → {} 张（×N）；duration：{} → {}（×N）；"
                        + "★EUt：{} → {}（必须相等 ⇒ 这就是「批处理不影响机器的耗电」）。",
                Math.max(1, ConfigHolder.INSTANCE.batchProcessingTimeLimitTicks), originRecipe.duration,
                wanted, parallel, applied, extra,
                before.outputs.size(), after.outputs.size(), before.duration, after.duration,
                euBefore, euAfter);
    }

    /**
     * <b>「批处理被跳过」的可 grep 证据行</b> —— 与"漏做"必须能分开：
     * 本行说明<b>不是没实现</b>，而是这一轮的输入/输出空间装不下第二份。
     */
    private static void shanhai$logBatchSkippedOnce(IRecipeLogicMachine machine, GTRecipe originRecipe,
                                                    long parallel, int wanted) {
        final String key = wanted + "/" + parallel;
        if (shanhai$batchSkippedLogged.size() > 256 || !shanhai$batchSkippedLogged.add(key)) {
            return;
        }
        LOGGER.info("[SHANHAI-BATCH] 批处理【本轮跳过】：时间窗份数={}，而 p={} 这一份之后"
                        + "输入量或输出空间已装不下第二份 ⇒ 退化为未开批处理（不扣额外料、不改耗时）。"
                        + "这不是「没实现」，是「料不够」。（时间窗={} tick ／ 原配方时长={} tick ／ 机器={}）",
                wanted, parallel, Math.max(1, ConfigHolder.INSTANCE.batchProcessingTimeLimitTicks),
                originRecipe.duration, machine == null ? null : machine.self());
    }

    /** 饱和加（本工程红线：并行/份数算术一律饱和，不许回绕成负数）。 */
    private static long saturatingAdd(long a, long b) {
        if (a < 0L || b < 0L) {
            return Long.MAX_VALUE;
        }
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }

    /** 饱和乘（同上）。 */
    private static long saturatingMultiply(long a, long b) {
        if (a <= 0L || b <= 0L) {
            return 0L;
        }
        return a > Long.MAX_VALUE / b ? Long.MAX_VALUE : a * b;
    }

    /** 时长 ×N 的饱和形态（不许回绕成负数）。 */
    private static int saturatingDuration(int duration, int cycles) {
        final long product = saturatingMultiply(duration, cycles);
        return product > (long) Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) product;
    }

    /**
     * 🔴 <b>long 通道的贪心并行分配（引擎并行预算 &gt; 2^31−1 时使用）。</b>
     *
     * <h2>它是什么：gtladditions {@code calculateParallelsWithGreedyAllocation} 的 long 版</h2>
     * <p>引擎父类 {@code MutableRecipesLogic.calculateParallels()} 的预算是
     * {@code (long) getMaxParallel() * getMultipleThreads()} —— 第一个因子是
     * {@code gtlcore ParallelMachine} 的 <b>int</b> 方法 ⇒ <b>并行表末三档在这里被压平</b>。
     * 本方法把预算换成 long（老山海的 {@code getTotalParallelLimit()} 形状），
     * 其余<b>逐句照抄上游</b>（{@code [源码原文] RecipeCalculationHelper.kt:361-404}）：
     * <pre>
     *   long remain = totalParallel;
     *   for (match : recipes) {
     *       if (remain &lt;= 0L) break;
     *       LongLongPair pair = getter.get(match, remain);      ← 上游是 calculateParallel(machine, match, remain)
     *       long p = pair.firstLong();
     *       if (p &gt; 0L) {
     *           GTRecipe paralleledRecipe = getRecipeOutputChance(machine, multipleRecipe(match, p));
     *           if (handleRecipeInput(machine, paralleledRecipe)) {
     *               remain -= pair.secondLong();
     *               …三个列表各加一项…
     *           }
     *       }
     *   }
     *   if (recipeList.isEmpty()) return null;
     *   return new ParallelData(recipeList, parallels, false, processedRecipeList);
     * </pre>
     *
     * <h2>🔴 调用方必须自己保证"只在预算 &gt; 2^31−1 时调用它"</h2>
     * 预算 ≤ int 时调用方走的是<b>父类原方法</b>（上游那条字节码），本方法只服务被压平的那几档
     * ⇒ 这是本工程「不造第二份实现」纪律下的最小代价：<b>第二份实现只在原方法够不着的地方存在</b>。
     *
     * <h2>溢出（作者红线落在的这一处）</h2>
     * <ul>
     *   <li>{@code remain -= pair.secondLong()}：{@code pair} 的两个分量都由
     *       {@code IParallelLogic.getMaxParallel(…, remain)} 产出 ⇒ {@code secondLong() <= remain}
     *       （{@code calculateParallel} 的默认实现整段就是 {@code LongLongPair.of(p, p)}，
     *       而 {@code p = min(remain, 输入量上限)}）⇒ {@code remain} 单调不增、<b>不可能回绕</b>。</li>
     *   <li>{@code multipleRecipe(match, p)} 内部是 {@code ContentModifier.multiplier(p)}：
     *       数值走 {@code double}，由 {@code EURecipeCapability.copyWithModifier} 的
     *       {@code longValue()} 窄化<b>饱和</b>到 {@code Long.MAX_VALUE}（不抛异常、不回绕）
     *       —— 同一现象本类 {@code shanhai$probeGeneratorEnergyWall} 的注释里已逐条记录过。</li>
     * </ul>
     *
     * @param totalParallel 并行预算（调用方已经用饱和乘法算好；本方法<b>不再</b>做钳制）
     * @param getter        与上游同形的"取该配方并行 + 消耗"回呼（通常是
     *                      {@code (r, rem) -> calculateParallel(machine, r, rem)}）
     * @return 与上游同形：没有可跑配方时 {@code null}
     */
    public static ParallelData greedyAllocateWithLongLimit(Collection<GTRecipe> recipes, long totalParallel,
                                                           IRecipeLogicMachine machine, ParallelGetter getter) {
        if (recipes == null || recipes.isEmpty() || machine == null || getter == null) {
            return null;
        }
        long remain = totalParallel;
        final ObjectArrayList<GTRecipe> recipeList = new ObjectArrayList<>();
        final ObjectArrayList<GTRecipe> processedRecipeList = new ObjectArrayList<>();
        final LongArrayList parallelsList = new LongArrayList();
        for (GTRecipe match : recipes) {
            if (remain <= 0L) {
                break;
            }
            final LongLongPair pair = getter.parallelOf(match, remain);
            if (pair == null) {
                continue;
            }
            final long p = pair.firstLong();
            if (p <= 0L) {
                continue;
            }
            final GTRecipe paralleledRecipe = IParallelLogic.getRecipeOutputChance(
                    machine, RecipeCalculationHelper.INSTANCE.multipleRecipe(match, p));
            if (RecipeRunnerHelper.handleRecipeInput(machine, paralleledRecipe)) {
                remain -= pair.secondLong();
                recipeList.add(match);
                processedRecipeList.add(paralleledRecipe);
                parallelsList.add(p);
            }
        }
        if (recipeList.isEmpty()) {
            return null;
        }
        return new ParallelData(recipeList, parallelsList.toLongArray(), false, processedRecipeList);
    }

    /**
     * 🔴 <b>跨配方并行的「公平分配」——上游 {@code calculateParallelsWithFairAllocation}
     * （+ {@code getFinalParallelData} / {@code getParallelDataIndexArray}）的 long 版，逐句照搬。</b>
     *
     * <h2>它治的病（用户 2026-09-29 实机原文）</h2>
     * <blockquote>
     *   「我放入了两种配方的原材料，并且这两种的配方都可以正常执行，但是我开启跨配方种类并行时，
     *    它们并没有同时进行，而是一种挤占了另一种，而 gtladd 的机器就可以同时执行」
     * </blockquote>
     * 父类 {@code MutableRecipesLogic.calculateParallels()} 走的是<b>贪心</b>
     * （{@code calculateParallelsWithGreedyAllocation}），它的循环体第一条判据是
     * <pre>
     *   [字节码原文] RecipeCalculationHelper.calculateParallelsWithGreedyAllocation
     *      82: lload 6 / 84: lconst_0 / 86: ifle 191      ← remain &lt;= 0 就 break（191 = 整个循环出口）
     * </pre>
     * 而每条的份数是 {@code p = IParallelLogic.getMaxParallel(holder, recipe, remain)}，
     * 它的实现是「以传入的上限为起点，用实际输入量逐项做 {@code Math.min}」
     * <pre>
     *   [字节码原文] IParallelLogic.getMaxParallel(holder, recipe, limit)
     *      46: lload_2 / 49: getInputItemParallel(holder, recipe, lload_2) / 53: Math.min(JJ)J / 56: lstore_2
     * </pre>
     * ⇒ 只要<b>第一条候选</b>的原料管够（创造输入总线里 2.14G 那种），它就把
     * {@code remain} 一次吃光 ⇒ {@code remain == 0} ⇒ 循环在第二条<b>之前</b>结束
     * ⇒ <b>整台机器只跑一条配方</b>。这就是玩家说的「一种挤占了另一种」。
     *
     * <h2>上游自己的解法（本方法照搬的那一份）</h2>
     * gtladd 自己的多配方机器（{@code GTLAddMultipleRecipesLogic} 及其全部子类，
     * 含「伪神之锻炉」那一支）用的<b>不是</b>贪心，而是公平分配，分两相：
     * <pre>
     *   相 1（每条候选先拿一份保底）
     *     share_i = min(需求_i, 总预算 / 候选条数)         ← 上游原文 ldiv + Math.min
     *     预算 -= share_i;  余下的需求进 remainingWants
     *   相 2（水填充：余量按轮转补给还在要的人）
     *     while (预算 &gt; 0 &amp;&amp; 还有人在要) {
     *         每人份 = 预算 / 还在要的人数
     *         for (还在要的人) { 补 min(还要多少, 每人份); 记消耗 }
     *         预算 -= 本轮的消耗
     *     }
     * </pre>
     * ⇒ <b>没有任何一条候选会被别人饿死</b>；而 {@code 候选条数 == 1} 时
     * {@code share = min(需求, 总预算)} 与贪心<b>逐值相等</b>
     * ⇒ 这就是「线程槽空 ⇒ 行为与今天完全一致」那条硬要求能被满足的原因。
     *
     * <h2>🔴 与上游 fair 版的<b>唯一</b>刻意差异：扣料仍留在分配相（shouldProcess = false）</h2>
     * 上游 fair 版<b>不扣料</b>，返回的 {@code ParallelData.shouldProcess = true}，
     * 扣料被推迟到 {@code buildFinalNormalRecipe} 里逐条做。
     * 本工程<b>不跟着改这一点</b>，理由是「值不变」优先：
     * <ol>
     *   <li>本工程模块的 {@code ParallelData} 形态今天恒为
     *       {@code (origins, parallels, false, processedRecipeList)}
     *       ⇒ 整条 {@code buildFinalNormalRecipe} 契约<b>一个字都不用动</b>，
     *       不存在「扣两次」或「都不扣」的风险面；</li>
     *   <li>扣料与分配<b>交织</b>时，共享原料的稀缺性会被后续候选看见
     *       （例：两配方共吃同一个编程电路，只有一个可用 ⇒ 第二条当场拿不到份数而退出，
     *       而不是「先都算上、到装配期再失败」把预算浪费掉）。</li>
     * </ol>
     * ⚠️ 代价（<b>必须让验收方知情</b>）：某条候选在这一步扣料失败时，它那份额度
     * <b>不会</b>被重新分给别人（上游 fair 版会，因为它的扣料在更晚处）。
     * 表现 = 「候选 N 条、实际成料 M &lt; N 条」时总并行量小于预算。
     * 调用方会把这两个数打进证据行，让这件事<b>看得见</b>，而不是静默少算。
     *
     * <h2>溢出</h2>
     * 全程只有 {@code Math.min} / 饱和减 / 饱和加：{@code share_i <= 预算}
     * 且 {@code Σ share_i <= 预算}（相 2 每轮扣掉的都是自己加出去的）
     * ⇒ 上界恒为入参 {@code totalParallel}，<b>不可能回绕</b>。
     *
     * @param totalParallel 并行预算（调用方已用饱和乘法算好；本方法不再钳制）
     * @param getter        与上游同形的回呼；相 1 传的是<b>总预算</b>（上游
     *                      {@code GTLAddMultipleRecipesLogic} 的 {@code lload_2}，不是剩余量）
     * @return 与上游同形：没有可跑配方时 {@code null}
     */
    public static ParallelData fairAllocateWithLongLimit(Collection<GTRecipe> recipes, long totalParallel,
                                                         IRecipeLogicMachine machine, ParallelGetter getter) {
        if (recipes == null || recipes.isEmpty() || machine == null || getter == null) {
            return null;
        }
        // ── 相 0：先把每条候选的"需求"问出来（**不扣料**，纯读）──
        //     ⚠️ 上限传的是【总预算】而不是剩余量 —— 与上游 GTLAddMultipleRecipesLogic 的
        //       {@code getMaxParallel(recipe, lload_2)}（lload_2 = 总预算）逐字一致。
        final int count = recipes.size();
        final long[] demands = new long[count];
        final GTRecipe[] ordered = new GTRecipe[count];
        int i = 0;
        for (GTRecipe match : recipes) {
            final LongLongPair pair = getter.parallelOf(match, totalParallel);
            demands[i] = pair == null ? 0L : pair.firstLong();
            ordered[i] = match;
            i++;
        }

        // ── 相 1 + 相 2：公平分配（纯算术，**实现在 {@link ShanhaiFairAllocation}** —— 那边零依赖、
        //    可用 javac 单独编译并用离线判据驱动；本方法只负责"问需求 / 扣料"这两件必须碰机器的事）──
        final long[] shares = ShanhaiFairAllocation.fairShares(demands, totalParallel);

        // ── 扣料相：与 {@link #greedyAllocateWithLongLimit} 逐句同形 ──
        final ObjectArrayList<GTRecipe> recipeList = new ObjectArrayList<>();
        final ObjectArrayList<GTRecipe> processedRecipeList = new ObjectArrayList<>();
        final LongArrayList parallelsList = new LongArrayList();
        for (int k = 0; k < count; k++) {
            final long p = shares[k];
            if (p <= 0L) {
                continue;
            }
            final GTRecipe match = ordered[k];
            final GTRecipe paralleledRecipe = IParallelLogic.getRecipeOutputChance(
                    machine, RecipeCalculationHelper.INSTANCE.multipleRecipe(match, p));
            if (!RecipeRunnerHelper.handleRecipeInput(machine, paralleledRecipe)) {
                continue;
            }
            recipeList.add(match);
            processedRecipeList.add(paralleledRecipe);
            parallelsList.add(p);
        }
        if (recipeList.isEmpty()) {
            return null;
        }
        return new ParallelData(recipeList, parallelsList.toLongArray(), false, processedRecipeList);
    }

    // ⚠️ 公平分配的【纯算术核】（相 1 保底 + 相 2 水填充）刻意【不在本类里】——
    //    它搬到了 {@link ShanhaiFairAllocation#fairShares(long[], long)}：那个类只 import java.*，
    //    可以单独 javac 编译、用离线判据驱动（判据：temp/probes/fair-alloc/FairAllocCheck.java）。
    //    本类只留"问每条候选的需求"与"扣料"这两件必须碰机器的事。

    /**
     * {@link #greedyAllocateWithLongLimit} / {@link #fairAllocateWithLongLimit} 的回呼。
     *
     * <p>形状 = 上游 {@code MutableRecipesLogic.calculateParallels$lambda$3}
     * （{@code (recipe, remaining) -> this.calculateParallel(machine, recipe, remaining)}）
     * 的 Java 写法；之所以自己定义这个接口，是因为上游那个形参类型是
     * {@code kotlin.jvm.functions.Function2}，而本工程的编译类路径里<b>没有</b> kotlin-stdlib
     * （gtladditions 把 kotlinforforge 打包在 {@code META-INF/jars/} 里，
     * 那属于<b>运行期</b> jarjar，javac 看不到）。
     */
    @FunctionalInterface
    public interface ParallelGetter {

        /** @return 与上游一致的 {@code LongLongPair.of(该配方的并行, 本次消耗)} */
        LongLongPair parallelOf(GTRecipe recipe, long remain);
    }

    /**
     * 🔴 <b>发电饱和探针（2026-09-26 用户实测"零点能反应堆输出被限制"专用）—— 只打日志，不改任何数值</b>。
     *
     * <h2>墙在哪（三处字节码已逐条读过，本探针就是把它们变成可核对的日志）</h2>
     * <pre>
     *   ContentModifier.apply(Number)                → 非 BigDecimal/BigInteger 时走
     *                                                  {@code number.doubleValue() * multiplier + addition}
     *                                                  （double 乘法：不抛异常、也还远没到 double 上限）
     *   EURecipeCapability.copyWithModifier(Long, m) → {@code Long.valueOf(modifier.apply(original).longValue())}
     *                                                  ↑ 真正的墙：这个 {@code Double.longValue()} 窄化，
     *                                                    JLS 规定超出 long 范围时【饱和到 Long.MAX_VALUE】，静默无异常
     * </pre>
     * ⇒ 沿途<b>没有任何</b> {@code Math.min(x, Long.MAX_VALUE)} 之类的钳位 —— <b>唯一的墙就是那个窄化</b>。
     * 上游（宇宙之心）走的是 {@code BigDecimal/BigInteger} 分支，从根上绕开它 ⇒ 才到得了 8.79e14 A。
     *
     * <p>只在<b>发电模块</b>（{@code definition.isGenerator()}）上触发；打：调用前/后的 EU 内容、本次并行 p、
     * 以及是否撞顶 ⇒ 修完前后一对比就知道数字有没有变、还卡不卡。
     */
    /**
     * 撞顶探针那一行的闸门。
     *
     * <p>🔴 2026-10-01（用户点单「日志的问题」）：实测日志里这一行打了 <b>1268 次</b>，
     * 取值<b>完全相同</b>（{@code p=9223372036854775 乘前=2147483648 乘后=Long.MAX 撞顶=true}）。
     * 而且「long 档下撞顶」是<b>用户自己拍过板的预期内行为</b>
     * （「破一次红线让 25 台抬到 10」）⇒ 它不该每 tick 报。
     * ⇒ 改成「<b>同一形态只打一次</b>」；只有「<b>没进 long 档却撞了墙</b>」才是异常，当场 WARN。
     */
    private static final ShanhaiLogThrottle.Gate shanhai$GEN_WALL_GATE = new ShanhaiLogThrottle.Gate();

    private static void shanhai$probeGeneratorEnergyWall(MetaMachine machine, GTRecipe before, GTRecipe after,
                                                         long parallel) {
        try {
            if (after == null
                    || !(machine.getDefinition()
                            instanceof com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition def)
                    || !def.isGenerator()) {
                return;
            }
            final long beforeEu = shanhai$firstTickOutputEu(before);
            final long afterEu = shanhai$firstTickOutputEu(after);
            // 🔴 2026-10-01：判定本体在 ShanhaiLogThrottle（纯 JDK；离线可单跑自检）。
            //    正常档（long 档内撞顶 = 预期）⇒ 同一形态只打一次 INFO；
            //    异常档（未进 long 档却撞顶）⇒ 当场 WARN，不受「正常行条数上限」约束。
            final ShanhaiLogThrottle.Verdict v = ShanhaiLogThrottle.decideEnergyWall(
                    shanhai$GEN_WALL_GATE, parallel, beforeEu, afterEu, System.currentTimeMillis());
            if (v.level == ShanhaiLogThrottle.Level.NONE) {
                return;
            }
            if (v.abnormal) {
                LOGGER.warn("[SHANHAI-GEN-LIMIT] 🔴 出乎预期的撞顶：p={} 未进 long 档（≤ {}）"
                                + "却仍读到 tickOutputs.eu={}（乘前 {}）⇒ 请把这一行原文交回。"
                                + "自上次落盘以来同形重复 {} 次已静默。",
                        parallel, ShanhaiLogThrottle.INT_CEILING, afterEu, beforeEu, v.suppressed);
            } else {
                LOGGER.info("[SHANHAI-GEN-LIMIT] p={} 乘前 tickOutputs.eu={} ⇒ 乘后={}；撞 Long.MAX_VALUE 顶={}"
                                + "（墙 = EURecipeCapability.copyWithModifier 的 Double.longValue() 窄化；"
                                + "上游走 BigDecimal/BigInteger 分支绕开它）"
                                + "【本行已改为「同一形态只打一次」：自上次落盘以来同形重复 {} 次已静默】",
                        parallel, beforeEu, afterEu, afterEu == Long.MAX_VALUE, v.suppressed);
            }
        } catch (Throwable t) {
            LOGGER.warn("[SHANHAI-GEN-LIMIT] 探针异常（已忽略，配方不受影响）：{}", t.toString());
        }
    }

    /** 取配方第一条 {@code tickOutputs[eu]} 的数值（发电机侧的电量存在这里）。 */
    private static long shanhai$firstTickOutputEu(GTRecipe recipe) {
        if (recipe == null) {
            return -1L;
        }
        final List<Content> contents = recipe.getTickOutputContents(EURecipeCapability.CAP);
        if (contents.isEmpty()) {
            return -1L;
        }
        final Long raw = EURecipeCapability.CAP.of(contents.get(0).content);
        return raw == null ? -1L : raw;
    }

    // ═════════════════ 主机侧效果尾巴：N3 → N6 → N5（两个引擎路径共用的一份算术） ═════════════════

    /**
     * 🔴 <b>主机侧 {@code N3 产出倍率 → N6 时长 min 天花板 → N5 耗电减免} 的一份实现</b>（⚠️ <b>已不在生产路径上</b>：主机现行走 {@link #applyHostTailEffectsA} 的 A 下限）。
     *
     * <h2>为什么必须抽出来（这是"换引擎"里最容易出事的一步）</h2>
     * 主机有<b>两条互斥</b>的配方装配路径，而它们对四项效果的<b>应用点完全不同</b>：
     * <pre>
     *   ① 原生路径（{@code RecipeLogic.checkMatchedRecipeAvailable} → {@code recipeModifier} 链）
     *      落点：{@code PrimordialOmegaEngineMachine#applyHostRecipeModifier}
     *      入口配方 = 配方定义原实例 ⇒ 链上要自己先做 N4 并行
     *
     *   ② 多配方引擎路径（{@code MutableRecipesLogic.buildFinalNormalRecipe}）
     *      落点：{@code PrimordialEngineRecipeLogic#buildFinalNormalRecipe}
     *      入口配方 = 引擎<b>已经装配好</b>的成品（并行与输入扣减都发生在更早的
     *      {@code calculateParallelsWithGreedyAllocation} 里）
     * </pre>
     * 两条路径的尾巴（N3/N6/N5 + duration 绊线）<b>语义逐字相同</b>，只有入口不同。
     * 如果各写一份，"改了一边忘了另一边"的后果是<b>静默</b>的（配方照样跑，数值悄悄不一样）。
     * ⇒ 尾巴只此一份，两个调用点都走这里。
     *
     * <h2>门控只读一次（调用方的责任）</h2>
     * N3 的倍率与 N5 的成本系数<b>必须同源</b>，所以 {@code gateBonus} 由调用方在进入本方法前
     * 读<b>一次</b>再传进来（两条路径的调用点都遵守同一条纪律）。
     *
     * <h2>顺序（写死，不许换）</h2>
     * <pre>
     *   N4 并行     —— 已由调用方先做完（本方法<b>不</b>碰并行）
     *   ② N3 倍率    —— 只动 outputs / tickOutputs，概率拉满 10000
     *   ③ N6 时长    —— 本入口 = {@code min(原时长, max(1, limitedDuration))}（min 天花板；⚠️ 本入口已不在生产路径上）
     *                   （旧口径「恒等 = max(1, limitedDuration)」已作废；A 口径见 {@link #applyHostTailEffectsA}）
     *   ④ N5 耗电    —— EUt ×= f，钳 ≥ 1
     * </pre>
     *
     * <h2>🔴 duration 绊线在这里</h2>
     * <pre>
     *   N3 之后：{@code auditDuration}（期望 = 入口快照）        ⇒ 证明「倍率没有顺手改时长」
     *   N6 之后：{@code auditDurationWithin}（期望 = min(原时长, 上限)；
     *            并断言 ≤ 原时长、≤ 上限）                      ⇒ 终检 + 「永不变长」红线
     *   N5 之后：{@code auditDuration}（期望 = N6 目标）         ⇒ 证明「耗电减免没有顺手改时长」
     * </pre>
     * 任一次不一致都会打出 {@code [SHANHAI-…]} ERROR 行并把值强制还原，
     * <b>而不是静默地改变时长</b>。
     * <p>⚠️ <b>覆盖范围的诚实边界</b>：快照取自<b>本方法入口</b>。在多配方引擎路径下，
     * 入口配方已经是引擎装配好的（引擎自己按能量算过一个 duration）——
     * 那一次赋值发生在绊线<b>之前</b>，<b>不在</b>绊线的覆盖范围内。
     * 绊线保证的是「<b>本工程接手之后的链上没有任何步骤偷偷改 duration</b>」。
     * <p>🔴 <b>但「永不变长」那条不受这个边界影响</b>：它比的是 {@code originalDuration}
     * （**配方定义的原时长**，由调用方传入，引擎路径 = d0），不是入口快照 ⇒ 即使引擎自己把
     * duration 抬到 20，1 tick 的配方照样会被 N6 拉回 1 tick。
     *
     * <h2>为什么返回的可能是新实例</h2>
     * {@link #multiplyOutputs} / {@link #applyHostDurationCap} / {@link #reduceEnergy} 都是
     * 「没生效就原样返回入参、生效就返回 {@link #guardedCopy} 出来的副本」，
     * 所以本方法的返回值<b>必须</b>被调用方接住并使用（丢掉它 = 效果全丢）。
     *
     * @param recipe           入口配方（原生路径 = 已做完并行的配方；引擎路径 = 引擎装配好的配方）
     * @param gateBonus        主机专属槽门控等级（{@code moduleSlotBonus()}，0 = 未生效；调用方只读一次）
     * @param limitedDuration  主机「配方最短耗时」（N6 的上限来源，单位 tick）
     * @param originalDuration 🔴 <b>配方定义的原时长</b>（天花板）：
     *                         原生路径传链入口快照；<b>引擎路径必须传 d0</b>
     *                         （{@code ParallelData.getOriginRecipeList().get(0).duration}）——
     *                         <b>不许传引擎装配后的 D</b>（D 自带 20 下限，会让判据①失败）。
     *                         {@code <= 0} ⇒ 未知，退化成只夹上限。
     * @param tag              写进绊线日志的调用点标识（两条路径必须能区分，便于定位）
     * @return 施加完 N3/N6/N5 的配方；{@code recipe == null} 时返回 {@code null}
     */
    public static GTRecipe applyHostTailEffects(GTRecipe recipe, int gateBonus,
                                                int limitedDuration, int originalDuration, String tag) {
        if (recipe == null) {
            return null;
        }
        // 链入口快照：用来证明「除了 N6 那一步，本链上没有任何步骤改动 duration」。
        final int durationAtEntry = recipe.duration;
        final int cap = Math.max(1, limitedDuration);
        // 🔴 N6 目标（唯一一份算式，由 durationTarget 承担）：min(原时长, 上限)
        final int durationTarget = durationTarget(originalDuration, limitedDuration);

        // ② N3 产出倍率（只动产出：outputs / tickOutputs 两张表，概率拉满）
        GTRecipe modified = multiplyOutputs(recipe, outputMultiplier(gateBonus));
        modified = auditDuration(modified, durationAtEntry, tag + "-N3-倍率后");

        // ③ N6 时长：min(原时长, 上限) —— 与并行数无关；🔴 永不变长
        modified = applyHostDurationCap(modified, limitedDuration, originalDuration);
        modified = auditDurationWithin(modified, durationTarget, originalDuration, cap, tag + "-N6-终检");

        // ④ N5 耗电减免（主机只吃这半边；f 取主机专属槽等级，等级 ≤ 0 时 reductionFactor 已短路成 1.0）
        modified = reduceEnergy(modified, reductionFactor(gateBonus));
        // ④ 之后不再改 duration：绊线再核一次，证明耗电减免没有顺手动时长。
        modified = auditDuration(modified, durationTarget, tag + "-N5-耗电后");

        return modified;
    }

    /**
     * 🔴 <b>主机侧 N6 的 A1 专用入口（2026-09-22 新增）—— 与 {@link #applyHostTailEffects} 语义不同。</b>
     *
     * <h2>为什么必须新增这个入口（不许改原入口）</h2>
     * 原 {@link #applyHostTailEffects} 的 N6 是 <b>{@code min(原时长, 上限)}</b>（旧的"上限"口径），
     * 而且它的 {@code auditDurationWithin} <b>用的是同一个 min 目标</b>。后果（2026-09-22 查实）：
     * <pre>
     *   调用方若把 T 设成 A 口径的 max(dx, 下限)，原入口会：
     *     ① 用 min 把它<b>当场钳回去</b>；
     *     ② 绊线用的是<b>同一个 min 目标</b> ⇒ <b>连响都不响</b>。
     *   = "静默抹掉 + 自证清白" —— 本工程最坏的失败形态。
     * </pre>
     * ⇒ <b>唯一安全的做法是给 A 口径单开一个入口</b>；原入口<b>原样保留给模块路径</b>。
     *
     * <h2>🔴 本路径<b>故意不用</b> {@code auditDurationWithin}</h2>
     * {@code auditDurationWithin} 的三条不变式里含 <b>{@code duration ≤ 原时长}</b>（"时长永不变长"），
     * 而 <b>A 口径的下限（{@code max(dx, L)}）可以把短配方<u>拖长</u></b> ⇒ 那条不变式在 A 路径上
     * <b>必然失败（误报）</b>。
     * <p>⇒ 本路径改用 {@link #auditDuration(GTRecipe, int, String)} 断言
     * <b>{@code duration == A 目标}</b> + "没有别的步骤偷改 duration"。
     * <p>⚠️ <b>这不是遗漏，是刻意的选择</b>（2026-09-22 队长确认）：两套绊线的适用范围不同，
     * 不要为了让它们"看起来一致"而在 A 路径上复用旧不变式。
     *
     * <h2>🔴 为什么模块继续用 {@code min}（原入口），不跟着改 A</h2>
     * A 的"下限"是靠 <b>总能量守恒</b>（{@code EUt = 总能量 ÷ T}）正当化的，
     * 而那个公式里含 <b>"最大电压"</b> —— 那是<b>引擎参数</b>。**模块没有"整批总能量 ÷ 最大电压"这一层**：
     * <pre>
     *   若把模块也改成 max ⇒ 模块会把快配方<b>拉长</b>，却<b>没有"总能量守恒"这个由头补回来</b>
     *   ⇒ 净丢产能 ⇒ 恰好违背用户原话「不会因为这个而丢失产能」。
     * </pre>
     * ⇒ <b>主机 A / 模块 min，两侧口径不同</b>（必须在用户清单里明说）。
     *
     * @param targetDuration  调用方算好的 A 目标 {@code T = max(dx, 下限)}（本入口<b>原样采用</b>）
     * @param originalDuration 配方定义的原时长（⚠️ <b>本入口并未读取它</b>：A 口径下既不是天花板也不参与绊线 —— 形参只为与旧入口签名对称而保留）
     */
    public static GTRecipe applyHostTailEffectsA(GTRecipe recipe, int gateBonus,
                                                 int targetDuration, int originalDuration, String tag) {
        // 🔴 2026-10-23（队长选 A 之后新增）：5 参入口 = 7 参入口的"原样委托"。
        //    两个实参与改动前【逐位相同】：outputMultiplier(gateBonus) / reductionFactor(gateBonus)
        //    ⇒ 老主机（以及任何没改的调用点）行为一个字节都没变。
        return applyHostTailEffectsA(recipe, gateBonus, targetDuration, originalDuration, tag,
                outputMultiplier(gateBonus), reductionFactor(gateBonus));
    }

    /**
     * 🔴 <b>2026-10-23 新增（队长选 A：让"宿主可覆写"的倍率与耗电系数进入引擎路径）。</b>
     *
     * <p>为什么要多这两个形参：多配方引擎路径<b>不经过</b> {@code RecipeModifierList.apply}
     * （见 {@code PrimordialEngineRecipeLogic} 的注释与字节码），所以
     * {@code GuixuVisionCoreMachine#applyGuixuRecipeModifier} 那条原生修饰链在引擎路径上
     * <b>永远不会被调用</b>。要让"材料增产额外 +5"真正生效，唯一干净的位置就是这里的 N3 一步 ——
     * 由调用方（引擎）把宿主算好的倍率与耗电系数传进来。
     *
     * <h2>与 5 参入口的关系（不许漂移）</h2>
     * 5 参入口 = 本方法 + {@code (outputMultiplier(gateBonus), reductionFactor(gateBonus))}
     * ⇒ 两条入口的算术只有"这两个数从哪来"不同，步骤/顺序/绊线逐字相同。
     *
     * @param multiplier   N3 的产出倍率（调用方算；老主机 = {@code outputMultiplier(gateBonus)}）
     * @param energyFactor N5 的耗电成本系数（调用方算；老主机 = {@code reductionFactor(gateBonus)}）。
     *                     {@code 1.0} ⇒ 不减免（{@code reduceEnergy} 内部对 ≥1 原样返回入参）。
     */
    public static GTRecipe applyHostTailEffectsA(GTRecipe recipe, int gateBonus,
                                                 int targetDuration, int originalDuration, String tag,
                                                 int multiplier, double energyFactor) {
        if (recipe == null) {
            return null;
        }
        final int durationAtEntry = recipe.duration;
        final int target = Math.max(1, targetDuration);

        // ② N3 产出倍率（只动产出，与时长无关）
        GTRecipe modified = multiplyOutputs(recipe, multiplier);
        modified = auditDuration(modified, durationAtEntry, tag + "-N3-倍率后");

        // ③ N6（A1）：**直接采用调用方给定的目标**，本入口自己不算 min
        final GTRecipe n6 = guardedCopy(modified, "N6-A口径");
        n6.duration = target;
        modified = n6;
        // 绊线：期望值就是 A 目标（**不是** min 目标 —— 这是与原入口的关键区别）
        modified = auditDuration(modified, target, tag + "-N6-终检");

        // ④ N5 耗电减免（主机只吃这半边）
        modified = reduceEnergy(modified, energyFactor);
        modified = auditDuration(modified, target, tag + "-N5-耗电后");

        return modified;
    }

    // ═══════════════════════════════════ 内部工具 ═══════════════════════════════════

    /**
     * 安全拷贝 + <b>duration 不变</b>的绊线 + <b>上游 copy 缺口的补齐</b>。
     *
     * <h2>① duration 绊线</h2>
     * {@code GTRecipe.copy()}（无参）的字节码把 {@code duration} 原样塞进构造器，
     * 结构上不可能改动它。这道德意志断言的作用是：<b>万一上游将来把它改成
     * {@code copy(ContentModifier.multiplier(1))} 之类</b>，这里会<b>响亮地</b>喊出来并强制还原，
     * 而不是让「倍率顺手把时长也改了」这种事故静默发生。
     *
     * <h2>② 🔴 上游 copy 的等价性缺口（旧私货撞过，本工程补齐）</h2>
     * {@code GTRecipe.copy()} 的构造器<b>根本不接收</b>这几个可写字段，于是它们在新实例上
     * 回到字段初值：{@code [源码原文 / 字节码]}
     * <pre>
     *   public int duration;              // ← copy() 会带上（构造器第 14 参）
     *   public int parallels = 1;         // ← copy() 不带：新实例恒为 1
     *   public int ocTier = 0;            // ← copy() 不带：新实例恒为 0
     *   // gtlcore GTRecipeMixin（全部 @Unique，构造器都给初值）：
     *   &#64;Unique private long    realParallels = 1L;        // ← copy() 不带：新实例恒为 1
     *   &#64;Unique private int     batchSize = 1;             // ← copy() 不带：新实例恒为 1
     *   &#64;Unique private boolean batchProcessed = false;    // ← copy() 不带：新实例恒为 false
     *   &#64;Unique private boolean subTickParallelized = false; // ← copy() 不带：新实例恒为 false
     * </pre>
     * 证据：旧私货 {@code PrimordialRecipeOutputAmplifier.apply} 在 {@code recipe.copy()} 之后
     * 紧接着两行 {@code copy.parallels = recipe.parallels; copy.ocTier = recipe.ocTier;}
     * —— 那<b>不是</b>装饰，是在补这个缺口。
     * 而 vanilla {@code ParallelLogic.doParallelRecipes} 同样在 copy 之后手写
     * {@code multiRecipe.parallels = limitByOutput;}（{@code [字节码]}）。
     *
     * <p>本工程比旧私货多补一个 {@code realParallels}：并行真正生效的是 gtlcore 的
     * {@code ParallelLogicMixin#doParallelRecipes}，它<b>不</b>写 {@code parallels}，改写成
     * {@code ((IGTRecipe)multiRecipe).setRealParallels(limitByOutput * ((IGTRecipe)currentRecipe).getRealParallels());}
     * （{@code [字节码]}）。⇒ 若不在后续副本里带上它，「实际跑了几个并行」会静默记成 1。
     * 用 {@code instanceof} 守卫而不是硬转：万一日后 gtlcore 不再对 {@code GTRecipe} 织入该接口，
     * 这里退化成「不复制」而<b>不是</b>抛 {@code ClassCastException}。
     *
     * <p>🔴🔴 <b>2026-09-27 再补三个：{@code batchProcessed} / {@code batchSize} / {@code subTickParallelized}。</b>
     * 其中 {@code batchProcessed} 是「1 个铁锭 → 100 个铁板、跑 5 秒」那个 bug 的<b>真根因</b>：
     * gtladditions 的引擎成品把它标成 {@code true}（"已经按批装配过了"），而它的 {@code inputs} 是
     * <b>空的</b>（材料在引擎侧就扣完了）⇒ 标记一丢，gtlcore 就会对同一张配方再乘一次 batchSize，
     * 结果只有 outputs 与 duration 被乘。完整推导、审计与正负对照见方法体里的注释与
     * {@link #shanhai$auditBatchFlagOnce(GTRecipe)}。
     *
     * <p>🔴 <b>2026-09-27 同日两改（先后两次用户实机反馈）</b>：
     * <ol>
     *   <li>用户报「现在不会凭空生成物品了，但是批处理完全不生效了」⇒ 一度把批处理搬到
     *       <b>并行分配那一步</b>（在 {@code calculateParallel} 里把并行数 ×N）。</li>
     *   <li>🔴 用户 2026-09-27 再报「批处理还是没修好，<b>批处理应该是不影响机器的耗电的，
     *       而且批处理应该是延长机器运行时间的</b>」⇒ 上面那一步<b>做成了"并行"而不是"批处理"</b>：
     *       把 p 乘上去会让 {@code multipleRecipe} 连 {@code tickInputs}(EU/t) 一起乘
     *       ⇒ <b>耗电 ×N</b>，而耗时反而被模块的 N6 钉回原值 ⇒ 与用户规格<b>正好相反</b>。
     *       <b>该放大已整体删除</b>，批处理改为 {@link #applyBatchProcessing}
     *       （在成品上"连跑 N 次"：输入 ×N／输出 ×N／<b>耗时 ×N</b>／<b>EU/t 不变</b>）。</li>
     * </ol>
     * <b>本方法照旧忠实搬运 {@code batchProcessed}</b>（它仍然是"不许让 gtlcore 再乘一次"的正确信号）。
     */
    private static GTRecipe guardedCopy(GTRecipe recipe, String step) {
        final int before = recipe.duration;
        final GTRecipe copy = recipe.copy();
        if (copy.duration != before) {
            LOGGER.error("[SHANHAI-{}] GTRecipe.copy() 意外改动了 duration：{} → {}；"
                            + "上游契约已变，已强制还原。N3/N6 的口径是「duration 只许由 N6 改写」，"
                            + "这条日志一旦出现就是口令失效，请立刻检查 GTRecipe.copy()。",
                    step, before, copy.duration);
            copy.duration = before;
        }
        // 补齐 copy() 不搬运的字段（见 javadoc ②）。
        copy.parallels = recipe.parallels;
        copy.ocTier = recipe.ocTier;
        if (recipe instanceof IGTRecipe source && copy instanceof IGTRecipe target) {
            target.setRealParallels(source.getRealParallels());
            // 🔴🔴 2026-09-27 补第二个缺口：batchProcessed / batchSize / subTickParallelized。
            //   【缺口本身】它们是 gtlcore 织进 GTRecipe 的 @Unique 字段（GTRecipeMixin）：
            //       javap -p org.gtlcore.gtlcore.mixin.gtm.api.recipe.GTRecipeMixin
            //         private long    realParallels;      ← 初值 1（构造器里 putfield）
            //         private int     batchSize;          ← 初值 1
            //         private boolean batchProcessed;     ← 初值 false
            //         private boolean subTickParallelized;← 初值 false
            //     而 GTRecipe.copy() 造的是【新实例】（[字节码] 走构造器，这些字段不是构造器形参）
            //     ⇒ 四个字段全部回到初值。realParallels 上方已补；剩下三个今天才补。
            //   【为什么非要补 batchProcessed（本 bug 的真根因）】
            //     gtladditions 的多配方引擎在装配成品时【显式】把它标成 true：
            //       RecipeCalculationHelper.buildNormalRecipe   末尾：IGTRecipe.of(built).setBatchProcessed(true);
            //       RecipeCalculationHelper.buildWirelessRecipe 末尾：同一句（[字节码] invokeinterface #462）
            //     语义 = 「这张配方已经由引擎按批装配过了，别再乘一次」。
            //     更关键的是：引擎成品的 inputs 是【空的】——
            //       buildNormalRecipe 的字节码只写 outputs / tickInputs(只放 EU) / duration，
            //       **从头到尾没有一条对 GTRecipe.inputs 的 put**（材料早在
            //       calculateParallelsWithGreedyAllocation 里由 handleRecipeInput 扣掉了）。
            //     ⇒ 一旦这个标记被 copy() 洗掉，gtlcore 的 BatchProcessing 会在
            //       RecipeLogicMixin.handleRecipeIO(GTRecipe, IO.IN) 里对同一张配方再放大一次：
            //       scaleRecipe(recipe, batchSize) 对【四张表】同乘 —— 但 inputs 是空的，乘了还是空的；
            //       outputs ×batchSize、duration ×batchSize（tickInputs 被原样写回 ⇒ EU/t 不变）。
            //       ⇒ 净效果 = 【输出凭空多 batchSize 倍、料一份没多要、时间 ×batchSize】。
            //         这正是用户 2026-09-27 报的「开批处理 + 输入 1 个铁锭 ⇒ 跑 5 秒、出 100 个铁板」。
            //   【审计（这条修法为什么只影响坏的那一支）】
            //     `isBatchProcessed` 在整个 gtlcore jar 里只有【一个读点】：
            //       findstr /s /m /c:"isBatchProcessed" <extracted gtlcore>
            //         → BatchProcessing.class / IGTRecipe.class / GTRecipeMixin.class（后两个只是声明）
            //     而 BatchProcessing.apply(...) 的第一件事就是
            //       [字节码] 123: IGTRecipe.of(recipe).isBatchProcessed() → ifeq 137 / 135: return recipe;
            //     ⇒ 标记在位 ⇒ 整个批处理放大被跳过（applyInPlace 随之在 `scaled == recipe` 处直接 return true）。
            //     其余读点全是 `getBatchSize()`（Jade 显示 / GTRecipePayloadMixin / 主机的
            //     gtlcore$finishRecipeWhenBatchOutputFits），与本次改动无关。
            //   ⚠️ 原生 recipeModifier 链（ModuleRegistry 那条）不受影响：gtlcore 是在
            //      RecipeModifierListMixin.apply 的【最后一句】才调 BatchProcessing.apply，
            //      我们的尾链修饰器跑在它【之前】，那时源配方的 batchProcessed 本来就是 false
            //      ⇒ 补的是 false，逐值等价。
            final boolean batchProcessed = source.isBatchProcessed();
            target.setBatchProcessed(batchProcessed);
            target.setBatchSize(source.getBatchSize());
            if (source.isSubTickParallelized()) {
                target.setSubTickParallelized(true);
            }
            if (batchProcessed) {
                shanhai$auditBatchFlagOnce(recipe);
            }
        }
        return copy;
    }

    /** 上面那条自检的一次性闸门（只在真的见到「引擎已按批装配」的配方时打一行）。 */
    private static boolean shanhai$batchFlagAudited;

    /**
     * 🔴 <b>「批处理标记被 copy() 洗掉」的可 grep 证据行（一次性 · 正负对照自证）</b>。
     *
     * <p>只在这条路径上真的拿到一张 {@code batchProcessed == true} 的配方时打一次，内容包含：
     * <ol>
     *   <li>{@code 源=true} —— 上游 gtladditions 确实标了（这一步跑不到 ⇒ 说明本链没经过引擎成品）；</li>
     *   <li>{@code 无补偿副本=false} —— <b>负面对照</b>：现场再 {@code copy()} 一次、不做任何补偿，
     *       读回 false ⇒ 证明「缺口真实存在」，也证明这行本身不是橡皮图章；</li>
     *   <li>{@code 补偿后=true} —— <b>正面对照</b>：{@link #guardedCopy} 修好之后必须读回 true；
     *       若这里读到 false ⇒ 修法没生效，本行会直接喊 FAIL。</li>
     * </ol>
     * 顺带打印引擎成品的 {@code inputs} 规模 —— 它是「输出能乘、料没得乘」那一半的现场读数。
     */
    private static void shanhai$auditBatchFlagOnce(GTRecipe source) {
        if (shanhai$batchFlagAudited) {
            return;
        }
        shanhai$batchFlagAudited = true;
        try {
            final GTRecipe control = source.copy();     // 故意不补偿 = 修复前的行为
            final boolean controlFlag = control instanceof IGTRecipe c && c.isBatchProcessed();
            final GTRecipe fixed = guardedCopy(source, "批处理标记自检");
            final boolean fixedFlag = fixed instanceof IGTRecipe f && f.isBatchProcessed();
            final boolean ok = !controlFlag && fixedFlag;
            LOGGER.info("[SHANHAI-BATCH] 批处理标记自检（一次性）：源 batchProcessed={} ／ 无补偿副本={}（= 修复前的行为，"
                            + "失去标记 ⇒ gtlcore 会再乘一次 batchSize）／ 补偿后={} ⇒ {}；"
                            + "现场配方规模：inputs 条目={}（capabilities）／ outputs 条目={} ／ duration={} ／ "
                            + "realParallels={} batchSize={}。"
                            + "（inputs=0 是引擎成品的常态：材料在引擎侧已扣，buildNormalRecipe 从不写 inputs）",
                    ((IGTRecipe) source).isBatchProcessed(), controlFlag, fixedFlag,
                    ok ? "PASS（标记已保留 ⇒ gtlcore 不会再乘一次 batchSize；"
                            + "批处理改由 applyBatchProcessing 在成品上按「连跑 N 次」施加）"
                            : "🔴 FAIL（修法没生效，请把本行原文交回）",
                    source.inputs.size(), source.outputs.size(), source.duration,
                    ((IGTRecipe) source).getRealParallels(), ((IGTRecipe) source).getBatchSize());
        } catch (Throwable t) {
            LOGGER.error("[SHANHAI-BATCH] 批处理标记自检自身异常（不影响配方）：{}", t.toString());
        }
    }

    /** {@code Math.round} 到 int，并饱和到 {@code [1, Integer.MAX_VALUE]}（不溢出成负数）。 */
    private static int saturateMillisToTicks(double value) {
        if (value >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        final long rounded = Math.round(value);
        return (int) Math.max(1L, rounded);
    }
}
