package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.sh_better_ui.client.glass.GlassTheme;
import com.dishanhai.gt_shanhai.client.shop.ClientShopUiSettings;

/** 山海：商店只负责本机主题选择，共用绘制由独立 SH-Better-UI 库提供。 */
public final class ShopTheme extends GlassTheme {
    public static final ShopTheme CLASSIC = new ShopTheme(GlassTheme.CLASSIC);
    public static final ShopTheme GLASS = new ShopTheme(GlassTheme.GLASS);

    private ShopTheme(GlassTheme source) {
        super(source);
    }

    public static ShopTheme current() {
        return ClientShopUiSettings.glassTheme() ? GLASS : CLASSIC;
    }
}
