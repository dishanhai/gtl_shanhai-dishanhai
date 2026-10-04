package com.dishanhai.gt_shanhai.common.machine.part;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class MEStellarStockTargetPlannerTest {

    @Test
    void largeFluidAmountsKeepTheirLongMultiplier() {
        assertEquals(Map.of("water", 102_400_000_000_000L),
                plan(List.of(input(1_000L, candidate("water", 102_400_000_000L))), Map.of()));
    }

    @Test
    void duplicateKeysAreMerged() {
        assertEquals(Map.of("crystal", 7L),
                plan(List.of(input(2L, candidate("crystal", 2L)),
                        input(3L, candidate("crystal", 1L))), Map.of()));
    }

    @Test
    void aStockedAlternativeIsPreferredOverAnEmptyFirstCandidate() {
        assertEquals(Map.of("copper", 3L),
                plan(List.of(input(1L, candidate("iron", 3L), candidate("copper", 3L))),
                        Map.of("copper", 3L)));
    }

    @Test
    void previousInputDemandIsReservedWhenChoosingAnAlternative() {
        assertEquals(Map.of("iron", 3L, "copper", 2L),
                plan(List.of(input(1L, candidate("iron", 3L)),
                        input(1L, candidate("iron", 2L), candidate("copper", 2L))),
                        Map.of("iron", 3L, "copper", 2L)));
    }

    @Test
    void emptyNetworkKeepsADeterministicTargetForLaterStock() {
        assertEquals(Map.of("iron", 3L),
                plan(List.of(input(1L, candidate("iron", 3L), candidate("copper", 3L))), Map.of()));
    }

    @Test
    void partiallyStockedCandidatesAreComparedByDemandCoverage() {
        assertEquals(Map.of("copper", 2L),
                plan(List.of(input(1L, candidate("iron", 100L), candidate("copper", 2L))),
                        Map.of("iron", 10L, "copper", 1L)));
    }

    @Test
    void fullyAvailableCandidateWinsEvenAtLongPrecisionBoundary() {
        assertEquals(Map.of("copper", Long.MAX_VALUE),
                plan(List.of(input(1L, candidate("iron", Long.MAX_VALUE),
                        candidate("copper", Long.MAX_VALUE))),
                        Map.of("iron", Long.MAX_VALUE - 1L, "copper", Long.MAX_VALUE)));
    }

    @Test
    void multiplicationOverflowIsRejected() {
        assertThrows(ArithmeticException.class,
                () -> plan(List.of(input(2L, candidate("water", Long.MAX_VALUE))), Map.of()));
    }

    @Test
    void mergingOverflowIsRejected() {
        assertThrows(ArithmeticException.class,
                () -> plan(List.of(input(1L, candidate("water", Long.MAX_VALUE)),
                        input(1L, candidate("water", 1L))), Map.of()));
    }

    @Test
    void exceedingCapacityRejectsTheWholePlan() {
        assertThrows(IllegalArgumentException.class,
                () -> MEStellarStockTargetPlanner.plan(
                        List.of(input(1L, candidate("iron", 1L)), input(1L, candidate("water", 1L))),
                        key -> 0L, 1));
    }

    @Test
    void duplicateKeysUseOnlyOneConfigurationSlot() {
        assertEquals(Map.of("iron", 2L),
                MEStellarStockTargetPlanner.plan(
                        List.of(input(1L, candidate("iron", 1L)), input(1L, candidate("iron", 1L))),
                        key -> 0L, 1));
    }

    @Test
    void nonPositiveRequirementsAndMissingCandidatesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> plan(List.of(input(0L, candidate("iron", 1L))), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> plan(List.of(input(1L, candidate("iron", 0L))), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> plan(List.of(input(1L)), Map.of()));
    }

    @Test
    void configurationOrderFollowsPatternInputOrder() {
        Map<String, Long> result = plan(List.of(input(1L, candidate("water", 1L)),
                input(1L, candidate("crystal", 1L))), Map.of());
        assertEquals(List.of("water", "crystal"), List.copyOf(result.keySet()));
    }

    @Test
    void capacityUsesBaseFourPagesAndExpandsBySixteen() {
        assertEquals(64, MEStellarStockTargetPlanner.requiredCapacity(List.of()));
        assertEquals(64, MEStellarStockTargetPlanner.requiredCapacity(java.util.stream.IntStream.range(0, 64)
                .mapToObj(index -> new Object()).toList()));
        assertEquals(96, MEStellarStockTargetPlanner.requiredCapacity(java.util.stream.IntStream.range(0, 65)
                .mapToObj(index -> new Object()).toList()));
        assertEquals(96, MEStellarStockTargetPlanner.requiredCapacity(java.util.stream.IntStream.range(0, 80)
                .mapToObj(index -> new Object()).toList()));
        assertEquals(112, MEStellarStockTargetPlanner.requiredCapacity(java.util.stream.IntStream.range(0, 81)
                .mapToObj(index -> new Object()).toList()));
    }

    private static Map<String, Long> plan(List<MEStellarStockTargetPlanner.Input<String>> inputs,
            Map<String, Long> available) {
        return MEStellarStockTargetPlanner.plan(inputs, key -> available.getOrDefault(key, 0L), 64);
    }

    @SafeVarargs
    private static MEStellarStockTargetPlanner.Input<String> input(long multiplier,
            MEStellarStockTargetPlanner.Candidate<String>... candidates) {
        return new MEStellarStockTargetPlanner.Input<>(List.of(candidates), multiplier);
    }

    private static MEStellarStockTargetPlanner.Candidate<String> candidate(String key, long amount) {
        return new MEStellarStockTargetPlanner.Candidate<>(key, amount);
    }
}
