package com.dishanhai.gt_shanhai.common.recipe.editor

import com.lowdragmc.lowdraglib.gui.widget.Widget
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup
import net.minecraft.client.gui.GuiGraphics

/**
 * Sidebar and footer of one stage. They do not slide with the content: during a
 * stage change the old set is veiled over the first half and the new set is
 * unveiled over the second half while settling [lift] units upward.
 *
 * LDLib resets the shader colour before every child, so alpha cannot reach nested
 * widgets. The fade is a veil in the stationary background colour instead, drawn
 * over the rectangles registered with [addVeil].
 */
class GlassFadePage(width: Int, height: Int) : WidgetGroup(0, 0, width, height) {

    /** 0 shows the page, 1 covers it completely. */
    @JvmField
    var veil: Float = 0f

    @JvmField
    var lift: Float = 0f

    private val veils = ArrayList<IntArray>()

    fun addVeil(x: Int, y: Int, width: Int, height: Int, rgb: Int): GlassFadePage {
        veils.add(intArrayOf(x, y, width, height, rgb))
        return this
    }

    override fun drawInBackground(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        graphics.pose().pushPose()
        graphics.pose().translate(0f, lift, 0f)
        super.drawInBackground(graphics, mouseX, mouseY, partialTicks)
        graphics.pose().popPose()
        val amount = veil.coerceIn(0f, 1f)
        if (amount <= 0.001f) return
        val alpha = Math.round(amount * 255f) shl 24
        val x = getPositionX()
        val y = getPositionY()
        graphics.pose().pushPose()
        // Button faces are TextTextures drawn at z 400; the veil has to sit above them.
        graphics.pose().translate(0f, 0f, VEIL_Z)
        for (v in veils) {
            graphics.fill(x + v[0], y + v[1], x + v[0] + v[2], y + v[1] + v[3], alpha or (v[4] and 0xFFFFFF))
        }
        graphics.pose().popPose()
    }

    override fun drawInForeground(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        graphics.pose().pushPose()
        graphics.pose().translate(0f, lift, 0f)
        super.drawInForeground(graphics, mouseX, mouseY, partialTicks)
        graphics.pose().popPose()
    }

    /**
     * The page spans the whole editor and sits above the content page. Only its
     * children count as hovered; the page itself never does, or it would hide
     * every card and cell tooltip underneath.
     */
    override fun getHoverElement(mouseX: Double, mouseY: Double): Widget? = childHover(this, mouseX, mouseY)

    companion object {
        private const val VEIL_Z = 500f

        /** Topmost visible child under the mouse, without falling back to [group] itself. */
        @JvmStatic
        fun childHover(group: WidgetGroup, mouseX: Double, mouseY: Double): Widget? {
            for (i in group.widgets.indices.reversed()) {
                val child = group.widgets[i]
                if (!child.isVisible()) continue
                val hovered = child.getHoverElement(mouseX, mouseY)
                if (hovered != null) return hovered
            }
            return null
        }
    }
}
