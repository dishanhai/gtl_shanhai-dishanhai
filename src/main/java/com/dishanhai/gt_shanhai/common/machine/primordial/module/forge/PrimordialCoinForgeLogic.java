package com.dishanhai.gt_shanhai.common.machine.primordial.module.forge;

import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialModuleRecipeLogic;
import com.dishanhai.gt_shanhai.common.shop.CurrencyStageConfig;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
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
        return recipe != null && CoinForgeMintGate.allowsRequiredLevel(moduleLevel, requiredMintModuleLevel(recipe));
    }

    public static int requiredMintModuleLevel(GTRecipe recipe) {
        if (recipe == null) return 0;
        return Math.max(requiredOutputModuleLevel(recipe.outputs), requiredOutputModuleLevel(recipe.tickOutputs));
    }

    public long getMintParallelLimit() { return getTotalParallelLimit(); }

    /** 只讀診斷：優先用精確配方輸出，無 ID 的舊樣板按其 AE 產物讀同一貨幣階段表。 */
    public String describeMintRestriction(GTRecipe recipe, IPatternDetails pattern) {
        if (!(getMachine() instanceof PrimordialCoinForgeModule forge)) return "";
        int required = requiredMintModuleLevel(recipe);
        if (recipe == null && pattern != null) {
            for (var output : pattern.getOutputs()) {
                if (output != null && output.what() instanceof AEItemKey item) {
                    required = Math.max(required, CoinForgeMintGate.requiredModuleLevel(CurrencyStageConfig.tierOf(item.getId())));
                }
            }
        }
        String reason = CoinForgeMintGate.restrictionText(forge.getModuleLevel(), required);
        if (reason.isEmpty()) return "";
        var modules = PrimordialCoinForgeModule.registeredModuleIds();
        if (required > 0 && required <= modules.size()) {
            String moduleId = modules.get(required - 1);
            var module = ForgeRegistries.ITEMS.getValue(new ResourceLocation(moduleId));
            reason += "\n所需模块: " + (module == null || module == net.minecraft.world.item.Items.AIR
                    ? moduleId : module.getDescription().getString())
                    + " (" + moduleId + ")";
        }
        return reason;
    }

    private static int requiredOutputModuleLevel(Map<RecipeCapability<?>, List<Content>> contents) {
        if (contents == null) return 0;
        List<Content> items = contents.get(ItemRecipeCapability.CAP);
        if (items == null) return 0;
        int required = 0;
        for (Content content : items) {
            if (content == null || content.content == null) continue;
            Object value = content.content;
            if (value instanceof SizedIngredient sized) {
                required = Math.max(required, requiredStackModuleLevel(sized.getItems()));
            } else if (value instanceof Ingredient ingredient) {
                required = Math.max(required, requiredStackModuleLevel(ingredient.getItems()));
            } else if (value instanceof ItemStack stack) {
                required = Math.max(required, requiredStackModuleLevel(stack));
            }
        }
        return required;
    }

    private static int requiredStackModuleLevel(ItemStack[] stacks) {
        if (stacks == null) return 0;
        int required = 0;
        for (ItemStack stack : stacks) {
            required = Math.max(required, requiredStackModuleLevel(stack));
        }
        return required;
    }

    private static int requiredStackModuleLevel(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return CoinForgeMintGate.requiredModuleLevel(CurrencyStageConfig.tierOf(id));
    }
}
