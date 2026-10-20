package com.dishanhai.gt_shanhai.client.gui.glass

/** 配方修改器的颜色 token（ARGB）。沿用编辑器原有的深蓝青配色，只补半透明玻璃底色。 */
object GlassPalette {
    // 文字与强调
    const val ACCENT = 0xFF69E8FF.toInt()
    const val TEXT = 0xFFEAF7FF.toInt()
    const val TEXT_MUTED = 0xFFB7C9D6.toInt()
    const val TEXT_DIM = 0xFF8AA6B9.toInt()
    const val TEXT_OFF = 0xFF6E8494.toInt()
    const val TEXT_HEAD = 0xFF9FD7FF.toInt()
    const val OK = 0xFF91F7BC.toInt()

    // 玻璃底板（半透明，能透出后面的世界）
    const val ROOT_FILL = 0xE60E1726.toInt()
    const val ROOT_EDGE = 0xFF36546A.toInt()
    // 下拉弹层的底，不透明：弹层本体在模糊 Hole 里不被糊，半透明会让后面的文字清楚地透出来
    const val PANEL_FILL = 0xFF101B2E.toInt()
    const val PANEL_EDGE = 0xFF2E4A60.toInt()
    const val DISPLAY_FILL = 0xE6091221.toInt()
    const val DISPLAY_EDGE = 0xFF1F3347.toInt()
    const val PAGE_FILL = 0xB30B1424.toInt()

    // 按钮
    const val BUTTON_FILL = 0xE6183049.toInt()
    const val BUTTON_FILL_HOVER = 0xE6214A6B.toInt()
    const val BUTTON_EDGE = 0xFF36546A.toInt()

    // 受光亮边
    const val LIT = 0x40BFE8FF.toInt()
    const val LIT_SOFT = 0x1ABFE8FF.toInt()

    // 卡片 / 行 / 页签 / 格子（不透明，保证图标与文字对比度）
    const val ROW_FILL = 0xFF111B2D.toInt()
    const val ROW_FILL_HOVER = 0xFF19324A.toInt()
    const val ROW_EDGE = 0xFF29445A.toInt()
    const val ROW_ICON_BG = 0xFF20374B.toInt()
    const val TAB_EDGE = 0xFF36546A.toInt()
    const val TAB_FILL = 0xFF16243A.toInt()
    const val TAB_FILL_ON = 0xFF1A3549.toInt()
    const val GROUP_FILL = 0xFF163044.toInt()
    const val CELL_FILL = 0xFF272A35.toInt()
}
