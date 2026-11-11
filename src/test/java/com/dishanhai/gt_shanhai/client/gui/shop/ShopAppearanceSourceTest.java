package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 山海：从正式商店和设置入口使用同一套外观配置与背景绘制。 */
class ShopAppearanceSourceTest {
    private static final Path CLIENT = Path.of("src/main/java/com/dishanhai/gt_shanhai/client");

    @Test
    void savesAnIndependentAppearanceAndUsesItInTheShop() throws Exception {
        String prefs = Files.readString(CLIENT.resolve("shop/ClientShopUiSettings.java"));
        String shop = Files.readString(CLIENT.resolve("gui/shop/ShopScreen.java"));
        String settings = Files.readString(CLIENT.resolve("gui/shop/ShopSettingsScreen.java"));
        assertTrue(prefs.contains("root.add(\"shopAppearance\""));
        assertTrue(shop.contains("ClientShopUiSettings.shopAppearance()"));
        assertTrue(settings.contains("new ShopThemeEditorScreen("));
        assertTrue(settings.contains("setShopAppearance("));
    }
}
