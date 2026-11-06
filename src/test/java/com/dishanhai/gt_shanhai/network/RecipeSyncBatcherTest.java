package com.dishanhai.gt_shanhai.network;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RecipeSyncBatcherTest {
    @Test
    void sendsAllSixtyFiveRecipesWithoutSpuriousDeletion() {
        assertAllUpdates(65, 2);
    }

    @Test
    void honorsExactBoundaryAndMultipleFullBatches() {
        assertAllUpdates(64, 1);
        assertAllUpdates(130, 3);
    }

    private static void assertAllUpdates(int count, int expectedBatches) {
        Map<String, Integer> available = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) available.put("gtceu:recipe_" + i, i);
        var batches = RecipeSyncBatcher.partition(available.keySet(), available, 64);
        assertEquals(expectedBatches, batches.size());
        Map<String, Integer> received = new LinkedHashMap<>();
        for (var batch : batches) {
            assertTrue(batch.updates().size() <= 64);
            assertTrue(batch.removed().isEmpty());
            batch.updates().forEach((id, recipe) -> assertNull(received.put(id, recipe)));
        }
        assertEquals(available, received);
    }

    @Test
    void separatesActualDeletionsAndDoesNotLoseMoreThanFourThousandIds() {
        List<String> wanted = new ArrayList<>();
        Map<String, Integer> available = new LinkedHashMap<>();
        for (int i = 0; i < 5000; i++) {
            String id = "gtceu:recipe_" + i;
            wanted.add(id);
            if (i % 3 == 0) available.put(id, i);
        }
        Map<String, Integer> received = new LinkedHashMap<>();
        var removed = new LinkedHashSet<String>();
        for (var batch : RecipeSyncBatcher.partition(wanted, available, 64)) {
            assertTrue(batch.updates().size() + batch.removed().size() <= 64);
            batch.updates().forEach((id, recipe) -> assertNull(received.put(id, recipe)));
            for (String id : batch.removed()) assertTrue(removed.add(id));
        }
        assertEquals(available, received);
        assertTrue(removed.stream().noneMatch(available::containsKey));
        assertEquals(wanted.size(), received.size() + removed.size());
    }

    @Test
    void deduplicatesIdsAndIgnoresEmptyRequests() {
        var wanted = new ArrayList<>(List.of("", "a", "a", "missing"));
        wanted.add(null);
        var batches = RecipeSyncBatcher.partition(wanted, Map.of("a", 1), 64);
        assertEquals(1, batches.size());
        assertEquals(Map.of("a", 1), batches.getFirst().updates());
        assertEquals(List.of("missing"), batches.getFirst().removed());
        assertTrue(RecipeSyncBatcher.partition(List.of(), Map.of(), 64).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> RecipeSyncBatcher.partition(wanted, Map.of(), 0));
    }
}
