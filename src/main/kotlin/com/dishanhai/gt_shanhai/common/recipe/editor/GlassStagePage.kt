package com.dishanhai.gt_shanhai.common.recipe.editor

import com.lowdragmc.lowdraglib.gui.widget.Widget
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup
import net.minecraft.client.gui.GuiGraphics
import org.joml.Vector4f

/**
 * Content column of one stage. The page spans the whole editor so its children
 * use root coordinates, and it clips to the stationary content viewport. The
 * scissor follows the same pose transform LDLib uses for the recipe list, so the
 * two nest.
 */
class GlassStagePage(
    private val clipX: Int,
    private val clipY: Int,
    private val clipW: Int,
    private val clipH: Int,
    width: Int,
    height: Int,
) : WidgetGroup(0, 0, width, height) {

    /** Pose offset in GUI units. The hit box stays put; only the drawing moves. */
    @JvmField
    var slide: Float = 0f

    override fun drawInBackground(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        val x = getPositionX() + clipX
        val y = getPositionY() + clipY
        val pose = graphics.pose().last().pose()
        val min = pose.transform(Vector4f(x.toFloat(), y.toFloat(), 0f, 1f))
        val max = pose.transform(Vector4f((x + clipW).toFloat(), (y + clipH).toFloat(), 0f, 1f))
        graphics.enableScissor(min.x.toInt(), min.y.toInt(), max.x.toInt(), max.y.toInt())
        graphics.pose().pushPose()
        graphics.pose().translate(slide, 0f, 0f)
        super.drawInBackground(graphics, mouseX, mouseY, partialTicks)
        graphics.pose().popPose()
        graphics.disableScissor()
    }

    /** Spans the whole editor like the sidebar page: only children count as hovered. */
    override fun getHoverElement(mouseX: Double, mouseY: Double): Widget? =
        GlassFadePage.childHover(this, mouseX, mouseY)
}
