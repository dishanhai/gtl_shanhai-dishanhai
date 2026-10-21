package com.dishanhai.gt_shanhai.common.recipe.editor

import com.lowdragmc.lowdraglib.gui.widget.Widget
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup

/**
 * Stacks children top to bottom, [RecipeEditorLayout.GAP] apart, each keeping its own x.
 * Hidden or zero-height children take no room. The widget added with [addFill]
 * stretches to the bottom; with [autoHeight] the flow instead grows to its content,
 * which lets a scroll box around it see the real height.
 * Positions change only when a height or visibility did, so a sliding page is not
 * rebuilt every frame.
 */
class GlassFlow @JvmOverloads constructor(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    private val autoHeight: Boolean = false,
) : WidgetGroup(x, y, width, height) {

    private var fill: Widget? = null
    private var shape = IntArray(0)

    fun add(widget: Widget): GlassFlow {
        addWidget(widget)
        relayout()
        return this
    }

    fun addFill(widget: Widget): GlassFlow {
        fill = widget
        return add(widget)
    }

    override fun updateScreen() {
        super.updateScreen()
        relayout()
    }

    override fun onChildSizeUpdate(child: Widget) {
        super.onChildSizeUpdate(child)
        if (child !== fill) relayout()
    }

    fun relayout() {
        val next = IntArray(widgets.size * 2)
        for (i in widgets.indices) {
            val child = widgets[i]
            next[i * 2] = if (child.isVisible()) 1 else 0
            next[i * 2 + 1] = if (child === fill) 0 else child.getSizeHeight()
        }
        if (next.contentEquals(shape)) return
        shape = next
        var y = 0
        var bottom = 0
        for (child in widgets) {
            if (!child.isVisible()) continue
            if (child === fill) {
                child.setSelfPosition(child.getSelfPosition().x, y)
                child.setSize(child.getSizeWidth(), maxOf(0, getSizeHeight() - y))
                continue
            }
            val height = child.getSizeHeight()
            if (height <= 0) continue
            child.setSelfPosition(child.getSelfPosition().x, y)
            bottom = y + height
            y = bottom + RecipeEditorLayout.GAP
        }
        if (autoHeight && bottom != getSizeHeight()) setSize(getSizeWidth(), bottom)
    }
}
