package com.dishanhai.gt_shanhai.common.recipe.editor

import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup

/**
 * Select-page card list. A wheel tick that cannot move the scroll changes
 * the query page: up at the top goes back, down at the bottom goes forward.
 * The tick that first reaches the edge still only scrolls.
 */
class GlassRecipeCardList(
    private val host: ShanhaiRecipeEditorWidget,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
) : DraggableScrollableWidgetGroup(x, y, width, height) {

    private var edgedAt = 0L

    fun scrollToEnd() {
        setScrollYOffset(maxOf(0, getMaxHeight() - getSize().height))
    }

    override fun mouseWheelMove(mouseX: Double, mouseY: Double, wheelDelta: Double): Boolean {
        if (!isMouseOverElement(mouseX, mouseY)) {
            setFocus(false)
            return false
        }
        val before = getScrollYOffset()
        super.mouseWheelMove(mouseX, mouseY, wheelDelta)
        if (getScrollYOffset() != before) return true
        val step = wheelStep(wheelDelta)
        if (step == 0) return true
        val max = maxOf(0, getMaxHeight() - getSize().height)
        var dir = 0
        if (step < 0 && before <= 0) dir = -1
        else if (step > 0 && before >= max) dir = 1
        if (dir == 0) return true
        val now = ShanhaiRecipeEditorAnimation.nowMs()
        if (now - edgedAt < EDGE_GAP_MS) return true
        edgedAt = now
        host.overscrollQueryPage(dir)
        return true
    }

    private companion object {
        const val EDGE_GAP_MS = 380L

        fun wheelStep(wheelDelta: Double): Int {
            val clamped = wheelDelta.coerceIn(-1.0, 1.0)
            return ((-clamped) * 13.0).toInt()
        }
    }
}
