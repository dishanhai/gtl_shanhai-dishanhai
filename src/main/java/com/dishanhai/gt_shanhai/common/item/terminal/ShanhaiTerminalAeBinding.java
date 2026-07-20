package com.dishanhai.gt_shanhai.common.item.terminal;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.MEStorage;
import com.dishanhai.gt_shanhai.common.item.ShanhaiUltimateTerminalConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class ShanhaiTerminalAeBinding {

    public record Context(IGrid grid, MEStorage storage, IActionHost host, IActionSource source) {}

    private ShanhaiTerminalAeBinding() {}

    public static boolean bind(ServerPlayer player, ItemStack terminal, BlockPos pos) {
        BlockEntity blockEntity = player.level().getBlockEntity(pos);
        if (!(blockEntity instanceof IActionHost host)) return false;
        IGridNode node = host.getActionableNode();
        if (node == null || !node.isOnline() || node.getGrid() == null) return false;
        ShanhaiUltimateTerminalConfig.setBoundAe(
                terminal, GlobalPos.of(player.level().dimension(), pos.immutable()));
        return true;
    }

    public static Context resolve(ServerPlayer player, ItemStack terminal) {
        GlobalPos bound = ShanhaiUltimateTerminalConfig.getBoundAe(terminal);
        if (bound == null || player.getServer() == null) return null;
        ServerLevel level = player.getServer().getLevel(bound.dimension());
        if (level == null || !level.hasChunkAt(bound.pos())) return null;
        BlockEntity blockEntity = level.getBlockEntity(bound.pos());
        if (!(blockEntity instanceof IActionHost host)) return null;
        IGridNode node = host.getActionableNode();
        if (node == null || !node.isOnline() || node.getGrid() == null) return null;
        IGrid grid = node.getGrid();
        MEStorage storage = grid.getStorageService().getInventory();
        IActionSource source = IActionSource.ofPlayer(player, host);
        return new Context(grid, storage, host, source);
    }
}
