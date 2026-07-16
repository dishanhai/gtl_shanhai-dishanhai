package com.dishanhai.gt_shanhai.common.item;

import appeng.api.crafting.IPatternDetails;
import appeng.crafting.pattern.AEProcessingPattern;

import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WildcardPatternRecipeTypeBinding {

    private WildcardPatternRecipeTypeBinding() {
    }

    public static ItemStack assign(ItemStack source, String recipeTypeId) {
        if (source == null) return ItemStack.EMPTY;
        ItemStack result = source.copy();
        if (result.isEmpty()) return result;

        if (recipeTypeId == null || recipeTypeId.isBlank()) {
            CompoundTag tag = result.getTag();
            if (tag != null) {
                tag.remove(PatternRecipeTypeHelper.TAG_RECIPE_TYPE);
                if (tag.isEmpty()) result.setTag(null);
            }
        } else {
            result.getOrCreateTag().putString(PatternRecipeTypeHelper.TAG_RECIPE_TYPE, recipeTypeId);
        }
        return result;
    }

    public static ItemStack clear(ItemStack source) {
        return assign(source, "");
    }

    public static GTRecipe findRecipe(IPatternDetails details, String recipeTypeId) {
        if (!(details instanceof AEProcessingPattern pattern)) return null;
        if (recipeTypeId == null || recipeTypeId.isBlank()) {
            return PatternRecipeTypeHelper.findRecipe(details);
        }
        return VirtualPatternEncodingHelper.findMatchingRecipeForPattern(
                pattern.getSparseInputs(), pattern.getSparseOutputs(), recipeTypeId);
    }

    public static List<GTRecipeType> collectHostRecipeTypes(Iterable<IMultiController> controllers) {
        if (controllers == null) return List.of();
        Map<ResourceLocation, GTRecipeType> types = new LinkedHashMap<>();
        for (IMultiController controller : controllers) {
            if (!(controller instanceof IRecipeLogicMachine machine)) continue;
            GTRecipeType[] machineTypes = machine.getRecipeTypes();
            if (machineTypes == null) continue;
            for (GTRecipeType type : machineTypes) {
                if (type == null || type.registryName == null
                        || PatternRecipeExecutionGuard.isAuxiliaryIORecipeTypeId(type.registryName)) {
                    continue;
                }
                types.putIfAbsent(type.registryName, type);
            }
        }
        return new ArrayList<>(types.values());
    }
}
