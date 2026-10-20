package com.dishanhai.gt_shanhai.common.recipe.editor

import com.lowdragmc.lowdraglib.gui.ingredient.Target
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup
import net.minecraft.client.gui.GuiGraphics
import org.joml.Vector4f

/**
 * One item or fluid page. Adding a page changes the index immediately, and this
 * viewport slides the old cells out before drawing the new ones. Positions stay
 * put; only the pose moves, same as the stage pages.
 */
class GlassIoGrid(x: Int, y: Int, width: Int, height: Int) : WidgetGroup(x, y, width, height) {

    private var fromPage = 0
    private var visualPage = Int.MIN_VALUE
    private var targetPage = Int.MIN_VALUE
    private var started = 0L
    private var primed = false
    private var want = 0
    private var base = 0
    private var count = 0
    private var pageSize = 0
    private var shown = IntArray(0)

    fun snap() {
        visualPage = Int.MIN_VALUE
        targetPage = Int.MIN_VALUE
        fromPage = 0
        primed = false
    }

    fun sync(want: Int, base: Int, count: Int, pageSize: Int, shown: IntArray) {
        this.want = want
        this.base = base
        this.count = count
        this.pageSize = pageSize
        this.shown = shown
        this.primed = true
        applyShown()
    }

    private fun applyShown() {
        if (!primed) return
        val page = visual(want)
        ShanhaiRecipeEditorPanel.fillPage(shown, page, base, count, pageSize)
        setActive(!moving())
        var i = 0
        while (i < widgets.size && i < shown.size) {
            widgets[i].setVisible(shown[i] >= 0)
            i++
        }
    }

    private fun visual(next: Int): Int {
        if (visualPage == Int.MIN_VALUE) {
            visualPage = next
            targetPage = next
            fromPage = next
            return next
        }
        if (next != targetPage) {
            fromPage = visualPage
            targetPage = next
            started = ShanhaiRecipeEditorAnimation.nowMs()
        }
        if (progress() >= 0.5f) visualPage = targetPage
        return visualPage
    }

    private fun moving(): Boolean = fromPage != targetPage && progress() < 1f

    private fun progress(): Float {
        if (fromPage == targetPage) return 1f
        val linear = minOf(1f, maxOf(0L, ShanhaiRecipeEditorAnimation.nowMs() - started) / IO_PAGE_MS.toFloat())
        return linear * linear * (3f - 2f * linear)
    }

    private fun offset(): Float {
        val amount = progress()
        if (amount >= 1f || fromPage == targetPage) return 0f
        val direction = if (fromPage < targetPage) -1 else 1
        val width = getSizeWidth().toFloat()
        if (amount < 0.5f) return direction * (amount / 0.5f) * width
        val tail = (amount - 0.5f) / 0.5f
        return -direction * (1f - tail) * width
    }

    override fun getPhantomTargets(ingredient: Any?): List<Target> {
        applyShown()
        if (moving()) return emptyList()
        return super.getPhantomTargets(ingredient)
    }

    override fun isMouseOverElement(mouseX: Double, mouseY: Double): Boolean {
        if (moving()) return false
        return super.isMouseOverElement(mouseX, mouseY)
    }

    override fun drawInBackground(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        applyShown()
        val x = getPositionX()
        val y = getPositionY()
        val pose = graphics.pose().last().pose()
        val min = pose.transform(Vector4f(x.toFloat(), y.toFloat(), 0f, 1f))
        val max = pose.transform(Vector4f((x + getSizeWidth()).toFloat(), (y + getSizeHeight()).toFloat(), 0f, 1f))
        graphics.enableScissor(min.x.toInt(), min.y.toInt(), max.x.toInt(), max.y.toInt())
        graphics.pose().pushPose()
        graphics.pose().translate(offset(), 0f, 0f)
        super.drawInBackground(graphics, mouseX, mouseY, partialTicks)
        graphics.pose().popPose()
        graphics.disableScissor()
    }

    private companion object {
        /** Short slide for one IO page. The full-screen stage slide stays on its own clock. */
        const val IO_PAGE_MS = 320L
    }
}
