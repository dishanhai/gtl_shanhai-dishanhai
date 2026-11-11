package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.sh_better_ui.client.gui.scaled.UiButton;
import com.dishanhai.sh_better_ui.client.gui.scaled.UiPreferences;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;

/**
 * 山海：共享按钮的商店画布适配。输入和焦点沿用原生 AbstractButton，操作及选中旁白
 * 委托 UiButton；宿主负责主题绘制，避免共享控件的 Screen scissor 再次缩放。
 */
final class ShopNativeButton extends AbstractButton {
    @FunctionalInterface
    interface Painter {
        void paint(ShopNativeButton button, GuiGraphics graphics, int mouseX, int mouseY);
    }

    private final UiButton shared;
    private final Painter painter;
    private int clipLeft = Integer.MIN_VALUE;
    private int clipRight = Integer.MAX_VALUE;

    ShopNativeButton(Component label, Runnable action, Painter painter) {
        super(0, 0, 1, 1, label);
        this.painter = painter;
        shared = new UiButton(0, 0, 1, 1, label, ignored -> action.run());
        // 原生 tooltip 的 Screen 坐标不适用于虚拟画布；保留商店已有的提示绘制。
        shared.setTooltip(null);
    }

    void sync(int x, int y, int width, int height, Component label, boolean enabled,
              boolean selected, int clipLeft, int clipRight) {
        setX(x);
        setY(y);
        setWidth(width);
        setHeight(height);
        setMessage(label);
        this.clipLeft = clipLeft;
        this.clipRight = clipRight;
        active = enabled;
        shared.setMessage(label);
        shared.setTooltip(null);
        shared.setSelected(selected);
        shared.setPreferences(UiPreferences.configured());
        syncSharedState();
    }

    private void syncSharedState() {
        shared.active = active;
        shared.visible = visible;
        shared.setFocused(isFocused());
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return visible && ShopControlInput.hitClipped(mouseX, mouseY,
                getX(), getY(), getWidth(), getHeight(), clipLeft, clipRight);
    }

    @Override
    protected boolean clicked(double mouseX, double mouseY) {
        return active && isMouseOver(mouseX, mouseY);
    }

    @Override
    public void onPress() {
        syncSharedState();
        if (active && visible) shared.onPress();
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        isHovered = isMouseOver(mouseX, mouseY);
        syncSharedState();
        painter.paint(this, graphics, mouseX, mouseY);
        if (isFocused() && active) {
            ShopTheme.current().focus(graphics, getX(), getY(), getWidth(), getHeight());
        }
    }

    @Override
    public void playDownSound(SoundManager soundManager) {
        ShopUiSounds.click();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        syncSharedState();
        shared.updateWidgetNarration(output);
    }
}
