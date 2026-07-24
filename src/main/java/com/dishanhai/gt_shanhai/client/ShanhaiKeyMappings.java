package com.dishanhai.gt_shanhai.client;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.dishanhai.gt_shanhai.network.WalletOpenRequestPacket;
import com.dishanhai.gt_shanhai.network.ShanhaiPatternTerminalOpenRequestPacket;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 山海钱包快捷键：默认绑定逗号键「,」（玩家可在控制选项里自行改绑）。按下时不要求手持/装备，
 * 只要背包（含盔甲栏/副手）或 Curios 饰品栏任意位置有钱包即可直接打开商店
 * （见 {@link com.dishanhai.gt_shanhai.common.item.WalletItem#isCarrying}）。
 */
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ShanhaiKeyMappings {

    private static final String CATEGORY = "key.categories." + GTDishanhaiMod.MOD_ID;

    public static KeyMapping OPEN_SHOP;
    public static KeyMapping OPEN_PATTERN_MANAGEMENT;
    public static KeyMapping AE_TERMINAL_FAVORITE;

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        OPEN_SHOP = new KeyMapping("key." + GTDishanhaiMod.MOD_ID + ".open_shop",
                InputConstants.Type.KEYSYM, org.lwjgl.glfw.GLFW.GLFW_KEY_COMMA, CATEGORY);
        event.register(OPEN_SHOP);
        OPEN_PATTERN_MANAGEMENT = new KeyMapping(
                "key." + GTDishanhaiMod.MOD_ID + ".open_pattern_management",
                InputConstants.Type.KEYSYM, org.lwjgl.glfw.GLFW.GLFW_KEY_N, CATEGORY);
        event.register(OPEN_PATTERN_MANAGEMENT);
        AE_TERMINAL_FAVORITE = new KeyMapping(
                "key." + GTDishanhaiMod.MOD_ID + ".ae_terminal_favorite",
                InputConstants.Type.KEYSYM, org.lwjgl.glfw.GLFW.GLFW_KEY_Q, CATEGORY);
        event.register(AE_TERMINAL_FAVORITE);
    }

    /** 由 {@link ClientInit} 注册到 Forge 事件总线（RegisterKeyMappingsEvent 是 MOD 总线，需分开监听）。 */
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || OPEN_SHOP == null) return;
        while (OPEN_SHOP.consumeClick()) {
            if (Minecraft.getInstance().player != null) {
                ShanhaiNetwork.CHANNEL.sendToServer(new WalletOpenRequestPacket());
            }
        }
        if (OPEN_PATTERN_MANAGEMENT != null) {
            while (OPEN_PATTERN_MANAGEMENT.consumeClick()) {
                if (Minecraft.getInstance().player != null) {
                    ShanhaiNetwork.CHANNEL.sendToServer(new ShanhaiPatternTerminalOpenRequestPacket());
                }
            }
        }
    }

    /** 钱包 tooltip 用：当前"打开山海商店"快捷键的显示文案（未绑定则提示未指定）。 */
    public static Component getOpenShopKeyTooltip() {
        if (OPEN_SHOP == null || OPEN_SHOP.isUnbound()) {
            return Component.literal("§8未指定按键打开钱包");
        }
        return Component.literal("§7按下 [§e")
                .append(OPEN_SHOP.getTranslatedKeyMessage())
                .append(Component.literal("§7] 键打开钱包"));
    }
}
