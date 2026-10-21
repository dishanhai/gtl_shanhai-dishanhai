package com.dishanhai.gt_shanhai.common.recipe.editor;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RecipeEditorGlassPopupSourceTest {

    private static final Path EDITOR = Path.of("common", "recipe", "editor");

    @Test
    void popupsBlurTheRingAroundThemAndKeepThemselvesCrisp() throws IOException {
        String blur = GlassSources.read(GlassSources.KOTLIN.resolve(Path.of("client", "gui", "glass", "GlassBlur.kt")));
        String selector = GlassSources.read(GlassSources.JAVA.resolve(EDITOR.resolve("ShanhaiRecipeTypeSelector.java")));
        String history = GlassSources.read(GlassSources.JAVA.resolve(EDITOR.resolve("ShanhaiRecipeHistoryWidget.java")));
        assertAll(
                // 走第二矩形槽位，不覆盖换页的主请求；弹层本身作为 Hole 保持清晰
                () -> assertTrue(blur.contains("fun requestPopup(graphics: GuiGraphics, popup: GuiRect, openAmount: Float)")),
                () -> assertTrue(blur.contains("ShopGaussBlur.requestExtra(")),
                () -> assertTrue(blur.contains("ShopGlassStyle.OVERLAY")),
                () -> assertTrue(blur.contains("POPUP_MARGIN_VIRTUAL = 14f")),
                // 弹层本体作 Hole，并上锚点按钮：弹层一打开，触发它的按钮也要保持清晰
                () -> assertTrue(blur.contains("ShopGlassStyle.OVERLAY,")),
                () -> assertTrue(blur.contains("hole.x, hole.y, hole.w, hole.h,")),
                () -> assertTrue(selector.contains(".union(new GuiRect(x, y, popUp.getSizeWidth(), shown)), open);")),
                () -> assertTrue(history.contains(".union(new GuiRect(x, y, POPUP_W, height)),")),
                () -> assertTrue(history.contains("new GuiRect(x, y - BUTTON - 2, BUTTON, BUTTON)")),
                () -> assertTrue(history.contains("new Spring(Spring.PANEL)"))
        );
    }
}
