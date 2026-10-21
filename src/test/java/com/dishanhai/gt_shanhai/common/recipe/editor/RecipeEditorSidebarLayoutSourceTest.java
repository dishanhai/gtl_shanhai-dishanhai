package com.dishanhai.gt_shanhai.common.recipe.editor;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 主从分栏布局的源码契约：页面结构、遮罩换页，以及界面文字一律完整显示。 */
class RecipeEditorSidebarLayoutSourceTest {

    private static final Path EDITOR = Path.of("common", "recipe", "editor");

    private static String java(String name) throws IOException {
        return GlassSources.read(GlassSources.JAVA.resolve(EDITOR.resolve(name)));
    }

    private static String kotlin(String name) throws IOException {
        return GlassSources.read(GlassSources.KOTLIN.resolve(EDITOR.resolve(name)));
    }

    @Test
    void eachStageHasASlidingContentPageAndAVeiledSidebar() throws IOException {
        String panel = java("ShanhaiRecipeEditorPanel.java");
        String fade = kotlin("GlassFadePage.kt");
        assertAll(
                () -> assertTrue(panel.contains("super(0, 0, ShanhaiRecipeEditorWidget.WIDTH, ShanhaiRecipeEditorWidget.HEIGHT);")),
                () -> assertTrue(panel.contains("new ShanhaiRecipeTabBarWidget(host, MAIN_X, TAB_Y, MAIN_W)")),
                // 左栏页在内容页之后加入：左栏的弹层要盖在内容栏上
                () -> assertTrue(panel.indexOf("addWidget(selectSide);") > panel.indexOf("addWidget(reviewPage);")),
                () -> assertTrue(panel.contains("swapSides(side(host.fromStage), side(host.stage), progress);")),
                () -> assertTrue(panel.contains("settleSide(selectSide, ShanhaiRecipeEditorWidget.STAGE_SELECT);")),
                // LDLib 每个子节点前重置 shader 颜色，淡出只能靠背景色遮罩，且要压在 z 400 的按钮字上面
                () -> assertTrue(fade.contains("const val VEIL_Z = 500f")),
                () -> assertFalse(fade.contains("setShaderColor"))
        );
    }

    @Test
    void displayTextIsNeverCutToFit() throws IOException {
        String panel = java("ShanhaiRecipeEditorPanel.java");
        String widget = java("ShanhaiRecipeEditorWidget.java");
        String header = java("ShanhaiRecipeGroupHeaderWidget.java");
        String history = java("ShanhaiRecipeHistoryWidget.java");
        String selector = java("ShanhaiRecipeTypeSelector.java");
        String io = java("ShanhaiIOWidget.java");
        String table = java("ShanhaiIoTable.java");
        String card = kotlin("ShanhaiRecipeCardWidget.kt");
        assertAll(
                () -> assertFalse(panel.contains("compact(")),
                () -> assertFalse(widget.contains("compact(")),
                () -> assertFalse(widget.contains("Math.min(3, values.size())")),
                () -> assertFalse(header.contains("fit(")),
                () -> assertFalse(history.contains("fitText(")),
                () -> assertFalse(card.contains("fun fit(")),
                () -> assertFalse(card.contains("fun trim(")),
                () -> assertFalse(io.contains("Math.min(6, tags.size())")),
                () -> assertFalse(table.contains("text.length() > 96")),
                // 下拉按钮面与候选行都换行显示，不再 ROLL
                () -> assertTrue(selector.contains("textTexture.setType(TextTexture.TextType.NORMAL);")),
                () -> assertTrue(selector.contains("protected void computeLayout()")),
                () -> assertFalse(selector.contains("TextType.ROLL")),
                // 卡片与分组标题的高度跟着换行走
                () -> assertTrue(card.contains("fun heightFor(card: ShanhaiRecipeQuery.Card, width: Int): Int")),
                () -> assertTrue(header.contains("super(x, y, width, heightFor(title, width));"))
        );
    }
}
