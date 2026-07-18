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
}
