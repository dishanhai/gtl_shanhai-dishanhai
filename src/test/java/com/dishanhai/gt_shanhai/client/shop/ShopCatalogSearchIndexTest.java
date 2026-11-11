package com.dishanhai.gt_shanhai.client.shop;

import com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：搜索索引的分区、缓存与目录更新行为。 */
class ShopCatalogSearchIndexTest {
    @Test
    void emptyNameIdFallbackDoesNotOutrankRealNamesOrNeedLoadedChunks() {
        var unloaded = new ShopCatalogManifest.Stub(91, "unloaded", "", "", "", false,
                Integer.MAX_VALUE, "", "", List.of("demo:iron"), "unloaded-91", "");
        var index = new ShopCatalogSearchIndex(List.of(unloaded,
                stub(92, false, "demo:iron", "other:iron")));
        assertEquals(List.of(91L, 92L), index.search("demo:iron", "unloaded", null));
        var suggestions = index.suggest("demo:iron", List.of(91L, 92L), 5);
        assertEquals(List.of(92L, 91L), suggestions.stream()
                .map(ShopCatalogSearchIndex.Suggestion::entryKey).toList());
        assertEquals("demo:iron", suggestions.getLast().displayName());
        assertEquals("demo:iron", suggestions.getLast().completion());
    }

    @Test
    void localizedNamesMatchPinyinWithoutOptionalSearchMods() {
        var index = new ShopCatalogSearchIndex(List.of(stub(1, false, "铁锭", "mod:iron"),
                stub(2, false, "铜锭", "mod:copper"), stub(3, true, "铁锭块", "mod:iron_block")));
        for (String query : List.of("tieding", "铁 ding", "tieding mod:iron")) {
            assertEquals(List.of(1L), index.search(query, "all", null), query);
            assertEquals(List.of(1L), index.suggest(query, null, 20).stream()
                    .map(ShopCatalogSearchIndex.Suggestion::entryKey).toList(), query);
        }
        assertEquals(List.of(1L, 2L), index.search("td", "all", null));
        assertEquals(List.of(2L), index.search("td", "copper", List.of(2L)));
        assertEquals(List.of(2L, 1L), index.search("td", "reverse", List.of(2L, 3L, 1L)));
        assertEquals(List.of(2L, 1L), index.suggest("td", List.of(2L, 3L, 1L), 20).stream()
                .map(ShopCatalogSearchIndex.Suggestion::entryKey).toList());
    }

    @Test
    void catalogKeepsLiteralPrefixWordsPunctuationAndEmptyNames() {
        var index = new ShopCatalogSearchIndex(List.of(stub(1, false, "Block #1", "mod:block"),
                stub(2, false, "Block @home", "mod:home"), stub(3, false, "", "mod:unnamed"),
                stub(4, false, "Named", "mod:unnamed_plate"),
                stub(5, false, "Iron, Ingot", "mod:ingot")));
        assertEquals(List.of(1L), index.search("#1", "all", null));
        assertEquals(List.of(2L), index.search("@home", "all", null));
        assertEquals(List.of(1L), index.suggest("#1", null, 20).stream()
                .map(ShopCatalogSearchIndex.Suggestion::entryKey).toList());
        assertEquals("Block #1", index.suggest("#1", null, 20).getFirst().completion());
        assertEquals(List.of(3L, 4L), index.suggest("mod:unnamed", List.of(4L, 3L), 20).stream()
                .map(ShopCatalogSearchIndex.Suggestion::entryKey).toList());
        assertEquals("mod:unnamed", index.suggest("mod:unnamed", null, 20).getFirst().completion());
        assertEquals("mod:unnamed", index.suggest("mod:unnamed", null, 20).getFirst().displayName());
        assertEquals("Iron, Ingot", index.suggest("iron ingot", null, 20).getFirst().completion());
    }

