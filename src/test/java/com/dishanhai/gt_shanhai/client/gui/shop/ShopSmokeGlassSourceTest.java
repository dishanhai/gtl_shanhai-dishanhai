package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：煙灰主題與選擇器的背景／內容分層契約。 */
class ShopSmokeGlassSourceTest {
    private String read(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/" + name));
    }

    @Test
    void smokeIsSelectableAndPersistedWithoutReplacingExistingThemes() throws Exception {
        String settings = read("client/shop/ClientShopUiSettings.java");
        String theme = read("client/gui/shop/ShopTheme.java");
        String screen = read("client/gui/shop/ShopSettingsScreen.java");
        String welcome = read("client/gui/shop/ShopWelcomeScreen.java");
        assertAll(
                () -> assertTrue(settings.contains("THEME_SMOKE")),
                () -> assertTrue(theme.contains("GlassTheme.SMOKE")),
                () -> assertTrue(theme.contains("ShBetterUiApi.materials().findTheme(id)")),
                () -> assertTrue(screen.contains("ShopTheme.next(")),
                () -> assertTrue(welcome.contains("ShopTheme.choices()")),
                () -> assertTrue(settings.contains("root.addProperty(\"shopTheme\", shopTheme)")));
    }

    @Test
    void themePreviewsParticipateInBlurWarmupAndResourceReload() throws Exception {
        String init = read("client/ClientInit.java");
        assertTrue(init.contains("screen instanceof com.dishanhai.gt_shanhai.client.gui.shop.ShopSettingsScreen"));
        assertTrue(init.contains("screen instanceof com.dishanhai.gt_shanhai.client.gui.shop.ShopWelcomeScreen"));
    }

    @Test
    void pickerBlursBackdropBeforeDrawingCrispContent() throws Exception {
        String picker = read("client/gui/shop/MultiPickerScreen.java");
        String render = picker.substring(picker.indexOf("protected void renderScaledBackground("),
                picker.indexOf("private List<Mode> availableModes("));
        assertAll(
                () -> assertTrue(render.contains("theme.rootPanel(")),
                () -> assertTrue(render.indexOf("theme.rootPanel(") < render.indexOf("drawSidebar(")),
                () -> assertTrue(render.contains("if (theme.glass)")),
                () -> assertTrue(render.contains("g.fill(left, top, left + panelWidth, top + panelHeight, GOLD_DARK)")),
                () -> assertTrue(picker.contains("theme.box(g, selX - 2")),
                () -> assertTrue(picker.contains("GaussBlur.deferTopLayer(this")),
                () -> assertTrue(picker.contains("g.pose().translate(0, 0, -500)")));
    }
}
