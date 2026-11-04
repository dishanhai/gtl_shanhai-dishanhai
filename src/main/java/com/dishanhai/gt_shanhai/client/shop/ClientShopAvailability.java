package com.dishanhai.gt_shanhai.client.shop;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.client.gui.shop.ShopWelcomeScreen;
import com.dishanhai.gt_shanhai.client.gui.shop.ShopScreen;
import com.dishanhai.gt_shanhai.client.gui.shop.ShopScreenOpener;
import com.dishanhai.gt_shanhai.client.gui.shop.ShopSettingsScreen;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.dishanhai.gt_shanhai.network.ShopAvailabilityPacket;
import com.dishanhai.gt_shanhai.common.shop.ShopAvailability;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 山海商店：只缓存本次连接的服务端状态，个人开关另存本机 config。 */
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, value = Dist.CLIENT)
public final class ClientShopAvailability {
    private static boolean globalDisabled;
    private static boolean canConfigureGlobal;

    private ClientShopAvailability() {}

    public static boolean globalDisabled() { return globalDisabled; }
    public static boolean canConfigureGlobal() { return canConfigureGlobal; }
    public static boolean isDisabled() {
        return ShopAvailability.isDisabled(globalDisabled, ClientShopUiSettings.shopDisabled());
    }

    public static void apply(boolean disabled, boolean canConfigure, boolean updateResult) {
        globalDisabled = disabled;
        canConfigureGlobal = canConfigure;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ShopWelcomeScreen wizard) {
            wizard.onAvailabilitySynced(updateResult);
        } else if (isDisabled() && mc.screen instanceof ShopScreen shop) {
            shop.onAvailabilityChanged();
        } else if (isDisabled() && mc.screen != null && !(mc.screen instanceof ShopSettingsScreen)
                && mc.screen.getClass().getPackageName().equals("com.dishanhai.gt_shanhai.client.gui.shop")) {
            ShopScreenOpener.returnToShop();
        }
    }

    public static void syncPersonal() {
        if (Minecraft.getInstance().getConnection() != null) {
            ShanhaiNetwork.CHANNEL.sendToServer(ShopAvailabilityPacket.personal(ClientShopUiSettings.shopDisabled()));
        }
    }

    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) { syncPersonal(); }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        globalDisabled = false;
        canConfigureGlobal = false;
        ShopScreenOpener.clear();
    }
}
