package com.dishanhai.gt_shanhai.common.item;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;

import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import org.gtlcore.gtlcore.api.item.tool.ae2.patternTool.Ae2GtmProcessingPattern;
import org.gtlcore.gtlcore.api.recipe.ingredient.LongIngredient;

import java.util.ArrayList;
import java.util.List;

/** Encodes GT recipes with gt_shanhai virtual inputs and authoritative recipe-type metadata. */
public final class ShanhaiPatternEncoder {

    private static final long VIRTUAL_FLUID_MARKER_AMOUNT = 1L;

    public static Ae2GtmProcessingPattern encode(GTRecipe recipe, ServerPlayer player,
                                                  boolean respectAutoWrapExclusions) {
        if (recipe == null || player == null) return null;

        List<GenericStack> inputs = new ArrayList<>();
        List<GenericStack> outputs = new ArrayList<>();
        appendItemInputs(recipe, inputs, respectAutoWrapExclusions);
        appendFluidInputs(recipe, inputs);
        appendItemOutputs(recipe, outputs);
        appendFluidOutputs(recipe, outputs);
        if (outputs.isEmpty()) return null;

        ItemStack patternStack;
        PatternRecipeTypeHelper.pushAuthoritativeEncodingRecipe(recipe);
        try {
            patternStack = PatternDetailsHelper.encodeProcessingPattern(
                    inputs.toArray(new GenericStack[0]),
                    outputs.toArray(new GenericStack[0]));
        } finally {
            PatternRecipeTypeHelper.popAuthoritativeEncodingRecipe();
        }
        if (patternStack == null || patternStack.isEmpty()) return null;

        PatternRecipeTypeHelper.writeAuthoritativeRecipeType(patternStack, recipe);
        return new Ae2GtmProcessingPattern(patternStack, player, recipe);
    }

    private static void appendItemInputs(GTRecipe recipe, List<GenericStack> inputs,
                                         boolean respectAutoWrapExclusions) {
        List<Content> contents = recipe.getInputContents(ItemRecipeCapability.CAP);
        if (contents == null || contents.isEmpty()) return;
        for (Content content : contents) {
            if (content == null) continue;
            ItemStack stack = firstItemStack(content);
            if (stack.isEmpty()) continue;

            // Programmed circuits are recipe selectors, not virtual inventory requirements.
            if (IntCircuitBehaviour.isIntegratedCircuit(stack)) {
                inputs.add(new GenericStack(AEItemKey.of(stack.copy()), getItemAmount(content, stack)));
                continue;
            }

            if (isNonConsumable(content) && !VirtualItemProviderHelper.isProviderItem(stack)) {
                boolean excluded = respectAutoWrapExclusions
                        && VirtualItemProviderHelper.isAutoWrapExcluded(stack);
                if (!respectAutoWrapExclusions || !excluded) {
                    ItemStack provider = VirtualItemProviderHelper.createBoundProvider(stack);
                    if (!provider.isEmpty() && VirtualItemProviderHelper.isBoundProvider(provider)) {
                        inputs.add(new GenericStack(AEItemKey.of(provider), 1));
                        continue;
                    }
                }
            }

            inputs.add(new GenericStack(AEItemKey.of(stack.copy()), getItemAmount(content, stack)));
        }
    }

    private static void appendFluidInputs(GTRecipe recipe, List<GenericStack> inputs) {
        List<Content> contents = recipe.getInputContents(FluidRecipeCapability.CAP);
        if (contents == null || contents.isEmpty()) return;
        for (Content content : contents) {
            if (content == null) continue;
            FluidIngredient ingredient = FluidRecipeCapability.CAP.of(content.getContent());
            if (ingredient == null || ingredient.isEmpty()) continue;
            com.lowdragmc.lowdraglib.side.fluid.FluidStack[] stacks = ingredient.getStacks();
            if (stacks == null || stacks.length == 0 || stacks[0].isEmpty()) continue;
            long amount = isNonConsumable(content) ? VIRTUAL_FLUID_MARKER_AMOUNT : stacks[0].getAmount();
            inputs.add(new GenericStack(fluidKeyOf(stacks[0]), Math.max(1L, amount)));
        }
    }

    private static void appendItemOutputs(GTRecipe recipe, List<GenericStack> outputs) {
        List<Content> contents = recipe.getOutputContents(ItemRecipeCapability.CAP);
        if (contents == null || contents.isEmpty()) return;
        for (Content content : contents) {
            if (content == null) continue;
            ItemStack stack = firstItemStack(content);
            if (!stack.isEmpty()) {
                outputs.add(new GenericStack(AEItemKey.of(stack.copy()), getItemAmount(content, stack)));
            }
        }
    }

    private static void appendFluidOutputs(GTRecipe recipe, List<GenericStack> outputs) {
        List<Content> contents = recipe.getOutputContents(FluidRecipeCapability.CAP);
        if (contents == null || contents.isEmpty()) return;
        for (Content content : contents) {
            if (content == null) continue;
            FluidIngredient ingredient = FluidRecipeCapability.CAP.of(content.getContent());
            if (ingredient == null || ingredient.isEmpty()) continue;
            com.lowdragmc.lowdraglib.side.fluid.FluidStack[] stacks = ingredient.getStacks();
            if (stacks == null || stacks.length == 0 || stacks[0].isEmpty()) continue;
            outputs.add(new GenericStack(fluidKeyOf(stacks[0]), stacks[0].getAmount()));
        }
    }

    private static boolean isNonConsumable(Content content) {
        return content != null && content.chance <= 0;
    }

    private static AEFluidKey fluidKeyOf(com.lowdragmc.lowdraglib.side.fluid.FluidStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && !tag.isEmpty()
                ? AEFluidKey.of(stack.getFluid(), tag)
                : AEFluidKey.of(stack.getFluid());
    }

    private static ItemStack firstItemStack(Content content) {
        Ingredient ingredient = ItemRecipeCapability.CAP.of(content.getContent());
        if (ingredient == null || ingredient.isEmpty()) return ItemStack.EMPTY;
        ItemStack[] stacks = ingredient.getItems();
        if (stacks == null || stacks.length == 0 || stacks[0].isEmpty()) return ItemStack.EMPTY;
        ItemStack result = stacks[0].copy();
        result.setCount(getItemAmount(content, result));
        return result;
    }

    private static int getItemAmount(Content content, ItemStack fallback) {
        Object raw = content.getContent();
        if (raw instanceof SizedIngredient sized) {
            return Math.max(1, sized.getAmount());
        }
        if (raw instanceof LongIngredient ingredient) {
            return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, ingredient.getActualAmount()));
        }
        return Math.max(1, fallback.getCount());
    }

    private ShanhaiPatternEncoder() {}
}
