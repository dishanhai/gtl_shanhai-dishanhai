package com.dishanhai.gt_shanhai.common.ae2;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.helpers.patternprovider.PatternProviderLogicHost;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.trait.MachineTrait;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;

import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.gtlcore.gtlcore.api.machine.trait.NotifiableCircuitItemStackHandler;

/**
 * 樣板供應器把編程電路寫進相鄰單方塊（或輸入倉）的電路槽。
 * 虛擬在場路徑不會把電路當實料推進物品欄，電路槽要在發料之後單獨覆蓋。
 */
public final class MachineCircuitDelivery {

    private MachineCircuitDelivery() {}

    public static void deliver(PatternProviderLogicHost host, Direction direction, IPatternDetails details) {
        if (host == null || direction == null || !(details instanceof AEProcessingPattern pattern)) return;
        AEItemKey circuit = findCircuit(pattern.getSparseInputs());
        if (circuit == null) return;
        BlockEntity provider = host.getBlockEntity();
        if (provider == null || provider.getLevel() == null) return;
        MetaMachine machine = MetaMachine.getMachine(provider.getLevel(), provider.getBlockPos().relative(direction));
        if (machine == null) return;
        for (MachineTrait trait : machine.getTraits()) {
            if (!(trait instanceof NotifiableCircuitItemStackHandler handler)) continue;
            ItemStack incoming = circuit.toStack();
            if (incoming.getCount() != 1) incoming.setCount(1);
            ItemStack existing = handler.getStackInSlot(0);
            if (ItemStack.isSameItemSameTags(existing, incoming) && existing.getCount() == 1) return;
            handler.insertItem(0, incoming, false, true);
            handler.onContentsChanged();
            return;
        }
    }

    private static AEItemKey findCircuit(GenericStack[] inputs) {
        if (inputs == null) return null;
        AEItemKey found = null;
        for (GenericStack input : inputs) {
            if (input == null || !(input.what() instanceof AEItemKey key) || input.amount() <= 0) continue;
            if (!IntCircuitBehaviour.isIntegratedCircuit(key.toStack())) continue;
            found = key;
        }
        return found;
    }
}
