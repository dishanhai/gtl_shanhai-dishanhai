package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.sh_better_ui.client.glass.GlassColors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：三種主題的選擇與既有設定相容性。 */
class ShopThemeSelectionTest {
    @Test
    void cyclesThroughAllThreeThemesAndReturnsToClassic() {
        assertEquals("glass", ShopTheme.next("classic"));
        assertEquals("smoke", ShopTheme.next("glass"));
        assertEquals("classic", ShopTheme.next("smoke"));
    }

    @Test
    void retainsClassicAndBlueWhileAddingStellarSmoke() {
        assertSame(ShopTheme.CLASSIC, ShopTheme.of("classic"));
        assertSame(ShopTheme.GLASS, ShopTheme.of("glass"));
        assertSame(ShopTheme.SMOKE, ShopTheme.of("smoke"));
        assertSame(ShopTheme.CLASSIC, ShopTheme.of("unknown"));
        assertFalse(ShopTheme.CLASSIC.glass);
        assertSame(GlassColors.DARK, ShopTheme.GLASS.colors);
        assertSame(GlassColors.SMOKE, ShopTheme.SMOKE.colors);
        assertEquals(GlassColors.SMOKE.PANEL_FILL, ShopTheme.SMOKE.panelBg);
    }
}