    @Test
    void externallyMatchedSuggestionsKeepForeignMatchesAndHostOrder() {
        var index = new ShopCatalogSearchIndex(List.of(stub(1, false, "Copper", "mod:copper"),
                stub(2, false, "Iron", "mod:iron"), stub(3, false, "Iron Plate", "mod:plate")));
        var matches = List.of(1L, 3L, 999L, 2L);
        assertEquals(List.of(2L, 3L, 1L), index.suggestMatches("iron", matches, 20).stream()
                .map(ShopCatalogSearchIndex.Suggestion::entryKey).toList());
        assertEquals(List.of(3L, 1L), index.suggestMatches("conductors", List.of(3L, 1L), 20).stream()
                .map(ShopCatalogSearchIndex.Suggestion::entryKey).toList());
    }

    private ShopCatalogManifest.Stub stub(long key, boolean hidden, String name, String id) {
        return new ShopCatalogManifest.Stub(key, "materials", "", "", "", hidden, 0,
                "", name, List.of(id), "stable-" + key, "");
    }

    private Object index(List<ShopCatalogManifest.Stub> stubs) {
        return assertDoesNotThrow(() -> Class.forName(
                "com.dishanhai.gt_shanhai.client.shop.ShopCatalogSearchIndex")
                .getConstructor(List.class).newInstance(stubs), "缺少预建搜索索引");
    }

    @SuppressWarnings("unchecked")
    private List<Long> search(Object index, String query, String scope, List<Long> candidates) throws Exception {
        return (List<Long>) index.getClass().getMethod("search", String.class, String.class, List.class)
                .invoke(index, query, scope, candidates);
    }

    @Test
    void scopedSearchNeverReturnsOtherCategoriesAndPreservesCandidateOrder() throws Exception {
        var index = index(List.of(stub(1, false, "Iron Ingot", "minecraft:iron_ingot"),
                stub(2, false, "Iron Dust", "mod:iron_dust"), stub(3, true, "Iron Secret", "mod:secret")));
        assertEquals(List.of(1L, 2L), search(index, "iron", "all", null));
        assertEquals(List.of(2L), search(index, "iron", "dust", List.of(2L)));
        assertEquals(List.of(2L, 1L), search(index, "iron", "reverse", List.of(2L, 3L, 1L)));
        assertEquals(List.of(), search(index, "iron", "empty", List.of()));
    }

    @Test
    void repeatedNormalizedQueryReusesResultAndRebuildInvalidatesIt() throws Exception {
        var index = index(List.of(stub(1, false, "Iron Ingot", "minecraft:iron_ingot")));
        var first = search(index, "  IRON  ", "all", null);
        assertSame(first, search(index, "iron", "all", null));
        index.getClass().getMethod("rebuild", List.class).invoke(index,
                List.of(stub(4, false, "Iron Dust", "mod:dust")));
        assertEquals(List.of(4L), search(index, "iron", "all", null));
    }

    @Test
    void cachedSearchKeepsMultiWordPunctuationAndChineseMatching() throws Exception {
        var index = index(List.of(stub(1, false, "铁锭 Iron Ingot", "minecraft:iron_ingot"),
                stub(2, false, "Copper Ingot", "mod:copper_ingot")));
        assertEquals(List.of(1L), search(index, "IRON, ingot", "all", null));
        assertEquals(List.of(1L), search(index, "铁锭", "all", null));
        assertEquals(List.of(1L), search(index, "minecraft:iron", "all", null));
        assertEquals(List.of(1L, 2L), search(index, "", "all", null));
    }

    @SuppressWarnings("unchecked")
    private List<Object> suggest(Object index, String query, List<Long> candidates, int limit) throws Exception {
        return (List<Object>) index.getClass().getMethod("suggest", String.class, List.class, int.class)
                .invoke(index, query, candidates, limit);
    }

    private Object property(Object suggestion, String name) throws Exception {
        return suggestion.getClass().getMethod(name).invoke(suggestion);
    }

