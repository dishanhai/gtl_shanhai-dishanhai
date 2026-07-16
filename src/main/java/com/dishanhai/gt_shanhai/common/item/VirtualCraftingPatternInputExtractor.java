package com.dishanhai.gt_shanhai.common.item;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.execution.InputTemplate;
import appeng.crafting.inv.ICraftingInventory;

import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

/**
 * Extracts real pattern inputs while keeping reusable virtual-presence targets in the CPU.
 */
public final class VirtualCraftingPatternInputExtractor {

    private VirtualCraftingPatternInputExtractor() {}

    @Nullable
    public static KeyCounter[] extract(IPatternDetails details, ICraftingInventory sourceInv, Level level,
            KeyCounter expectedOutputs, KeyCounter expectedContainerItems) {
        IPatternDetails.IInput[] inputs = details.getInputs();
        KeyCounter[] inputHolder = new KeyCounter[inputs.length];
        for (int index = 0; index < inputs.length; index++) {
            IPatternDetails.IInput input = inputs[index];
            inputHolder[index] = new KeyCounter();
            if (VirtualPatternEncodingHelper.isPresenceInput(input)) {
                if (!hasPresence(sourceInv, input)) {
                    CraftingCpuHelper.reinjectPatternInputs(sourceInv, inputHolder);
                    return null;
                }
                continue;
            }

            long remainingMultiplier = input.getMultiplier();
            for (InputTemplate template : CraftingCpuHelper.getValidItemTemplates(sourceInv, input, level)) {
                long extracted = CraftingCpuHelper.extractTemplates(sourceInv, template, remainingMultiplier);
                inputHolder[index].add(template.key(), extracted * template.amount());
                AEKey containerItem = input.getRemainingKey(template.key());
                if (containerItem != null) {
                    expectedContainerItems.add(containerItem, extracted);
                }
                remainingMultiplier -= extracted;
                if (remainingMultiplier == 0L) break;
            }
            if (remainingMultiplier > 0L) {
                CraftingCpuHelper.reinjectPatternInputs(sourceInv, inputHolder);
                return null;
            }
        }
        appendOutputs(details, expectedOutputs, 1L);
        return inputHolder;
    }

    @Nullable
    public static KeyCounter[] extractBulk(IPatternDetails details, ICraftingInventory sourceInv,
            KeyCounter expectedOutputs, long patternMultiplier) {
        IPatternDetails.IInput[] inputs = details.getInputs();
        KeyCounter[] inputHolder = new KeyCounter[inputs.length];
        for (int index = 0; index < inputs.length; index++) {
            IPatternDetails.IInput input = inputs[index];
            inputHolder[index] = new KeyCounter();
            if (VirtualPatternEncodingHelper.isPresenceInput(input)) {
                if (!hasPresence(sourceInv, input)) {
                    CraftingCpuHelper.reinjectPatternInputs(sourceInv, inputHolder);
                    return null;
                }
                continue;
            }

            GenericStack template = input.getPossibleInputs()[0];
            long amount = multiplyAmount(input.getMultiplier(), patternMultiplier);
            long simulated = sourceInv.extract(template.what(), amount, Actionable.SIMULATE);
            if (simulated < amount) {
                CraftingCpuHelper.reinjectPatternInputs(sourceInv, inputHolder);
                return null;
            }
            long extracted = sourceInv.extract(template.what(), amount, Actionable.MODULATE);
            inputHolder[index].add(template.what(), extracted);
            if (extracted != amount) {
                CraftingCpuHelper.reinjectPatternInputs(sourceInv, inputHolder);
                throw new IllegalStateException("Failed to extract virtual-pattern inputs after a successful simulation");
            }
        }
        appendOutputs(details, expectedOutputs, patternMultiplier);
        return inputHolder;
    }

    private static boolean hasPresence(ICraftingInventory sourceInv, IPatternDetails.IInput input) {
        long needed = input.getMultiplier();
        for (GenericStack possible : input.getPossibleInputs()) {
            if (VirtualCraftingPresenceState.hasPresence(sourceInv, possible.what(), needed)) return true;
        }
        return false;
    }

    private static void appendOutputs(IPatternDetails details, KeyCounter expectedOutputs, long multiplier) {
        for (GenericStack output : details.getOutputs()) {
            expectedOutputs.add(output.what(), multiplyAmount(output.amount(), multiplier));
        }
    }

    private static long multiplyAmount(long amount, long multiplier) {
        if (amount <= 0L || multiplier <= 0L) return 0L;
        if (amount > Long.MAX_VALUE / multiplier) return Long.MAX_VALUE;
        return amount * multiplier;
    }
}
