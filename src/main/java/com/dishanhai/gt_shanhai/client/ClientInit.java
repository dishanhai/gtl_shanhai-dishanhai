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
}
