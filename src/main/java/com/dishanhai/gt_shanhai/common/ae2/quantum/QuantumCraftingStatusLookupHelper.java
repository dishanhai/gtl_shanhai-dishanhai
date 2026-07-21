package com.dishanhai.gt_shanhai.common.ae2.quantum;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.stacks.AEKey;
import appeng.me.service.CraftingService;
import appeng.menu.me.crafting.CraftingStatusMenu;

import com.dishanhai.gt_shanhai.mixin.CraftingStatusMenuAccessor;
import com.dishanhai.gt_shanhai.network.QuantumCraftingStatusResponsePacket;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

/** 为当前打开的量子 CPU 状态页提供轻量、只读的阻塞状态查询。 */
public final class QuantumCraftingStatusLookupHelper {

    private QuantumCraftingStatusLookupHelper() {}

    public static void resolveAndReply(ServerPlayer player, AEKey key) {
        if (!(player.containerMenu instanceof CraftingStatusMenu statusMenu)) return;
        ICraftingCPU selected = ((CraftingStatusMenuAccessor) statusMenu).gtShanhai$getSelectedCpuRaw();
        if (!(selected instanceof QuantumCraftingCPU quantumCpu)) return;
        IGrid grid = quantumCpu.getGrid();
        if (grid == null || !(grid.getCraftingService() instanceof CraftingService craftingService)) return;

        QuantumCraftingStatus status = quantumCpu.craftingLogic.getCraftingStatus(key, craftingService);
        ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new QuantumCraftingStatusResponsePacket(key, status));
    }
}
