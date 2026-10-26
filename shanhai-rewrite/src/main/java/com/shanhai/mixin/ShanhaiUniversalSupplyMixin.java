package com.shanhai.mixin;

import com.gregtechceu.gtceu.api.machine.feature.multiblock.IDistinctPart;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableMultiblockMachine;
import com.gregtechceu.gtceu.api.machine.trait.IRecipeHandlerTrait;
import com.shanhai.api.machine.IShanhaiNormalSupplyPart;
import com.shanhai.api.machine.ShanhaiNormalSupplyBridge;
import org.gtlcore.gtlcore.api.machine.trait.MEPart.IMEFilterIOPartMachine;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 「<b>万能仓室</b>」注入点：让<b>带 {@link IShanhaiNormalSupplyPart} 标记的 ME 仓室</b>
 * （当前只有星律样板总成 {@code shanhai:stellar_sample_assembler}）在<b>保留 ME 身份</b>的同时，
 * 把它声明的那几份 handler 也注册进 gtlcore 的「<b>普通供料名单</b>」。
 *
 * <h2>一、为什么是这一个注入点（ｊavap 实证，不是猜）</h2>
 * gtlcore 把 {@code gtceu} 的 {@code WorkableMultiblockMachine} 整个接管了，
 * 供料结构的<b>唯一重建入口</b>是它自己加的 {@code upDate()V}：
 * <pre>
 *   org.gtlcore.gtlcore.mixin.gtm.api.machine.WorkableMultiblockMachineMixin#upDate:()V
 *         0: aload_0
 *         1: getfield      isFormed:Z                 ← 未成型直接 return
 *         4: ifne 8 / 7: return
 *         8: aload_0
 *        10: getfield      traitSubscriptions         → 参数 1
 *        14: invokevirtual getParts:()Ljava/util/List;  ← ★ 本 mixin @Redirect 的就是这一句
 *        17: aload_0
 *        18: getfield      recipeLogic                → 参数 3
 *        21: aload_0
 *        22: getfield      isDistinct:Z                → 参数 4
 *        25: invokevirtual rebuildRecipeHandleParts:(Ljava/util/List;Ljava/util/List;LRecipeLogic;Z)V
 * </pre>
 * 而 {@code rebuildRecipeHandleParts} 的分流是（同一方法内偏移，{@code javap -p -c} 原文）：
 * <pre>
 *   43: aload 7                                     ← 当前 part
 *   45: instanceof      IMEFilterIOPartMachine       ← ★ 所有 ME 仓室都在这里被分走
 *   48: ifeq 69
 *   51..61: handleMETraitPart(subscriptions, recipeLogic, me)   （偏移 63 调用）
 *   66: goto 104
 *   69: instanceof FluidHatchPartMachine / IDistinctPart …
 *   92: invokevirtual   handleNormalPart:(LIMultiPart;ZLjava/util/List;)V  ← 只有非 ME 仓室才走
 * </pre>
 * ⇒ ME 仓室<b>永远</b>走不到 {@code handleNormalPart}（那里才 {@code part.getRecipeHandlers()}）。
 *
 * <p>🔴 <b>还有第二道门</b>（同一方法内偏移，别漏）：不是 ME 仓室之后，
 * <b>也不是所有 part 都能进 {@code handleNormalPart}</b>：
 * <pre>
 *   69: aload 7 / 71: instanceof FluidHatchPartMachine
 *   74: ifne 85
 *   77: aload 7 / 79: instanceof IDistinctPart
 *   82: ifeq 98                                    ← 两个都不是 ⇒ 96/98 handleSpecialPart（对它是空操作）
 *   85: invokevirtual handleNormalPart:(LIMultiPart;ZLjava/util/List;)V   ← 这里是 92
 *   98: invokevirtual handleSpecialPart:(LIMultiPart;)V
 * </pre>
 * 实证依据（{@code javap} 类头原文）：{@code ItemBusPartMachine implements IDistinctPart}，
 * 而 {@code FluidHatchPartMachine} <b>不</b>实现 {@code IDistinctPart}（所以上游单独列了它）。
 * ⇒ <b>视图必须实现 {@link IDistinctPart}</b>，否则会被当成「特殊仓室」吞掉（观察不到任何效果）。
 * 本 mixin 让视图的 {@code isDistinct() == true}，于是它按「<b>独立输入仓</b>」的语义被处理
 * （物品自成一组，见 {@code shanhai$normalSupplyView} 里那一行的长注释）。
 *
 * <h2>二、本 mixin 做了什么（只做一件事）</h2>
 * 把 {@code upDate()} 里 {@code getParts()} 的<b>返回值</b>换成一个「每个带标记 ME 仓室后面
 * 多插一个视图」的列表——<b>原元素一个不少、顺序不变</b>。于是 gtlcore 自己的循环会：
 * <ul>
 *   <li>对<b>原元素</b>：仍然 {@code instanceof IMEFilterIOPartMachine} ⇒ 走它自己的
 *       {@code handleMETraitPart}（ME 身份、样板认领、缓冲变更监听<b>全部照旧</b>）；</li>
 *   <li>对<b>追加的视图</b>：不是 ME 仓室 ⇒ 落进它自己的 {@code handleNormalPart(...)}
 *       ⇒ <b>IO 归类、{@code isProxy()} 过滤、共享输入池、流体二次归并全部由 gtlcore 原生代码跑</b>，
 *       本 mixin 一行都没复制。</li>
 * </ul>
 *
 * <h2>三、🔴 三条安全边界（代码里逐条标了，改代码前先看这里）</h2>
 * <ol>
 *   <li><b>只放行带标记的仓室</b>。没有带标记的 part ⇒ {@code expandForNormalSupply} 返回
 *       <b>同一个 {@code List} 实例</b> ⇒ 未标记的 ME 仓室走的列表与改前<b>完全相同</b>（逐字节不变）；
 *       {@code marked == 0} 这条路径上本类除了一个 {@code volatile}/{@code AtomicBoolean}
 *       读 + 一次 trySet 之外没有任何副作用。</li>
 *   <li><b>只放行它自己声明的 handler</b>。视图的 {@code getRecipeHandlers()} ==
 *       {@code IShanhaiNormalSupplyPart#shanhaiNormalSupplyHandlers()}，
 *       <b>绝不</b>回读 {@code part.getRecipeHandlers()} ⇒ 样板槽 / 样板库存 / 催化剂 / 电路配置
 *       <b>结构上不可能</b>进入普通供料名单。另外还要求 part <b>同时是</b>
 *       {@link IMEFilterIOPartMachine}：不是 ME 仓室的 part 本来就走普通分支，
 *       不需要（也不该）再插一个视图 —— 不然它的 handler 会被算两遍。</li>
 *   <li><b>不 @Overwrite、不碰别的分支</b>。全类只有一个 {@code @Redirect}（替换
 *       {@code getParts()} 的返回值），{@code require = 0}（找不到目标就<b>静默跳过</b>，
 *       不会把游戏崩在类加载期）。没有 {@code @Shadow}、没有 {@code @Unique} 字段、
 *       没有改写 gtlcore 的任何集合。</li>
 * </ol>
 *
 * <h2>四、为什么 {@code priority = 1500}</h2>
 * 目标方法 {@code upDate} 是 <b>gtlcore 的 mixin 自己加进目标类的</b>方法（gtceu 原类里没有）。
 * Mixin 官方文档（{@code @Mixin#priority}，0.8.5 原文）：
 * 「<i>Priorities sort mixins into natural order with <b>lower priority mixins being applied first</b>.</i>」
 * ⇒ gtlcore 用默认 1000，本 mixin 必须<b>更大</b>才会在它<b>之后</b>应用（那时 {@code upDate} 已经在
 * 目标类里，注入才找得到）。<b>这是本文件唯一的版本耦合点</b>，见交付报告「升级重核清单」。
 *
 * <h2>五、P5 实机验收判据（日志）</h2>
 * <ul>
 *   <li>任意一台多方块成型 ⇒ 应有 <b>一行</b>
 *       {@code [SHANHAI-UNIVERSAL] 注入生效：upDate#getParts 已被接管}。
 *       <b>没有这行 ⇒ 注入没挂上</b>（先核 {@code method/target}，别先怀疑星律）。</li>
 *   <li>星律装进主机并成型 ⇒ 应有 {@code [SHANHAI-UNIVERSAL] 普通供料注入：parts A → B}，
 *       且 {@code B = A + 1}。</li>
 * </ul>
 */
@Mixin(value = WorkableMultiblockMachine.class, priority = 1500, remap = false)
public abstract class ShanhaiUniversalSupplyMixin {


    /**
     * 替换 {@code upDate()} 里 {@code getParts()} 的返回值。
     *
     * <p>⚠️ 这是<b>替换</b>型注入（{@code @Redirect}）：所以本方法<b>必须</b>自己把原值取出来
     * （{@code self.getParts()}）并在不需要改动时<b>原样返回它</b>。
     *
     * <p>⚠️ 第一个参数是「被调用方法的宿主对象」——{@code getParts()} 在字节码里的宿主是
     * gtlcore 的 mixin 类（它继承 {@code MultiblockControllerMachine}），Mixin 会把宿主重映射到
     * 目标类；两种形态都是 {@code MultiblockControllerMachine} 的子类，所以这里用它接 +
     * {@link Coerce} 让 Mixin 插 cast 而不做严格签名比对。
     *
     * @param self 目标多方块（运行时就是 {@code WorkableMultiblockMachine} 实例）
     * @return 原列表（没有带标记的仓室时，<b>同一个实例</b>）或「原元素 + 视图」的新列表
     */
    @Redirect(
            method = "upDate()V",
            at = @At(value = "INVOKE", target = "getParts()Ljava/util/List;"),
            remap = false,
            require = 0)
    private List<IMultiPart> shanhai$expandForNormalSupply(WorkableMultiblockMachine self) {
        final List<IMultiPart> parts = self.getParts();

        // 🔴 这里用 lambda 而不是方法引用：gtlcore 自己的 class 里 lambda 体
        //    （lambda$handleNormalPart$1/2/3 + InvokeDynamic 的 MethodHandle）是被 Mixin 正常
        //    重映射过的既有形态，照抄这个形态最稳。
        final List<IMultiPart> expanded = ShanhaiNormalSupplyBridge.expandForNormalSupply(
                parts,
                part -> shanhai$isMarkedMePart(part),   // 判定只有一处（见该方法注释）
                part -> shanhai$normalSupplyView(part));

        // 没有任何带标记的 ME 仓室 ⇒ expand 把入参原样返回（同一个实例）⇒ 这里也原样返回。
        final boolean touched = expanded != parts;
        final int views = touched ? expanded.size() - parts.size() : 0;

        if (ShanhaiNormalSupplyBridge.claimFirstHookLog()) {
            // 这一行是「注入到底挂没挂上」的唯一判据，只打一次。
            LoggerFactory.getLogger("shanhai/universal-supply").info(
                    "[SHANHAI-UNIVERSAL] 注入生效：upDate#getParts 已被接管（machine={}, parts={}, 插入了 {} 个普通供料视图）",
                    self.getClass().getName(), parts == null ? -1 : parts.size(), views);
        }
        if (views > 0 && ShanhaiNormalSupplyBridge.claimFirstExpansionLog()) {
            LoggerFactory.getLogger("shanhai/universal-supply").info(
                    "[SHANHAI-UNIVERSAL] 普通供料注入：parts {} → {}（多出来的是只含声明 handler 的视图；样板库存不在其中）",
                    parts.size(), expanded.size());
        }
        return expanded;
    }

    /**
     * 判定「这个 part 要不要追加一个普通供料视图」。
     *
     * <p>🔴 两个条件<b>都要</b>：带我们的标记 <b>且</b> 本来就是 ME 仓室
     * （{@code IMEFilterIOPartMachine}）。理由：非 ME 仓室本来就走
     * {@code handleNormalPart}，再插一个视图就是<b>把它的 handler 算两遍</b>（会影响到别的机器，
     * 属于越权）。
     */
    @Unique
    private static boolean shanhai$isMarkedMePart(IMultiPart part) {
        return part instanceof IShanhaiNormalSupplyPart && part instanceof IMEFilterIOPartMachine;
    }

    /**
     * 给一台带标记的 ME 仓室造「普通供料视图」：<b>只把 {@code getRecipeHandlers()} 换成标记接口
     * 声明的那几份</b>，并让它以「<b>独立输入仓</b>」的身份被 gtlcore 处理，其余全部转发给真实仓室。
     *
     * <p>🔴 安全边界 2 就落在 {@code "getRecipeHandlers"} 这一行上：视图读的是
     * {@code shanhaiNormalSupplyHandlers()}，<b>不是</b> {@code part.getRecipeHandlers()}
     * ⇒ 样板库存/催化剂在结构上不可能被 gtlcore 看到。
     *
     * <p>另外两个覆盖项是<b>为了让 gtlcore 收下它</b>（不是业务逻辑）：
     * <ul>
     *   <li>{@code isDistinct -> TRUE}（<b>2026-10-26 五修</b>，原来给的是 {@code FALSE}）：
     *       普通分支门栅要 {@code IDistinctPart}，而「本台自成一组」那条分支（偏移 <b>292–348</b>）
     *       还要 {@code isDistinct()==true}。<b>给 {@code true} ⇒ 物品 handler 进
     *       {@code normalCapabilities[IO.IN]}（偏移 315），成为自成一组、<b>无条件存活</b>的 handle part</b>；
     *       给 {@code false} ⇒ 只能寄生于整机共享输入池（偏移 361 → 120），而共享池的存活条件是
     *       「主机 {@code isDistinct==false} 且共享清单非空」（偏移 107–137）—— 一旦共享池为 {@code null}，
     *       {@code RecipeRunner} 三条匹配路径<b>全都是「不问就判失败」</b>，表观就是「输入材料不足」。
     *       <br>🔴 <b>范围只到物品</b>：偏移 292 那条分支只把物品放进独立组，<b>流体仍走偏移 349 进共享池</b>。
     *       这是<b>刻意</b>的（当前故障是物品配方；扩到流体要另外取证 + 动 gtlcore 层），<b>别顺手改</b>。</li>
     *   <li>{@code setDistinct -> null}：no-op。视图只活在本次 rebuild 里，
     *       而且真实仓室并不实现 {@code IDistinctPart}（转发过去会抛 NoSuchMethodException）。</li>
     * </ul>
     *
     * @return 视图；标记接口返回空列表时返回 {@code null}（= 不插入，等于没标记）
     */
    @Unique
    private static IMultiPart shanhai$normalSupplyView(IMultiPart part) {
        final List<IRecipeHandlerTrait> declared = ((IShanhaiNormalSupplyPart) part).shanhaiNormalSupplyHandlers();
        if (declared == null || declared.isEmpty()) {
            return null;
        }
        final List<IRecipeHandlerTrait> only = new ArrayList<>(declared.size());
        for (int i = 0, n = declared.size(); i < n; i++) {
            final IRecipeHandlerTrait handler = declared.get(i);
            if (handler != null) {
                only.add(handler);
            }
        }
        if (only.isEmpty()) {
            return null;
        }
        // 快照一份：视图活在整个 rebuild 期间，别让调用方后续改 list 影响到它。
        final List<IRecipeHandlerTrait> snapshot = List.copyOf(only);
        final Map<String, Supplier<?>> overrides = Map.of(
                "getRecipeHandlers", () -> snapshot,
                // 🔴🔴 2026-10-27（C5，用户口径）：**改回 FALSE —— 并入普通仓室的池**。
                //
                //  【静态证明：改回 FALSE 之后它仍在 handleNormalPart 的候选里】
                //  org.gtlcore.gtlcore.mixin.gtm.api.machine.WorkableMultiblockMachineMixin
                //  （{@code javap -p -c} 原文，偏移全部可复现；取证文件
                //    temp\poolsplit\javap\WorkableMultiblockMachineMixin.txt）：
                //
                //    handleNormalPart(IMultiPart part, boolean hostDistinct, List sharedList)
                //      71–196  part.getRecipeHandlers() 逐个按 capability 分桶：
                //                list4 = 流体 IN、list5 = 物品 IN、list6 = OUT（代理 handler 跳过）
                //      199–238 有 OUT ⇒ normalCapabilities[IO.OUT] += RecipeHandlePart.of(OUT, list6)
                //      241–289 hostDistinct==true ⇒ normalCapabilities[IO.IN] += of(IN, list5+list4)
                //      292–358 part instanceof IDistinctPart && isDistinct()==true
                //                 ⇒ normalCapabilities[IO.IN] += of(IN, list5)   ← 只独立了物品
                //      361–378 ★ else 分支（我们改回 FALSE 后走的就是这里）
                //                 sharedList.addAll(list5)  ← 物品 handler 进共享池
                //                 sharedList.addAll(list4)  ← 流体 handler 也进共享池
                //      ⇒ **我们的 7 份 handler 一个不少地进了参数 3 那个池的候选集**；
                //        即使 isDistinct 返回 false，"进普通这一路"本身（偏移 71–196 的分桶）
                //        已经发生 ⇒ 「分组」不决定「会不会被问」，只有「进不进这份名单」才决定。
                //
                //  rebuildRecipeHandleParts（同一类，{@code javap -p -c}）：
                //      85–95   FluidHatchPartMachine | IDistinctPart ⇒ handleNormalPart(part, isDistinct(主机), list5)
                //              —— 我们的视图实现了 IDistinctPart（下面 new Class<?>[]{IDistinctPart.class}）
                //              ⇒ ★ 一定被 handleNormalPart 处理
                //      107–137 if (!hostDistinct && !sharedList.isEmpty())
                //                 sharedInputRecipeHandlePart = RecipeHandlePart.of(IO.IN, sharedList)
                //              —— 而 hostDistinct 的默认值 = **false**
                //                 （WorkableMultiblockMachineMixin.<init> 偏移 5–7: iconst_0; putfield isDistinct）
                //              ⇒ 共享池默认就会被建出来，并被 mergeSharedHandlePart() 并进各 handle part。
                //      然后 getSharedRecipeHandlePart() / getNormalRecipeHandlePart(IO.IN) 都落到
                //      这份名单上（同一类偏移 581/599）⇒ 引擎问"这一路的输入"时问到的就是它。
                "isDistinct", () -> Boolean.FALSE,
                "setDistinct", () -> null);
        return ShanhaiNormalSupplyBridge.viewWithOverrides(
                part, IMultiPart.class, new Class<?>[]{IDistinctPart.class}, overrides);
    }
}
