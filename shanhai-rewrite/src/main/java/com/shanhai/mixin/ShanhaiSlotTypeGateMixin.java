package com.shanhai.mixin;

import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.shanhai.machine.stellar.ShanhaiSlotTypeGate;
import com.shanhai.machine.stellar.ShanhaiWildcardLayout;
import com.shanhai.machine.stellar.ShanhaiWildcardMaterialGuard;
import com.shanhai.machine.stellar.ShanhaiWildcardRecipeBinding;
import com.shanhai.machine.stellar.StellarSampleAssemblerPartMachine;

import com.mojang.logging.LogUtils;

import it.unimi.dsi.fastutil.objects.Object2LongMap;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachineBase;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>【落到槽上的类型闸】—— 引擎拿一条配方问某个槽时，那个槽自己回答"我能不能跑它"。</b>
 *
 * <h2>一、为什么必须是这一跳（字节码依据）</h2>
 * <pre>
 *   MEPatternBufferRecipeHandlerTraitBase$MEItemInputHandlerBase#meHandleRecipeInner
 *       (GTRecipe recipe, Object2LongMap left, boolean simulate, int slot)  ← 第 4 个参数就是【槽号】
 *   偏移 0–9   getInternalSlotOrNull(slot)         ← 按槽取池（一池一条配方）
 *   偏移 16–22 InternalSlot.isItemActive(simulate)
 *   偏移 62–74 InternalSlot.handleItemInternal(…)  ← 真正试配/扣料
 *   （流体侧 MEFluidHandlerBase#meHandleRecipeInner 同形，同样是第 4 个参数给槽号）
 * </pre>
 * 调用方是 {@code MEPatternRecipeHandlePart}（gtlcore）：
 * <pre>
 *   237: invokeinterface IMERecipeHandler.meHandleRecipe:(GTRecipe;ZI)Z   ← 逐槽调用
 *   242: ifne  248     // 返回 true  ⇒ 这个槽接下了，继续流体侧
 *   245: goto  199     // 返回 false ⇒ 换下一个候选槽（原版语义，不是整机失败）
 *   272: iload 11; ireturn  // 接下的那个槽号
 * </pre>
 * 而 {@code IMERecipeHandler#meHandleRecipe} 是 default 方法，body 只有一句
 * {@code invokeinterface meHandleRecipeInner} ⇒ 本注入点就是"引擎问槽"的唯一入口。
 * 在 HEAD 拒绝，引擎的下一槽逻辑自动接手，<b>既不影响别的槽，也不需要任何"优先级/顺序"仲裁</b>
 * （用户口径：一个池只管一条配方 ⇒ 结构上不可能串）。
 *
 * <h2>二、安全边界（三条，缺一不可）</h2>
 * <ol>
 *   <li><b>只对星律生效</b>：{@code getMachine() instanceof StellarSampleAssemblerPartMachine}
 *       —— 其它样板总成 / 总线走原路，一个字节不变；</li>
 *   <li><b>拿不到就放行</b>：配方类型读不出、机器可执行类型表读不出、槽号越界 ⇒ 一律放行；</li>
 *   <li><b>只拒明确不一致</b>：判定核是纯逻辑 {@link ShanhaiSlotTypeGate}。</li>
 * </ol>
 *
 * <h2>三、一键关</h2>
 * 环境变量（或 {@code -D}）{@code SHANHAI_DISABLE_TYPE_FILTER=1} ⇒
 * {@link ShanhaiSlotTypeGate#disabled()} 为真 ⇒ 判定核直接回 {@code ALLOW_DISABLED} 放行，
 * <b>机器行为与没有本 mixin 逐字节相同</b>。
 *
 * <h2>四、日志（2026-10-28 补 —— 让每一次判定都留下可 grep 的痕迹）</h2>
 * 前缀一律 {@code [SHANHAI-SLOTGATE]}，三种行：
 * <ol>
 *   <li><b>启动期一次性</b>（静态标志位，只打一次）：
 *       <pre>[SHANHAI-SLOTGATE] loaded=true targets=item+fluid env=… first=…</pre>
 *       出现 ⇒ 注入点<b>确实被应用且确实被调用过</b>；一行都没有 ⇒ 注入点没应用/没被调用（这本身就是答案）。</li>
 *   <li><b>每次判定</b>（按 {@code (pos,slot,recipeType)} 去重，同一组合 20 tick 内只打一条）：
 *       <pre>[SHANHAI-SLOTGATE] pos=… slot=… declared=… recipeType=… verdict=<七种之一></pre></li>
 *   <li><b>汇总</b>（每 200 tick 一条）：
 *       <pre>[SHANHAI-SLOTGATE] calls=N allow=M deny=K byVerdict={…} doorbell=… skip={…}</pre>
 *       {@code calls=0} 且 {@code doorbell>0} ⇒ 引擎枚举过本 handler 的槽，但<b>从没拿配方来问</b>；
 *       {@code calls=0} 且 {@code doorbell=0} ⇒ 这个 handler 类<b>压根没被引擎碰过</b>。</li>
 * </ol>
 * 另有 {@code verdict=NONE reason=…} 的短路行（不是判定，单列以免污染七种 verdict 的口径）。
 */
@Mixin(targets = {
        "org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferRecipeHandlerTraitBase$MEItemInputHandlerBase",
        "org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferRecipeHandlerTraitBase$MEFluidHandlerBase"
}, remap = false)
public abstract class ShanhaiSlotTypeGateMixin {

    /**
     * 目标类自己的 {@code getMachine()}（返回 {@code MEPatternBufferPartMachineBase}）。
     *
     * <p>🔴 <b>2026-10-30 修：这里必须显式写 {@code remap = false}。</b>
     * 实机启动日志原文：
     * <pre>
     * [main/ERROR] [mixin/]: shanhai.mixin.json:ShanhaiSlotTypeGateMixin:
     *     Found a remappable @Shadow annotation on getMachine in MixinInfo$State
     * </pre>
     * 类级 {@code @Mixin(remap = false)} <b>不会</b>一并关掉 {@code @Shadow} 自己的可重映射判定
     * ⇒ Mixin 直接拒收这个 mixin（只报一条 ERROR、游戏照常启动）⇒ <b>闸从未生效</b>，
     * 表现就是"串配方 + 一行闸日志都没有"。
     */
    @Shadow(remap = false)
    public abstract MEPatternBufferPartMachineBase getMachine();

    /**
     * <b>判定注入点</b>：引擎拿一条配方问某个槽时，先问闸。
     *
     * <p>{@code require = 1 / expect = 2}：两个目标类都必须各有一条成功注入
     * （缺一条只会在日志里报 "Expected 2 injections"，不会崩 —— 那条警告本身就是诊断信息）。
     */
    @Inject(method = "meHandleRecipeInner", at = @At("HEAD"), cancellable = true, remap = false,
            require = 1, expect = 2)
    private void shanhai$slotTypeGate(GTRecipe recipe, Object2LongMap<?> left, boolean simulate, int slot,
                                      CallbackInfoReturnable<Boolean> cir) {
        // 🔴 2026-10-31（wildfix T3）：预检<b>反调</b>流体 handler 时会再进本方法一次 ⇒ 那一次整段跳过。
        //    只跳过"闸"（判定/计数/日志），**不**改变被反调的那次调用的返回语义 —— 它只是一次纯模拟询问。
        if (ShanhaiWildcardMaterialGuard.inGuard()) {
            return;
        }
        final MEPatternBufferPartMachineBase machine = this.getMachine();
        final long tick = shanhai$tick(machine);
        final String pos = shanhai$pos(machine);
        // 🔴 wildfix-2：判据行必须能分开"物品侧 / 流体侧"—— 两个 handler 共用一套日志，没有这一位就分不出。
        final boolean itemSide = this.shanhai$isItemHandler(machine);
        String recipeType = null;
        try {
            if (recipe == null) {
                ShanhaiSlotGateLog.noteSkip(tick, pos, slot, null, "null_recipe", null, simulate, itemSide);
                return;
            }
            recipeType = ShanhaiWildcardRecipeBinding.typeIdOf(recipe);
            if (!(machine instanceof StellarSampleAssemblerPartMachine stellar)) {
                // 不是星律 ⇒ 一个字节都不动（但仍计数：这能证明"注入点被别的机器用着，就是没轮到我们"）。
                ShanhaiSlotGateLog.noteSkip(tick, pos, slot, recipeType, "not_stellar", null, simulate, itemSide);
                return;
            }
            // 🔴 2026-10-31（wildfix T3）：**真扣遍之前的联合预检** —— 见 ShanhaiWildcardMaterialGuard 的类注释。
            //    只对「真扣遍 + 物品侧 + 通配符段 + 配方确实要流体」生效；过不了就当场拒（一个料都不扣），
            //    让上游 MEPatternRecipeHandlePart#handleRecipe 的 `&&` 短路去试下一个槽
            //    —— 与"类型闸拒掉一个槽"的语义完全相同，**不是**"静默放行"。
            if (this.shanhai$materialGuardVeto(machine, recipe, simulate, slot, pos, tick, recipeType)) {
                cir.setReturnValue(Boolean.FALSE);
                return;
            }
            final List<String> declared = stellar.shanhaiSlotDeclaredTypes(slot);
            final ShanhaiSlotTypeGate.Outcome outcome = stellar.shanhaiSlotTypeVerdict(slot, recipeType);
            ShanhaiSlotGateLog.noteVerdict(tick, pos, slot, declared, recipeType, outcome, simulate, itemSide);
            // 每台星律机器 200 tick 一条"闸的上游输入"—— TYPEROUTE 全静默时，答案多半在这一行里
            // （machineTypes 为空 ⇒ 仓室读不到控制器 ⇒ shanhaiSlotTypeVerdict 提前放行且不打印）。
            ShanhaiSlotGateLog.maybeMachineDiagnose(tick, pos, stellar);
            if (!outcome.blocked()) {
                return;
            }
            // 拒绝 = "这个槽不接这条配方"；引擎换下一槽（原版语义词，不是"整机失败"）。
            cir.setReturnValue(Boolean.FALSE);
        } catch (Throwable t) {
            // 闸不许把机器弄停：任何异常 ⇒ 当作放行（不 setReturnValue）。
            ShanhaiSlotGateLog.noteSkip(tick, pos, slot, recipeType, "thrown", t.getClass().getName(), simulate, itemSide);
        }
    }

    /**
     * 🔴 <b>2026-10-31（wildfix-2）：这一侧到底返回了什么 + 为什么。</b>
     *
     * <h2>为什么必须有这一行（上一轮实机把它暴露得很清楚）</h2>
     * 实机读数：{@code sim={{true=695, false=3}}}、{@code WILDCACHE} 一行都没有、{@code slot=1132} 只有
     * {@code sim=true} 的判定行，而 {@code slot=2/3/1}（普通槽）有 {@code sim=false}。字节码已定死两件事：
     * <ol>
     *   <li>{@code setSlotCache = RecipeCacheStrategy.cacheToMEInternal}，而
     *       {@code matchRecipeInput}(模拟) 与 {@code handleRecipeInput}(真扣) 走的是
     *       {@code RecipeRunnerHelper.handleRecipe(...)} 的<b>同一个重载</b> ⇒ {@code FULL_CACHE(true,true)}
     *       ⇒ {@code setSlotCache == true}（所以"没登记"不是 setSlotCache 的锅）；</li>
     *   <li>因此 {@code WILDCACHE=0} 只能是：{@code MEPatternRecipeHandlePart#handleRecipe:136}
     *       <b>根本没被执行到</b> —— 循环里那句 {@code &&} 先在物品侧或流体侧返回了 false。</li>
     * </ol>
     * HEAD 注入只能证明"闸放行"，证明不了"这一侧接住了" ⇒ <b>本 RETURN 行就是缺的那一格拼图</b>：
     * 打出某侧 {@code ret} 与逐子闸读数（{@code active / catalyst / virtual / items / fluids / stockCfg}）。
     *
     * <h2>取舍</h2>
     * 只对<b>通配符段</b>（{@code slot >= 1080}）打；同一 {@code (pos,slot,recipeType,sim,cap,ret)} 20 tick 一条；
     * 全局前若干条必打、之后抽样（见 {@code ShanhaiSlotGateLog.noteReturn}）。永不抛、永不改返回值。
     */
    @Inject(method = "meHandleRecipeInner", at = @At("RETURN"), require = 1, remap = false)
    private void shanhai$slotGateReturn(GTRecipe recipe, Object2LongMap<?> left, boolean simulate, int slot,
                                       CallbackInfoReturnable<Boolean> cir) {
        if (ShanhaiWildcardMaterialGuard.inGuard()) {
            return;
        }
        if (slot < ShanhaiWildcardLayout.PATTERN_SLOTS) {
            return;
        }
        try {
            final MEPatternBufferPartMachineBase machine = this.getMachine();
            if (!(machine instanceof StellarSampleAssemblerPartMachine stellar)) {
                return;
            }
            final long tick = shanhai$tick(machine);
            final String pos = shanhai$pos(machine);
            final String recipeType = recipe == null ? null : ShanhaiWildcardRecipeBinding.typeIdOf(recipe);
            final boolean itemSide = this.shanhai$isItemHandler(machine);
            final boolean ret = cir.getReturnValueZ();
            final String why = ret ? "" : stellar.shanhaiDiagnoseSlotGate(slot, recipe, simulate, itemSide);
            ShanhaiSlotGateLog.noteReturn(tick, pos, slot, recipeType, simulate, itemSide, ret, why);
        } catch (Throwable ignored) {
            // 判据行永远不许影响配方路径
        }
    }

    /** 本实例是不是<b>物品</b>侧 handler（{@code getMERecipeHandlerTraits().left()} 就是那对里同一个对象）。 */
    private boolean shanhai$isItemHandler(MEPatternBufferPartMachineBase machine) {
        try {
            final it.unimi.dsi.fastutil.Pair<?, ?> pair = machine.getMERecipeHandlerTraits();
            return pair != null && pair.left() == (Object) this;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 🔴 <b>2026-10-31（wildfix T3）：扣料前的联合预检</b>（详细理由见 {@link ShanhaiWildcardMaterialGuard}）。
     *
     * <h2>它插在哪一步</h2>
     * 上游 {@code MEPatternRecipeHandlePart#handleRecipe}（{@code temp\wildbug\dec\src-out6\...:132-142}）：
     * <pre>
     *   (!hasItemx || itemHandler.meHandleRecipe(recipe, simulate, slot))     ← 本方法在这个调用的 HEAD
     *   &amp;&amp; (!hasFluid || fluidHandler.meHandleRecipe(recipe, simulate, slot))  ← 扣完物品才轮到它
     * </pre>
     * ⇒ 在物品侧 HEAD 先把<b>流体侧以 {@code simulate=true}</b> 试一遍：过不了就拒，<b>一个物品都不扣</b>。
     *
     * <h2>五道闸（缺一条都可能把能跑的配方拦掉 —— 每条的"拿不到就放行"都写在这里）</h2>
     * <ol>
     *   <li>{@code simulate} ⇒ 不预检（模拟遍不动库存）；</li>
     *   <li>槽号 {@code < ShanhaiWildcardLayout.PATTERN_SLOTS} ⇒ 不预检（<b>普通槽逐位不变</b>，回归红线）；</li>
     *   <li>本实例不是<b>物品</b>侧 handler ⇒ 不预检（流体侧被问到时物品已经扣过了，拦不住也不该拦）；</li>
     *   <li>配方<b>没有流体输入</b> ⇒ 不预检（没有流体能力就没有"半扣"，而且此时流体 handler 里那份
     *       {@code preparedMEHandleContents} 可能是<b>上一条配方</b>留下的 —— 拿它判这一条就是错的）；</li>
     *   <li>流体侧那份"待扣内容"为空 / 与这条配方的流体条目数对不上 ⇒ 不预检（拿不准就不动，宁可漏拦）。</li>
     * </ol>
     * 另：回退位 {@code SHANHAI_DISABLE_MATERIAL_GUARD=1} ⇒ 整段不生效；反调期间的异常 ⇒ 一律不否决。
     *
     * @return {@code true} = 否决这一次物品侧真扣
     */
    private boolean shanhai$materialGuardVeto(MEPatternBufferPartMachineBase machine, GTRecipe recipe,
                                             boolean simulate, int slot, String pos, long tick,
                                             String recipeType) {
        if (simulate || recipe == null) {
            return false;
        }
        if (slot < ShanhaiWildcardLayout.PATTERN_SLOTS) {
            return false;
        }
        if (ShanhaiWildcardMaterialGuard.disabled()) {
            return false;
        }
        final java.util.List<?> fluidContents;
        try {
            fluidContents = recipe.getInputContents(FluidRecipeCapability.CAP);
        } catch (Throwable t) {
            return false;
        }
        if (fluidContents == null || fluidContents.isEmpty()) {
            return false;
        }
        final org.gtlcore.gtlcore.api.capability.IMERecipeHandler<?, ?> fluidHandler;
        try {
            final it.unimi.dsi.fastutil.Pair<?, ?> pair = machine.getMERecipeHandlerTraits();
            if (pair == null || pair.left() != (Object) this) {
                // 不是物品侧（或者是拿不到 pair 的怪状态）⇒ 不预检。
                return false;
            }
            if (!(pair.right() instanceof org.gtlcore.gtlcore.api.capability.IMERecipeHandler<?, ?> fh)) {
                return false;
            }
            fluidHandler = fh;
        } catch (Throwable t) {
            return false;
        }
        boolean fluidOk;
        try {
            final Object2LongMap<?> prepared = fluidHandler.getPreparedMEHandleContents();
            if (prepared == null || prepared.isEmpty() || prepared.size() != fluidContents.size()) {
                // 拿不准这份"待扣内容"是不是这一条配方的 ⇒ 不预检（漏拦好过误拦）。
                return false;
            }
            if (!ShanhaiWildcardMaterialGuard.enterGuard()) {
                return false;
            }
            try {
                fluidOk = fluidHandler.meHandleRecipe(recipe, true, slot);
            } finally {
                ShanhaiWildcardMaterialGuard.exitGuard();
            }
        } catch (Throwable t) {
            return false;
        }
        final boolean veto = ShanhaiWildcardMaterialGuard.vetoItemPass(false, true, true, true, fluidOk);
        if (veto) {
            ShanhaiSlotGateLog.noteSkip(tick, pos, slot, recipeType, "material_guard",
                    ShanhaiWildcardMaterialGuard.describe(true, false), simulate, true);
        }
        return veto;
    }

    /**
     * 🔴 <b>2026-10-30 22:0x：门铃注入已删除（它会崩服，而且信息是冗余的）。</b>
     *
     * <h2>为什么删</h2>
     * 第一版把 handler 写成 `shanhai$slotGateDoorbell()` <b>无参</b>，而目标方法
     * `getActiveSlots()` 返回 `int[]` ⇒ Mixin 在 APPLY 阶段抛
     * <pre>
     * InvalidInjectionException: Invalid descriptor on
     *   ShanhaiSlotTypeGateMixin->@Inject::shanhai$slotGateDoorbell()V
     * ⇒ MixinTransformerError ⇒ 「Exception in server tick loop」直接把游戏崩掉
     * （crash-2026-10-30_19.09.49-server.txt）
     * </pre>
     * 虽然改成 `CallbackInfoReturnable<int[]>` 就能过，但它的<b>唯一用途</b>是让 `calls=0` 也能落盘；
     * 而 {@code ShanhaiSlotGateLog.loaded(...)} 是"第一次进入判定注入点"时打的 ——
     * <b>它不出现，就等于"引擎一次都没把配方递到这里"，信息完全一样</b>。
     * ⇒ 少一个注入点就少一处能把游戏崩掉的机关。
     */

    /** 世界时间（tick）；读不到就退回墙钟（1 tick = 50ms），只为让节流/汇总有个连续基准。 */
    private static long shanhai$tick(MEPatternBufferPartMachineBase machine) {
        try {
            if (machine != null) {
                final Level level = machine.getLevel();
                if (level != null) {
                    return level.getGameTime();
                }
            }
        } catch (Throwable ignored) {
            // 掉到下面的墙钟
        }
        return System.currentTimeMillis() / 50L;
    }

    /** {@code pos=x,y,z}（读不到就 {@code ?}）。 */
    private static String shanhai$pos(MEPatternBufferPartMachineBase machine) {
        try {
            if (machine != null) {
                final BlockPos p = machine.getPos();
                if (p != null) {
                    return p.getX() + "," + p.getY() + "," + p.getZ();
                }
            }
        } catch (Throwable ignored) {
            // 掉到下面的 ?
        }
        return "?";
    }

/**
 * <b>闸的日志闸</b>（普通类，不是 mixin —— 所以只有一份静态状态，两个目标类共用一套计数）。
 *
 * <p>纪律：
 * <ul>
 *   <li>永远不抛（日志类抛异常会把机器的配方路径带崩）；</li>
 *   <li>节流在<b>拼字符串之前</b>判断（热路径，见 {@link #noteVerdict}）；</li>
 *   <li>计数用普通 {@code long}：本路径只在服务端主线程跑；万一多线程，最坏是少记一条日志，不影响判定。</li>
 * </ul>
 */

    public static final class ShanhaiSlotGateLog {

            private static final Logger LOG = LogUtils.getLogger();
            private static final String P = "[SHANHAI-SLOTGATE]";

        /** 同一 (pos,slot,recipeType) 的判定行最多 20 tick 一条。 */
            private static final int PER_KEY_TICKS = 20;
        /** 汇总行 200 tick 一条。 */
            private static final int SUMMARY_TICKS = 200;
        /** 判据串（machineTypes / gate 状态）200 tick 一条 —— 它要读机器侧缓存，比判定行贵。 */
            private static final int DIAG_TICKS = 200;
        /** 节流键表上限（防无界增长；超了就整表清空，最坏是下一轮多打几条）。 */
            private static final int MAX_KEYS = 4096;

            private static boolean loadedOnce;
            private static long calls;
            private static long allowCount;
            private static long denyCount;
            private static long skipped;
            private static long skipNotStellar;
            private static long skipNullRecipe;
            private static long skipThrown;
            /** T3 预检否决的次数（{@code >0} ⇒ 有槽因为"流体过不了"被拦在扣料之前）。 */
            private static long skipMaterialGuard;
            /** 判定行里 {@code simulate=true} / {@code false} 各自的条数（T1；判读见报告）。 */
            private static long simTrue;
            private static long simFalse;
            private static long doorbellCount;
            private static long lastSummaryTick = Long.MIN_VALUE;

            private static final Map<ShanhaiSlotTypeGate.Outcome, long[]> BY_VERDICT =
                new EnumMap<>(ShanhaiSlotTypeGate.Outcome.class);
            private static final ConcurrentHashMap<String, Long> LAST = new ConcurrentHashMap<>();

        static {
            for (final ShanhaiSlotTypeGate.Outcome o : ShanhaiSlotTypeGate.Outcome.values()) {
                BY_VERDICT.put(o, new long[1]);
            }
        }

            private ShanhaiSlotGateLog() {}

        /** 启动期一次性行（第一次进注入点 / 第一次响门铃时打；只打一次）。 */
            public static void ensureLoaded(String first) {
            if (loadedOnce) {
                return;
            }
            synchronized (ShanhaiSlotGateLog.class) {
                if (loadedOnce) {
                    return;
                }
                loadedOnce = true;
                try {
                    LOG.info("{} loaded=true targets=item+fluid env={} first={} java={}", P,
                            ShanhaiSlotTypeGate.envStatus(), first, System.getProperty("java.version"));
                } catch (Throwable ignored) {
                    // 日志类永远不抛
                }
            }
        }

        /** 门铃：引擎枚举活跃槽（只计数 + 驱动汇总）。 */
            public static void doorbell(long tick) {
            doorbellCount++;
            ensureLoaded("getActiveSlots");
            maybeSummary(tick);
        }

        /** {@code RETURN} 判据行最多打多少条（之后每 64 条抽 1 条）—— 防把日志刷爆。 */
            public static final int RETURN_LOG_BUDGET = 400;

        /** 已经打出的 {@code RETURN} 行数（只增）。 */
            private static long returnLines;

        /**
         * <b>这一侧接住了没有</b>：{@code meHandleRecipeInner} 的 RETURN 判据行。
         *
         * <p>行形（{@code grep 'verdict=RET' } 一行就能判）：
         * <pre>
         *   [SHANHAI-SLOTGATE] pos=… slot=1132 sim=true cap=item ret=false
         *       diag=[cap=item active=true catalyst=true virtual=true items=1 fluids=0 stockCfg=false findItem=0]
         *   [SHANHAI-SLOTGATE] pos=… slot=1132 sim=true cap=item ret=true
         * </pre>
         * 判读：<b>两侧都 ret=true</b> ⇒ 上游 {@code handleRecipe} 必然 {@code return slot} ⇒ 必须跟着出现
         * {@code WILDCACHE reg slot=…}；某一侧 {@code ret=false} ⇒ 这一侧就是"输入材料不足"的真凶，
         * 而 {@code diag=} 里第一个为 {@code false} 的字段就是具体哪一道子闸
         * （{@code active} = isItemActive/isFluidActive；{@code catalyst}/{@code virtual} = 只在模拟遍跑的
         * 催化剂/虚拟料闸；都 true 则真凶在 {@code handleItemInternal/handleFluidInternal} 内部的
         * {@code getBest…Match} 找不到匹配）。
         */
            public static void noteReturn(long tick, String pos, int slot, String recipeType, boolean simulate,
                                boolean itemSide, boolean ret, String diag) {
            ensureLoaded("meHandleRecipeInner");
            final long n = ++returnLines;
            if (n > RETURN_LOG_BUDGET && (n & 63) != 0) {
                return;
            }
            final String key = "r#" + pos + '#' + slot + '#' + recipeType + '#' + simulate + '#' + itemSide + '#' + ret;
            if (throttled(key, tick)) {
                return;
            }
            try {
                LOG.info("{} pos={} slot={} sim={} cap={} ret={}{} #{}", P, pos, Integer.valueOf(slot),
                        Boolean.valueOf(simulate), itemSide ? "item" : "fluid", Boolean.valueOf(ret),
                        (diag == null || diag.isEmpty() ? "" : " diag=[" + diag + ']'), Long.valueOf(n));
            } catch (Throwable ignored) {
                // 日志类永远不抛
            }
        }

        /** 一次<b>真正的判定</b>（七种 verdict 之一）。 */
            public static void noteVerdict(long tick, String pos, int slot, List<String> declared, String recipeType,
                                ShanhaiSlotTypeGate.Outcome outcome, boolean simulate, boolean itemSide) {
            ensureLoaded("meHandleRecipeInner");
            calls++;
            if (outcome.allows()) {
                allowCount++;
            } else {
                denyCount++;
            }
            // 🔴 2026-10-31（wildfix T1）：把 simulate 单独计数 —— 「料被谁吃掉」只看真扣遍，
            //    所以 `sim=false` 的条数与 `sim=true` 的条数必须能分开读（判读口径见报告 T1）。
            if (simulate) {
                simTrue++;
            } else {
                simFalse++;
            }
            final long[] cell = BY_VERDICT.get(outcome);
            if (cell != null) {
                cell[0]++;
            }
            final String key = "v#" + pos + '#' + slot + '#' + recipeType + '#' + simulate;
            if (throttled(key, tick)) {
                maybeSummary(tick);
                return;
            }
            try {
                LOG.info("{} cap={} {}", P, itemSide ? "item" : "fluid", ShanhaiSlotTypeGate.line(pos, slot, Boolean.valueOf(simulate), declared,
                        recipeType, outcome));
            } catch (Throwable ignored) {
                // 日志类永远不抛
            }
            maybeSummary(tick);
        }

        /** 一次<b>短路</b>（不是判定：不是星律机器 / 配方为空 / 抛出 / T3 预检否决）。 */
            public static void noteSkip(long tick, String pos, int slot, String recipeType, String reason, String detail,
                                boolean simulate, boolean itemSide) {
            ensureLoaded("meHandleRecipeInner");
            skipped++;
            if ("not_stellar".equals(reason)) {
                skipNotStellar++;
            } else if ("null_recipe".equals(reason)) {
                skipNullRecipe++;
            } else if ("thrown".equals(reason)) {
                skipThrown++;
            } else if ("material_guard".equals(reason)) {
                skipMaterialGuard++;
            }
            final String key = "s#" + reason + '#' + pos + '#' + slot + '#' + recipeType + '#' + simulate;
            if (throttled(key, tick)) {
                maybeSummary(tick);
                return;
            }
            try {
                final String line = ShanhaiSlotTypeGate.skipLine(pos, slot, Boolean.valueOf(simulate), recipeType, reason)
                        + (detail == null ? "" : " detail=" + detail);
                LOG.info("{} cap={} {}", P, itemSide ? "item" : "fluid", line);
            } catch (Throwable ignored) {
                // 日志类永远不抛
            }
            maybeSummary(tick);
        }

        /**
         * 每台星律机器 200 tick 一条"闸的上游输入"。
         *
         * <p>为什么要这一行：{@code shanhaiSlotTypeVerdict} 里有两条<b>提前放行且不打印任何日志</b>的分支
         * （槽不是样板槽/展开槽、或者 {@code shanhaiHostRecipeTypeIds} 为空 = 仓室读不到控制器）。
         * 若线上 {@code TYPEROUTE} 一条都没有，最可能的原因就是这两条之一 —— 本行把它们的状态直接摊开。
         */
            public static void maybeMachineDiagnose(long tick, String pos, StellarSampleAssemblerPartMachine stellar) {
            final String key = "d#" + pos;
            if (throttled(key, tick, DIAG_TICKS)) {
                return;
            }
            try {
                LOG.info("{} machine pos={} at={}", P, pos, stellar.shanhaiTypeBlockDescribe());
            } catch (Throwable ignored) {
                // 日志类永远不抛
            }
        }

            private static boolean throttled(String key, long tick) {
            return throttled(key, tick, PER_KEY_TICKS);
        }

        /** true = 还在节流窗口里，不要打。 */
            private static boolean throttled(String key, long tick, int window) {
            final Long prev = LAST.get(key);
            if (prev != null && tick - prev < window) {
                return true;
            }
            if (LAST.size() > MAX_KEYS) {
                LAST.clear();
            }
            LAST.put(key, tick);
            return false;
        }

            private static void maybeSummary(long tick) {
            if (lastSummaryTick != Long.MIN_VALUE && tick - lastSummaryTick < SUMMARY_TICKS) {
                return;
            }
            lastSummaryTick = tick;
            try {
                LOG.info("{} calls={} allow={} deny={} byVerdict={} sim={{true={}, false={}}} doorbell={} skipped={}"
                                + " skip={not_stellar={} null_recipe={} thrown={} material_guard={}} retLines={}",
                        P, calls, allowCount, denyCount, byVerdict(), simTrue, simFalse, doorbellCount, skipped,
                        skipNotStellar, skipNullRecipe, skipThrown, skipMaterialGuard, returnLines);
            } catch (Throwable ignored) {
                // 日志类永远不抛
            }
        }

            private static String byVerdict() {
            final StringBuilder sb = new StringBuilder("{");
            for (final Map.Entry<ShanhaiSlotTypeGate.Outcome, long[]> e : BY_VERDICT.entrySet()) {
                if (sb.length() > 1) {
                    sb.append(", ");
                }
                sb.append(e.getKey().verdict()).append('=').append(e.getValue()[0]);
            }
            return sb.append('}').toString();
        }
    }

}
