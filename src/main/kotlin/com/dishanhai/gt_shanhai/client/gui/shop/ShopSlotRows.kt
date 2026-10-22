package com.dishanhai.gt_shanhai.client.gui.shop

/** 编辑器槽位换行几何（山海署名，纯函数）；绘制、命中与补位共用同一套坐标。 */
object ShopSlotRows {
    private const val SLOT = 20
    private const val PITCH = 24

    @JvmStatic
    fun perLine(availableWidth: Int): Int = maxOf(3, availableWidth / PITCH)

    /** 到上限只停止显示加号，不隐藏旧数据中已经存在的超限项。 */
    @JvmStatic
    fun slotCount(itemCount: Int, maximum: Int): Int {
        val count = maxOf(0, itemCount)
        return if (count < maximum) count + 1 else count
    }

    @JvmStatic
    fun lines(slotCount: Int, perLine: Int): Int =
        if (slotCount <= 0) 0 else (slotCount - 1) / maxOf(1, perLine) + 1

    @JvmStatic
    fun slotX(originX: Int, index: Int, perLine: Int): Int = originX + index % maxOf(1, perLine) * PITCH

    @JvmStatic
    fun slotY(originY: Int, index: Int, perLine: Int, rowPitch: Int): Int =
        originY + index / maxOf(1, perLine) * rowPitch

    @JvmStatic
    fun height(slotCount: Int, perLine: Int, rowPitch: Int, lastLineHeight: Int): Int {
        val rows = lines(slotCount, perLine)
        return if (rows == 0) 0 else (rows - 1) * rowPitch + lastLineHeight
    }

    /** 右/下边界、格距和末行空位均拒收；数量文字传 originY+21、hitHeight=9。 */
    @JvmStatic
    fun slotIndexAt(
        mouseX: Double, mouseY: Double, originX: Int, originY: Int,
        slotCount: Int, perLine: Int, rowPitch: Int, hitHeight: Int
    ): Int {
        val dx = mouseX - originX
        val dy = mouseY - originY
        if (!dx.isFinite() || !dy.isFinite() || dx < 0 || dy < 0 || rowPitch <= 0 || hitHeight <= 0) return -1
        val columns = maxOf(1, perLine)
        if (dx >= columns.toDouble() * PITCH || dy >= lines(slotCount, columns).toDouble() * rowPitch) return -1
        val col = (dx / PITCH).toInt()
        val row = (dy / rowPitch).toInt()
        if (dx - col * PITCH >= SLOT || dy - row * rowPitch >= hitHeight) return -1
        val index = row * columns + col
        return if (index < slotCount) index else -1
    }
}
