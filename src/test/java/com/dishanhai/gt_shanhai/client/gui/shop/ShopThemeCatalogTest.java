package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.shop.ClientShopUiSettings;
import com.dishanhai.gt_shanhai.client.shop.ShopAppearance;
import com.dishanhai.sh_better_ui.client.api.MaterialsApi.ThemeChoice;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 山海：初始化向导不得将共享主题缩减成旧版三项。 */
class ShopThemeCatalogTest {
    @Test
    void onboardingDoesNotRenderOrSelectTheLegacyThreeThemesDirectly() throws Exception {
        String welcome = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopWelcomeScreen.java"));
        assertFalse(welcome.contains("ClientShopUiSettings.THEME_SMOKE"));
    }

    @Test
    void savedCustomThemeCanLoadBeforeTheProviderRegistersIt() throws Exception {
        var normalize = ClientShopUiSettings.class.getDeclaredMethod("normalizeTheme", String.class);
        normalize.setAccessible(true);
        assertEquals("example:obsidian", normalize.invoke(null, "example:obsidian"));
        assertEquals("classic", normalize.invoke(null, "bad ID"));
    }

    @Test
    void materialOverridesRoundTripBeforeTheProviderRegisters() {
        var saved = JsonParser.parseString("""
                {"background":"none","material":"example:obsidian","effects":{}}
                """);
        assertEquals(saved, ShopAppearance.fromJson(saved).toJson());
    }

    @Test
    void onboardingCanReachARegisteredThemeAfterTheFirstPage() {
        var custom = new ThemeChoice("example:obsidian", "example.obsidian");
        var choices = List.of(new ThemeChoice("classic", "example.classic"), new ThemeChoice("glass", "example.glass"),
                new ThemeChoice("smoke", "example.smoke"), custom);
        assertEquals(choices.subList(0, 3), ShopWelcomeScreen.themesOnPage(choices, 0));
        assertEquals(List.of(custom), ShopWelcomeScreen.themesOnPage(choices, 1));
    }
}
