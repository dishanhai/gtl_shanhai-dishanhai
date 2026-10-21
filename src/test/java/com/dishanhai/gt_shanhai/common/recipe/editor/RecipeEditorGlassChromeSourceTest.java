package com.dishanhai.gt_shanhai.common.recipe.editor;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RecipeEditorGlassChromeSourceTest {

    private static String editor(String name) throws IOException {
        return GlassSources.read(GlassSources.JAVA.resolve(Path.of("common", "recipe", "editor", name)));
    }

    @Test
    void paletteAndTextureAreInPlace() throws IOException {
        String palette = GlassSources.read(GlassSources.KOTLIN.resolve(
                Path.of("client", "gui", "glass", "GlassPalette.kt")));
        String texture = GlassSources.read(GlassSources.KOTLIN.resolve(
                Path.of("client", "gui", "glass", "GlassPanelTexture.kt")));
        assertAll(
                () -> assertTrue(palette.contains("object GlassPalette")),
                () -> assertTrue(palette.contains("const val ACCENT")),
                () -> assertTrue(palette.contains("const val TEXT ")),
                () -> assertTrue(texture.contains(": IGuiTexture")),
                () -> assertTrue(texture.contains("Spring(Spring.HOVER)")),
                () -> assertTrue(texture.contains("enum class Style { ROOT, PANEL, DISPLAY, PAGE, BUTTON }"))
        );
    }

    @Test
    void editorNoLongerUsesVanillaChromeTextures() throws IOException {
        for (String name : new String[]{"ShanhaiRecipeEditorPanel.java", "ShanhaiRecipeEditorWidget.java",
                "ShanhaiRecipeEditorFactory.java"}) {
            String src = editor(name);
            assertFalse(src.contains("GuiTextures.BUTTON"), name);
            assertFalse(src.contains("GuiTextures.BACKGROUND"), name);
            assertFalse(src.contains("GuiTextures.DISPLAY"), name);
            assertFalse(src.contains("0xFF1A1A1A"), name);
        }
        assertTrue(editor("ShanhaiRecipeEditorFactory.java").contains(".background(GlassPanelTexture.root())"));
        assertTrue(editor("ShanhaiRecipeEditorPanel.java").contains("GlassPanelTexture.button()"));
        // 两栏底板由 Panel 静止绘制，页面本身只负责滑动 / 遮罩
        assertTrue(editor("ShanhaiRecipeEditorPanel.java")
                .contains("private final GlassPanelTexture mainBack = GlassPanelTexture.page();"));
    }

    @Test
    void smallWidgetsReadTheSharedPalette() throws IOException {
        assertAll(
                () -> assertTrue(editor("ShanhaiRecipeTabBarWidget.java").contains("GuiMotion.lerpColor(")),
                () -> assertFalse(editor("ShanhaiRecipeTabBarWidget.java").contains("private static int mix(")),
                () -> assertTrue(editor("ShanhaiRecipeGroupHeaderWidget.java").contains("GlassPalette.GROUP_FILL")),
                () -> assertFalse(editor("ShanhaiRecipeSortMarkerWidget.java").contains("0xFF69E8FF")),
                () -> assertFalse(editor("ShanhaiRecipeChoiceMarkerWidget.java").contains("0xFF69E8FF")),
                () -> assertFalse(editor("ShanhaiRecipeHistoryWidget.java").contains("0xFF69E8FF"))
        );
    }
}
