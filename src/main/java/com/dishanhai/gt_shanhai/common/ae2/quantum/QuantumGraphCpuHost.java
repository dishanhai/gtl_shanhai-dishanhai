package com.dishanhai.gt_shanhai.common.ae2.quantum;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.inv.ListCraftingInventory;
import net.minecraft.world.level.Level;
import org.gtlcore.gtlcore.integration.ae2.crafting.transfinite.MissingCraftingPlan;
import org.gtlcore.gtlcore.integration.ae2.graph.AeGraphPlan;
import org.gtlcore.gtlcore.integration.ae2.graph.GraphCpuController;
import org.gtlcore.gtlcore.integration.ae2.graph.GraphCpuHost;

import java.util.function.Consumer;

record QuantumGraphCpuHost(QuantumCraftingCPU cpu, ListCraftingInventory orphanInventory,
                           Consumer<AEKey> changeListener) implements GraphCpuHost {

    static boolean fitsStorage(ICraftingPlan plan, long bytes) {
        ICraftingPlan delegate = plan instanceof MissingCraftingPlan missing ? missing.delegate() : plan;
        return ((AeGraphPlan) delegate).fitsStorage(bytes, false);
    }

    GraphCpuController createController() {
        return new GraphCpuController(this);
    }

    @Override
    public IGrid grid() {
        return cpu.getGrid();
    }

    @Override
    public Level level() {
        return cpu.getLevel();
    }

    @Override
    public IActionSource source() {
        return cpu.getSrc();
    }

    @Override
    public boolean active() {
        return cpu.isActive();
    }

    @Override
    public long dispatchCapacity() {
        return (long) cpu.getCoProcessors() + 1;
    }

    @Override
    public void dirty() {
        cpu.markDirty();
    }

    @Override
    public void changed(AEKey key) {
        changeListener.accept(key);
    }

    @Override
    public void output(GenericStack output) {
        cpu.updateOutput(output);
    }
}
