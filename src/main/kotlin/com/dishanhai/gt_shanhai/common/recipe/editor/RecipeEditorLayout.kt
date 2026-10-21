package com.dishanhai.gt_shanhai.common.recipe.editor

/**
 * The editor's two column lines, in root coordinates.
 *
 * Every control edge sits on [SIDE_X] .. [SIDE_X] + [SIDE_W] or [MAIN_X] .. [MAIN_RIGHT].
 * Controls are [CTRL_H] or [BTN_H] tall and rows are [GAP] apart.
 */
object RecipeEditorLayout {
    const val GAP = 4
    const val LINE_H = 10
    const val CTRL_H = 16
    const val BTN_H = 18
    const val CELL_STRIDE = 22

    const val TITLE_X = 12
    const val TITLE_Y = 5
    const val SUBTITLE_Y = 16
    const val TITLE_W = 136

    const val SIDE_X = 8
    const val SIDE_W = 140
    const val MAIN_X = 156
    const val MAIN_W = 356
    const val MAIN_RIGHT = MAIN_X + MAIN_W

    const val TAB_Y = 8
    const val TAB_H = 20

    const val BODY_Y = 36
    const val BODY_H = 266
    const val BODY_BOTTOM = BODY_Y + BODY_H

    const val FOOT_Y = 306
    const val FOOT_BTN_W = 140
    const val FOOT_RIGHT_X = MAIN_RIGHT - FOOT_BTN_W

    const val STATUS_X = 12
    const val STATUS_Y = 328
    const val STATUS_W = 496

    /** Width of each of [count] equal pieces that fill [total] with [GAP] between them. */
    @JvmStatic
    fun share(total: Int, count: Int): Int =
        if (count <= 1) total else (total - GAP * (count - 1)) / count

    /** Left edge of piece [index] of [count] laid from [left] across [total]. */
    @JvmStatic
    fun shareX(left: Int, total: Int, count: Int, index: Int): Int =
        left + index * (share(total, count) + GAP)
}
