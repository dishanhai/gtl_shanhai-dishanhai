package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.scaled.ScaledScreen;
import com.dishanhai.gt_shanhai.client.shop.ClientShopUiSettings;
import com.dishanhai.gt_shanhai.client.shop.ShopAppearance;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** 山海：商店子页共用主题与显示偏好作用域，结束绘制后恢复外层快照。 */
abstract class ShopScaledScreen extends ScaledScreen {
    protected ShopScaledScreen(Component title) {
        super(title);
    }

    protected ShopAppearance screenAppearance() {
        return ClientShopUiSettings.shopAppearance();
    }

    protected boolean screenReducedMotion() {
        return ClientShopUiSettings.reduceMotion();
    }

    protected ShopTheme screenTheme() {
        return ShopTheme.of(ClientShopUiSettings.shopTheme(), screenAppearance().materialId());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ShopTheme.preview(screenTheme(), () -> ShopVisuals.scoped(screenAppearance(), screenReducedMotion(),
                () -> super.render(graphics, mouseX, mouseY, partialTick)));
    }
}
