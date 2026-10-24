package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.client.shop.ClientShopUiSettings;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 商店初始化向导的测试命令（山海署名，纯客户端，不发服务端）：
 * <ul>
 *     <li>{@code /shanhai shopwizard}：立刻弹出向导。预览模式——不动「已完成」标记，关掉后回到游戏；
 *     「完成」仍会把偏好页的选择写进本机设置，方便测保存路径。</li>
 *     <li>{@code /shanhai shopwizard reset}：把「已完成」标记清掉，下次真的打开商店时按新玩家再弹一次。</li>
 * </ul>
 * 根字面量 {@code shanhai} 与服务端、全息菜单那几条共用，Brigadier 按名字合并子节点。
 */
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShopWizardCommand {

    public static final String ROOT = "shanhai";
    public static final String ARG = "shopwizard";

    private ShopWizardCommand() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(ROOT).then(Commands.literal(ARG)
                .executes(ctx -> {
                    // 命令由聊天框触发，聊天框处理完回车后会 setScreen(null)；排到下一轮再开，否则刚开的向导立刻被关掉
                    Minecraft mc = Minecraft.getInstance();
                    mc.tell(() -> mc.setScreen(new ShopWelcomeScreen(() -> null, true)));
                    return 1;
                })
                .then(Commands.literal("reset").executes(ctx -> {
                    ClientShopUiSettings.setSetupDone(false);
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "§a已清除商店初始化标记，下次打开商店会按新玩家再弹一次向导"), false);
                    return 1;
                }))));
    }
}
