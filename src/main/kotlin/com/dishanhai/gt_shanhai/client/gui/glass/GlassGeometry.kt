package com.dishanhai.gt_shanhai.client.gui.glass

/** GUI 坐标矩形（Screen 缩放后的坐标，原点左上）。宽高 <= 0 视为空。 */
data class GuiRect(val x: Float, val y: Float, val w: Float, val h: Float) {
    val isEmpty: Boolean get() = w <= 0f || h <= 0f

    /** 两个矩形的外接矩形。弹层打开时用来把锚点按钮和弹层一起留在清晰区。 */
    fun union(other: GuiRect): GuiRect {
        val x0 = minOf(x, other.x)
        val y0 = minOf(y, other.y)
        return GuiRect(x0, y0, maxOf(x + w, other.x + other.w) - x0, maxOf(y + h, other.y + other.h) - y0)
    }
}

/** framebuffer 像素矩形，原点左上，与 `ShopGaussBlur.request*` 的参数一致。 */
data class PixelRect(val x: Int, val y: Int, val w: Int, val h: Int) {
    val isEmpty: Boolean get() = w <= 0 || h <= 0

    companion object {
        @JvmField
        val EMPTY = PixelRect(0, 0, 0, 0)
    }
}

/** GUI 矩形 → framebuffer 像素的纯数学，不依赖 Minecraft。 */
object GlassGeometry {

    /** 套 pose 的缩放与平移（不含旋转）。 */
    @JvmStatic
    fun applyAffine(rect: GuiRect, scaleX: Float, scaleY: Float, tx: Float, ty: Float): GuiRect =
        GuiRect(rect.x * scaleX + tx, rect.y * scaleY + ty, rect.w * scaleX, rect.h * scaleY)

    /** 四边各向外放宽 [margin]（GUI 单位）。 */
    @JvmStatic
    fun expand(rect: GuiRect, margin: Float): GuiRect =
        GuiRect(rect.x - margin, rect.y - margin, rect.w + margin * 2f, rect.h + margin * 2f)

    /** 按两个角取整，避免“左上角和宽度各自取整”带来的 1px 漂移。 */
    @JvmStatic
    fun toPixels(rect: GuiRect, fbW: Int, fbH: Int, guiW: Int, guiH: Int): PixelRect {
        if (rect.isEmpty || fbW <= 0 || fbH <= 0 || guiW <= 0 || guiH <= 0) return PixelRect.EMPTY
        val sx = fbW.toFloat() / guiW
        val sy = fbH.toFloat() / guiH
        val x0 = Math.round(rect.x * sx)
        val y0 = Math.round(rect.y * sy)
        val x1 = Math.round((rect.x + rect.w) * sx)
        val y1 = Math.round((rect.y + rect.h) * sy)
        if (x1 <= x0 || y1 <= y0) return PixelRect.EMPTY
        return PixelRect(x0, y0, x1 - x0, y1 - y0)
    }
}
