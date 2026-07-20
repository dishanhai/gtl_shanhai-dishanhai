package com.dishanhai.gt_shanhai.common.item.terminal;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.item.DShanhaiItems;
import com.dishanhai.gt_shanhai.common.item.ShanhaiUltimateTerminalBehavior;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiUltimateTerminalInteractionHandler {

    private ShanhaiUltimateTerminalInteractionHandler() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        ItemStack terminal = event.getItemStack();
        if (!terminal.is(DShanhaiItems.ULTIMATE_TERMINAL.get())) return;
        if (!(MetaMachine.getMachine(event.getLevel(), event.getPos()) instanceof IMultiController)) return;

        event.setCanceled(true);
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            ShanhaiUltimateTerminalBehavior.INSTANCE.scanOnly(serverPlayer, terminal, event.getPos());
        }
    }
}
