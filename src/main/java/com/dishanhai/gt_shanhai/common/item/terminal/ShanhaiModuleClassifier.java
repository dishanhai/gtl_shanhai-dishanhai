package com.dishanhai.gt_shanhai.common.item.terminal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.registry.GTRegistries;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.gtlcore.gtlcore.api.machine.multiblock.IModularMachineModule;
import org.gtlcore.gtlcore.api.machine.multiblock.IModularMachineHost;

public final class ShanhaiModuleClassifier {

    private static volatile Set<Block> moduleBlocks;

    private ShanhaiModuleClassifier() {}

    public static boolean isModulePosition(List<ItemStack> candidates) {
        if (candidates == null) return false;
        for (ItemStack candidate : candidates) {
            if (candidate == null || candidate.isEmpty()) continue;
            if (getModuleBlocks().contains(Block.byItem(candidate.getItem()))) return true;
        }
        return false;
    }

    public static List<BlockPos> hostModulePositions(MetaMachine machine) {
        if (!(machine instanceof IModularMachineHost<?> host)) return List.of();
        BlockPos[] positions = host.getModuleScanPositions();
        if (positions == null || positions.length == 0) return List.of();

        List<BlockPos> result = new ArrayList<>(positions.length);
        for (BlockPos pos : positions) {
            if (pos != null) result.add(pos.immutable());
        }
        return Collections.unmodifiableList(result);
    }

    private static Set<Block> getModuleBlocks() {
        Set<Block> cached = moduleBlocks;
        if (cached != null) return cached;

        Set<Block> result = new LinkedHashSet<>();
        GTRegistries.MACHINES.forEach(definition -> {
            Block block = definition.getBlock();
            var blockEntity = definition.getBlockEntityType().create(BlockPos.ZERO, block.defaultBlockState());
            if (blockEntity instanceof IMachineBlockEntity machineBlockEntity
                    && definition.createMetaMachine(machineBlockEntity) instanceof IModularMachineModule<?, ?>) {
                result.add(block);
            }
        });
        moduleBlocks = Collections.unmodifiableSet(result);
        return moduleBlocks;
    }
}
