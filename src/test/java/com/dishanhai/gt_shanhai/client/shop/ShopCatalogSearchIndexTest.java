package com.dishanhai.gt_shanhai.client.shop;

import com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：搜索索引的分区、缓存与目录更新行为。 */
class ShopCatalogSearchIndexTest {
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
}
