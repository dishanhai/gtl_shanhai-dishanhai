package com.dishanhai.gt_shanhai.common.machine.thread;

// Author: dishanhai
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialThreadedRecipeLogic;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import java.util.function.Supplier;

/** 山海：一次接單的唯讀候選快照；每次原生建構只看一個候選，未扣料失敗才往後試。 */
public final class RecipeThreadPriority<T> {
    private record Candidate<T>(T recipe, long reserved, long ticks, int originalTicks) {}
    private final ToLongFunction<T> estimate;
    private final ToIntFunction<T> originalDuration;
    private final ToLongFunction<T> reservation;
    private List<Candidate<T>> ordered;
    private int index;
    private boolean nextBatch;

    public RecipeThreadPriority(ToLongFunction<T> estimate, ToIntFunction<T> originalDuration) {
        this(estimate, originalDuration, ignored -> 0L);
    }

    public RecipeThreadPriority(ToLongFunction<T> estimate, ToIntFunction<T> originalDuration,
                                ToLongFunction<T> reservation) {
        this.estimate = estimate;
        this.originalDuration = originalDuration;
        this.reservation = reservation;
    }

    public List<T> select(Collection<T> candidates) {
        return select(() -> candidates);
    }

    public List<T> select(Supplier<? extends Collection<T>> lookup) {
        if (ordered == null) {
            Collection<T> candidates = lookup.get();
            ordered = new ArrayList<>(candidates.size());
            for (T recipe : candidates) {
                if (recipe != null) ordered.add(new Candidate<>(recipe, reservation.applyAsLong(recipe),
                        Math.max(1L, estimate.applyAsLong(recipe)), originalDuration.applyAsInt(recipe)));
            }
            ordered.sort(Comparator.<Candidate<T>>comparingLong(Candidate::reserved)
                    .thenComparing(Comparator.comparingLong(Candidate<T>::ticks).reversed())
                    .thenComparing(Comparator.comparingInt(Candidate<T>::originalTicks).reversed()));
        }
        if (nextBatch && index < ordered.size()) {
            // 同 tick 已扣料後，各池占用只會增加。重選剩餘候選，復用耗時估算。
            int best = index;
            long bestUsed = reservation.applyAsLong(ordered.get(best).recipe);
            for (int i = index + 1; i < ordered.size(); i++) {
                Candidate<T> candidate = ordered.get(i), current = ordered.get(best);
                long used = reservation.applyAsLong(candidate.recipe);
                if (used < bestUsed || used == bestUsed && (candidate.ticks > current.ticks
                        || candidate.ticks == current.ticks && candidate.originalTicks > current.originalTicks)) {
                    best = i;
                    bestUsed = used;
                }
            }
            java.util.Collections.swap(ordered, index, best);
            nextBatch = false;
        }
        return index < ordered.size() ? List.of(ordered.get(index).recipe) : List.of();
    }

    /** 成功候選移到隊尾；再次使用前仍由原生路徑重新核對庫存與池餘額。 */
    public void nextBatch() {
        if (ordered != null && index < ordered.size()) ordered.add(ordered.remove(index));
        nextBatch = true;
    }

    public boolean retry() { return ordered != null && ++index < ordered.size(); }
    public boolean hasSnapshot() { return ordered != null; }

    public static long energyTicks(double energyPerRecipe, long parallel, long power, int minimum) {
        double ticks = Math.abs(energyPerRecipe) * Math.max(1L, parallel) / Math.max(1L, power);
        if (Double.isNaN(ticks)) return Math.max(1, minimum);
        return (long) Math.max(Math.max(1, minimum), Math.min(Integer.MAX_VALUE, ticks));
    }

    public static long standardTicks(int duration, long eut, long voltage) {
        if (eut <= 0L || voltage <= 0L) return Math.max(1, duration);
        return standardTicks(duration, eut, voltage, Math.max(0,
                com.gregtechceu.gtceu.utils.GTUtil.getTierByVoltage(voltage)
                        - com.gregtechceu.gtceu.utils.GTUtil.getTierByVoltage(eut)));
    }

    static long standardTicks(int duration, long eut, long voltage, int overclocks) {
        var params = new com.gregtechceu.gtceu.api.recipe.logic.OCParams();
        var result = new com.gregtechceu.gtceu.api.recipe.logic.OCResult();
        params.initialize(eut, Math.max(1, duration), Math.max(0, overclocks));
        com.gregtechceu.gtceu.api.recipe.OverclockingLogic.NON_PERFECT_OVERCLOCK
                .getLogic().runOverclockingLogic(params, result, voltage);
        return Math.max(1, result.getDuration());
    }

    /** 在實際分配入口呼叫，晚於樣板槽位合併與子類條件過濾。 */
    public static Set<GTRecipe> select(RecipeLogic logic, Set<GTRecipe> candidates) {
        return select(logic, () -> candidates);
    }

    public static Set<GTRecipe> select(RecipeLogic logic, Supplier<Set<GTRecipe>> lookup) {
        if (logic instanceof PrimordialThreadedRecipeLogic primordial) return primordial.priorityCandidates(lookup);
        var runtime = NativeRecipeThreadRuntime.of(logic);
        return runtime == null ? lookup.get() : runtime.priorityCandidates(lookup);
    }

    public static Iterator<GTRecipe> select(RecipeLogic logic, Iterator<GTRecipe> candidates) {
        var runtime = NativeRecipeThreadRuntime.of(logic);
        if (runtime == null || !runtime.isPrioritizing() || candidates == null) return candidates;
        if (runtime.hasPrioritySnapshot()) return runtime.priorityCandidates(Set.of()).iterator();
        var snapshot = new LinkedHashSet<GTRecipe>();
        candidates.forEachRemaining(snapshot::add);
        return runtime.priorityCandidates(snapshot).iterator();
    }
}
