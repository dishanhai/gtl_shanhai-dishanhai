package com.dishanhai.gt_shanhai.common.recipe.editor

import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.gui.GuiGraphics

/** One rebuilt row. Pose slides in; the hit box stays put so a click still lands. */
class GlassEnterRow(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    order: Int,
    animate: Boolean,
) : WidgetGroup(x, y, width, height) {

    private val order: Int = maxOf(0, order)
    private val born: Long =
        ShanhaiRecipeEditorAnimation.nowMs() - (if (animate) 0L else ENTER_MS + this.order * STAGGER_MS)

    override fun drawInBackground(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        val enter = enter()
        graphics.pose().pushPose()
        graphics.pose().translate(0f, (1f - enter) * 8f, 0f)
        RenderSystem.setShaderColor(1f, 1f, 1f, 0.2f + 0.8f * enter)
        super.drawInBackground(graphics, mouseX, mouseY, partialTicks)
        graphics.pose().popPose()
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
    }

    private fun enter(): Float {
        val elapsed = ShanhaiRecipeEditorAnimation.nowMs() - born - order * STAGGER_MS
        if (elapsed <= 0L) return 0f
        val linear = minOf(1f, elapsed / ENTER_MS.toFloat())
        return linear * linear * (3f - 2f * linear)
    }

    private companion object {
        const val ENTER_MS = 200L
        const val STAGGER_MS = 28L
    }
}
