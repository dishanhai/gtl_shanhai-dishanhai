package com.dishanhai.gt_shanhai.client;

import appeng.client.render.crafting.CraftingCubeModel;
import appeng.hooks.BuiltInModelHooks;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.client.renderer.quantum.QuantumInternalModelProvider;
import com.dishanhai.gt_shanhai.client.renderer.quantum.QuantumStructureModelProvider;
import com.dishanhai.gt_shanhai.common.block.DShanhaiAE2Blocks;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingUnitTypes;
import com.dishanhai.gt_shanhai.common.item.SuperDiskArrayItem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;

public class ClientInit {

    public static void init() {
        // 程序化注册 Guide（支持 config/gt_shanhai/guides/ 热重载）
        GuideRegistration.register();

        ItemBlockRenderTypes.setRenderLayer(DShanhaiAE2Blocks.QUANTUM_COMPUTER.get(), RenderType.cutout());
        ItemBlockRenderTypes.setRenderLayer(DShanhaiAE2Blocks.QUANTUM_COMPUTER_UNIT.get(), RenderType.cutout());
        ItemBlockRenderTypes.setRenderLayer(DShanhaiAE2Blocks.QUANTUM_PARALLEL_PROCESSOR.get(), RenderType.cutout());
        ItemBlockRenderTypes.setRenderLayer(DShanhaiAE2Blocks.QUANTUM_CRAFTING_STORAGE.get(), RenderType.cutout());
        ItemBlockRenderTypes.setRenderLayer(DShanhaiAE2Blocks.QUANTUM_STRUCTURE.get(), RenderType.cutout());

        BuiltInModelHooks.addBuiltInModel(
            new ResourceLocation(GTDishanhaiMod.MOD_ID, "block/crafting/quantum_computer_formed"),
            new CraftingCubeModel(new QuantumInternalModelProvider(QuantumCraftingUnitTypes.COMPUTER_UNIT))
        );
        BuiltInModelHooks.addBuiltInModel(
            new ResourceLocation(GTDishanhaiMod.MOD_ID, "block/crafting/quantum_computer_unit_formed"),
            new CraftingCubeModel(new QuantumInternalModelProvider(QuantumCraftingUnitTypes.COMPUTER_UNIT))
        );
        BuiltInModelHooks.addBuiltInModel(
            new ResourceLocation(GTDishanhaiMod.MOD_ID, "block/crafting/quantum_parallel_processor_formed"),
            new CraftingCubeModel(new QuantumInternalModelProvider(QuantumCraftingUnitTypes.PARALLEL_PROCESSOR))
        );
        BuiltInModelHooks.addBuiltInModel(
            new ResourceLocation(GTDishanhaiMod.MOD_ID, "block/crafting/quantum_crafting_storage_formed"),
            new CraftingCubeModel(new QuantumInternalModelProvider(QuantumCraftingUnitTypes.CRAFTING_STORAGE))
        );
        BuiltInModelHooks.addBuiltInModel(
            new ResourceLocation(GTDishanhaiMod.MOD_ID, "block/crafting/quantum_structure_formed"),
            new CraftingCubeModel(new QuantumStructureModelProvider())
        );

        Minecraft.getInstance().getItemColors().register(
            (stack, tintIndex) -> SuperDiskArrayItem.getColor(stack, tintIndex),
            GTDishanhaiMod.SUPER_DISK_ARRAY.get()
        );

        MinecraftForge.EVENT_BUS.addListener(ClientInit::onClientChatReceived);
        MinecraftForge.EVENT_BUS.addListener(ClientInit::onMouseButtonPressed);
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
