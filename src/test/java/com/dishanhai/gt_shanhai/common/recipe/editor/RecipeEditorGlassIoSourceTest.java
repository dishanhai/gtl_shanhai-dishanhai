package com.dishanhai.gt_shanhai.common.recipe.editor;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RecipeEditorGlassIoSourceTest {

    private static final Path EDITOR = Path.of("common", "recipe", "editor");

    @Test
    void ioCellsAreDrawnByGlassCellAndKeepTheirDragDropContract() throws IOException {
        String io = GlassSources.read(GlassSources.JAVA.resolve(EDITOR.resolve("ShanhaiIOWidget.java")));
        String cell = GlassSources.read(GlassSources.KOTLIN.resolve(Path.of("client", "gui", "glass", "GlassCell.kt")));
        assertAll(
                () -> assertTrue(io.contains("GlassCell.draw(graphics, x, y, CELL,")),
                // 拖放契约与边框色常量不变
                () -> assertTrue(io.contains("FRAME_FLUID")),
                () -> assertTrue(io.contains("getPhantomTargets")),
                () -> assertTrue(io.contains("implements IGhostIngredientTarget")),
                () -> assertTrue(cell.contains("object GlassCell")),
                () -> assertTrue(cell.contains("GlassPalette.CELL_FILL"))
        );
    }

    @Test
    void gridAndRowArePortedAndTheGridKeepsItsGhostTargetGuard() throws IOException {
        String grid = GlassSources.read(GlassSources.KOTLIN.resolve(EDITOR.resolve("GlassIoGrid.kt")));
        String row = GlassSources.read(GlassSources.KOTLIN.resolve(EDITOR.resolve("GlassEnterRow.kt")));
        String panel = GlassSources.read(GlassSources.JAVA.resolve(EDITOR.resolve("ShanhaiRecipeEditorPanel.java")));
        assertAll(
                // 翻页滑动期间不接受 JEI 拖放，也不响应悬停
                () -> assertTrue(grid.contains("override fun getPhantomTargets(")),
                () -> assertTrue(grid.contains("if (moving()) return emptyList()")),
                () -> assertTrue(grid.contains("override fun isMouseOverElement(")),
                () -> assertTrue(grid.contains("ShanhaiRecipeEditorPanel.fillPage(")),
                () -> assertTrue(row.contains("STAGGER_MS = 28L")),
                () -> assertFalse(panel.contains("class IoGrid")),
                () -> assertFalse(panel.contains("class EnterRow")),
                () -> assertTrue(panel.contains("new GlassIoGrid(")),
                () -> assertTrue(panel.contains("new GlassEnterRow(")),
                // 每个格子网格后面垫一块玻璃底板
                () -> assertTrue(panel.contains("new ImageWidget(x0 - 3, y0 - 3,")),
                () -> assertTrue(panel.contains("GlassPanelTexture.display()"))
        );
    }
}
