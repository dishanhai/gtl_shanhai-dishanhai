package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：保护全屏商店接线，避免退回背包取物或跨分区全局搜索。 */
class ShopResourceFilterScreenSourceTest {
    private String screen() throws Exception {
        return Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopScreen.java"));
    }

    @Test
    void resourcePickerPreselectsAndOnlyAppliesItsDraftAfterConfirmation() throws Exception {
        String source = screen();
        assertTrue(source.contains("private void openResourceFilterPicker()"));
        assertTrue(source.contains("picker.preselect(stack, 1L)"));
        assertTrue(source.contains("picker.preselect(fluid)"));
        assertTrue(source.contains("picker.setAfterConfirm(() ->"));
        assertTrue(source.contains("resourceFilter = List.copyOf(draft)"));
    }

    @Test
    void searchResponderSelectsAllOnceWhileRecomputeUsesCurrentCategoryScope() throws Exception {
        String source = screen();
        assertTrue(source.contains("searchBox.setResponder(this::onShopSearchChanged)"));
        assertTrue(source.contains("ClientShopCatalog.searchKeys(q, selectedTop, selectedSub, selectedSub2, selectedSub3)"));
        assertTrue(source.contains("selectAllForSearch()"));
        assertTrue(source.contains("btn == 1 && hit(mx, my, searchBoxX(), searchBoxY(), SEARCH_W, 12)"));
        assertTrue(source.contains("searchBox.setValue(\"\")"));
        assertTrue(source.contains("Math.max(resourceFilterY() + 18,"));
    }

    @Test
    void aggregatedOrFilteredResultsCannotBeReorderedAsACompleteLeafCategory() throws Exception {
        String source = screen();
        String leaf = source.substring(source.indexOf("private boolean isLeafCategoryView()"),
                source.indexOf("private String currentViewCategory()"));
        assertTrue(leaf.contains("selectedTop.isEmpty()"));
        assertTrue(source.contains("btn == 0 && dragEnabled() && resourceFilter.isEmpty()"));
    }
}
