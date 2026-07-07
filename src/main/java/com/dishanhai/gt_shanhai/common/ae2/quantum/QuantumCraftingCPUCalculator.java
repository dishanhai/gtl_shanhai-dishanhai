package com.dishanhai.gt_shanhai.common.ae2.quantum;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.events.GridCraftingCpuChange;
import appeng.me.cluster.IAEMultiBlock;
import appeng.me.cluster.MBCalculator;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class QuantumCraftingCPUCalculator extends MBCalculator<QuantumCraftingBlockEntity, QuantumCraftingCPUCluster> {

    private static final int MAX_QUANTUM_SIZE = 7;

    public QuantumCraftingCPUCalculator(QuantumCraftingBlockEntity target) {
        super(target);
    }

    @Override
    public boolean checkMultiblockScale(BlockPos min, BlockPos max) {
        return max.getX() - min.getX() < MAX_QUANTUM_SIZE
                && max.getY() - min.getY() < MAX_QUANTUM_SIZE
                && max.getZ() - min.getZ() < MAX_QUANTUM_SIZE;
    }

    @Override
    public QuantumCraftingCPUCluster createCluster(ServerLevel level, BlockPos min, BlockPos max) {
        return new QuantumCraftingCPUCluster(min, max);
    }

    @Override
    public boolean verifyInternalStructure(ServerLevel level, BlockPos min, BlockPos max) {
        long start = QuantumDiagnostics.start();
        boolean hasCore = false;
        boolean hasStorage = false;
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof QuantumCraftingUnitBlock)) {
                return false;
            }
            BlockEntity rawBlockEntity = level.getBlockEntity(pos);
            if (!(rawBlockEntity instanceof QuantumCraftingBlockEntity)) return false;
            IAEMultiBlock<?> multiBlock = (IAEMultiBlock<?>) rawBlockEntity;
            if (!multiBlock.isValid()) return false;
            QuantumCraftingUnitType type = ((QuantumCraftingUnitBlock) state.getBlock()).getQuantumType();
            boolean boundary = isBoundary(pos, min, max);
            if (type == QuantumCraftingUnitTypes.STRUCTURE) {
                if (!boundary) return false;
            } else {
                if (boundary) return false;
                if (!hasCore) {
                    hasCore = true;
                }
            }
            if (type.getStorageBytes() > 0) {
                hasStorage = true;
            }
        }
        QuantumDiagnostics.slow("calculator.verifyInternalStructure", start,
                "min=" + min.toShortString() + " max=" + max.toShortString()
                        + " hasCore=" + hasCore + " hasStorage=" + hasStorage);
        return hasCore && hasStorage;
    }

    @Override
    public void updateBlockEntities(QuantumCraftingCPUCluster cluster, ServerLevel level, BlockPos min, BlockPos max) {
        long start = QuantumDiagnostics.start();
        int blocks = 0;
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!(level.getBlockState(pos).getBlock() instanceof QuantumCraftingUnitBlock)) continue;
            BlockEntity rawBlockEntity = level.getBlockEntity(pos);
            if (!(rawBlockEntity instanceof QuantumCraftingBlockEntity)) continue;
            QuantumCraftingBlockEntity blockEntity = (QuantumCraftingBlockEntity) rawBlockEntity;
            blockEntity.updateStatus(cluster);
            cluster.addBlockEntity(blockEntity);
            blocks++;
        }
        cluster.done();
        IteratorAccess.postCpuChange(cluster);
        QuantumDiagnostics.slow("calculator.updateBlockEntities", start,
                "min=" + min.toShortString() + " max=" + max.toShortString() + " blocks=" + blocks);
    }

    @Override
    public boolean isValidBlockEntity(BlockEntity blockEntity) {
        return blockEntity instanceof QuantumCraftingBlockEntity;
    }

    private boolean isBoundary(BlockPos pos, BlockPos min, BlockPos max) {
        return pos.getX() == min.getX() || pos.getX() == max.getX()
                || pos.getY() == min.getY() || pos.getY() == max.getY()
                || pos.getZ() == min.getZ() || pos.getZ() == max.getZ();
    }

    private static final class IteratorAccess {
        private static void postCpuChange(QuantumCraftingCPUCluster cluster) {
            java.util.Iterator<QuantumCraftingBlockEntity> iterator = cluster.getBlockEntities();
            while (iterator.hasNext()) {
                IGridNode node = iterator.next().getGridNode();
                if (node != null) {
                    IGrid grid = node.getGrid();
                    grid.postEvent(new GridCraftingCpuChange(node));
                    return;
                }
            }
        }
    }
}
