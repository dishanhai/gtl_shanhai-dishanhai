package com.dishanhai.gt_shanhai.common.compat.eaep;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EaepProviderRecipeTypeBridgeTest {

    @Test
    void exactRecipeTypeMatchBeatsShorterContainedType() {
        List<Long> ids = new ArrayList<>(List.of(1L, 2L));
        List<String> names = new ArrayList<>(List.of("通用组装主机", "电路主机"));
        List<Integer> slots = new ArrayList<>(List.of(10, 20));
        List<Integer> counts = new ArrayList<>(List.of(1, 1));
        Map<String, Set<String>> types = new LinkedHashMap<>();
        types.put("通用组装主机", Set.of("assembler"));
        types.put("电路主机", Set.of("circuit_assembler"));

        EaepProviderRecipeTypeBridge.sortProvidersByRecipeTypeMatch(
                ids, names, slots, counts, types, "circuit_assembler");

        assertEquals(List.of("电路主机", "通用组装主机"), names);
        assertEquals(List.of(2L, 1L), ids);
    }

    @Test
    void sortingScoresEachProviderOnceAndPreservesAlignedStableRows() {
        List<Long> ids = new ArrayList<>(List.of(1L, 2L, 3L, 4L));
        List<String> names = new ArrayList<>(List.of("partial", "exact-first", "unmatched", "exact-second"));
        List<Integer> slots = new ArrayList<>(List.of(10, 20, 30, 40));
        List<Integer> counts = new ArrayList<>(List.of(1, 2, 3, 4));
        CountingRecipeTypes types = new CountingRecipeTypes();
        types.put("partial", Set.of("assembler"));
        types.put("exact-first", Set.of("circuit_assembler"));
        types.put("unmatched", Set.of("macerator"));
        types.put("exact-second", Set.of("circuit_assembler"));

        EaepProviderRecipeTypeBridge.sortProvidersByRecipeTypeMatch(
                ids, names, slots, counts, types, "circuit_assembler");

        assertEquals(Map.of("partial", 1, "exact-first", 1, "unmatched", 1, "exact-second", 1),
                types.lookups, "每個供應器只應算分一次，排序比較器不可再查詢類型或設定檔");
        assertEquals(List.of("exact-first", "exact-second", "partial", "unmatched"), names);
        assertEquals(List.of(2L, 4L, 1L, 3L), ids);
        assertEquals(List.of(20, 40, 10, 30), slots);
        assertEquals(List.of(2, 4, 1, 3), counts);
    }

    private static final class CountingRecipeTypes extends LinkedHashMap<String, Set<String>> {
        private final Map<String, Integer> lookups = new LinkedHashMap<>();

        @Override
        public Set<String> get(Object key) {
            lookups.merge((String) key, 1, Integer::sum);
            return super.get(key);
        }
    }
}
