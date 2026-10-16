package com.dishanhai.gt_shanhai.common.machine.part;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Decides which stock-slot stacks belong to the player and which automatic
 * pattern or tag pulls may replace them.
 */
final class MEStellarStockConfiguration {

    private MEStellarStockConfiguration() {}

    static <T> T reconcile(T current, T manual, T automatic) {
        T applied = automatic != null ? automatic : manual;
        if (Objects.equals(current, applied)) {
            return manual;
        }
        return current;
    }

    static <T> T initialManual(T current, T automatic) {
        if (Objects.equals(current, automatic)) {
            return null;
        }
        return current;
    }

    static <T> boolean releaseBaked(T manual, T automatic, boolean stillTargeted) {
        return manual != null && Objects.equals(manual, automatic) && !stillTargeted;
    }

    static <T> boolean acceptTagTarget(T key, Set<T> patternTargets, Set<T> manualKeys, Set<T> suppressed) {
        return !patternTargets.contains(key) && !manualKeys.contains(key) && !suppressed.contains(key);
    }

    static <T> void omitSuppressed(Map<T, Long> targets, Set<T> suppressed) {
        if (targets.isEmpty() || suppressed.isEmpty()) {
            return;
        }
        targets.keySet().removeIf(suppressed::contains);
    }
}
