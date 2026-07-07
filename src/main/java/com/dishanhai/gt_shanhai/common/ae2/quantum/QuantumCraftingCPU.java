package com.dishanhai.gt_shanhai.common.ae2.quantum;

import appeng.api.config.CpuSelectionMode;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CraftingJobStatus;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.GenericStack;
import appeng.crafting.inv.ListCraftingInventory;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class QuantumCraftingCPU implements ICraftingCPU {

    private final UUID id;
    private final long bytes;
    private final QuantumCraftingCPUCluster cluster;
    public final QuantumCraftingCPULogic craftingLogic;
    public GenericStack finalOutput;

    QuantumCraftingCPU(QuantumCraftingCPUCluster cluster, UUID id, long bytes) {
        this.cluster = cluster;
        this.id = id;
        this.bytes = bytes;
        this.craftingLogic = new QuantumCraftingCPULogic(this);
    }

    QuantumCraftingCPU(QuantumCraftingCPUCluster cluster, long bytes) {
        this(cluster, null, bytes);
    }

    @Override
    public boolean isBusy() {
        return craftingLogic.hasJob();
    }

    @Override
    @Nullable
    public CraftingJobStatus getJobStatus() {
        GenericStack output = craftingLogic.getFinalJobOutput();
        if (output == null) return null;
        QuantumElapsedTimeTracker tracker = craftingLogic.getElapsedTimeTracker();
        long progress = Math.max(0, tracker.getStartItemCount() - tracker.getRemainingItemCount());
        return new CraftingJobStatus(output, tracker.getStartItemCount(), progress, tracker.getElapsedTime());
    }

    @Override
    public void cancelJob() {
        if (id != null) {
            cluster.cancelJob(id);
        }
    }

    @Override
    public long getAvailableStorage() {
        return bytes;
    }

    @Override
    public int getCoProcessors() {
        return cluster.getCoProcessors();
    }

    @Override
    @Nullable
    public Component getName() {
        return cluster.getName();
    }

    @Override
    public CpuSelectionMode getSelectionMode() {
        return cluster.getSelectionMode();
    }

    public boolean isActive() {
        return cluster.isActive();
    }

    public Level getLevel() {
        return cluster.getLevel();
    }

    @Nullable
    public IGrid getGrid() {
        return cluster.getGrid();
    }

    public IActionSource getSrc() {
        return cluster.getSrc();
    }

    public void markDirty() {
        cluster.markDirty();
    }

    public void updateOutput(GenericStack stack) {
        finalOutput = stack;
        cluster.updateOutput(stack);
    }

    public ListCraftingInventory getInventory() {
        return craftingLogic.getInventory();
    }

    public void deactivate() {
        if (id != null) {
            cluster.deactivate(id);
        }
    }

    public QuantumCraftingCPUCluster getCluster() {
        return cluster;
    }

    public void writeToNBT(CompoundTag data) {
        craftingLogic.writeToNBT(data);
    }

    public void readFromNBT(CompoundTag data) {
        craftingLogic.readFromNBT(data);
    }
}
