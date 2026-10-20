package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.scaled.GuiRenderUtil;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.function.Supplier;

/**
 * 绘制时跟上宿主面板的开场缩放或切页平移。控件的 x/y 保持布局坐标，点击不跟视觉偏移。
 */
public class AnimatableEditBox extends EditBox {

    private Supplier<Float> animScale = () -> 1f;
    private Supplier<Float> slideX = () -> 0f;
    private float anchorCx;
    private float anchorCy;

    public AnimatableEditBox(Font font, int x, int y, int width, int height, Component msg) {
        super(font, x, y, width, height, msg);
    }

    /**
     * 开场缩放。锚点用宿主面板中心；供应器返回当前缩放系数（1 = 已落位），
     * 通常是 {@code GuiMotion.panelScale(openMotion.value())}，和宿主面板用同一个值。
     */
    public void setAnim(Supplier<Float> animScale, float anchorCx, float anchorCy) {
        this.animScale = animScale == null ? () -> 1f : animScale;
        this.anchorCx = anchorCx;
        this.anchorCy = anchorCy;
    }

    /** 切页横移。只改绘制，不改布局坐标。 */
    public void setSlideX(Supplier<Float> slideX) {
        this.slideX = slideX == null ? () -> 0f : slideX;
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        float s = animScale.get();
        float dx = slideX.get();
        if (s >= 0.9995f && dx == 0f) {
            super.renderWidget(g, mouseX, mouseY, partialTick);
            return;
        }
        g.pose().pushPose();
        try {
            if (s < 0.9995f) GuiRenderUtil.scaleAt(g, anchorCx, anchorCy, s);
            if (dx != 0f) g.pose().translate(dx, 0f, 0f);
            super.renderWidget(g, mouseX, mouseY, partialTick);
        } finally {
            g.pose().popPose();
        }
    }
}
