package com.dishanhai.gt_shanhai.common.ae2.quantum;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.CraftingLink;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.me.service.CraftingService;
import it.unimi.dsi.fastutil.objects.Object2LongMap;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

class QuantumExecutingCraftingJob {

    final CraftingLink link;
    final ListCraftingInventory waitingFor;
    final Map<IPatternDetails, TaskProgress> tasks = new HashMap<>();
    final QuantumElapsedTimeTracker timeTracker;
    GenericStack finalOutput;
    long remainingAmount;
    @Nullable Integer playerId;

    @FunctionalInterface
    interface CraftingDifferenceListener {
        void onCraftingDifference(AEKey key);
    }

    QuantumExecutingCraftingJob(ICraftingPlan plan, CraftingDifferenceListener postCraftingDifference,
            CraftingLink link, @Nullable Integer playerId) {
        this.finalOutput = plan.finalOutput();
        this.remainingAmount = finalOutput.amount();
        Objects.requireNonNull(postCraftingDifference);
        this.waitingFor = new ListCraftingInventory(postCraftingDifference::onCraftingDifference);
        this.timeTracker = new QuantumElapsedTimeTracker();
        for (Object2LongMap.Entry<AEKey> entry : plan.emittedItems()) {
            waitingFor.insert(entry.getKey(), entry.getLongValue(), Actionable.MODULATE);
            timeTracker.addMaxItems(entry.getLongValue(), entry.getKey().getType());
        }
        for (Map.Entry<IPatternDetails, Long> entry : plan.patternTimes().entrySet()) {
            tasks.computeIfAbsent(entry.getKey(), p -> new TaskProgress()).value += entry.getValue();
            for (GenericStack output : entry.getKey().getOutputs()) {
                long amount = output.amount() * entry.getValue() * output.what().getAmountPerUnit();
                timeTracker.addMaxItems(amount, output.what().getType());
            }
        }
        this.link = link;
        this.playerId = playerId;
    }

    QuantumExecutingCraftingJob(CompoundTag data, CraftingDifferenceListener postCraftingDifference,
            QuantumCraftingCPULogic logic) {
        this.link = new CraftingLink(data.getCompound("link"), logic.cpu);
        IGrid grid = logic.cpu.getGrid();
        if (grid != null) {
            ((CraftingService) grid.getCraftingService()).addLink(link);
        }
        this.finalOutput = GenericStack.readTag(data.getCompound("finalOutput"));
        this.remainingAmount = data.getLong("remainingAmount");
        Objects.requireNonNull(postCraftingDifference);
        this.waitingFor = new ListCraftingInventory(postCraftingDifference::onCraftingDifference);
        this.waitingFor.readFromNBT(data.getList("waitingFor", 10));
        this.timeTracker = new QuantumElapsedTimeTracker(data.getCompound("timeTracker"));
        this.playerId = data.contains("playerId", 3) ? data.getInt("playerId") : null;
        ListTag tasksTag = data.getList("tasks", 10);
        for (int i = 0; i < tasksTag.size(); i++) {
            CompoundTag item = tasksTag.getCompound(i);
            AEItemKey pattern = AEItemKey.fromTag(item);
            IPatternDetails details = PatternDetailsHelper.decodePattern(pattern, logic.cpu.getLevel());
            if (details != null) {
                TaskProgress progress = new TaskProgress();
                progress.value = item.getLong("#craftingProgress");
                tasks.put(details, progress);
            }
        }
    }

    CompoundTag writeToNBT() {
        CompoundTag data = new CompoundTag();
        CompoundTag linkData = new CompoundTag();
        link.writeToNBT(linkData);
        data.put("link", linkData);
        data.put("finalOutput", GenericStack.writeTag(finalOutput));
        data.put("waitingFor", waitingFor.writeToNBT());
        data.put("timeTracker", timeTracker.writeToNBT());
        ListTag taskList = new ListTag();
        for (Map.Entry<IPatternDetails, TaskProgress> entry : tasks.entrySet()) {
            CompoundTag item = entry.getKey().getDefinition().toTag();
            item.putLong("#craftingProgress", entry.getValue().value);
            taskList.add(item);
        }
        data.put("tasks", taskList);
        data.putLong("remainingAmount", remainingAmount);
        if (playerId != null) {
            data.putInt("playerId", playerId);
        }
        return data;
    }

    static class TaskProgress {
        long value;
    }
}
