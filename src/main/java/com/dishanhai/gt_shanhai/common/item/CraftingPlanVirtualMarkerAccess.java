package com.dishanhai.gt_shanhai.common.item;

import appeng.api.stacks.AEKey;

import com.dishanhai.gt_shanhai.common.ae2.CraftingRecursionDetector;

import java.util.List;

public interface CraftingPlanVirtualMarkerAccess {

    boolean gtShanhai$isVirtualPresence();

    void gtShanhai$setVirtualPresence(boolean virtualPresence);

    /** 该条目缺失的递归原因；没有递归时为 {@link CraftingRecursionDetector.Kind#NONE}。 */
    CraftingRecursionDetector.Kind gtShanhai$getRecursionKind();

    /** 递归依赖链，首尾都是本条目自身；无递归时为空。 */
    List<AEKey> gtShanhai$getRecursionPath();

    void gtShanhai$setRecursion(CraftingRecursionDetector.Kind kind, List<AEKey> path);

    /** 该条目缺失，且网络里根本没有任何样板能做出它。 */
    boolean gtShanhai$isNoPattern();

    void gtShanhai$setNoPattern(boolean noPattern);

    /**
     * 该条目的数量已经溢出 64 位整数。
     *
     * <p>AE2 用 {@code long} 记数量，{@code CraftingPlanSummary.fromJob} 里
     * {@code out.amount() * patternTimes} 这类乘法在 GTL 级别的数量下会回绕成负数。
     * 而 AE2 的 tooltip 与格子小字全都是 {@code if (amount > 0)} 才渲染，
     * 负数一律不满足——整格于是完全空白，玩家看不到任何信息。
     */
    boolean gtShanhai$isOverflow();

    void gtShanhai$setOverflow(boolean overflow);
}
