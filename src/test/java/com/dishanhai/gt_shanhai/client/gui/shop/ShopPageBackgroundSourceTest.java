package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：背景必须在控件之前覆盖整个页面，并使用同一范围处理指针。 */
class ShopPageBackgroundSourceTest {
    @Test
    void savedBackgroundIdDrivesRenderingAndEditorWithoutAClosedEffectList() throws Exception {
        Path shop = Path.of("src/main/java/com/dishanhai/gt_shanhai/client/gui/shop");
        String visuals = Files.readString(shop.resolve("ShopVisuals.java"));
        String editor = Files.readString(shop.resolve("ShopThemeEditorScreen.java"));
        assertFalse(visuals.contains("switch (background)"), "商店不应逐一分派背景实现");
        assertFalse(editor.contains("ShopAppearance.Background.values()"), "背景选项应来自共享库注册表");
    }

    @Test
    void pageArtPrecedesToolbarAndIsNotBoundToTheProductGrid() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopScreen.java"));
        int draw = source.indexOf("drawPageBackground(g,");
        assertTrue(draw >= 0, "整页背景入口缺失");
        assertTrue(draw < source.indexOf("advanceTopStripScroll(nowMs);", source.indexOf("float openScale =")));
        assertFalse(source.contains("Bounds visualArea = new Bounds(gx, gy, gw, gh)"));
        assertTrue(source.contains("new Bounds(0, 0, this.width, this.height)"));
        assertTrue(source.contains("mx * guiScale + offsetX"));
        assertTrue(source.contains("my * guiScale + offsetY"));
    }
}
