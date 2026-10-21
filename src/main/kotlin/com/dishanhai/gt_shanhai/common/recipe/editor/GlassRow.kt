package com.dishanhai.gt_shanhai.common.recipe.editor

import com.lowdragmc.lowdraglib.gui.widget.Widget
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup

/**
 * One row of side-by-side controls whose height is the tallest child, never
 * below [minHeight]. A wrapped label in the row pushes the next row down
 * instead of drawing over it.
 */
class GlassRow(x: Int, y: Int, width: Int, private val minHeight: Int) :
    WidgetGroup(x, y, width, minHeight) {

    fun add(widget: Widget): GlassRow {
        addWidget(widget)
        fit()
        return this
    }

    override fun updateScreen() {
        super.updateScreen()
        fit()
    }

    override fun onChildSizeUpdate(child: Widget) {
        super.onChildSizeUpdate(child)
        fit()
    }

    private fun fit() {
        var height = minHeight
        for (child in widgets) {
            if (!child.isVisible()) continue
            height = maxOf(height, child.getSelfPosition().y + child.getSizeHeight())
        }
        if (height != getSizeHeight()) setSize(getSizeWidth(), height)
    }
}
