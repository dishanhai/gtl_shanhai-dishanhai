package com.dishanhai.gt_shanhai.common.machine.primordial;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimordialRecipeSearchCostTest {

    private static final Path HOST_LOGIC_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "primordial", "PrimordialOmegaEngineRecipeLogic.java");

    @Test
    void blockedMachineStopsAfterProbingParallelOne() {
        AtomicInteger calls = new AtomicInteger();
        long found = PrimordialModuleRecipeLogic.findHighestMatchableParallel(
                1_000_000_000_000L,
                parallel -> {
                    calls.incrementAndGet();
                    return false;
                });

        assertEquals(0L, found);
        assertEquals(3, calls.get(), "输出倉满时只应探测 requested、requested-1、1 三次，不能再二分");
    }

    @Test
    void parallelSearchMatchesBruteForceForEveryThreshold() {
        for (long requested = 1L; requested <= 40L; requested++) {
            for (long threshold = 0L; threshold <= requested + 2L; threshold++) {
                long limit = threshold;
                long expected = Math.min(requested, limit);
                assertEquals(expected,
                        PrimordialModuleRecipeLogic.findHighestMatchableParallel(requested, p -> p <= limit),
                        "requested=" + requested + " threshold=" + threshold);
            }
        }
    }

    @Test
    void parallelSearchStaysLogarithmicWhenPartiallyBlocked() {
        AtomicInteger calls = new AtomicInteger();
        long found = PrimordialModuleRecipeLogic.findHighestMatchableParallel(
                Long.MAX_VALUE,
                parallel -> {
                    calls.incrementAndGet();
                    return parallel <= 123_456L;
                });

        assertEquals(123_456L, found);
        assertTrue(calls.get() <= 67, "探测次数应保持 O(log n)，实际 " + calls.get());
    }

    @Test
    void amplifiedCandidatesAreReusedWhileSourceAndMultiplierStay() {
        AmplifiedCandidateCache<String> cache = new AmplifiedCandidateCache<>();
        AtomicInteger amplifyCalls = new AtomicInteger();
        AmplifiedCandidateCache.Amplifier<String> amplifier = (item, multiplier) -> {
            amplifyCalls.incrementAndGet();
            return item + "x" + multiplier;
        };
        Set<String> source = new LinkedHashSet<>(List.of("a", "b", "c"));

        Set<String> first = cache.get(source, 4, amplifier);
        Set<String> second = cache.get(source, 4, amplifier);

        assertSame(first, second, "候选集实例与倍率都没变时必须复用上次的放大结果");
        assertEquals(3, amplifyCalls.get(), "第二次查找不得重新放大任何候选");
        assertEquals(List.of("ax4", "bx4", "cx4"), List.copyOf(first), "放大后必须保持候选原有顺序");
    }

    @Test
    void amplifiedCandidatesRebuildWhenSourceOrMultiplierChanges() {
        AmplifiedCandidateCache<String> cache = new AmplifiedCandidateCache<>();
        AmplifiedCandidateCache.Amplifier<String> amplifier = (item, multiplier) -> item + "x" + multiplier;
        Set<String> source = new LinkedHashSet<>(List.of("a", "b"));

        Set<String> base = cache.get(source, 4, amplifier);
        Set<String> otherMultiplier = cache.get(source, 8, amplifier);
        Set<String> otherSource = cache.get(new LinkedHashSet<>(List.of("a", "b")), 8, amplifier);

        assertNotSame(base, otherMultiplier);
        assertEquals(List.of("ax8", "bx8"), List.copyOf(otherMultiplier), "倍率变化必须重算");
        assertNotSame(otherMultiplier, otherSource, "候选集换了新实例（缓存重建）必须重算，即使内容相同");
    }

    @Test
    void hostLogicNoLongerCopiesEveryCandidateOnEachLookup() throws Exception {
        String source = Files.readString(HOST_LOGIC_SOURCE);

        assertTrue(source.contains("amplifiedCandidates.get(recipes, multiplier,"),
                "主机放大候选集必须走按 (实例, 倍率) 命中的记忆");
        assertTrue(!source.contains("PrimordialRecipeOutputAmplifier.apply(recipe, multiplier)"),
                "不得再在每次查找里逐候选调用放大器");
    }
}
