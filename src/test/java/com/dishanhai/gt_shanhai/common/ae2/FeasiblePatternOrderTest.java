package com.dishanhai.gt_shanhai.common.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

class FeasiblePatternOrderTest {

    private static final Function<FeasiblePatternOrder.Pattern, FeasiblePatternOrder.Pattern> IDENTITY = pattern -> pattern;

    @Test
    void shortCycleYieldsToLongerPatternWhenDustOnlyComesFromTheFluid() {
        FeasiblePatternOrder.Pattern shortPattern = produce("fluid", 1L, slot("dust", 2L));
        FeasiblePatternOrder.Pattern longPattern = produce("fluid", 1L, slot("ore", 1L), slot("circuit", 1L));
        Catalog catalog = new Catalog();
        catalog.patterns.put("dust", List.of(produce("dust", 1L, slot("fluid", 1L))));

        List<FeasiblePatternOrder.Pattern> order = new ArrayList<>(List.of(shortPattern, longPattern));

        assertTrue(demote(order, "fluid", 1L, catalog));
        assertSame(longPattern, order.get(0));
        assertSame(shortPattern, order.get(1));
    }

    @Test
    void threeStepCycleStillYields() {
        FeasiblePatternOrder.Pattern shortPattern = produce("fluid", 1L, slot("dust", 2L));
        FeasiblePatternOrder.Pattern longPattern = produce("fluid", 1L, slot("ore", 1L));
        Catalog catalog = new Catalog();
        catalog.patterns.put("dust", List.of(produce("dust", 1L, slot("ingot", 1L))));
        catalog.patterns.put("ingot", List.of(produce("ingot", 1L, slot("fluid", 1L))));

        List<FeasiblePatternOrder.Pattern> order = new ArrayList<>(List.of(shortPattern, longPattern));

        assertTrue(demote(order, "fluid", 1L, catalog));
        assertSame(longPattern, order.get(0));
    }

    @Test
    void enoughDustKeepsTheShortPatternFirst() {
        FeasiblePatternOrder.Pattern shortPattern = produce("fluid", 1L, slot("dust", 2L));
        FeasiblePatternOrder.Pattern longPattern = produce("fluid", 1L, slot("ore", 1L));
        Catalog catalog = new Catalog();
        catalog.patterns.put("dust", List.of(produce("dust", 1L, slot("fluid", 1L))));
        catalog.stock.put("dust", 2L);

        List<FeasiblePatternOrder.Pattern> order = new ArrayList<>(List.of(shortPattern, longPattern));

        assertFalse(demote(order, "fluid", 1L, catalog));
        assertSame(shortPattern, order.get(0));
    }

    @Test
    void partialStockStillCountsAsACycleForTheWholeOrder() {
        FeasiblePatternOrder.Pattern shortPattern = produce("fluid", 1L, slot("dust", 2L));
        FeasiblePatternOrder.Pattern longPattern = produce("fluid", 1L, slot("ore", 1L));
        Catalog catalog = new Catalog();
        catalog.patterns.put("dust", List.of(produce("dust", 1L, slot("fluid", 1L))));
        catalog.stock.put("dust", 2L);

        List<FeasiblePatternOrder.Pattern> order = new ArrayList<>(List.of(shortPattern, longPattern));

        assertTrue(demote(order, "fluid", 2L, catalog));
        assertSame(longPattern, order.get(0));
    }

    @Test
    void everyPatternCyclingLeavesTheOrderAlone() {
        FeasiblePatternOrder.Pattern first = produce("fluid", 1L, slot("dust", 1L));
        FeasiblePatternOrder.Pattern second = produce("fluid", 1L, slot("dust", 3L));
        Catalog catalog = new Catalog();
        catalog.patterns.put("dust", List.of(produce("dust", 1L, slot("fluid", 1L))));
        List<FeasiblePatternOrder.Pattern> order = new ArrayList<>(List.of(first, second));

        assertFalse(demote(order, "fluid", 1L, catalog));
        assertEquals(List.of(first, second), order);
    }

