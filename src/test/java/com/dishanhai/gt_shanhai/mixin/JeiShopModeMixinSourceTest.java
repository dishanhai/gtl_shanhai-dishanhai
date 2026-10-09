package com.dishanhai.gt_shanhai.mixin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JeiShopModeMixinSourceTest {

    private static final Path BUTTON_MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "JeiShopModeButtonMixin.java");
    private static final Path GRID_MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "JeiShopProductGridSourceMixin.java");
    private static final Path MIXIN_CONFIG = Path.of("src", "main", "resources", "gt_shanhai.mixin.json");

    @Test
    void addsNativeBookmarkToolbarButtonAndScopesShopFilterToJeiOverlay() throws IOException {
        String buttonMixin = Files.readString(BUTTON_MIXIN);
        String gridMixin = Files.readString(GRID_MIXIN);
        String config = Files.readString(MIXIN_CONFIG);

        assertTrue(buttonMixin.contains("BookmarkOverlay.class"));
        assertTrue(buttonMixin.contains("historyArea.moveRight(22)"));
        assertTrue(buttonMixin.contains("CombinedInputHandler"));
        assertTrue(gridMixin.contains("IngredientGridWithNavigation.class"));
        assertTrue(gridMixin.contains("ShopJeiProductGridSource.isStandardJeiFilter(source)"));
        assertTrue(gridMixin.contains("new IngredientGridScrollController"));
        assertTrue(Files.readString(Path.of("src", "main", "java", "com", "dishanhai",
                "gt_shanhai", "client", "shop", "ShopJeiProductGridSource.java"))
                .contains("ClientShopJeiMode.isEnabled()"));
        assertTrue(config.contains("\"JeiShopModeButtonMixin\""));
        assertTrue(config.contains("\"JeiShopProductGridSourceMixin\""));
    }
}
