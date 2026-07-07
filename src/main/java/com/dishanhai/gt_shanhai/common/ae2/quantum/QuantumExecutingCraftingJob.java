package com.dishanhai.gt_shanhai.common.ae2.quantum;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.me.service.CraftingService;
import it.unimi.dsi.fastutil.objects.Object2LongMap;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

class QuantumExecutingCraftingJob {

    final CraftingLink link;
    final ListCraftingInventory waitingFor;
    final Map<IPatternDetails, TaskProgress> tasks = new HashMap<>();
    final List<PatternBufferRefund> patternBufferRefunds = new ArrayList<>();
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
        ListTag refundTag = data.getList("patternBufferRefunds", 10);
        for (int i = 0; i < refundTag.size(); i++) {
            CompoundTag entry = refundTag.getCompound(i);
            AEItemKey pattern = AEItemKey.fromTag(entry.getCompound("pattern"));
            IPatternDetails details = PatternDetailsHelper.decodePattern(pattern, logic.cpu.getLevel());
            KeyCounter inputs = readInputs(entry.getList("inputs", 10));
            String providerId = entry.getString("providerId");
            if (details != null && !providerId.isEmpty() && !inputs.isEmpty()) {
                patternBufferRefunds.add(new PatternBufferRefund(providerId, details, inputs));
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
        if (!patternBufferRefunds.isEmpty()) {
            ListTag refundTag = new ListTag();
            for (PatternBufferRefund refund : patternBufferRefunds) {
                CompoundTag item = new CompoundTag();
                item.putString("providerId", refund.providerId);
                item.put("pattern", refund.patternDetails.getDefinition().toTag());
                item.put("inputs", writeInputs(refund.inputs));
                refundTag.add(item);
            }
            data.put("patternBufferRefunds", refundTag);
        }
        return data;
    }

    void recordPatternBufferPush(String providerId, IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        if (providerId == null || providerId.isEmpty() || patternDetails == null || inputHolder == null) {
            return;
        }
        KeyCounter inputs = new KeyCounter();
        for (KeyCounter counter : inputHolder) {
            if (counter != null) {
                inputs.addAll(counter);
            }
        }
        inputs.removeZeros();
        if (!inputs.isEmpty()) {
            patternBufferRefunds.add(new PatternBufferRefund(providerId, patternDetails, inputs));
        }
    }

    private static ListTag writeInputs(KeyCounter inputs) {
        ListTag tag = new ListTag();
        for (Object2LongMap.Entry<AEKey> entry : inputs) {
            if (entry.getLongValue() > 0) {
                tag.add(GenericStack.writeTag(new GenericStack(entry.getKey(), entry.getLongValue())));
            }
        }
        return tag;
    }

    private static KeyCounter readInputs(ListTag tag) {
        KeyCounter inputs = new KeyCounter();
        for (int i = 0; i < tag.size(); i++) {
            GenericStack stack = GenericStack.readTag(tag.getCompound(i));
            if (stack != null && stack.amount() > 0) {
                inputs.add(stack.what(), stack.amount());
            }
        }
        return inputs;
    }

    static class TaskProgress {
        long value;
    }

    static class PatternBufferRefund {
        final String providerId;
        final IPatternDetails patternDetails;
        final KeyCounter inputs;

        PatternBufferRefund(String providerId, IPatternDetails patternDetails, KeyCounter inputs) {
            this.providerId = providerId;
            this.patternDetails = patternDetails;
            this.inputs = inputs;
        }
    }
}
