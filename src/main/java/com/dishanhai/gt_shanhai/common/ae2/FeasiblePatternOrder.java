package com.dishanhai.gt_shanhai.common.ae2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 多张样板抢同一个产物时，决定谁留在队首。
 *
 * <p>ULTRA_FAST 的模拟会接受第一张「能把产物写进子库存」的样板，缺料只记成 missing。
 * 粉 → 流体、流体又 → 粉 这种短样板因此总是定案，后面那张真正缺原料的样板不会被试。
 * 这里只把「整单必须绕回当前产物才能补齐」的样板从队首挪走。库存够跑完整单的短样板不动。
 *
 * <p>深度或访问预算用尽时不做让位，保持原顺序。证明不了回环，就不改 AE 的选择。
 * 库存只够做一部分、不够整单时，整张算回环样板，不拆成「短样板做一半、长样板补剩余」。
 */
public final class FeasiblePatternOrder {

    static final int MAX_DEPTH = 16;
    static final int MAX_VISITS = 400;

    /** 读不清的上游样板：不参与回环判定，调用方把它当成一条放行来源。 */
    public static final Pattern OPEN = new Pattern(1L, List.of(), Set.of());

    private static final int OK = 1;
    private static final int BLOCKED = 2;
    private static final int VISITING = 3;

    private FeasiblePatternOrder() {}

    /** 一张样板的一个输入。{@code perCraft <= 0} 或 {@code key == null} 表示看不清单次消耗。 */
    public static final class Slot {
        private final Object key;
        private final long perCraft;

        public Slot(Object key, long perCraft) {
            this.key = key;
            this.perCraft = perCraft;
        }

        public Object key() {
            return key;
        }

        public long perCraft() {
            return perCraft;
        }
    }

    /**
     * @param outputPerCraft 跑一次能产出多少「当前正在选的产物」。不是这个数的样板不会被当成回环样板。
     * @param involved 输入和产出里出现过的物品，用来识别「这张样板碰过当前产物」。
     */
    public static final class Pattern {
        private final long outputPerCraft;
        private final List<Slot> inputs;
        private final Set<Object> involved;

        public Pattern(long outputPerCraft, List<Slot> inputs, Set<Object> involved) {
            this.outputPerCraft = outputPerCraft;
            this.inputs = inputs == null ? List.of() : List.copyOf(inputs);
            this.involved = involved == null ? Set.of() : Set.copyOf(involved);
        }

        public long outputPerCraft() {
            return outputPerCraft;
        }

        public List<Slot> inputs() {
            return inputs;
        }

        public boolean involves(Object key) {
            return key != null && involved.contains(key);
        }
    }

    public interface Lookup {
        boolean canEmit(Object key);

        List<Pattern> patternsFor(Object key);

        /** 模拟库存里还能抽出多少。不要在这里真的扣料。 */
        long available(Object key);
    }

    /**
     * 队首连续的回环样板挪到末尾，直到第一张不是回环样板。
     *
     * @return 顺序被改过
     */
    public static <T> boolean demoteLeadingCycles(List<T> items, Function<T, Pattern> view, Object product,
            long totalRequested, Lookup lookup) {
        return demoteLeadingCycles(items, view, product, totalRequested, lookup, MAX_DEPTH, MAX_VISITS);
    }

    static <T> boolean demoteLeadingCycles(List<T> items, Function<T, Pattern> view, Object product,
            long totalRequested, Lookup lookup, int maxDepth, int maxVisits) {
        if (items == null || view == null || lookup == null || product == null) return false;
        int count = items.size();
        if (count < 2 || totalRequested <= 0) return false;

        Pattern[] views = new Pattern[count];
        for (int i = 0; i < count; i++) {
            views[i] = view.apply(items.get(i));
        }
        Search search = new Search(lookup, product, maxDepth, maxVisits);
        boolean[] poisoned = new boolean[count];
        boolean anyFeasible = false;
        for (int i = 0; i < count; i++) {
            poisoned[i] = search.isPoisoned(views[i], totalRequested);
            if (search.truncated) return false;
            if (!poisoned[i]) anyFeasible = true;
        }
        if (!poisoned[0] || !anyFeasible) return false;

        List<T> kept = new ArrayList<>(count);
        List<T> deferred = new ArrayList<>();
        boolean seenFeasible = false;
        for (int i = 0; i < count; i++) {
            T item = items.get(i);
            if (!seenFeasible && poisoned[i]) {
                deferred.add(item);
            } else {
                seenFeasible = true;
                kept.add(item);
            }
        }
        if (kept.isEmpty() || deferred.isEmpty()) return false;
        items.clear();
        items.addAll(kept);
        items.addAll(deferred);
        return true;
    }

