package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.sh_better_ui.client.gui.display.GraphicIcon;

import java.util.function.Supplier;

/** 山海：顶栏图文的宽度预算与旧库购物车图标回退。 */
final class ShopToolbarIcons {
    static final int ICON_SPACE = 16;

    private ShopToolbarIcons() {}

    static int contentWidth(int minimum, int maximum, int textWidth) {
        return Math.max(minimum, Math.min(maximum, textWidth + ICON_SPACE + 4));
    }

    static GraphicIcon shoppingCart(Supplier<GraphicIcon> lookup) {
        try {
            GraphicIcon icon = lookup.get();
            return icon == null ? GraphicIcon.BUY : icon;
        } catch (NoSuchFieldError ignored) {
            return GraphicIcon.BUY;
        }
    }
}
