package com.dishanhai.gt_shanhai.common.machine.primordial.module.forge;

import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialModuleRecipeLogic;
import com.dishanhai.gt_shanhai.common.shop.CurrencyStageConfig;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;
import com.gtladd.gtladditions.api.machine.wireless.GTLAddWirelessWorkableElectricMultipleRecipesMachine;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PrimordialCoinForgeLogic extends PrimordialModuleRecipeLogic {
    public PrimordialCoinForgeLogic(GTLAddWirelessWorkableElectricMultipleRecipesMachine machine) {
        super(machine);
    }

    @Override
    protected boolean checkRecipe(GTRecipe recipe) {
        return super.checkRecipe(recipe) && mintAllowed(recipe);
    }

    @Override
    protected Set<GTRecipe> lookupRecipeIterator() {
        Set<GTRecipe> recipes = super.lookupRecipeIterator();
        if (recipes.isEmpty() || !(getMachine() instanceof PrimordialCoinForgeModule forge)) return recipes;
        int level = forge.getModuleLevel();
        Set<GTRecipe> allowed = null;
        for (GTRecipe recipe : recipes) {
            if (mintAllowed(level, recipe)) continue;
            if (allowed == null) allowed = new LinkedHashSet<>(recipes);
            allowed.remove(recipe);
        }
        return allowed == null ? recipes : allowed;
    }

    private boolean mintAllowed(GTRecipe recipe) {
        if (!(getMachine() instanceof PrimordialCoinForgeModule forge) || recipe == null) return true;
        return mintAllowed(forge.getModuleLevel(), recipe);
    }

    private static boolean mintAllowed(int moduleLevel, GTRecipe recipe) {
        return recipe != null
                && outputsAllowed(moduleLevel, recipe.outputs)
                && outputsAllowed(moduleLevel, recipe.tickOutputs);
    }

    private static boolean outputsAllowed(int moduleLevel, Map<RecipeCapability<?>, List<Content>> contents) {
        if (contents == null) return true;
        List<Content> items = contents.get(ItemRecipeCapability.CAP);
        if (items == null) return true;
        for (Content content : items) {
            if (content == null || content.content == null) continue;
            Object value = content.content;
            if (value instanceof SizedIngredient sized) {
                if (!stacksAllowed(moduleLevel, sized.getItems())) return false;
            } else if (value instanceof Ingredient ingredient) {
                if (!stacksAllowed(moduleLevel, ingredient.getItems())) return false;
            } else if (value instanceof ItemStack stack && !stackAllowed(moduleLevel, stack)) {
                return false;
            }
        }
        return true;
    }

    private static boolean stacksAllowed(int moduleLevel, ItemStack[] stacks) {
        if (stacks == null) return true;
        for (ItemStack stack : stacks) {
            if (!stackAllowed(moduleLevel, stack)) return false;
        }
        return true;
    }

    private static boolean stackAllowed(int moduleLevel, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return true;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return CoinForgeMintGate.allows(moduleLevel, CurrencyStageConfig.tierOf(id));
    }
}
