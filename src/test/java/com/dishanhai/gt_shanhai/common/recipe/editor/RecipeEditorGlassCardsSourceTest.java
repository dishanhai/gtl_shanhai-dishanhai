package com.dishanhai.gt_shanhai.common.recipe.editor;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RecipeEditorGlassCardsSourceTest {

    private static final Path EDITOR = Path.of("common", "recipe", "editor");

    @Test
    void cardAndListAreKotlinPortsThatKeepTheirContracts() throws IOException {
        String card = GlassSources.read(GlassSources.KOTLIN.resolve(EDITOR.resolve("ShanhaiRecipeCardWidget.kt")));
        String list = GlassSources.read(GlassSources.KOTLIN.resolve(EDITOR.resolve("GlassRecipeCardList.kt")));
        String panel = GlassSources.read(GlassSources.JAVA.resolve(EDITOR.resolve("ShanhaiRecipeEditorPanel.java")));

        assertAll(
                () -> assertFalse(Files.exists(GlassSources.JAVA.resolve(EDITOR.resolve("ShanhaiRecipeCardWidget.java")))),
                () -> assertTrue(card.contains("const val CH = 36")),
                () -> assertTrue(card.contains("Spring(Spring.HOVER)")),
                () -> assertTrue(card.contains("order * 28L")),
                () -> assertTrue(card.contains("GlassPalette.ROW_FILL_HOVER")),
                () -> assertTrue(card.contains("override fun drawInBackground(")),
                // 边缘翻页逻辑与 380ms 防抖原样保留
                () -> assertTrue(list.contains("EDGE_GAP_MS = 380L")),
                () -> assertTrue(list.contains("host.overscrollQueryPage(dir)")),
                () -> assertTrue(list.contains("if (step < 0 && before <= 0) dir = -1")),
                () -> assertTrue(list.contains("else if (step > 0 && before >= max) dir = 1")),
                () -> assertTrue(list.contains("fun scrollToEnd()")),
                () -> assertFalse(panel.contains("class RecipeCardList")),
                () -> assertTrue(panel.contains("new GlassRecipeCardList(host, MAIN_X, listY, MAIN_W, PAGER_Y - GAP - listY)"))
        );
    }
}
