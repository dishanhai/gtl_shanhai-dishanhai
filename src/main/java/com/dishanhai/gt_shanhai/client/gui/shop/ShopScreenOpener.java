package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.shop.ClientShopCatalog;
import com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.dishanhai.gt_shanhai.network.WalletOpenRequestPacket;

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

    /** 复用任务书跳转链路：记录目标后由服务端校验钱包并下发最新目录，再自动定位商品。 */
    public static void requestOpenAt(long entryKey) {
        ShopCatalogManifest.Stub stub = ClientShopCatalog.stub(entryKey);
        ShopScreen.requestFocus(stub == null ? null : stub.stableId(), entryKey);
        ShanhaiNetwork.CHANNEL.sendToServer(new WalletOpenRequestPacket());
    }
}
