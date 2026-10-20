package com.dishanhai.gt_shanhai.client.gui.glass

import com.dishanhai.gt_shanhai.client.gui.scaled.GuiMotion
import net.minecraft.client.gui.GuiGraphics

/** 配方修改器 IO 格子的玻璃绘制：选中光晕、边框、不透明格心、左上受光线。 */
object GlassCell {

    /**
     * @param frame  边框色（物品灰 / 流体蓝，由 `ShanhaiIOWidget` 传入）
     * @param select 选中强度 0..1，0 表示没选中
     * @param hover  鼠标是否在格子上；悬停时边框向强调色靠一截
     */
    @JvmStatic
    fun draw(g: GuiGraphics, x: Int, y: Int, size: Int, frame: Int, select: Float, hover: Boolean) {
        if (select > 0.02f) {
            val alpha = minOf(255, Math.round(255f * select))
            val inset = Math.round((1f - select) * 3f)
            g.fill(x - 1 + inset, y - 1 + inset, x + size + 1 - inset, y + size + 1 - inset,
                (alpha shl 24) or 0x69E8FF)
        }
        val edge = if (hover) GuiMotion.lerpColor(frame, GlassPalette.ACCENT, 0.6f) else frame
        g.fill(x, y, x + size, y + size, edge)
        g.fill(x + 1, y + 1, x + size - 1, y + size - 1, GlassPalette.CELL_FILL)
        g.fill(x + 1, y + 1, x + size - 1, y + 2, GlassPalette.LIT)
    }
}
