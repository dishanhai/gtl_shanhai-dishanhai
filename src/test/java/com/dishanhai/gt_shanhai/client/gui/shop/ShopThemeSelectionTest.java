package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.shop.ClientShopUiSettings;
import com.dishanhai.gt_shanhai.client.shop.ShopAppearance;
import com.dishanhai.sh_better_ui.client.api.MaterialsApi;
import com.dishanhai.sh_better_ui.client.glass.GlassTheme;
import com.dishanhai.sh_better_ui.theme.Theme;
import com.dishanhai.sh_better_ui.theme.ThemeManager;
import com.dishanhai.sh_better_ui.theme.token.ColorValue;
import com.dishanhai.sh_better_ui.theme.token.ThemeTokens;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：所有主题可从普通设置入口切换，保存规范化不丢失新主题。 */
class ShopThemeSelectionTest {
    @Test
    void quickSelectionVisitsEveryChoiceWithoutFallingBack() throws Exception {
        var normalize = ClientShopUiSettings.class.getDeclaredMethod("normalizeTheme", String.class);
        normalize.setAccessible(true);
        var seen = new java.util.HashSet<String>();
        String id = "classic";
        try (var manager = new ThemeManager()) {
            var api = materials(manager);
            var choices = api.themeChoices();
            for (var choice : choices) {
                assertEquals(choice.id(), id);
                assertTrue(seen.add(id));
                assertEquals(id, normalize.invoke(null, id));
                if (!id.equals("classic")) assertNotSame(GlassTheme.CLASSIC, api.findTheme(id).orElseThrow(), id);
                id = ShopTheme.adjacent(id, 1, choices);
            }
        }
        assertEquals("classic", id);
        assertEquals("classic", normalize.invoke(null, new Object[]{null}));
        assertEquals("classic", normalize.invoke(null, "removed"));
        assertTrue(seen.containsAll(java.util.Set.of("classic", "glass", "smoke", "slate", "liquidGlass", "network",
                "aurora", "amethyst", "rose", "ember")));
    }

    @Test
    void customMaterialSelectionAdvancesFromTheVisibleTheme() throws Exception {
        try (var manager = new ThemeManager()) {
            var visible = ShopTheme.visibleId("classic", ShopAppearance.Material.SLATE.id);
            assertEquals("liquidGlass", ShopTheme.adjacent(visible, 1, materials(manager).themeChoices()));
        }
    }

    @Test
    void lateProviderRegistrationEntersTheCatalogWithItsActualPalette() throws Exception {
        try (var manager = new ThemeManager()) {
            var api = materials(manager);
            String id = "example:obsidian";
            assertTrue(api.findTheme(id).isEmpty());
            int accent = 0xffe23a93;
            manager.register(Theme.builder(id).nameKey("example.theme.obsidian")
                    .token(ThemeTokens.ACCENT, ColorValue.argb(accent)).build());
            var choices = api.themeChoices();
            assertTrue(choices.contains(new MaterialsApi.ThemeChoice(id, "example.theme.obsidian")));
            var adapt = ShopTheme.class.getDeclaredMethod("adapt", GlassTheme.class);
            adapt.setAccessible(true);
            var shopTheme = (ShopTheme) adapt.invoke(null, api.findTheme(id).orElseThrow());
            assertEquals(accent, shopTheme.colors.ACCENT);
            manager.unregister(id);
            assertTrue(api.findTheme(id).isEmpty());
        }
    }

    private static MaterialsApi materials(ThemeManager manager) throws Exception {
        var constructor = MaterialsApi.class.getDeclaredConstructor(Supplier.class);
        constructor.setAccessible(true);
        return constructor.newInstance((Supplier<ThemeManager>) () -> manager);
    }
}
