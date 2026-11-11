package com.dishanhai.gt_shanhai.client.shop;

import com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest;
import com.dishanhai.gt_shanhai.common.shop.ShopResourceFilter.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopCatalogEditViewTest {
    private static final Resource ITEM = new Resource(false, "minecraft:diamond", "");

    @AfterEach
    void resetCatalog() {
        ClientShopCatalog.clear();
    }

    @Test
    void editingIncludesHiddenEntriesInCategoriesSearchSuggestionsAndResourceFilters() {
        ClientShopCatalog.applyManifest(new ShopCatalogManifest(1L, true, List.of(
                stub(0, "Public", "", "", "", false),
                stub(1, "Public", "Private", "Deep", "Leaf", true),
                stub(2, "Hidden only", "", "", "", true)),
                Map.of("", List.of("Hidden only", "Public"))));

        var browse = ClientShopCatalog.view(false);
        var edit = ClientShopCatalog.view(true);
        assertEquals(List.of("Public"), browse.topCategories());
        assertEquals(List.of("Hidden only", "Public"), edit.topCategories());
        assertEquals(List.of("Private"), edit.subCategories("Public"));
        assertEquals(List.of("Deep"), edit.subCategories2("Public", "Private"));
        assertEquals(List.of("Leaf"), edit.subCategories3("Public", "Private", "Deep"));
        assertEquals(List.of(0L), browse.keysOfGroup("", "", "", ""));
        assertEquals(List.of(0L, 1L, 2L), edit.keysOfGroup("", "", "", ""));
        assertEquals(List.of(1L), edit.keysOfGroup("Public", "Private", "Deep", "Leaf"));
        assertEquals(List.of(2L), edit.keysOfGroup("Hidden only", "", "", ""));
        assertEquals(List.of(0L), browse.searchKeys("diamond", "Public", "", "", ""));
        assertEquals(List.of(0L, 1L), edit.searchKeys("diamond", "Public", "", "", ""));
        assertEquals(List.of(1L), edit.searchKeys("diamond", "Public", "Private", "Deep", "Leaf"));
        assertEquals(List.of(1L), edit.searchSuggestions("diamond", List.of(1L), 5).stream()
                .map(ShopCatalogSearchIndex.Suggestion::entryKey).toList());
        assertEquals(List.of(0L), browse.resourceKeys(List.of(ITEM), false));
        assertEquals(List.of(0L, 1L, 2L), edit.resourceKeys(List.of(ITEM), false));

        assertEquals(List.of(0L), ClientShopCatalog.keysOfGoodsId(ITEM.id()));
        assertEquals(List.of(0L), ClientShopCatalog.keysOfPrerequisiteQuest("AB"));
        assertEquals(1L, ClientShopCatalog.resolveLink("link-1"));
    }

    @Test
    void newRevisionRefreshesBothViewsAfterUnhiding() {
        ClientShopCatalog.applyManifest(new ShopCatalogManifest(1L, true,
                List.of(stub(0, "Private", "", "", "", true)), Map.of()));
        assertEquals(List.of(), ClientShopCatalog.view(false).searchKeys("diamond", "", "", "", ""));
        assertEquals(List.of(0L), ClientShopCatalog.view(true).searchKeys("diamond", "", "", "", ""));

        ClientShopCatalog.applyManifest(new ShopCatalogManifest(2L, true,
                List.of(stub(0, "Public", "", "", "", false)), Map.of()));
        assertEquals(List.of("Public"), ClientShopCatalog.view(true).topCategories());
        assertEquals(List.of(0L), ClientShopCatalog.view(false).searchKeys("diamond", "", "", "", ""));
    }

    @Test
    void personalHidingOnlyAffectsTheOwnerAndCannotRevealPubliclyHiddenGoods() {
        var personal = stub(0, "Public", "", "", "", false);
        var global = stub(1, "Public", "", "", "", true);
        var manifest = new ShopCatalogManifest(1L, true, List.of(personal, global), Map.of());
        var browse = new ShopCatalogView(manifest, false);
        var edit = new ShopCatalogView(manifest, true);
        var owner = Set.of(personal.stableId(), global.stableId());

        assertFalse(browse.includes(personal, owner));
        assertTrue(browse.includes(personal, Set.of()));
        assertFalse(browse.includes(global, Set.of()));
        assertTrue(edit.includes(personal, owner));
        assertTrue(edit.includes(global, owner));
        assertEquals(List.of(personal), browse.personalHiddenEntries(owner));
        assertEquals(List.of(personal), edit.personalHiddenEntries(owner));
        assertEquals(List.of(), browse.personalHiddenEntries(Set.of()));
    }

    private static ShopCatalogManifest.Stub stub(long key, String top, String sub, String sub2, String sub3,
                                                  boolean hidden) {
        return new ShopCatalogManifest.Stub(key, top, sub, sub2, sub3, hidden, 0, "link-" + key,
                "Diamond " + key, List.of(ITEM.id()), "stable-" + key, "AB", List.of(), List.of(ITEM), List.of());
    }
}
