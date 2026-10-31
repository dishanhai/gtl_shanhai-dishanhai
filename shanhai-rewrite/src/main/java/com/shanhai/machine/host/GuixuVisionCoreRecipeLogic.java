package com.shanhai.machine.host;

import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

import com.shanhai.ShanhaiMod;
import com.shanhai.common.heat.ShanhaiExtraMountGate;
import com.shanhai.common.heat.ShanhaiHeatGate;
import com.shanhai.common.machine.PrimordialEngineRecipeLogic;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import org.gtlcore.gtlcore.api.recipe.IGTRecipe;
import org.gtlcore.gtlcore.api.recipe.RecipeRunnerHelper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * 归墟视界枢核的配方逻辑 —— {@link PrimordialEngineRecipeLogic} ＋ <b>「额外挂载槽 ×3」闸门</b>。
 *
 * <h2>用户这次要的第三件事（原话逐字）</h2>
 * <blockquote>「然后我说的<b>配方条件（就是模块上面那 3 个槽）复写</b>你还没做，你也给他做了，
 * <b>接口 gui 就和模块的一样</b>」</blockquote>
 *
 * <h2>🔴 复用同一个判定核（不许写第二套）</h2>
 * <pre>
 *   槽位存储 / GUI / tooltip     {@link GuixuVisionCoreMachine}（本机的 3 格）
 *   条件 → 需求 / 判定 / 拒绝文案  {@link ShanhaiExtraMountGate}     ← 模块侧也调它（唯一实现）
 *   真正的判定                   {@link ShanhaiHeatGate#evaluate}  ← 本类<b>不</b>另算
 * </pre>
 * 本类<b>只有</b>三件机器侧的事：① 取本机三格内容；② 判"这台机器算不算热力可达"（交给机器的
 * {@code canUseExtraMountAsHeatSource()}，它读的还是 {@code ShanhaiHeatGate#hasHeatSlot}）；
 * ③ 把拒绝原因写进本机的失败原因（{@code [SHANHAI-HOSTGATE]} 日志 ＋ 一行 reason）。
 *
 * <h2>为什么闸门插在 {@code checkRecipe} 里（与模块同一位置）</h2>
 * 模块侧的理由同样成立：{@code checkRecipe} 是"这条配方能不能开工"的<b>唯一</b>收口
 * （{@code matchRecipe && 电压等级 && checkConditions} 三条合取），
 * 插在它这里 ⇒ 三种结果（放行 / 被槽拦下 / 由槽满足了条件）都能在这里定，
 * 而且<b>不需要</b>去改 gtladditions 的 {@code MutableRecipesLogic}（那是别人的类）。
 *
 * <h2>🔴 与模块侧的一处【刻意】不同：失败原因只落日志，不写 gtlcore 的 RecipeStatus</h2>
 * 模块侧把原因写进 {@code IRecipeStatus}（Jade + GUI 都会画那一行）。本机<b>没有</b>接那条链
 * （主机基类没实现 {@code IRecipeStatus}，本任务不许改 {@code common/machine} 那一层），
 * 所以这里只打一行<b>可 grep</b> 的日志（{@code [SHANHAI-HOSTGATE]}），
 * 并在探头里如实写明「玩家在 Jade 上看不到这句」。宁可缺，不可假：不假装它显示了。
 */
public class GuixuVisionCoreRecipeLogic extends PrimordialEngineRecipeLogic {

    /** 与模块侧同一个前缀族（{@code [SHANHAI-EXTRAMOUNT]} 的读数来自共用核）。 */
    public static final String PREFIX = "[SHANHAI-HOSTGATE]";

    /** 已报过的读数（只报一次，避免每次找配方刷屏；与模块侧同一条纪律）。 */
    private static final Set<String> LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 本轮的闸门判定结果 —— {@code checkRecipe} 里算一次，后面两段复用它（不重算）。
     */
    @Nullable
    private ShanhaiHeatGate.Outcome shanhai$gateOutcome;

    public GuixuVisionCoreRecipeLogic(GuixuVisionCoreMachine machine) {
        super(machine);
    }

    /**
     * <b>能不能开工</b> —— 父类那三条（{@code matchRecipe} / 电压等级 / {@code checkConditions}）
     * 之间插入本机的「额外挂载槽」闸门。
     *
     * <h2>两段分流（与模块侧逐字同语义，理由见 {@link ShanhaiExtraMountGate#conditionsPass}）</h2>
     * <ol>
     *   <li><b>没有任何需求被槽满足</b> ⇒ <b>原样委托回父类</b>
     *       （{@code super.checkRecipe(recipe)}）。这一条保证"没有额外条件的配方永远照跑"，
     *       而且<b>我们碰不到</b>上游的任何判据。</li>
     *   <li><b>有需求被槽满足</b> ⇒ 本方法自己复刻父类那三条，其中最后一条换成
     *       {@link ShanhaiExtraMountGate#conditionsPass}（"槽已满足"的那几条算过）。</li>
     * </ol>
     *
     * <h2>父类那三条的出处（javap 实证，见 PrimordialModuleRecipeLogic 同名方法的注释）</h2>
     * <pre>
     *   MutableRecipesLogic#checkRecipe(GTRecipe):
     *      7: RecipeRunnerHelper.matchRecipe(machine, recipe)     ⇒ 假就 false
     *     22: IGTRecipe.of(recipe).getEuTier() &gt; machine.getTier() ⇒ 真就 false
     *     40: recipe.checkConditions(this)                        ⇒ 失败就 false
     * </pre>
     */
    @Override
    protected boolean checkRecipe(@NotNull GTRecipe recipe) {
        final GuixuVisionCoreMachine machine = guixu();
        final GTRecipeType type = recipe.recipeType != null ? recipe.recipeType : machine.getRecipeType();
        final boolean heatReachable = type != null
                && ShanhaiHeatGate.isGated(type.registryName.toString())
                && machine.canUseExtraMountAsHeatSource();
        // 判定核：ShanhaiExtraMountGate → ShanhaiHeatGate#evaluate（与模块同一份）
        final ShanhaiHeatGate.Outcome outcome = ShanhaiExtraMountGate.evaluate(
                recipe, type, heatReachable, this, machine.getExtraMountContents());
        shanhai$gateOutcome = outcome;

        if (outcome.needs.isEmpty() && outcome.satisfied.isEmpty()) {
            // 这条配方对"额外条件"一个字都不要求 ⇒ 与今天逐字相同（含日志都不打）
            return super.checkRecipe(recipe);
        }
        shanhai$logGate(type, outcome, heatReachable);

        if (!outcome.allowed) {
            shanhai$logDeny(outcome);
            return false;
        }
        if (outcome.satisfied.isEmpty()) {
            return super.checkRecipe(recipe);
        }

        // ── 有需求被槽满足 ⇒ 复刻父类那三条（只把最后一条换成"槽也算数"的那一版） ──
        if (!RecipeRunnerHelper.matchRecipe((IRecipeCapabilityHolder) machine, recipe)) {
            return false;
        }
        if (IGTRecipe.of(recipe).getEuTier() > machine.getTier()) {
            // 与父类同一条判据（本机 getMaxVoltage 是 Long.MAX_VALUE，实际不会命中；
            // 留着是为了"复刻"而不是"少一条"——缺一条就是比上游更松，属于静默放水）。
            return false;
        }
        return ShanhaiExtraMountGate.conditionsPass(recipe, this, outcome);
    }

    /**
     * 三格的读数行（哪台机器 / 配方类型 / 热力可达 / 需求 / 判定）。
     * <p>没有需求 ⇒ <b>一个字都不打</b>（本包绝大多数配方没有额外条件，不该刷屏）。
     */
    /**
     * 本机（父类的 {@code getMachine()} 静态类型是主机基类 {@code PrimordialOmegaEngineMachine}，
     * 而本逻辑只装在 {@link GuixuVisionCoreMachine} 上 ⇒ 这里一次强转，别在每处都写）。
     */
    @NotNull
    private GuixuVisionCoreMachine guixu() {
        return (GuixuVisionCoreMachine) this.getMachine();
    }

    private void shanhai$logGate(@Nullable GTRecipeType type,
                                 @NotNull ShanhaiHeatGate.Outcome o,
                                 boolean heatReachable) {
        final String key = "gate|" + o.deny + "|" + o.describe();
        if (LOGGED.size() > 128 || !LOGGED.add(key)) {
            return;
        }
        final GuixuVisionCoreMachine machine = guixu();
        ShanhaiMod.LOGGER.info("{} 机器={}（{}） 配方类型={} 热力可达={} 三格={} {}",
                ShanhaiExtraMountGate.PREFIX,
                machine.getBlockState().getBlock(),
                machine.getPos(),
                type == null ? "-" : type.registryName,
                heatReachable,
                machine.describeExtraMounts(),
                o.describe());
    }

    /** 被槽拦下：一行可 grep 的日志 ＋ 一行<b>只给日志</b>的原因（见类注释的"刻意不同"）。 */
    private void shanhai$logDeny(@NotNull ShanhaiHeatGate.Outcome o) {
        final List<ItemStack> stacks = guixu().getExtraMountStacks();
        final Component reason = ShanhaiExtraMountGate.denyReason(o, stacks);
        final String key = "deny|" + o.deny + "|" + o.describe();
        if (LOGGED.size() > 128 || !LOGGED.add(key)) {
            return;
        }
        ShanhaiMod.LOGGER.info("{} 配方被额外挂载槽拦下 机器={} 原因={} {}",
                PREFIX, guixu().getPos(),
                reason == null ? "(无文案)" : reason.getString(),
                o.describe());
    }

    /** 上一轮的判定结果（只读出口；自检/日志用，别在别处改语义）。 */
    @Nullable
    public ShanhaiHeatGate.Outcome shanhai$lastGateOutcome() {
        return shanhai$gateOutcome;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  D/F（2026-10-25，第二版）：**「配方最短耗时」地板** —— 用户报「一直是 1s」，第一版没修好。
    //
    //  <h2>🔴 第一版为什么没生效（用户实测证据，不是猜）</h2>
    //  第一版把地板补在 {@code buildFinalNormalRecipe(ParallelData)} 的 {@code super} 之后，
    //  结果用户 grep 整份 latest.log：<b>没有任何一行 {@code [SHANHAI-DUR]}</b> ⇒
    //  <b>那个方法一次都没被走到</b> ⇒ 归墟跑的不是「多配方引擎的尾链」那条路
    //  （{@code MutableRecipesLogic#buildFinalNormalRecipe} 只由它自己的
    //   {@code findAndHandleRecipe/handleRecipeWorking} 在并行装配成功时调用；
    //   本机现在一次只跑一条配方 ⇒ 走不到）。⇒ 第一版的覆写已删除（死代码不留）。
    //
    //  <h2>✅ 第二版：挂在 {@code RecipeLogic#setupRecipe}</h2>
    //  {@code javap -p RecipeLogic} 实证：{@code public void setupRecipe(GTRecipe)} —— <b>public</b>，
    //  且它是<b>每条配方开始时唯一必过的闸</b>（{@code duration/maxProgress} 就在它里面被定下来）：
    //  <pre>
    //    引擎路径（MutableRecipesLogic）        ┐
    //    原生路径（RecipeModifierList.apply）   ├→ 都走 RecipeLogic#setupRecipe  ⇒ 这里是"最后一道共同关口"
    //    无线/发电那一脉                        ┘
    //  </pre>
    //  ⇒ 把「面板值」按<b>上游语义（地板）</b>在<b>过闸之前</b>写进配方：
    //     {@code duration = max(配方原时长, max(1, 面板值))}
    //     上游原文（{@code RecipeCalculationHelper.buildNormalRecipe} 反编译）：
    //     {@code recipe.duration = MathKt.roundToInt(Math.max(dx, (double) minDuration));} ⇒ 地板。
    //  🔴 两条安全纪律：
    //    ① <b>绝不原地改 {@code GTRecipe}</b>（同一份配方对象被整个配方表共享，改它 = 污染全部机器）
    //       ⇒ 先 {@code recipe.copy()} 再改副本，把副本交给 {@code super}；
    //    ② {@code panel <= 0} 或异常 ⇒ <b>原样交给 super</b>（行为与改动前逐位相同）。
    // ═══════════════════════════════════════════════════════════════════════════════════

    /** 上一次打 {@code [SHANHAI-DUR]} 的那组读数（只在变化时打，避免刷屏）。 */
    private String shanhai$lastDurLog = "";

    /**
     * 每条配方开始时把「最短耗时」按<b>地板</b>语义补上（上游语义），并打一行判据。
     *
     * <p>只会把<b>比面板值短</b>的配方拖长到面板值；比面板值长的配方<b>一个 tick 都不动</b>
     * （所以"永不变长"那条对长配方依旧成立，与 A 口径不冲突）。
     */
    @Override
    public void setupRecipe(@NotNull com.gregtechceu.gtceu.api.recipe.GTRecipe recipe) {
        if (recipe == null) {
            super.setupRecipe(null);
            return;
        }
        com.gregtechceu.gtceu.api.recipe.GTRecipe pass = recipe;
        try {
            final int panel = guixu().getLimitedDuration();
            final int origin = recipe.duration;
            final int applied = Math.max(origin, Math.max(1, panel));
            if (panel > 0 && applied != origin) {
                // ① 只改副本（共享配方对象一个字节都不动）
                final com.gregtechceu.gtceu.api.recipe.GTRecipe copy = recipe.copy();
                copy.duration = applied;
                pass = copy;
            }
            final String key = panel + "/" + origin + "/" + applied;
            if (!key.equals(shanhai$lastDurLog)) {
                shanhai$lastDurLog = key;
                ShanhaiMod.LOGGER.info("[SHANHAI-DUR] host=归墟 panel={} engine={} applied={}"
                                + "（第二版落点 = RecipeLogic#setupRecipe，每条配方必经；上游语义 = 地板："
                                + "applied = max(engine, panel)。engine 是配方进入本闸时的时长）",
                        panel, origin, applied);
            }
        } catch (Throwable ignored) {
            pass = recipe;      // 兜底：任何异常都退回"原样交给 super"（行为等于改动前）
        }
        super.setupRecipe(pass);
    }
}

