package com.dishanhai.gt_shanhai.common.item;

import appeng.api.inventories.InternalInventory;
import appeng.api.parts.SelectedPart;
import appeng.blockentity.networking.CableBusBlockEntity;
import appeng.helpers.patternprovider.PatternProviderLogicHost;

import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;

import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachine;

public final class PatternInventoryTargetHelper {

    public static InternalInventory find(UseOnContext context) {
        BlockEntity blockEntity = context.getLevel().getBlockEntity(context.getClickedPos());
        if (blockEntity instanceof CableBusBlockEntity cableBus) {
            Vec3 click = context.getClickLocation();
            Vec3 localClick = click.subtract(
                    context.getClickedPos().getX(),
                    context.getClickedPos().getY(),
                    context.getClickedPos().getZ());
            SelectedPart selected = cableBus.getCableBus().selectPartLocal(localClick);
            if (selected != null && selected.part instanceof PatternProviderLogicHost host) {
                return host.getLogic().getPatternInv();
            }
            return null;
        }
        if (blockEntity instanceof PatternProviderLogicHost host) {
            return host.getLogic().getPatternInv();
        }
        if (blockEntity instanceof MetaMachineBlockEntity metaMachine
                && metaMachine.getMetaMachine() instanceof MEPatternBufferPartMachine patternBuffer) {
            return patternBuffer.getTerminalPatternInventory();
        }
        return null;
    }

    private PatternInventoryTargetHelper() {}
}
