package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopScreenCatalogSourceTest {

    @Test
    void screenUsesClientManifestAndTrueViewportVirtualization() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopScreen.java"));

        assertAll(
                () -> assertTrue(source.contains("ClientShopCatalog")),
                () -> assertTrue(source.contains("visibleEntryKeys")),
                () -> assertTrue(source.contains("ShopGridViewport.visibleRange")),
                () -> assertTrue(source.contains("ShopGridViewport.indexAt")),
                () -> assertTrue(source.contains("drawLoadingCell")),
                () -> assertTrue(source.contains("cardCategoryBadge")),
                () -> assertTrue(source.contains("GuiRenderUtil.trimText(this.font, categoryBadge")),
                () -> assertFalse(source.contains("ShopConfig.getTopCategories")),
                () -> assertFalse(source.contains("ShopConfig.getSubCategories")),
                () -> assertFalse(source.contains("ShopConfig.getEntriesOfGroup")));
    }
}
