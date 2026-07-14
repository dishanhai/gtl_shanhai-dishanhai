package com.dishanhai.gt_shanhai.client.gui.shop;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.function.Supplier;

/**
 * 可被外部动画进度控制的 EditBox（山海署名）：渲染时按宿主传入的锚点 + 动画进度做一次
 * PoseStack 缩放变换，点击/焦点/光标/键盘等原版输入逻辑完全不动，只在绘制那一刻套一层几何变换。
 * 用于替代"动画播放期间反复 setX/setY/setWidth 追位置"的做法——见 ShopScreen 详情面板
 * 弹入动画与 amountBox（购买次数输入框）冲突的反馈，直接让控件自己跟着动画走。
 */
public class AnimatableEditBox extends EditBox {

    private Supplier<Float> animProgress = () -> 1f; // 1=动画已播完，不做变换
    private float anchorCx;
    private float anchorCy;
    private static final float MIN_SCALE = 0.94f; // 与 ShopScreen 弹入动画公式一致（0.94→1.0 ease-out）

    public AnimatableEditBox(Font font, int x, int y, int width, int height, Component msg) {
        super(font, x, y, width, height, msg);
    }

    /** 设置动画驱动源和缩放锚点；宿主每帧调用一次即可（锚点须跟宿主自己的弹入动画用同一套，视觉才同步）。 */
    public void setAnim(Supplier<Float> animProgress, float anchorCx, float anchorCy) {
        this.animProgress = animProgress;
        this.anchorCx = anchorCx;
        this.anchorCy = anchorCy;
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        float t = animProgress.get();
        if (t >= 1f) {
            super.renderWidget(g, mouseX, mouseY, partialTick);
            return;
        }
        float eased = 1f - (1f - t) * (1f - t);
        float scale = MIN_SCALE + (1f - MIN_SCALE) * eased;
        g.pose().pushPose();
        g.pose().translate(anchorCx, anchorCy, 0);
        g.pose().scale(scale, scale, 1f);
        g.pose().translate(-anchorCx, -anchorCy, 0);
        super.renderWidget(g, mouseX, mouseY, partialTick);
        g.pose().popPose();
    }
}
