package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.shop.ShopAppearance;
import com.dishanhai.sh_better_ui.client.gui.scaled.ThemeEffects;
import com.dishanhai.sh_better_ui.client.gui.scaled.UiPreferences;
import com.dishanhai.sh_better_ui.config.ClientUiSettings;
import com.dishanhai.sh_better_ui.config.ThemeOverrides;
import com.dishanhai.sh_better_ui.config.ThemeSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：新材质不能只换根板，预览作用域应保留外层偏好并最终归还。 */
class ShopThemeIntegrationTest {
    @Test
    void tabsInheritGlobalInputPreferencesBeforeApplyingLocalFields() {
        var tabs = new com.dishanhai.sh_better_ui.config.TabScrollSettings(false, false, false, 56);
        ThemeEffects.preview(ClientUiSettings.DEFAULT.withTabs(tabs), () -> {
            assertEquals(tabs, ShopVisuals.tabs(ShopAppearance.DEFAULT));
            var local = ShopAppearance.DEFAULT.withGroup("tabs", com.google.gson.JsonParser.parseString(
                    "{\"scrollStep\":16}").getAsJsonObject());
            assertEquals(new com.dishanhai.sh_better_ui.config.TabScrollSettings(false, false, false, 16),
                    ShopVisuals.tabs(local));
        });
    }

    @Test
    void globalMotionPreferenceDoesNotBecomeALocalDraftValue() throws Exception {
        var type = com.dishanhai.gt_shanhai.client.shop.ClientShopUiSettings.class;
        var loaded = type.getDeclaredField("loaded");
        var local = type.getDeclaredField("reduceMotion");
        loaded.setAccessible(true);
        local.setAccessible(true);
        boolean wasLoaded = loaded.getBoolean(null), wasLocal = local.getBoolean(null);
        try {
            loaded.setBoolean(null, true);
            local.setBoolean(null, false);
            ThemeEffects.preview(ClientUiSettings.DEFAULT.withDisplay(false, true, true), () -> {
                assertTrue(com.dishanhai.gt_shanhai.client.shop.ClientShopUiSettings.reduceMotion());
                assertFalse(com.dishanhai.gt_shanhai.client.shop.ClientShopUiSettings.localReduceMotion());
            });
            assertFalse(local.getBoolean(null));
        } finally {
            local.setBoolean(null, wasLocal);
            loaded.setBoolean(null, wasLoaded);
        }
    }

    @Test
    void modernMaterialsDoNotFallBackToThePreset() {
        for (var material : new ShopAppearance.Material[]{ShopAppearance.Material.SLATE,
                ShopAppearance.Material.LIQUID_GLASS, ShopAppearance.Material.NETWORK}) {
            ShopTheme theme = ShopTheme.of("classic", material);
            assertNotSame(ShopTheme.CLASSIC, theme, material.id);
            assertEquals(material.id, theme.materialGroup());
            assertSame(theme, ShopTheme.of("smoke", material));
        }
    }

    @Test
    void unchangedPresetKeepsAllThreeLegacyThemes() {
        assertSame(ShopTheme.CLASSIC, ShopTheme.of("classic", ShopAppearance.Material.PRESET));
        assertSame(ShopTheme.GLASS, ShopTheme.of("glass", ShopAppearance.Material.PRESET));
        assertSame(ShopTheme.SMOKE, ShopTheme.of("smoke", ShopAppearance.Material.PRESET));
    }

    @Test
    void nestedShopScopesKeepTheOuterMotionAndFeedbackPreferences() {
        var outer = ClientUiSettings.DEFAULT.withDisplay(true, true, false);
        ThemeEffects.preview(outer, () -> ShopVisuals.scoped(ShopAppearance.DEFAULT, false, () -> {
            UiPreferences prefs = UiPreferences.configured();
            assertTrue(prefs.highContrast());
            assertTrue(prefs.reducedMotion());
            assertFalse(prefs.useBackdropBlur());
        }));
    }

    @Test
    void materialPreviewRestoresTheOuterDraftAfterFailure() {
        ShopTheme.preview(ShopTheme.SLATE, () -> {
            assertThrows(IllegalStateException.class, () -> ShopTheme.preview(ShopTheme.LIQUID_GLASS, () -> {
                assertSame(ShopTheme.LIQUID_GLASS, ShopTheme.current());
                throw new IllegalStateException();
            }));
            assertSame(ShopTheme.SLATE, ShopTheme.current());
        });
    }

    @Test
    void localOverridesInheritUnchangedGlobalGroupsAndFields() {
        var theme = new ThemeSettings(ThemeSettings.Mode.CUSTOM, ThemeSettings.Base.LIGHT,
                "", "#DDEEFF", "", "", "", new ThemeOverrides(
                "{\"feedback\":{\"hoverAnimation\":false,\"tooltipDelayMs\":450}}"));
        var appearance = ShopAppearance.DEFAULT.withGroup("feedback", com.google.gson.JsonParser.parseString(
                "{\"pressAnimation\":false}").getAsJsonObject());
        ThemeEffects.preview(ClientUiSettings.DEFAULT.withTheme(theme), () -> ShopVisuals.scoped(appearance, false, () -> {
            assertFalse(UiPreferences.configured().feedback().hoverAnimation());
            assertFalse(UiPreferences.configured().feedback().pressAnimation());
            assertEquals(450, UiPreferences.configured().feedback().tooltipDelayMs());
            assertTrue(ThemeEffects.settings().theme().light());
            assertEquals("#DDEEFF", ThemeEffects.settings().theme().surface());
        }));
    }
}