    /**
     * 实际用上的样板都不是回环样板，同时至少有一张回环样板没被用上。
     * 深度或访问预算用尽、证明不了时返回 false。
     */
    public static <T> boolean usedSkipsCycle(List<T> items, Function<T, Pattern> view, Object product,
            long totalRequested, Lookup lookup, Predicate<T> used) {
        if (items == null || view == null || lookup == null || product == null || used == null) return false;
        int count = items.size();
        if (count < 2 || totalRequested <= 0) return false;
        Search search = new Search(lookup, product, MAX_DEPTH, MAX_VISITS);
        boolean unusedPoisoned = false;
        boolean usedFeasible = false;
        boolean usedPoisoned = false;
        for (int i = 0; i < count; i++) {
            T item = items.get(i);
            boolean poisoned = search.isPoisoned(view.apply(item), totalRequested);
            if (search.truncated) return false;
            if (used.test(item)) {
                if (poisoned) usedPoisoned = true;
                else usedFeasible = true;
            } else if (poisoned) {
                unusedPoisoned = true;
            }
        }
        return unusedPoisoned && usedFeasible && !usedPoisoned;
    }

    private static final class Search {
        private final Lookup lookup;
        private final Object product;
        private final int maxDepth;
        private final int maxVisits;
        private final HashMap<Object, Integer> memo = new HashMap<>();
        private int visits;
        private boolean truncated;

        private Search(Lookup lookup, Object product, int maxDepth, int maxVisits) {
            this.lookup = lookup;
            this.product = product;
            this.maxDepth = maxDepth;
            this.maxVisits = maxVisits;
        }

        private boolean isPoisoned(Pattern pattern, long totalRequested) {
            if (pattern == null || pattern.outputPerCraft() <= 0) return false;
            long times = ceilDiv(totalRequested, pattern.outputPerCraft());
            for (Slot slot : pattern.inputs()) {
                if (slot == null || slot.key() == null || slot.perCraft() <= 0) return false;
                long need = saturateMul(times, slot.perCraft());
                if (lookup.available(slot.key()) >= need) continue;
                int status = obtainable(slot.key(), 0);
                if (truncated) return false;
                if (status == BLOCKED) return true;
            }
            return false;
        }

        private int obtainable(Object key, int depth) {
            if (truncated) return OK;
            if (product.equals(key)) return BLOCKED;
            if (lookup.canEmit(key)) return OK;
            Integer known = memo.get(key);
            if (known != null) return known == VISITING ? BLOCKED : known;
            if (depth >= maxDepth || visits >= maxVisits) {
                truncated = true;
                return OK;
            }
            visits++;
            List<Pattern> producers = lookup.patternsFor(key);
            if (producers == null || producers.isEmpty()) {
                memo.put(key, OK);
                return OK;
            }
            memo.put(key, VISITING);
            for (int i = 0; i < producers.size(); i++) {
                Pattern producer = producers.get(i);
                if (producer == null || producer.involves(product)) continue;
                if (producerInputsOpen(producer, depth)) {
                    memo.put(key, OK);
                    return OK;
                }
                if (truncated) return OK;
            }
            memo.put(key, BLOCKED);
            return BLOCKED;
        }

        /** 这张上游样板的每个输入都能不绕回当前产物。看不清的输入当成放行，避免误杀。 */
        private boolean producerInputsOpen(Pattern producer, int depth) {
            List<Slot> inputs = producer.inputs();
            if (inputs.isEmpty()) return true;
            for (int i = 0; i < inputs.size(); i++) {
                Slot slot = inputs.get(i);
                if (slot == null || slot.key() == null || slot.perCraft() <= 0) return true;
                if (product.equals(slot.key())) return false;
                int status = obtainable(slot.key(), depth + 1);
                if (truncated || status == BLOCKED) return false;
            }
            return true;
        }
    }

    private static long ceilDiv(long total, long per) {
        return (total - 1) / per + 1;
    }

    private static long saturateMul(long left, long right) {
        if (left <= 0 || right <= 0) return 0L;
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE;
        return left * right;
    }
}
