package com.dishanhai.gt_shanhai.common.item;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.inventories.InternalInventory;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.pattern.AEProcessingPattern;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import org.gtlcore.gtlcore.api.item.tool.ae2.patternTool.Ae2BaseProcessingPatternHelper;

public final class ShanhaiPatternModifier {

    private static final String AE_INPUTS_KEY = "in";
    private static final String AE_OUTPUTS_KEY = "out";

    public static ModificationResult modifyInventory(
            ServerPlayer player, InternalInventory inventory, Settings settings) {
        if (player == null || inventory == null || settings == null) {
            return new ModificationResult(0, 0, 0);
        }

        int modified = 0;
        int skipped = 0;
        int failed = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack source = inventory.getStackInSlot(slot);
            if (source == null || source.isEmpty()) continue;

            AEProcessingPattern decoded = Ae2BaseProcessingPatternHelper
                    .decodeToAEProcessingPattern(source, player);
            if (decoded == null) {
                skipped++;
                continue;
            }

            ItemStack replacement = modifyPattern(player, source, settings);
            if (replacement.isEmpty()) {
                failed++;
                continue;
            }
            try {
                inventory.setItemDirect(slot, ItemStack.EMPTY);
                inventory.setItemDirect(slot, replacement);
                modified++;
            } catch (RuntimeException exception) {
                inventory.setItemDirect(slot, source);
                failed++;
            }
        }
        return new ModificationResult(modified, skipped, failed);
    }

    static ItemStack modifyPattern(ServerPlayer player, ItemStack source, Settings settings) {
        ItemStack current = source.copy();
        String originalRecipeType = PatternRecipeTypeHelper.readRecipeTypeId(source);
        for (int pass = 0; pass < settings.appliedTimes(); pass++) {
            AEProcessingPattern pattern = Ae2BaseProcessingPatternHelper
                    .decodeToAEProcessingPattern(current, player);
            if (pattern == null) return ItemStack.EMPTY;

            GenericStack[] inputs = modifyStacks(pattern.getSparseInputs(), settings, false);
            GenericStack[] outputs = modifyStacks(pattern.getSparseOutputs(), settings, true);
            if (inputs == null || outputs == null) return ItemStack.EMPTY;

            ItemStack replacement;
            PatternRecipeTypeHelper.pushEncodingRecipeType(originalRecipeType);
            try {
                replacement = PatternDetailsHelper.encodeProcessingPattern(inputs, outputs);
            } catch (RuntimeException exception) {
                return ItemStack.EMPTY;
            } finally {
                PatternRecipeTypeHelper.popEncodingRecipeType();
            }
            if (replacement == null || replacement.isEmpty()) return ItemStack.EMPTY;

            copyPatternMetadata(current, replacement);
            String replacementRecipeType = PatternRecipeTypeHelper.readRecipeTypeId(replacement);
            if (!originalRecipeType.isEmpty() && !originalRecipeType.equals(replacementRecipeType)) {
                return ItemStack.EMPTY;
            }
            current = replacement;
        }
        return current;
    }

    private static GenericStack[] modifyStacks(GenericStack[] source, Settings settings, boolean output) {
        GenericStack[] result = new GenericStack[source.length];
        for (int i = 0; i < source.length; i++) {
            GenericStack stack = source[i];
            if (stack == null) continue;
            long amount;
            try {
                amount = Math.multiplyExact(stack.amount(), settings.patternMultiplier());
            } catch (ArithmeticException exception) {
                return null;
            }
            if (amount % settings.patternDivisor() != 0) return null;
            amount /= settings.patternDivisor();
            if (amount <= 0 || exceedsLimit(stack, amount, settings)) return null;

            if (output) {
                try {
                    amount = Math.multiplyExact(amount, settings.outputMultiplier());
                } catch (ArithmeticException exception) {
                    return null;
                }
                amount /= settings.outputDivisor();
                if (amount <= 0) return null;
                amount = clampToLimit(stack, amount, settings);
            }
            result[i] = new GenericStack(stack.what(), amount);
        }
        return result;
    }

    private static boolean exceedsLimit(GenericStack stack, long amount, Settings settings) {
        if (stack.what() instanceof AEItemKey) return amount > settings.maxItemAmount();
        if (stack.what() instanceof AEFluidKey) return amount > settings.maxFluidAmount();
        return false;
    }

    private static long clampToLimit(GenericStack stack, long amount, Settings settings) {
        if (stack.what() instanceof AEItemKey) return Math.min(amount, settings.maxItemAmount());
        if (stack.what() instanceof AEFluidKey) return Math.min(amount, settings.maxFluidAmount());
        return amount;
    }

    static void copyPatternMetadata(ItemStack source, ItemStack replacement) {
        CompoundTag sourceTag = source.getTag();
        if (sourceTag == null) return;
        CompoundTag replacementTag = replacement.getOrCreateTag();
        copyPatternMetadata(sourceTag, replacementTag);
        replacement.setCount(source.getCount());
    }

    static void copyPatternMetadata(CompoundTag sourceTag, CompoundTag replacementTag) {
        for (String key : sourceTag.getAllKeys()) {
            if (AE_INPUTS_KEY.equals(key) || AE_OUTPUTS_KEY.equals(key)) continue;
            Tag value = sourceTag.get(key);
            if (value != null) replacementTag.put(key, value.copy());
        }
    }

    public record Settings(
            int patternMultiplier,
            int patternDivisor,
            int outputMultiplier,
            int outputDivisor,
            long maxItemAmount,
            long maxFluidAmount,
            int appliedTimes) {

        public Settings {
            patternMultiplier = Math.max(1, patternMultiplier);
            patternDivisor = Math.max(1, patternDivisor);
            outputMultiplier = Math.max(1, outputMultiplier);
            outputDivisor = Math.max(1, outputDivisor);
            maxItemAmount = Math.max(1L, maxItemAmount);
            maxFluidAmount = Math.max(1L, maxFluidAmount);
            appliedTimes = Math.max(1, Math.min(16, appliedTimes));
        }
    }

    public record ModificationResult(int modified, int skipped, int failed) {}

    private ShanhaiPatternModifier() {}
}
