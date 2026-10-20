package com.dishanhai.gt_shanhai.common.recipe.editor

import com.dishanhai.gt_shanhai.client.gui.glass.GlassPanelTexture
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup
import net.minecraft.client.gui.GuiGraphics
import org.joml.Vector4f

/**
 * Clips a sliding page to the stationary viewport. The scissor follows the
 * same pose transform LDLib uses for the recipe list, so the two nest.
 */
class GlassStagePage(private val viewport: ShanhaiRecipeEditorPanel) : WidgetGroup(
    0,
    ShanhaiRecipeEditorPanel.PAGE_Y,
    viewport.getSizeWidth(),
    viewport.getSizeHeight() - ShanhaiRecipeEditorPanel.PAGE_Y,
) {

    /** Pose offset in GUI units. The hit box stays put; only the drawing moves. */
    @JvmField
    var slide: Float = 0f

    init {
        setBackground(GlassPanelTexture.page())
    }

    override fun drawInBackground(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        val x = viewport.getPositionX()
        val y = viewport.getPositionY() + ShanhaiRecipeEditorPanel.PAGE_Y
        val right = x + viewport.getSizeWidth()
        val bottom = viewport.getPositionY() + viewport.getSizeHeight()
        val pose = graphics.pose().last().pose()
        val min = pose.transform(Vector4f(x.toFloat(), y.toFloat(), 0f, 1f))
        val max = pose.transform(Vector4f(right.toFloat(), bottom.toFloat(), 0f, 1f))
        graphics.enableScissor(min.x.toInt(), min.y.toInt(), max.x.toInt(), max.y.toInt())
        graphics.pose().pushPose()
        graphics.pose().translate(slide, 0f, 0f)
        super.drawInBackground(graphics, mouseX, mouseY, partialTicks)
        graphics.pose().popPose()
        graphics.disableScissor()
    }
}
