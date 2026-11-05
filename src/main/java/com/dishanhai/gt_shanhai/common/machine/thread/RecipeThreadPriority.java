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

/** 山海：一次接單的唯讀候選快照；每次原生建構只看一個候選，未扣料失敗才往後試。 */
public final class RecipeThreadPriority<T> {
    private record Candidate<T>(T recipe, long ticks, int originalTicks) {}
    private final ToLongFunction<T> estimate;
    private final ToIntFunction<T> originalDuration;
    private List<Candidate<T>> ordered;
    private int index;

    public RecipeThreadPriority(ToLongFunction<T> estimate, ToIntFunction<T> originalDuration) {
        this.estimate = estimate;
        this.originalDuration = originalDuration;
    }

    public List<T> select(Collection<T> candidates) {
        if (ordered == null) {
            ordered = new ArrayList<>(candidates.size());
            for (T recipe : candidates) {
                if (recipe != null) ordered.add(new Candidate<>(recipe,
                        Math.max(1L, estimate.applyAsLong(recipe)), originalDuration.applyAsInt(recipe)));
            }
            ordered.sort(Comparator.<Candidate<T>>comparingLong(Candidate::ticks).reversed()
                    .thenComparing(Comparator.comparingInt(Candidate<T>::originalTicks).reversed()));
        }
        return index < ordered.size() ? List.of(ordered.get(index).recipe) : List.of();
    }

    public boolean retry() { return ordered != null && ++index < ordered.size(); }

    public static long energyTicks(double energyPerRecipe, long parallel, long power, int minimum) {
        double ticks = Math.abs(energyPerRecipe) * Math.max(1L, parallel) / Math.max(1L, power);
        if (Double.isNaN(ticks)) return Math.max(1, minimum);
        return (long) Math.max(Math.max(1, minimum), Math.min(Integer.MAX_VALUE, ticks));
    }

    /** 在實際分配入口呼叫，晚於樣板槽位合併與子類條件過濾。 */
    public static Set<GTRecipe> select(RecipeLogic logic, Set<GTRecipe> candidates) {
        if (logic instanceof PrimordialThreadedRecipeLogic primordial) return primordial.priorityCandidates(candidates);
        var runtime = NativeRecipeThreadRuntime.of(logic);
        return runtime == null ? candidates : runtime.priorityCandidates(candidates);
    }

    public static Iterator<GTRecipe> select(RecipeLogic logic, Iterator<GTRecipe> candidates) {
        var runtime = NativeRecipeThreadRuntime.of(logic);
        if (runtime == null || !runtime.isPrioritizing() || candidates == null) return candidates;
        var snapshot = new LinkedHashSet<GTRecipe>();
        candidates.forEachRemaining(snapshot::add);
        return runtime.priorityCandidates(snapshot).iterator();
    }
}
