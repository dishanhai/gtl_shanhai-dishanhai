package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;

import com.dishanhai.gt_shanhai.common.item.VirtualItemProviderHelper;
import com.dishanhai.gt_shanhai.common.item.PatternRecipeTypeHelper;
import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import org.gtlcore.gtlcore.api.item.tool.ae2.patternTool.Ae2GtmProcessingPattern;
import org.gtlcore.gtlcore.api.recipe.ingredient.LongIngredient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(value = Ae2GtmProcessingPattern.class, remap = false)
public class Ae2GtmProcessingPatternMixin {

    private static final long VIRTUAL_FLUID_MARKER_AMOUNT = 1L;

    @Inject(method = "of", at = @At("HEAD"), cancellable = true, remap = false)
    private static void gtShanhai$encodeNonConsumablesAsVirtualProviders(
            GTRecipe recipe, ServerPlayer player, CallbackInfoReturnable<Ae2GtmProcessingPattern> cir) {
        if (recipe == null || player == null) return;

        List<GenericStack> inputs = new ArrayList<>();
        List<GenericStack> outputs = new ArrayList<>();

        appendItemInputs(recipe, inputs);
        appendFluidInputs(recipe, inputs);
        appendItemOutputs(recipe, outputs);
        appendFluidOutputs(recipe, outputs);

        if (outputs.isEmpty()) return;

        ItemStack pattern = PatternDetailsHelper.encodeProcessingPattern(
                inputs.toArray(new GenericStack[0]),
                outputs.toArray(new GenericStack[0]));
        if (pattern == null || pattern.isEmpty()) return;
        PatternRecipeTypeHelper.writeAuthoritativeRecipeType(pattern, recipe);

        cir.setReturnValue(new Ae2GtmProcessingPattern(pattern, player, recipe));
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("gt_shanhai:pattern_encode");

    private static void appendItemInputs(GTRecipe recipe, List<GenericStack> inputs) {
        List<Content> contents = recipe.getInputContents(ItemRecipeCapability.CAP);
        if (contents == null || contents.isEmpty()) return;
        for (Content content : contents) {
            if (content == null) continue;
            ItemStack stack = firstItemStack(content);
            if (stack.isEmpty()) continue;

            if (isNonConsumable(content)) {
                boolean excluded = VirtualItemProviderHelper.isAutoWrapExcluded(stack);
                if (excluded) {
                    LOG.info("[encode] recipe={} item={} chance={} -> excluded, kept raw",
                            recipe.id, stack.getItem(), content.chance);
                    inputs.add(new GenericStack(AEItemKey.of(stack.copy()), getItemAmount(content, stack)));
                    continue;
                }
                ItemStack provider = VirtualItemProviderHelper.createBoundProvider(stack);
                if (!provider.isEmpty()) {
                    LOG.info("[encode] recipe={} item={} chance={} -> wrapped as provider",
                            recipe.id, stack.getItem(), content.chance);
                    inputs.add(new GenericStack(AEItemKey.of(provider), 1));
                } else {
                    LOG.warn("[encode] recipe={} item={} chance={} -> createBoundProvider returned EMPTY, input dropped entirely",
                            recipe.id, stack.getItem(), content.chance);
                }
                continue;
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

    private static boolean isNonConsumable(Content content) {
        return content != null && content.chance <= 0;
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
        if (raw instanceof SizedIngredient) {
            SizedIngredient sized = (SizedIngredient) raw;
            return Math.max(1, sized.getAmount());
        }
        if (raw instanceof LongIngredient) {
            LongIngredient ingredient = (LongIngredient) raw;
            return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, ingredient.getActualAmount()));
        }
        return Math.max(1, fallback.getCount());
    }
}
