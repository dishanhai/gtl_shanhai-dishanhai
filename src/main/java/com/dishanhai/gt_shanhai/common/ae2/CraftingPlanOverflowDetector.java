package com.dishanhai.gt_shanhai.common.ae2;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.menu.me.crafting.CraftingPlanSummary;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;

import com.dishanhai.gt_shanhai.common.item.CraftingPlanVirtualMarkerAccess;

import it.unimi.dsi.fastutil.objects.Object2LongMap;

import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 找出「数量已经溢出 64 位整数」的物品。
 *
 * <p>AE2 全程用 {@code long} 记数量。在 GTL 这种量级下，
 * {@code CraftingPlanSummary.fromJob} 里 {@code out.amount() * patternTimes} 这类乘法会溢出回绕，
 * 而 AE2 的 tooltip 和格子小字全都是 {@code if (amount > 0)} 才渲染——
 * 回绕成负数后整格一个字都不显示，玩家连「这里出事了」都看不出来。
 *
 * <p><b>为什么不能只看 summary 的三个终值。</b>
 * 回绕不是只会变负。真值一旦超过 {@code 2^64} 就绕满整圈，落回 {@code [0, 2^63)} 的概率约一半；
 * 那时 stored / missing / craft 全是正数，看起来完全正常，其实是错的——这比空白更危险。
 * 而 {@code stats.stored += ...} 这类累加还会让正负互相抵消，把中间溢出的痕迹抹掉。
 *
 * <p>所以这里改从<b>源头</b>取证，只用两个零误报的信号：
 *
 * <ul>
 *   <li>{@code patternTimes} 的次数 ≤ 0 —— AE2 合法路径下恒 ≥ 1（要么 {@code request(child, 1)}，
 *       要么由 {@code totalRequestedItems > 0} 的循环守卫保证），出现即溢出；</li>
 *   <li>{@code usedItems / emittedItems / missingItems} 里出现负数量 —— 合法值恒非负。</li>
 * </ul>
 *
 * 两者都在累加抵消之前取到，能覆盖住「中间溢出、终值被抵消回正数」的那一类。
 */
public final class CraftingPlanOverflowDetector {

    private CraftingPlanOverflowDetector() {}

    /**
     * 这份计划里是否有任何一项数量已经溢出。
     *
     * <p>用于拦截下单：溢出的计划 AE2 自己察觉不到，{@code isSimulation()} 可能仍是 {@code false}，
     * 于是「开始」按钮照常可点，下出去的单必然是错的。
     */
    public static boolean hasOverflow(@Nullable CraftingPlanSummary plan) {
        if (plan == null) return false;
        for (CraftingPlanSummaryEntry entry : plan.getEntries()) {
            if (entry instanceof CraftingPlanVirtualMarkerAccess access && access.gtShanhai$isOverflow()) {
                return true;
            }
        }
        return false;
    }

    /** @return 数量不可信的物品集合；没有溢出时为空集 */
    public static Set<AEKey> collectOverflowKeys(@Nullable ICraftingPlan job) {
        if (job == null) return Set.of();
        Set<AEKey> keys = new HashSet<>();
        collectNegative(job.usedItems(), keys);
        collectNegative(job.emittedItems(), keys);
        collectNegative(job.missingItems(), keys);
        for (Map.Entry<IPatternDetails, Long> times : job.patternTimes().entrySet()) {
            Long count = times.getValue();
            if (count != null && count > 0L) continue;
            // 这张样板跑的次数已经不可信，它产出的每一样都跟着不可信。
            for (GenericStack output : times.getKey().getOutputs()) {
                if (output != null) keys.add(output.what());
            }
        }
        return keys;
    }

    private static void collectNegative(Iterable<Object2LongMap.Entry<AEKey>> counts, Set<AEKey> sink) {
        for (Object2LongMap.Entry<AEKey> count : counts) {
            if (count.getLongValue() < 0L) sink.add(count.getKey());
        }
    }
}
