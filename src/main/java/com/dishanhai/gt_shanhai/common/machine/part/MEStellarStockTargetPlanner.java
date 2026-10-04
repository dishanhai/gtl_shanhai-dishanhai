package com.dishanhai.gt_shanhai.common.machine.part;

import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.function.ToLongFunction;

final class MEStellarStockTargetPlanner {

    record Candidate<K>(K key, long amount) {}

    record Input<K>(List<Candidate<K>> candidates, long multiplier) {}

    private MEStellarStockTargetPlanner() {}

    static int requiredCapacity(List<?> inputs) {
        int count = inputs.size();
        if (count <= 64) {
            return 64;
        }
        // Crossing the four-page baseline reserves one additional page, matching
        // the requested 20 + 50 input example: 80 required slots -> 96 capacity.
        return Math.multiplyExact((count + 15) / 16 + 1, 16);
    }

    static <K> Map<K, Long> plan(List<Input<K>> inputs, ToLongFunction<K> available, int capacity) {
        Map<K, Long> targets = new LinkedHashMap<>();
        for (Input<K> input : inputs) {
            if (input.multiplier() <= 0 || input.candidates().isEmpty()) {
                throw new IllegalArgumentException("Invalid pattern input");
            }
            Candidate<K> selected = null;
            long selectedAmount = 0L;
            double bestCoverage = -1.0d;
            for (Candidate<K> candidate : input.candidates()) {
                if (candidate.key() == null || candidate.amount() <= 0) {
                    throw new IllegalArgumentException("Invalid pattern candidate");
                }
                long amount = Math.multiplyExact(candidate.amount(), input.multiplier());
                long remaining = Math.max(0L, Math.max(0L, available.applyAsLong(candidate.key()))
                        - targets.getOrDefault(candidate.key(), 0L));
                if (remaining >= amount) {
                    selected = candidate;
                    selectedAmount = amount;
                    break;
                }
                double coverage = (double) remaining / amount;
                if (selected == null || coverage > bestCoverage) {
                    selected = candidate;
                    selectedAmount = amount;
                    bestCoverage = coverage;
                }
            }
            targets.put(selected.key(),
                    Math.addExact(targets.getOrDefault(selected.key(), 0L), selectedAmount));
            if (targets.size() > capacity) {
                throw new IllegalArgumentException("Too many pattern input types");
            }
        }
        return Collections.unmodifiableMap(targets);
    }
}
