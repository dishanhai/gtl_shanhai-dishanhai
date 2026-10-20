package com.dishanhai.gt_shanhai.client.gui.glass

import com.dishanhai.gt_shanhai.client.gui.shop.ShopGaussBlur
import com.dishanhai.gt_shanhai.client.gui.shop.ShopGlassStyle
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeEditorFactory
import com.lowdragmc.lowdraglib.gui.modular.ModularUIGuiContainer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraftforge.client.event.ScreenEvent

/**
 * 配方修改器接商店模糊的门面。
 *
 * 模糊本体仍是 [ShopGaussBlur]（同一条 PostChain、同一份着色器、同一个 Post 事件），
 * 这里只做四件事：登记放行谓词、预热后处理、把 LDLib 部件的 GUI 坐标换成 framebuffer 像素、界面关闭时收掉残留请求。
 * 所有 `request*` 只能在客户端渲染线程里调（LDLib 的 drawInBackground / drawInForeground 本来就只在客户端跑）。
 */
object GlassBlur {

    private fun isEditorScreen(screen: Screen?): Boolean =
        screen is ModularUIGuiContainer &&
            screen.modularUI.holder === ShanhaiRecipeEditorFactory.INSTANCE

    /** 客户端初始化时调一次。只放行配方修改器的 ModularUI 界面，其他 GT 界面不受影响。 */
    @JvmStatic
    fun install() {
        ShopGaussBlur.allowScreen { screen -> isEditorScreen(screen) }
    }

    /**
     * 放行的编辑器界面关闭时收掉残留请求，否则“换页途中关闭、下一帧立刻开商店”会让商店吃到一帧编辑器的矩形。
     * 由 ClientInit 以方法引用挂到 Forge 总线（Forge 靠方法引用解析事件类型）。
     */
    @JvmStatic
    fun onScreenClosing(event: ScreenEvent.Closing) {
        if (isEditorScreen(event.screen)) ShopGaussBlur.clear()
    }

    /** 编辑器打开后的首帧预热，避免第一次换页才编译着色器造成卡顿。 */
    @JvmStatic
    fun warmUp() {
        ShopGaussBlur.warmUp()
    }

    /**
     * 换页期间每帧调用。[viewport] 是页面的静止区域（不随 slide 平移），
     * 强度 / 半径 / 方向来自 [GlassMotion]。后处理资源不可用时静默跳过，调用方退回纯平移。
     */
    @JvmStatic
    fun requestPage(graphics: GuiGraphics, viewport: GuiRect, sample: GlassMotion.Sample) {
        if (!sample.isActive || viewport.isEmpty || !ShopGaussBlur.isReady()) return
        val view = capture(graphics) ?: return
        val px = view.toPixels(viewport)
        if (px.isEmpty) return
        ShopGaussBlur.requestDirectional(
            px.x, px.y, px.w, px.h,
            sample.strength, sample.radiusVirtual * view.pixelScale,
            sample.axisX, sample.axisY,
            ShopGlassStyle.SWAP,
        )
    }

    private const val POPUP_STRENGTH = 0.85f
    private const val POPUP_MARGIN_VIRTUAL = 14f
    private const val MIN_STRENGTH = 0.01f

    /**
     * 下拉 / 历史弹层打开时：[popup]（弹层本体，调用方可并上锚点按钮）保持清晰并带柔影（Hole），
     * 周围一圈被糊开（Rect = [popup] 外扩 [POPUP_MARGIN_VIRTUAL]）。[openAmount] 是 0..1 的打开量。
     * 走第二矩形槽位；`ShopGaussBlur.request*` 会清掉第二槽位，所以必须在同一帧的换页主请求之后登记
     * （Panel.drawInBackground 先 requestSlideBlur 再 super.drawInBackground，弹层在后面的遍历里登记）。
     */
    @JvmStatic
    fun requestPopup(graphics: GuiGraphics, popup: GuiRect, openAmount: Float) {
        val strength = openAmount.coerceIn(0f, 1f) * POPUP_STRENGTH
        if (strength <= MIN_STRENGTH || popup.isEmpty || !ShopGaussBlur.isReady()) return
        val view = capture(graphics) ?: return
        val hole = view.toPixels(popup)
        val ring = view.toPixels(GlassGeometry.expand(popup, POPUP_MARGIN_VIRTUAL))
        if (hole.isEmpty || ring.isEmpty) return
        ShopGaussBlur.requestExtra(
            ring.x, ring.y, ring.w, ring.h,
            strength, ShopGaussBlur.BLUR_RADIUS_VIRTUAL * view.pixelScale,
            hole.x, hole.y, hole.w, hole.h,
            ShopGlassStyle.OVERLAY,
        )
    }

    /** 一帧内的换算上下文：framebuffer 尺寸、GUI 尺寸，以及当前 pose 的缩放与平移。 */
    private class View(
        val fbW: Int,
        val fbH: Int,
        val guiW: Int,
        val guiH: Int,
        val scaleX: Float,
        val scaleY: Float,
        val translateX: Float,
        val translateY: Float,
    ) {
        /** framebuffer 像素 / GUI 单位，用来把虚拟半径换成像素半径。 */
        val pixelScale: Float get() = fbW.toFloat() / guiW

        fun toPixels(rect: GuiRect): PixelRect =
            GlassGeometry.toPixels(
                GlassGeometry.applyAffine(rect, scaleX, scaleY, translateX, translateY),
                fbW, fbH, guiW, guiH,
            )
    }

    private fun capture(graphics: GuiGraphics): View? {
        val mc = Minecraft.getInstance()
        val target = mc.mainRenderTarget ?: return null
        val guiW = mc.window.guiScaledWidth
        val guiH = mc.window.guiScaledHeight
        if (guiW <= 0 || guiH <= 0) return null
        val pose = graphics.pose().last().pose()
        return View(target.width, target.height, guiW, guiH, pose.m00(), pose.m11(), pose.m30(), pose.m31())
    }
}
