package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.sh_better_ui.client.glass.GlassTheme;
import com.dishanhai.gt_shanhai.client.shop.ClientShopUiSettings;

/** 山海：商店只负责本机主题选择，共用绘制由独立 SH-Better-UI 库提供。 */
public final class ShopTheme extends GlassTheme {
    public static final ShopTheme CLASSIC = new ShopTheme(GlassTheme.CLASSIC);
    public static final ShopTheme GLASS = new ShopTheme(GlassTheme.GLASS);
    public static final ShopTheme SMOKE = new ShopTheme(GlassTheme.SMOKE);

    private ShopTheme(GlassTheme source) {
        super(source);
    }

    public static ShopTheme current() {
        return of(ClientShopUiSettings.shopTheme());
    }

    public static ShopTheme of(String id) {
        return switch (id) {
            case ClientShopUiSettings.THEME_GLASS -> GLASS;
            case ClientShopUiSettings.THEME_SMOKE -> SMOKE;
            default -> CLASSIC;
        };
    }

    public static String next(String id) {
        return switch (id) {
            case ClientShopUiSettings.THEME_CLASSIC -> ClientShopUiSettings.THEME_GLASS;
            case ClientShopUiSettings.THEME_GLASS -> ClientShopUiSettings.THEME_SMOKE;
            default -> ClientShopUiSettings.THEME_CLASSIC;
        };
    }
}