    @Test
    void suggestionsRankExactNameBeforePrefixBeforeSubstringAndId() throws Exception {
        var index = index(List.of(stub(1, false, "Copper", "mod:iron"),
                stub(2, false, "Cast Iron", "mod:cast"), stub(3, false, "Iron Ingot", "mod:ingot"),
                stub(4, false, "Iron", "mod:metal")));
        var result = suggest(index, "iron", null, 5);
        assertEquals(4, result.size());
        assertEquals(4L, property(result.get(0), "entryKey"));
        assertEquals(3L, property(result.get(1), "entryKey"));
        assertEquals(2L, property(result.get(2), "entryKey"));
        assertEquals(1L, property(result.get(3), "entryKey"));
        assertEquals("Iron", property(result.get(0), "completion"));
        assertEquals("mod:iron", property(result.get(3), "completion"));
    }

    @Test
    void suggestionsRespectFiltersHiddenEntriesLimitAndStableOrder() throws Exception {
        var index = index(List.of(stub(1, false, "Iron A", "mod:a"),
                stub(2, false, "Iron B", "mod:b"), stub(3, true, "Iron C", "mod:c")));
        var result = suggest(index, "iron", List.of(2L, 3L, 1L, 999L), 1);
        assertEquals(1, result.size());
        assertEquals(2L, property(result.get(0), "entryKey"));
        assertTrue(suggest(index, "iron", List.of(), 5).isEmpty());
        assertTrue(suggest(index, "iron", null, 0).isEmpty());
        assertTrue(suggest(index, " , ", null, 5).isEmpty());
    }

    @Test
    void suggestionsKeepChineseMultiWordMatchingAndRefreshOnRebuild() throws Exception {
        var index = index(List.of(stub(1, false, "铁锭 Iron Ingot", "minecraft:iron_ingot")));
        assertEquals("铁锭 Iron Ingot", property(suggest(index, "铁锭, ingot", null, 5).get(0), "completion"));
        assertEquals("minecraft:iron_ingot", property(suggest(index, "minecraft:iron", null, 5).get(0), "completion"));
        index.getClass().getMethod("rebuild", List.class).invoke(index,
                List.of(stub(2, false, "Iron Dust", "mod:dust")));
        var result = suggest(index, "iron", null, 5);
        assertEquals(1, result.size());
        assertEquals(2L, property(result.get(0), "entryKey"));
        assertTrue(suggest(index, "铁锭", null, 5).isEmpty());
    }

    @Test
    void formattedNamesCompleteVisibleTextAndKeepTheirDisplayStyle() throws Exception {
        var index = index(List.of(stub(1, false, "§b&$ultimate-Iron Ingot", "mod:ingot"),
                stub(2, false, "Cast Iron", "mod:cast")));
        var result = suggest(index, "iron", null, 5);
        assertEquals(1L, property(result.get(0), "entryKey"));
        assertEquals("Iron Ingot", property(result.get(0), "completion"));
        assertEquals("§b&$ultimate-Iron Ingot", property(result.get(0), "displayName"));
        assertEquals(List.of(1L), search(index, "iron ingot", "all", null));
    }

    @Test
    void expandedSuggestionsKeepTheOriginalTopFiveAndRankRemainingMatchesStably() {
        var index = new ShopCatalogSearchIndex(List.of(
                stub(1, false, "Copper", "mod:iron"), stub(2, false, "Cast Iron", "mod:cast"),
                stub(3, false, "Iron Ingot", "mod:ingot"), stub(4, false, "Iron", "mod:metal"),
                stub(5, false, "Iron Dust", "mod:dust"), stub(6, false, "Hot Iron", "mod:hot"),
                stub(7, false, "Tin", "iron"), stub(8, false, "Gold", "iron:gold"),
                stub(9, true, "Iron Secret", "mod:secret"), stub(10, false, "No Match", "mod:none")));
        var all = index.suggest("iron", null, Integer.MAX_VALUE);
        assertEquals(List.of(4L, 3L, 5L, 2L, 6L, 7L, 8L, 1L),
                all.stream().map(ShopCatalogSearchIndex.Suggestion::entryKey).toList());
        assertEquals(index.suggest("iron", null, 5), all.subList(0, 5));
        assertEquals(List.of(6L, 2L, 1L), index.suggest("iron", List.of(1L, 6L, 9L, 2L, 999L), 20)
                .stream().map(ShopCatalogSearchIndex.Suggestion::entryKey).toList());
    }
}
