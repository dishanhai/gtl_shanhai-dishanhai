package com.dishanhai.gt_shanhai.common.recipe.editor

import com.lowdragmc.lowdraglib.LDLib
import com.lowdragmc.lowdraglib.gui.widget.Widget
import com.lowdragmc.lowdraglib.utils.LocalizationUtils
import com.lowdragmc.lowdraglib.utils.Size
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence
import java.util.function.Supplier

/**
 * Label that wraps to its width and grows downward. No text is cut to fit:
 * the height follows the line count, and a parent [GlassFlow] or [GlassRow]
 * moves whatever sits below. Empty text takes no height. Colour codes carry
 * across lines.
 */
class GlassWrapLabel(
    x: Int,
    y: Int,
    width: Int,
    private val text: Supplier<String>,
) : Widget(x, y, width, RecipeEditorLayout.LINE_H) {

    constructor(x: Int, y: Int, width: Int, text: String) : this(x, y, width, Supplier { text })

    private var color = -1
    private var align = LEFT

    init {
        setClientSideWidget()
    }

    fun setColor(color: Int): GlassWrapLabel {
        this.color = color
        return this
    }

    /** Each line ends on the right edge instead of starting on the left one. */
    fun alignRight(): GlassWrapLabel {
        align = RIGHT
        return this
    }

    fun alignCenter(): GlassWrapLabel {
        align = CENTER
        return this
    }

    override fun updateScreen() {
        super.updateScreen()
        val height = shown().size * RecipeEditorLayout.LINE_H
        if (height != getSizeHeight()) setSize(Size(getSizeWidth(), height))
    }

    override fun drawInBackground(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        super.drawInBackground(graphics, mouseX, mouseY, partialTicks)
        val font = Minecraft.getInstance().font
        val x = getPositionX()
        val width = getSizeWidth()
        var y = getPositionY()
        for (line in shown()) {
            val left = when (align) {
                RIGHT -> x + width - font.width(line)
                CENTER -> x + (width - font.width(line)) / 2
                else -> x
            }
            graphics.drawString(font, line, left, y, color, true)
            y += RecipeEditorLayout.LINE_H
        }
    }

    /** Same formatting as LDLib's LabelWidget, so `%%` in a label still prints one `%`. */
    private fun shown(): List<FormattedCharSequence> =
        lines(LocalizationUtils.format(text.get() ?: ""), getSizeWidth())

    companion object {
        private const val LEFT = 0
        private const val CENTER = 1
        private const val RIGHT = 2

        /** Wrapped lines of [raw] at [width], taken as plain text with colour codes. */
        @JvmStatic
        fun lines(raw: String?, width: Int): List<FormattedCharSequence> {
            if (raw.isNullOrEmpty()) return emptyList()
            return Minecraft.getInstance().font.split(Component.literal(raw), maxOf(8, width))
        }

        /**
         * Text can be measured here: a client, on the render thread. The integrated
         * server builds its own copy of the widget tree, and the font's glyph caches
         * are not safe to fill from that thread.
         */
        @JvmStatic
        fun canMeasure(): Boolean = LDLib.isClient() && RenderSystem.isOnRenderThread()

        /** Line count for sizing rows before they are drawn. One line where text cannot be measured. */
        @JvmStatic
        fun lineCount(raw: String?, width: Int): Int {
            if (!canMeasure()) return 1
            return maxOf(1, lines(raw, width).size)
        }
    }
}
