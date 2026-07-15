package com.dishanhai.gt_shanhai.client.gui.shop;

import net.minecraft.client.Minecraft;

/**
 * 客户端专用：打开山海商店界面。
 * 单独成类以便用 {@code DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)} 隔离，
 * 避免服务端类加载客户端 Screen。
 */
public final class ShopScreenOpener {

    private ShopScreenOpener() {}

    public static void open(boolean canEdit, boolean catalogEditUnlocked) {
        Minecraft.getInstance().setScreen(new ShopScreen(canEdit, catalogEditUnlocked));
    }
}
