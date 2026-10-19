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

    private Supplier<Float> animProgress = () -> 1f;
    private Supplier<Float> slideX = () -> 0f;
    private float anchorCx;
    private float anchorCy;

    public AnimatableEditBox(Font font, int x, int y, int width, int height, Component msg) {
        super(font, x, y, width, height, msg);
    }

    /** 开场缩放。锚点用宿主面板中心，进度用同一条 {@link GuiRenderUtil#popAnimProgress}。 */
    public void setAnim(Supplier<Float> animProgress, float anchorCx, float anchorCy) {
        this.animProgress = animProgress == null ? () -> 1f : animProgress;
        this.anchorCx = anchorCx;
        this.anchorCy = anchorCy;
    }

    /** 切页横移。只改绘制，不改布局坐标。 */
    public void setSlideX(Supplier<Float> slideX) {
        this.slideX = slideX == null ? () -> 0f : slideX;
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        float t = animProgress.get();
        float dx = slideX.get();
        if (t >= 1f && dx == 0f) {
            super.renderWidget(g, mouseX, mouseY, partialTick);
            return;
        }
        g.pose().pushPose();
        try {
            if (t < 1f) GuiRenderUtil.popScaleAt(g, anchorCx, anchorCy, t);
            if (dx != 0f) g.pose().translate(dx, 0f, 0f);
            super.renderWidget(g, mouseX, mouseY, partialTick);
        } finally {
            g.pose().popPose();
        }
    }
}
