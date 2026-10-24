package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.shop.ClientShopCatalog;
import com.dishanhai.gt_shanhai.client.shop.ClientShopUiSettings;
import com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.dishanhai.gt_shanhai.network.WalletOpenRequestPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端专用：打开山海商店界面。
 * 单独成类以便用 {@code DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)} 隔离，
 * 避免服务端类加载客户端 Screen。
 */
public final class ShopScreenOpener {

    private ShopScreenOpener() {}

    public static void open(boolean canEdit, boolean catalogEditUnlocked) {
        ShopScreen shop = new ShopScreen(canEdit, catalogEditUnlocked);
        // 这台客户端第一次开商店：先走初始化向导（功能介绍 + 偏好），结束后再进商店本体
        Minecraft.getInstance().setScreen(ClientShopUiSettings.setupDone()
                ? shop : new ShopWelcomeScreen(() -> shop));
    }

    /** 复用任务书跳转链路：记录目标后由服务端校验钱包并下发最新目录，再自动定位商品。 */
    public static void requestOpenAt(long entryKey) {
        ShopCatalogManifest.Stub stub = ClientShopCatalog.stub(entryKey);
        ShopScreen.requestFocus(stub == null ? null : stub.stableId(), entryKey);
        ShanhaiNetwork.CHANNEL.sendToServer(new WalletOpenRequestPacket());
    }

    /** 悬停 GT 币：先走钱包校验开店，商店起来后立刻进入货币中心并选中该币。 */
    public static void requestOpenCurrency(ResourceLocation currency) {
        if (currency == null) return;
        ShopScreen.requestCurrency(currency);
        ShanhaiNetwork.CHANNEL.sendToServer(new WalletOpenRequestPacket());
    }
}
