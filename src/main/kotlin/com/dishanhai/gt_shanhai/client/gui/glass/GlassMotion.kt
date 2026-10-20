package com.dishanhai.gt_shanhai.client.gui.glass

/**
 * 换页动态模糊的纯数学：线性进度 → 强度 / 半径 / 方向。不依赖 Minecraft，可直接单测。
 *
 * 强度取 smoothstep 的归一化速度：起手略晚才糊、中段最重、落位前自然退掉，
 * 所以不需要再另加商店飞行里的 32ms 延迟和尾段淡出。
 */
object GlassMotion {

    /** 水平滑动峰值的模糊半径（虚拟 GUI 单位，乘 GUI 缩放换成像素）。初值，进游戏后调。 */
    const val MOTION_RADIUS_VIRTUAL = 8f

    /** 强度低于它就不登记请求。 */
    const val ACTIVE_STRENGTH = 0.01f

    data class Sample(
        val strength: Float,
        val radiusVirtual: Float,
        val axisX: Float,
        val axisY: Float,
    ) {
        val isActive: Boolean get() = strength > ACTIVE_STRENGTH
    }

    /** 归一化速度：smoothstep 的导数 6l(1-l) 除以它的峰值 1.5，l 夹在 [0,1]。 */
    @JvmStatic
    fun speed(linear: Float): Float {
        val l = linear.coerceIn(0f, 1f)
        return 4f * l * (1f - l)
    }

    /** 水平滑动：只糊 H 趟。[linear] 来自 `ShanhaiRecipeEditorAnimation.linear`。 */
    @JvmStatic
    fun pageSlide(linear: Float): Sample = Sample(speed(linear), MOTION_RADIUS_VIRTUAL, 1f, 0f)
}