    @Test
    void exhaustedVisitBudgetDoesNotDemote() {
        FeasiblePatternOrder.Pattern shortPattern = produce("fluid", 1L, slot("dust", 2L));
        FeasiblePatternOrder.Pattern longPattern = produce("fluid", 1L, slot("ore", 1L));
        Catalog catalog = new Catalog();
        catalog.patterns.put("dust", List.of(produce("dust", 1L, slot("fluid", 1L))));
        List<FeasiblePatternOrder.Pattern> order = new ArrayList<>(List.of(shortPattern, longPattern));

        assertFalse(FeasiblePatternOrder.demoteLeadingCycles(order, IDENTITY, "fluid", 1L, catalog, 16, 0));
        assertSame(shortPattern, order.get(0));
    }

    @Test
    void singlePatternIsNotReordered() {
        FeasiblePatternOrder.Pattern only = produce("fluid", 1L, slot("dust", 1L));
        List<FeasiblePatternOrder.Pattern> order = new ArrayList<>(List.of(only));

        assertFalse(demote(order, "fluid", 1L, new Catalog()));
        assertSame(only, order.get(0));
    }

    @Test
    void leadingPoisonedPrefixMovesBehindFeasiblePatterns() {
        FeasiblePatternOrder.Pattern poisoned = produce("fluid", 1L, slot("dust", 1L));
        FeasiblePatternOrder.Pattern secondPoisoned = produce("fluid", 1L, slot("dust", 4L));
        FeasiblePatternOrder.Pattern firstFeasible = produce("fluid", 1L, slot("ore", 1L));
        FeasiblePatternOrder.Pattern secondFeasible = produce("fluid", 1L, slot("circuit", 1L));
        Catalog catalog = new Catalog();
        catalog.patterns.put("dust", List.of(produce("dust", 1L, slot("fluid", 1L))));
        List<FeasiblePatternOrder.Pattern> order = new ArrayList<>(
                List.of(poisoned, secondPoisoned, firstFeasible, secondFeasible));

        assertTrue(demote(order, "fluid", 1L, catalog));
        assertEquals(List.of(firstFeasible, secondFeasible, poisoned, secondPoisoned), order);
    }

    @Test
    void emitableDustIsARealSource() {
        FeasiblePatternOrder.Pattern shortPattern = produce("fluid", 1L, slot("dust", 2L));
        FeasiblePatternOrder.Pattern longPattern = produce("fluid", 1L, slot("ore", 1L));
        Catalog catalog = new Catalog();
        catalog.patterns.put("dust", List.of(produce("dust", 1L, slot("fluid", 1L))));
        catalog.emitted.add("dust");
        List<FeasiblePatternOrder.Pattern> order = new ArrayList<>(List.of(shortPattern, longPattern));

        assertFalse(demote(order, "fluid", 1L, catalog));
        assertSame(shortPattern, order.get(0));
    }

    private static boolean demote(List<FeasiblePatternOrder.Pattern> order, Object product, long requested,
            Catalog catalog) {
        return FeasiblePatternOrder.demoteLeadingCycles(order, IDENTITY, product, requested, catalog);
    }

    private static FeasiblePatternOrder.Slot slot(Object key, long perCraft) {
        return new FeasiblePatternOrder.Slot(key, perCraft);
    }

    private static FeasiblePatternOrder.Pattern produce(Object output, long amount, FeasiblePatternOrder.Slot... inputs) {
        Set<Object> involved = new HashSet<>();
        involved.add(output);
        List<FeasiblePatternOrder.Slot> slots = new ArrayList<>();
        for (FeasiblePatternOrder.Slot input : inputs) {
            slots.add(input);
            if (input.key() != null) involved.add(input.key());
        }
        return new FeasiblePatternOrder.Pattern(amount, slots, involved);
    }

    private static final class Catalog implements FeasiblePatternOrder.Lookup {
        private final Map<Object, List<FeasiblePatternOrder.Pattern>> patterns = new HashMap<>();
        private final Map<Object, Long> stock = new HashMap<>();
        private final Set<Object> emitted = new HashSet<>();

        @Override
        public boolean canEmit(Object key) {
            return emitted.contains(key);
        }

        @Override
        public List<FeasiblePatternOrder.Pattern> patternsFor(Object key) {
            return patterns.getOrDefault(key, List.of());
        }

        @Override
        public long available(Object key) {
            return stock.getOrDefault(key, 0L);
        }
    }
}
