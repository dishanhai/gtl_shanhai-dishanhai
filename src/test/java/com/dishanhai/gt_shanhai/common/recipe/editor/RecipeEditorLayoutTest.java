package com.dishanhai.gt_shanhai.common.recipe.editor;

import static com.dishanhai.gt_shanhai.common.recipe.editor.RecipeEditorLayout.*;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 版面网格的对齐关系：两条栏线、等分按钮、上下各段首尾相接。 */
class RecipeEditorLayoutTest {

    @Test
    void equalPiecesFillTheirSpanExactly() {
        assertAll(
                () -> assertEquals(68, share(SIDE_W, 2)),
                () -> assertEquals(44, share(SIDE_W, 3)),
                () -> assertEquals(116, share(MAIN_W, 3)),
                () -> assertEquals(SIDE_W, shareX(0, SIDE_W, 2, 1) + share(SIDE_W, 2)),
                () -> assertEquals(SIDE_W, shareX(0, SIDE_W, 3, 2) + share(SIDE_W, 3)),
                () -> assertEquals(MAIN_RIGHT, shareX(MAIN_X, MAIN_W, 3, 2) + share(MAIN_W, 3))
        );
    }

    @Test
    void columnsAndBandsLineUp() {
        assertAll(
                // 左栏 + 两倍间距正好到内容栏；左右外边距相同
                () -> assertEquals(MAIN_X, SIDE_X + SIDE_W + GAP * 2),
                () -> assertEquals(ShanhaiRecipeEditorWidget.WIDTH - SIDE_X, MAIN_RIGHT),
                () -> assertEquals(MAIN_RIGHT, FOOT_RIGHT_X + FOOT_BTN_W),
                () -> assertEquals(SIDE_W, FOOT_BTN_W),
                // 标题列不越过左栏右缘
                () -> assertTrue(TITLE_X + TITLE_W <= SIDE_X + SIDE_W),
                // 主体、底部条、状态列上下首尾相接，状态列三行留在根板内
                () -> assertTrue(TAB_Y + TAB_H <= BODY_Y),
                () -> assertEquals(FOOT_Y, BODY_BOTTOM + GAP),
                () -> assertEquals(STATUS_Y, FOOT_Y + BTN_H + GAP),
                () -> assertTrue(STATUS_Y + 3 * LINE_H <= ShanhaiRecipeEditorWidget.HEIGHT),
                // 一页 16 格一排，加 3 像素内边距的底板正好铺满内容栏
                () -> assertEquals(MAIN_W, ShanhaiRecipeEditorPanel.ITEM_PAGE * CELL_STRIDE + 4)
        );
    }
}
