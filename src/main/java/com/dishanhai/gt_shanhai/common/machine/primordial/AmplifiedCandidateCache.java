package com.dishanhai.gt_shanhai.common.machine.primordial;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 候选集放大结果的单槽记忆，按「候选集实例 + 倍率」命中。
 * <p>
 * 候选集由 SelectableRecipeTypeSetRecipeLogic 缓存并在窗口内复用同一实例；实例没换、倍率没变，
 * 放大结果必然相同，没必要每次查找都对全部候选重新 copy 并缩放输出。候选集重建（新实例）、
 * 倍率变化都会自然未命中并重算。只记忆「候选是谁、产出放大成多少」，数量、并行、扣料仍由调用方每轮实算。
 */
final class AmplifiedCandidateCache<T> {

    @FunctionalInterface
    interface Amplifier<T> {

        T apply(T item, int multiplier);
    }

    private Set<T> cachedSource;
    private int cachedMultiplier;
    private Set<T> cachedResult;

    Set<T> get(Set<T> source, int multiplier, Amplifier<T> amplifier) {
        if (source == cachedSource && multiplier == cachedMultiplier && cachedResult != null) {
            return cachedResult;
        }
        Set<T> amplified = new LinkedHashSet<>(source.size());
        for (T item : source) {
            amplified.add(amplifier.apply(item, multiplier));
        }
        cachedSource = source;
        cachedMultiplier = multiplier;
        cachedResult = amplified;
        return amplified;
    }
}
