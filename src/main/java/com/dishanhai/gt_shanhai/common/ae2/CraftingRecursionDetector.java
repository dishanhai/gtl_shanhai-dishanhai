package com.dishanhai.gt_shanhai.common.ae2;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 解释「为什么这一项在合成计划里是缺失的」——定位样板之间的递归依赖。
 *
 * <p>AE2 自己有递归防护：{@code CraftingTreeNode.notRecursive} 会沿祖先链检查，
 * 只要某个样板的产出或输入命中祖先节点的产物，这个样板就被<b>静默丢弃</b>。
 * 结果是该物品直接落进 {@code missingAmount}，玩家只看到「缺失」，看不到原因。
 * 本类把原因还原出来，分两种：
 *
 * <ul>
 *   <li>{@link Kind#SELF_LOOP} 自反样板：产出自己也消耗自己，例如 1A + 4B → 8A。
 *       AE2 顶层会接受它但把 {@code limitQty} 置位，且下一层的 A 必须来自库存，
 *       所以没有种子库存就起不了步。</li>
 *   <li>{@link Kind#CYCLE} 互相依赖环：A 的样板要 B，B 的样板要 A，整条链绕回自己。</li>
 * </ul>
 *
 * <p>只在合成计划确认时对「缺失」条目按需调用，带深度和访问预算，不进 tick 循环。
 */
public final class CraftingRecursionDetector {

    /** 依赖链最大追踪深度。 */
    private static final int MAX_DEPTH = 16;
    /** 单次检测最多展开的物品节点数，防止在超大样板网络里爆炸。 */
    private static final int MAX_VISITS = 400;

    private CraftingRecursionDetector() {}

    public enum Kind {
        /** 没查到递归，缺失是别的原因。 */
        NONE,
        /** 自反样板：产出自己也消耗自己，但至少有一张净产出为正，靠种子能滚起来。 */
        SELF_LOOP,
        /** 互相依赖环：绕一圈回到自己。 */
        CYCLE,
        /**
         * 自反且没有任何一张样板净产出为正——吃掉的自身数量 ≥ 产出，
         * 例如 {@code 1A + 水 → 1A}。跑再多次总量也不会增加，要更多就是数学上不可能。
         */
        SELF_LOOP_NO_GAIN
    }

    /**
     * @param kind 递归类型
     * @param path 依赖链，首尾都是被查询的物品；{@link Kind#NONE} 时为空
     */
    public record Result(Kind kind, List<AEKey> path) {

        private static final Result NONE = new Result(Kind.NONE, List.of());

        public boolean recursive() {
            return kind != Kind.NONE;
        }
    }

    /**
     * 只判自反，不做图搜索——开销只有「样板数 × 输入数」，可以安全地对「不缺失但要合成」的条目调用。
     *
     * <p>自反样板即使当前有种子库存也值得提示：计划能跑起来只是因为手上恰好还有那几个，
     * 种子一旦耗尽就再也起不了步，这是玩家最容易踩空的地方。
     */
    public static Result detectSelfLoop(@Nullable ICraftingService service, @Nullable AEKey target) {
        if (service == null || target == null) return Result.NONE;
        Collection<IPatternDetails> patterns = service.getCraftingFor(target);
        if (patterns.isEmpty()) return Result.NONE;
        boolean anyGain = false;
        for (IPatternDetails pattern : patterns) {
            // 只要有一张不消耗自己的样板，就存在无种子的起步路径，不算自反。
            if (!consumes(pattern, target)) return Result.NONE;
            if (netGain(pattern, target) > 0L) anyGain = true;
        }
        return new Result(anyGain ? Kind.SELF_LOOP : Kind.SELF_LOOP_NO_GAIN, List.of(target));
    }

    /** 该样板跑一次，target 的净增量（产出 − 消耗）。≤ 0 意味着再怎么跑总量也不会涨。 */
    private static long netGain(IPatternDetails pattern, AEKey target) {
        long produced = 0L;
        for (GenericStack out : pattern.getOutputs()) {
            if (out != null && target.equals(out.what())) produced += out.amount();
        }
        long consumed = 0L;
        for (IPatternDetails.IInput input : pattern.getInputs()) {
            GenericStack[] possible = input.getPossibleInputs();
            if (possible == null || possible.length == 0 || possible[0] == null) continue;
            if (!target.equals(possible[0].what())) continue;
            consumed += possible[0].amount() * input.getMultiplier();
        }
        return produced - consumed;
    }

    public static Result detect(@Nullable ICraftingService service, @Nullable AEKey target) {
        if (service == null || target == null) return Result.NONE;
        Collection<IPatternDetails> patterns = service.getCraftingFor(target);
        if (patterns.isEmpty()) return Result.NONE;

        Result selfLoop = detectSelfLoop(service, target);
        if (selfLoop.recursive()) return selfLoop;

        List<AEKey> cycle = findCycle(service, target);
        if (cycle == null) return Result.NONE;
        // 链上只有它自己，说明命中的是某个自反样板，而不是绕一圈的互相依赖。
        Kind kind = cycle.size() <= 2 ? Kind.SELF_LOOP : Kind.CYCLE;
        return new Result(kind, kind == Kind.SELF_LOOP ? List.of(target) : cycle);
    }

    /** 单次 {@link #walk} 的结局。 */
    private enum Walk {
        /** 找到了绕回 target 的链。 */
        FOUND,
        /** 该子树已完整走完，确定无环。 */
        EXHAUSTED,
        /** 撞到深度或访问预算上限，结论不可信。 */
        TRUNCATED
    }

    /** 从 target 出发沿「样板输入」向上游走，找一条绕回 target 的链。 */
    @Nullable
    private static List<AEKey> findCycle(ICraftingService service, AEKey target) {
        Set<AEKey> exhausted = new HashSet<>();
        Deque<AEKey> path = new ArrayDeque<>();
        int[] budget = { MAX_VISITS };
        path.addLast(target);
        if (walk(service, target, target, exhausted, path, budget, 0) != Walk.FOUND) return null;
        List<AEKey> result = new ArrayList<>(path);
        result.add(target);
        return result;
    }

    /**
     * @param exhausted 已<b>完整</b>走完且确定无环的节点；只有这种结论才可以跨路径复用。
     *                  被深度／预算截断的节点不记进来，否则更浅的路径会被错误剪枝而漏报环。
     */
    private static Walk walk(ICraftingService service, AEKey current, AEKey target,
            Set<AEKey> exhausted, Deque<AEKey> path, int[] budget, int depth) {
        if (depth >= MAX_DEPTH || budget[0]-- <= 0) return Walk.TRUNCATED;
        boolean truncated = false;
        for (IPatternDetails pattern : service.getCraftingFor(current)) {
            for (IPatternDetails.IInput input : pattern.getInputs()) {
                AEKey next = primaryInput(input);
                if (next == null) continue;
                if (next.equals(target)) return Walk.FOUND;
                if (exhausted.contains(next)) continue;
                // 已经在当前链上：这是个不经过 target 的环，再走下去只会空耗预算。
                if (path.contains(next)) continue;
                path.addLast(next);
                Walk result = walk(service, next, target, exhausted, path, budget, depth + 1);
                if (result == Walk.FOUND) return Walk.FOUND;
                path.removeLast();
                if (result == Walk.TRUNCATED) {
                    truncated = true;
                } else {
                    exhausted.add(next);
                }
            }
        }
        return truncated ? Walk.TRUNCATED : Walk.EXHAUSTED;
    }

    private static boolean consumes(IPatternDetails pattern, AEKey what) {
        for (IPatternDetails.IInput input : pattern.getInputs()) {
            if (what.equals(primaryInput(input))) return true;
        }
        return false;
    }

    /** 与 AE2 {@code notRecursive} 一致，只看主输入（{@code getPossibleInputs()[0]}）。 */
    @Nullable
    private static AEKey primaryInput(IPatternDetails.IInput input) {
        GenericStack[] possible = input.getPossibleInputs();
        if (possible == null || possible.length == 0 || possible[0] == null) return null;
        return possible[0].what();
    }
}
