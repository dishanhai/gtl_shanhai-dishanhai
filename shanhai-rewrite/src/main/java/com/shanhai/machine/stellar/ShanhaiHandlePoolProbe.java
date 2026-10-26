package com.shanhai.machine.stellar;

import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.shanhai.api.machine.ShanhaiSupplyOnlyFluidHandler;
import com.shanhai.api.machine.ShanhaiSupplyOnlyItemHandler;
import org.gtlcore.gtlcore.api.machine.trait.IRecipeCapabilityMachine;
import org.gtlcore.gtlcore.api.machine.trait.RecipeHandlePart;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * <b>HANDLEPOOL</b> 探针：星律结构成型后打<b>一行</b>，回答「承载我们那两份 handler 的
 * {@code RecipeHandlePart} 到底存不存在、在哪一份池子里」。
 *
 * <h2>一、为什么必须是这一行（取证结论，不是推测）</h2>
 * 实机现象是「{@code SUPPLYCHAIN kind=fluid contents n=0 calls[h=0 c=11243]}」——
 * handler 的<b>内容物被轮询上万次</b>，可 {@code handleRecipeInner} <b>一次都没被调</b>，
 * 同时 Jade 报「配方失败原因：输入材料不足」。
 *
 * <p>而 {@code IRecipeHandler#handleRecipe} 的默认实现（{@code javap -p -c}，偏移 <b>0–75</b>）
 * 是<b>纯透传</b>：{@code copyContent} 逐个复制后<b>直接</b>调 {@code handleRecipeInner} 并返回，
 * <b>不看</b> {@code getHandlerIO()}、<b>不看</b> {@code getCapability()}、<b>不看</b> {@code isProxy()}、
 * <b>不看</b> {@code isDistinct()}。⇒ <b>{@code calls=0} 只可能是「含我们 handler 的
 * {@code RecipeHandlePart} 从来没被问」</b>，不可能是「问了但被过滤掉」。
 *
 * <h2>二、那份 part 只有两种存活方式（{@code handleNormalPart} 的两个分支）</h2>
 * <pre>
 *   WorkableMultiblockMachineMixin#handleNormalPart(part, 主机isDistinct, sharedList)
 *     … 241: else if (主机 isDistinct == true)        ⇒ 315 normalCapabilities[IN] 加一份 part
 *     … 292: else if (part instanceof IDistinctPart &amp;&amp; part.isDistinct())
 *             315:   normalCapabilities[IN].add(RecipeHandlePart.of(IN, 物品清单))   ← 只放物品
 *             349:   sharedList.addAll(流体清单)                                   ← 流体仍进共享
 *     … 361: else                                   ⇒ 物品+流体<b>全部</b>进共享池
 * </pre>
 * 而共享池本身还有一道存活闸（{@code rebuildRecipeHandleParts} 偏移 <b>107–137</b>）：
 * <b>只有</b>「主机 {@code isDistinct==false} 且共享清单非空」才建 {@code sharedInputRecipeHandlePart}。
 * 共享池一旦为 {@code null}，{@code RecipeRunner} 三条匹配路径
 * （{@code handleInputNotDistinctNocache} 偏移 <b>38/41/189</b>、
 * {@code handleInputNotDistinct} 偏移 <b>341</b>、{@code handleInputDistinct} 根本只看 normal）
 * <b>全都是「不问就判失败」</b> ⇒ 表观就是「输入材料不足」。
 *
 * <p>⇒ 我们要的读数就四个：{@code hostDistinct} / {@code shared} 存不存在 /
 * {@code normalIn} 里有哪些 handler（按<b>真实迭代顺序</b>）/ 我们在不在里面。
 *
 * <h2>三、为什么这一行能看见「别的仓室」（不用新增任何访问器）</h2>
 * 全部走 gtlcore 已经暴露的<b>公开</b>方法：
 * <ul>
 *   <li>{@link IRecipeCapabilityMachine#isDistinct()} —— 主机那个 boolean；</li>
 *   <li>{@link IRecipeCapabilityMachine#getSharedRecipeHandlePart()} —— 共享池（{@code null} = 没有）；</li>
 *   <li>{@link IRecipeCapabilityMachine#getNormalRecipeHandlePart(IO)} —— 各仓室「自成一组」的 part 列表；</li>
 *   <li>{@link IRecipeCapabilityMachine#getMEPatternRecipeHandleParts()} —— 星律自己那一路（ME 样板）；</li>
 *   <li>{@code RecipeHandlePart#getHandlerFastIterable()} —— <b>该 part 里每一份 handler，
 *       按 capability 分组、组内按真实迭代顺序</b>（{@code javap} 确认它是 {@code public}，
 *       返回 fastutil 的 {@code ObjectIterable<Entry<RecipeCapability<?>, List<IRecipeHandler<?>>>>}）。</li>
 * </ul>
 *
 * <h2>四、🔴 零行为改动</h2>
 * <ul>
 *   <li>本类<b>只读</b>：只调上面那些 getter 与 {@code getClass().getSimpleName()}，
 *       <b>没有</b> {@code setDistinct}、<b>没有</b> {@code handleRecipe}、<b>没有</b>任何集合写操作；</li>
 *   <li>整段包在 {@code try/catch} 里，<b>永不抛</b>（探针把 tick 带崩是最不可接受的失败形态）；</li>
 *   <li>节流由调用方负责（每台机只调一次）⇒ 本类没有 {@code static} 可变状态。</li>
 * </ul>
 *
 * <h2>五、判读表（改前 / 改后都对照这一张）</h2>
 * <pre>
 *   oursInNormal=true                        ⇒ 我们自成一组、<b>无条件存活</b>（改后的目标读数）
 *   oursInNormal=false 且 shared=none         ⇒ 我们哪儿都不在 ⇒ 必然「输入材料不足」（改前的最坏形态）
 *   oursInNormal=false 且 shared≠none 且★在共享 ⇒ 只能寄生共享池（改前的正常形态）；再看主机 isDistinct
 *   oursInNormal=false 且 shared≠none 但★不在   ⇒ 视图的 getRecipeHandlers() 与进池的不是同一批对象
 *                                             ⇒ 查 SUPPLYVIEW skipped=constructing（构造期返回空表）
 * </pre>
 */
public final class ShanhaiHandlePoolProbe {

    /** 一行里最多渲染多少个 handler（正常的结构一家仓室 1–7 个，40 已经远远够；防日志爆长）。 */
    private static final int MAX_RENDER = 40;

    private ShanhaiHandlePoolProbe() {}

    /**
     * 打那一行 {@code HANDLEPOOL}。
     *
     * @param pos              本机位置（{@code <维度id>@<x>,<y>,<z>}，由调用方的 {@code shanhaiPos()} 给）
     * @param host             主机（{@link IRecipeCapabilityMachine}，生产里由 {@code getControllers()} 取到）
     * @param stockItemHandler 本机那两份 160 格库存 handler 的实例（只用于 <b>{@code ==} 引用比对</b>）
     * @param stockFluidHandler 同上（流体侧）
     * @param itemCalls        物品侧 {@code handleRecipeInner} 至今被调次数（{@code -1} = 没有计数器）
     * @param fluidCalls       流体侧同上
     */
    public static void log(@NotNull String pos,
                           @NotNull IRecipeCapabilityMachine host,
                           @Nullable Object stockItemHandler,
                           @Nullable Object stockFluidHandler,
                           long itemCalls,
                           long fluidCalls) {
        final StringBuilder line = new StringBuilder(320);
        try {
            line.append("HANDLEPOOL pos=").append(pos);
            line.append(" hostDistinct=").append(safeDistinct(host));

            // ---- shared：只要类名；★ 的份数进判读（不改格式）
            Object shared = null;
            String sharedErr = null;
            try {
                shared = host.getSharedRecipeHandlePart();
            } catch (Throwable t) {
                sharedErr = "!" + t.getClass().getSimpleName();
            }
            line.append(" shared=");
            if (sharedErr != null) {
                line.append(sharedErr);
            } else {
                line.append(shared == null ? "none" : simpleName(shared));
            }
            final int[] sharedStats = new int[2];
            final StringBuilder sharedDetail = new StringBuilder();
            int sharedOurs = 0;
            if (shared != null) {
                sharedOurs = appendHandlers(shared, sharedDetail, sharedStats,
                        stockItemHandler, stockFluidHandler, itemCalls, fluidCalls);
            }

            // ---- normalIn：全部 part、全部 handler、按真实迭代顺序
            line.append(" normalIn=[");
            int normalParts = 0;
            int oursInNormal = 0;
            String normalErr = null;
            try {
                final List<RecipeHandlePart> normal = host.getNormalRecipeHandlePart(IO.IN);
                if (normal != null) {
                    for (int p = 0; p < normal.size(); p++) {
                        if (normalParts > 0) {
                            line.append(" | ");
                        }
                        normalParts++;
                        final int[] stats = new int[2];
                        oursInNormal += appendHandlers(normal.get(p), line, stats,
                                stockItemHandler, stockFluidHandler, itemCalls, fluidCalls);
                        if (stats[1] > 0) {
                            line.append(" …+").append(stats[1]);
                        }
                    }
                }
            } catch (Throwable t) {
                normalErr = "!" + t.getClass().getSimpleName();
                line.append(normalErr);
            }
            line.append(']');

            // ---- mePatterns
            line.append(" mePatterns=");
            try {
                final List<?> mePatterns = host.getMEPatternRecipeHandleParts();
                line.append(mePatterns == null ? -1 : mePatterns.size());
            } catch (Throwable t) {
                line.append('!').append(t.getClass().getSimpleName());
            }

            // ---- oursInNormal（本行唯一要判的那个 boolean）
            final boolean ours = oursInNormal > 0;
            line.append(" oursInNormal=").append(ours);

            // ---- 判读（紧跟在上面的固定 key 之后，grep 用前面的前缀即可）
            line.append(" ⇒ ");
            if (ours) {
                line.append("★在 normalIn 里（").append(oursInNormal).append(" 份）⇒ 我们自成一组、无条件存活：")
                        .append("handleInputNotDistinctNocache 偏移 114 / handleInputNotDistinct 偏移 428 / ")
                        .append("handleInputDistinct 偏移 122 三处都会问到 ⇒ 接下来应看到 SUPPLYCHAIN kind=item handle 的 h 开始涨");
            } else if (sharedErr != null || normalErr != null) {
                line.append("读数有异常（shared=").append(sharedErr).append(" normalIn=").append(normalErr)
                        .append("）⇒ 这次不作判读，看异常类名定位");
            } else if (shared == null) {
                line.append("我们哪儿都不在（shared=none 且 normalIn 无★）⇒ RecipeRunner 三条路径全都不问就判失败")
                        .append(" ⇒ 表观必然是「输入材料不足」（正是取证报告 §5.1 的形态）");
            } else if (sharedOurs > 0) {
                line.append("★只在共享池里（").append(sharedOurs).append(" 份）⇒ 我们只能寄生 sharedInputRecipeHandlePart")
                        .append("；主机 isDistinct=").append(safeDistinct(host))
                        .append(" —— 共享池的存活条件是「主机 isDistinct==false 且清单非空」（偏移 107–137）");
            } else {
                line.append("shared 存在但里面没有★，normalIn 也没有 ⇒ 视图的 getRecipeHandlers() 与真正进池的")
                        .append("不是同一批对象 ⇒ 先查 SUPPLYVIEW skipped=constructing（构造期返回空表，")
                        .append("expandForNormalSupply 会退回同一个 List）");
            }
            line.append("; sharedOurs=").append(sharedOurs);
            if (sharedDetail.length() > 0) {
                line.append("; sharedHandlers=[").append(sharedDetail).append(']');
            }
            line.append("; normalInParts=").append(normalParts);
        } catch (Throwable t) {
            // 探针自己炸了也必须留痕（否则"没这行"会被误判成"没成型"）
            line.append(" HANDLEPOOL pos=").append(pos).append(" !probe-failed ").append(t.getClass().getSimpleName());
        }
        ShanhaiStellarLog.info("{}", line);
    }

    /**
     * 把一份 {@code RecipeHandlePart} 里的 handler 按<b>真实迭代顺序</b>写进 {@code out}。
     *
     * @return 其中「我们的」handler 份数
     */
    private static int appendHandlers(@Nullable Object rhp, @NotNull StringBuilder out, int[] stats,
                                      @Nullable Object stockItemHandler, @Nullable Object stockFluidHandler,
                                      long itemCalls, long fluidCalls) {
        if (rhp == null) {
            out.append("null");
            return 0;
        }
        if (!(rhp instanceof RecipeHandlePart part)) {
            out.append("notRecipeHandlePart(").append(simpleName(rhp)).append(')');
            return 0;
        }
        int ours = 0;
        boolean first = true;
        boolean anySeen = false;
        try {
            for (Object group : part.getHandlerFastIterable()) {
                if (!(group instanceof Map.Entry<?, ?> entry)) {
                    continue;
                }
                if (!(entry.getValue() instanceof List<?> handlers)) {
                    continue;
                }
                final String tag = capabilityTag(entry.getKey());
                for (int h = 0; h < handlers.size(); h++) {
                    final Object handler = handlers.get(h);
                    anySeen = true;
                    final boolean isOurs = isOursHandler(handler, stockItemHandler, stockFluidHandler);
                    if (isOurs) {
                        ours++;
                    }
                    if (stats[0] >= MAX_RENDER) {
                        stats[1]++;
                        continue;
                    }
                    stats[0]++;
                    if (!first) {
                        out.append(", ");
                    }
                    first = false;
                    if (isOurs) {
                        out.append('★');
                    }
                    out.append(tag).append(':').append(handler == null ? "null" : simpleName(handler));
                    final long calls = callsOf(handler, stockItemHandler, stockFluidHandler, itemCalls, fluidCalls);
                    out.append(":calls=").append(calls < 0L ? "-" : Long.toString(calls));
                }
            }
        } catch (Throwable t) {
            out.append('!').append(t.getClass().getSimpleName());
        }
        if (!anySeen) {
            out.append("(无 handler)");
        }
        return ours;
    }

    /**
     * 「这份 handler 是不是我们交出去的那批」。
     *
     * <p>两份库存 handler 用 <b>{@code ==} 引用比对</b>（它们是本机的字段，实例稳定）；
     * 另外四类用<b>类</b>判定 —— 它们在 {@code shanhaiNormalSupplyHandlers()} 里<b>每次调用都 new</b>
     * （{@code StellarSampleAssemblerPartMachine.java:8522/8523/8525/8528/8531}），
     * 所以只能认类型；而且全工程<b>只有那五处</b> {@code new}（grep 实证），不会有误认。
     */
    private static boolean isOursHandler(@Nullable Object handler,
                                         @Nullable Object stockItemHandler,
                                         @Nullable Object stockFluidHandler) {
        if (handler == null) {
            return false;
        }
        if (handler == stockItemHandler || handler == stockFluidHandler) {
            return true;
        }
        return handler instanceof ShanhaiTagSupplyHandlers.Item
                || handler instanceof ShanhaiTagSupplyHandlers.Fluid
                || handler instanceof ShanhaiSupplyOnlyItemHandler
                || handler instanceof ShanhaiSupplyOnlyFluidHandler;
    }

    /** {@code handleRecipeInner} 计数；没有计数器的返回 {@code -1}（渲染成 {@code -}）。 */
    private static long callsOf(@Nullable Object handler,
                                @Nullable Object stockItemHandler,
                                @Nullable Object stockFluidHandler,
                                long itemCalls, long fluidCalls) {
        if (handler != null && handler == stockItemHandler) {
            return itemCalls;
        }
        if (handler != null && handler == stockFluidHandler) {
            return fluidCalls;
        }
        return -1L;
    }

    private static @NotNull String capabilityTag(@Nullable Object capability) {
        if (capability == ItemRecipeCapability.CAP) {
            return "item";
        }
        if (capability == FluidRecipeCapability.CAP) {
            return "fluid";
        }
        return "cap?";
    }

    private static @NotNull String safeDistinct(@NotNull IRecipeCapabilityMachine host) {
        try {
            return Boolean.toString(host.isDistinct());
        } catch (Throwable t) {
            return "!" + t.getClass().getSimpleName();
        }
    }

    /**
     * handler 的短名。<b>嵌套类要带外层类名</b>（{@code ShanhaiTagSupplyHandlers.Item} 的
     * {@code getSimpleName()} 只有 {@code "Item"}，跟别的 {@code Item} 分不开 —— 这一行要靠它判"是不是我们的"）。
     */
    private static @NotNull String simpleName(@NotNull Object o) {
        try {
            final Class<?> type = o.getClass();
            final Class<?> enclosing = type.getEnclosingClass();
            return enclosing == null
                    ? type.getSimpleName()
                    : enclosing.getSimpleName() + '$' + type.getSimpleName();
        } catch (Throwable t) {
            return "?";
        }
    }
}
