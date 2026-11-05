package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.shop.ShopCatalogSearchIndex;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：多选资源联想只能收窄当前来源和模组的匹配结果，不修改已选清单。 */
class PickerSearchSuggestionsTest {
    private Object index(List<PickerSortEntry> entries) throws Exception {
        return Class.forName("com.dishanhai.gt_shanhai.client.gui.shop.PickerSearchSuggestions")
                .getConstructor(List.class).newInstance(entries);
    }

    @SuppressWarnings("unchecked")
    private List<ShopCatalogSearchIndex.Suggestion> suggest(Object index, String query, List<Integer> matches) throws Exception {
        return (List<ShopCatalogSearchIndex.Suggestion>) index.getClass()
                .getMethod("suggest", String.class, List.class).invoke(index, query, matches);
    }

    private PickerSortEntry entry(int index, String name, String id) {
        return new PickerSortEntry(index, name, id.substring(0, id.indexOf(':')), "", id, 1);
    }

    @Test
    void normalQueryRanksAndCompletesNamesOrIdsWithinCurrentSource() throws Exception {
        var index = index(List.of(entry(0, "Cast Iron", "mod:cast"), entry(1, "Iron Ingot", "mod:ingot"),
                entry(2, "Iron", "mod:iron"), entry(3, "Copper", "other:iron")));
        var result = suggest(index, "iron", List.of(0, 1, 2));
        assertEquals(List.of(2L, 1L, 0L), result.stream().map(ShopCatalogSearchIndex.Suggestion::entryKey).toList());
        assertEquals("Iron", result.get(0).completion());
        assertEquals("other:iron", suggest(index, "other:ir", List.of(3)).get(0).completion());
        assertTrue(suggest(index, "iron", List.of()).isEmpty());
    }

    @Test
    void prefixSearchRefinesMatchingResourcesToAnExactIdWithoutLosingPrefixSyntax() throws Exception {
        var index = index(List.of(entry(0, "Copper", "mod:copper"), entry(1, "Water", "minecraft:water")));
        for (String query : List.of("#conductors", "$conductive", "@mod", "*cop")) {
            var result = suggest(index, query, List.of(0));
            assertEquals(1, result.size(), query);
            assertEquals("*mod:copper", result.get(0).completion(), query);
        }
        assertEquals("*minecraft:water", suggest(index, "#water", List.of(1)).get(0).completion());
        assertTrue(suggest(index, " ", List.of(0)).isEmpty());
    }

    @Test
    void sourceReplacementCannotRetainOldItemsAndCandidatesAreBounded() throws Exception {
        var entries = new java.util.ArrayList<PickerSortEntry>();
        for (int i = 0; i < 12; i++) entries.add(entry(i, "Iron " + i, "mod:iron_" + i));
        var index = index(entries);
        assertEquals(5, suggest(index, "iron", java.util.stream.IntStream.range(0, 12).boxed().toList()).size());
        index.getClass().getMethod("rebuild", List.class).invoke(index, List.of(entry(0, "Water", "minecraft:water")));
        assertEquals("Water", suggest(index, "water", List.of(0)).get(0).completion());
        assertTrue(suggest(index, "iron", List.of(0, 5)).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void paginatedCandidatesKeepTheFirstFiveAndAllRemainingPrefixMatches() throws Exception {
        var entries = new java.util.ArrayList<PickerSortEntry>();
        for (int i = 0; i < 12; i++) entries.add(entry(i, "Iron " + i, "mod:iron_" + i));
        var index = index(entries);
        var matches = java.util.stream.IntStream.range(0, 12).boxed().toList();
        var method = index.getClass().getMethod("suggestAll", String.class, List.class);
        var all = (List<ShopCatalogSearchIndex.Suggestion>) method.invoke(index, "iron", matches);
        assertEquals(12, all.size());
        assertEquals(suggest(index, "iron", matches), all.subList(0, 5));
        var prefixed = (List<ShopCatalogSearchIndex.Suggestion>) method.invoke(index, "#conductors", matches);
        assertEquals(12, prefixed.size());
        assertEquals("*mod:iron_11", prefixed.get(11).completion());
        assertEquals(2, ((List<?>) method.invoke(index, "iron", List.of(3, 8))).size());
    }
}
