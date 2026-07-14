package com.dishanhai.gt_shanhai.client;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.block.DShanhaiAE2Blocks;
import com.dishanhai.gt_shanhai.common.item.SuperDiskArrayItem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.ClickEvent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;

public class ClientInit {

    public static void init() {
        // 程序化注册 Guide（支持 config/gt_shanhai/guides/ 热重载）
        GuideRegistration.register();

        // 注册模组列表「配置」按钮的界面工厂（cloth-config，客户端专用）
        net.minecraftforge.fml.ModLoadingContext.get().registerExtensionPoint(
                net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory(
                        (mc, parent) -> com.dishanhai.gt_shanhai.client.config.DShanhaiConfigScreen.build(parent)));

        ItemBlockRenderTypes.setRenderLayer(DShanhaiAE2Blocks.QUANTUM_COMPUTER.get(), RenderType.cutout());
        ItemBlockRenderTypes.setRenderLayer(DShanhaiAE2Blocks.QUANTUM_COMPUTER_UNIT.get(), RenderType.cutout());
        ItemBlockRenderTypes.setRenderLayer(DShanhaiAE2Blocks.QUANTUM_PARALLEL_PROCESSOR.get(), RenderType.cutout());
        ItemBlockRenderTypes.setRenderLayer(DShanhaiAE2Blocks.QUANTUM_CRAFTING_STORAGE.get(), RenderType.cutout());
        // 结构玻璃的面用半透明（QuantumStructureBakedModel 内部 super(RenderType.translucent(), RenderType.cutout(), ...)），
        // 和同批其余量子方块（内部全 cutout）不同，这里必须单独注册 translucent，否则半透明面会被当 cutout 硬裁切成实心色块
        ItemBlockRenderTypes.setRenderLayer(DShanhaiAE2Blocks.QUANTUM_STRUCTURE.get(), RenderType.translucent());

        // 量子方块 formed 状态的连接材质模型改用原生 IGeometryLoader 注册
        // （见 QuantumModelRegistration，ModelEvent.RegisterGeometryLoaders）。
        // 原先这里用 AE2 的 BuiltInModelHooks.addBuiltInModel 做 mixin 拦截式替换，
        // 但 AE2 15.4.10 的 BuiltInModelHooks.getBuiltInModel 只认 "ae2" 命名空间，
        // gt_shanhai 的替换请求会被静默吞掉，5 个方块的连接材质模型从未真正生效过。

        Minecraft.getInstance().getItemColors().register(
            (stack, tintIndex) -> SuperDiskArrayItem.getColor(stack, tintIndex),
            GTDishanhaiMod.SUPER_DISK_ARRAY.get()
        );

        MinecraftForge.EVENT_BUS.addListener(ClientInit::onClientChatReceived);
        MinecraftForge.EVENT_BUS.addListener(ClientInit::onMouseButtonPressed);
        MinecraftForge.EVENT_BUS.addListener(ClientInit::onClientLoggingOut);
        MinecraftForge.EVENT_BUS.addListener(ClientInit::onClientLoggingIn);
        MinecraftForge.EVENT_BUS.addListener(ShanhaiKeyMappings::onClientTick);
    }

    private static void onClientChatReceived(ClientChatReceivedEvent event) {
        event.setMessage(JeiChatLinkHelper.makeLinks(event.getMessage()));
    }

    private static void onMouseButtonPressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() != 0 || !(event.getScreen() instanceof ChatScreen)) return;

        var style = Minecraft.getInstance().gui.getChat().getClickedComponentStyleAt(event.getMouseX(), event.getMouseY());
        if (style == null) return;

        ClickEvent clickEvent = style.getClickEvent();
        if (clickEvent == null || clickEvent.getAction() != ClickEvent.Action.RUN_COMMAND) return;

        if (JeiChatLinkHelper.runSearchCommand(clickEvent.getValue())) {
            event.setCanceled(true);
        }
    }

    /** 不允许跨服务器复用相同 revision 的旧商品目录。 */
    private static void onClientLoggingOut(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        com.dishanhai.gt_shanhai.client.shop.ClientShopCatalog.clear();
    }

    /**
     * 软依赖提醒（山海署名，非强制）：装了 JEI 但没装 JEI Optimize 时，进世界提示一句可选装。
     * JEI Optimize 是 All Rights Reserved 协议，gt_shanhai 不能内嵌分发，只能这样提醒玩家自行安装。
     * 只在 {@code ClientPlayerNetworkEvent.LoggingIn}（每次连接服务器/进世界各触发一次，不含重生/换维度）
     * 提一次，不做持久化抑制——不打扰、也不用额外状态管理。
     */
    private static void onClientLoggingIn(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingIn event) {
        var modList = net.minecraftforge.fml.ModList.get();
        if (!modList.isLoaded("jei") || modList.isLoaded("jei_optimize")) return;
        var player = event.getPlayer();
        if (player == null) return;
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§b[山海] §7检测到装了 JEI 但没装 §fJEI Optimize§7，建议自行搜索安装以加速 JEI 首次索引构建（可选，不影响正常游玩）"));
    }
}
