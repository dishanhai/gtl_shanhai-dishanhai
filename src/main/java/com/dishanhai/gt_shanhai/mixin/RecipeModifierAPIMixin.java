package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI;
import com.dishanhai.gt_shanhai.api.DShanhaiRuntimeRecipeCache;

import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.lookup.AbstractMapIngredient;
import com.gregtechceu.gtceu.api.recipe.lookup.Branch;
import com.gregtechceu.gtceu.api.recipe.lookup.GTRecipeLookup;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

@Mixin(value = GTRecipeLookup.class, remap = false)
public abstract class RecipeModifierAPIMixin {

    @Shadow
    @Final
    private GTRecipeType recipeType;

    @Shadow
    @Final
    private Branch lookup;

    @Inject(method = "removeAllRecipes", at = @At("HEAD"))
    private void gtShanhai$clearRuntimeRecipeCacheOnLookupReset(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        DShanhaiRuntimeRecipeCache.clear();
    }

    @Inject(method = "findRecipe", at = @At("HEAD"), cancellable = true)
    private void gtShanhai$getCachedFindRecipe(IRecipeCapabilityHolder holder, CallbackInfoReturnable<GTRecipe> cir) {
        String typeId = gtShanhai$getRecipeTypeId();
        if (!DShanhaiRecipeModifierAPI.canUseRuntimeRecipeCache(typeId)) return;

        DShanhaiRuntimeRecipeCache.Key key = DShanhaiRuntimeRecipeCache.key(typeId, holder, "findRecipe");
        if (DShanhaiRuntimeRecipeCache.contains(key)) {
            cir.setReturnValue(DShanhaiRuntimeRecipeCache.get(key).orElse(null));
        }
    }

    @Inject(method = "findRecipe", at = @At("RETURN"), cancellable = true)
    private void gtShanhai$stripOnFindRecipe(IRecipeCapabilityHolder holder, CallbackInfoReturnable<GTRecipe> cir) {
        String typeId = gtShanhai$getRecipeTypeId();
        if (!DShanhaiRecipeModifierAPI.canUseRuntimeRecipeCache(typeId)) {
            applyStrip(holder, cir);
            return;
        }

        DShanhaiRuntimeRecipeCache.Key key = DShanhaiRuntimeRecipeCache.key(typeId, holder, "findRecipe");
        if (DShanhaiRuntimeRecipeCache.contains(key)) {
            cir.setReturnValue(DShanhaiRuntimeRecipeCache.get(key).orElse(null));
            return;
        }

        GTRecipe recipe = gtShanhai$applyRuntimeModifiers(holder, cir.getReturnValue(), typeId);
        DShanhaiRuntimeRecipeCache.put(key, recipe);
        cir.setReturnValue(recipe);
    }

    @Inject(method = "find", at = @At("RETURN"), cancellable = true)
    private void gtShanhai$stripOnFind(IRecipeCapabilityHolder holder, Predicate<GTRecipe> predicate, CallbackInfoReturnable<GTRecipe> cir) {
        applyStrip(holder, cir);
    }

    @Inject(method = "find", at = @At("HEAD"), cancellable = true)
    private void gtShanhai$getCachedFindCandidates(IRecipeCapabilityHolder holder, Predicate<GTRecipe> predicate, CallbackInfoReturnable<GTRecipe> cir) {
        String typeId = gtShanhai$getRecipeTypeId();
        if (!DShanhaiRecipeModifierAPI.canUseRuntimeRecipeCache(typeId)) return;

        DShanhaiRuntimeRecipeCache.Key key = DShanhaiRuntimeRecipeCache.key(typeId, holder, "findCandidates");
        if (!DShanhaiRuntimeRecipeCache.contains(key)) {
            DShanhaiRuntimeRecipeCache.putCandidates(key, gtShanhai$collectCandidateRecipes(holder));
        }

        GTRecipe recipe = DShanhaiRuntimeRecipeCache.findFirstCandidate(key, predicate);
        cir.setReturnValue(gtShanhai$applyRuntimeModifiers(holder, recipe, typeId));
    }

    private void applyStrip(IRecipeCapabilityHolder holder, CallbackInfoReturnable<GTRecipe> cir) {
        GTRecipe recipe = cir.getReturnValue();
        if (recipe == null) return;
        String typeId = recipe.recipeType == null ? gtShanhai$getRecipeTypeId() : recipe.recipeType.registryName.toString();
        if (!DShanhaiRecipeModifierAPI.hasRuntimeRecipeModifiers(typeId)) return;
        if (!(holder instanceof MetaMachine mm)) return;
        GTRecipe copy = recipe.copy();
        DShanhaiRecipeModifierAPI.applyFromRecipe(mm, copy);
        cir.setReturnValue(copy);
    }

    @Unique
    private GTRecipe gtShanhai$applyRuntimeModifiers(IRecipeCapabilityHolder holder, GTRecipe recipe, String typeId) {
        if (recipe == null || !DShanhaiRecipeModifierAPI.hasRuntimeRecipeModifiers(typeId)) {
            return recipe;
        }
        if (!(holder instanceof MetaMachine mm)) {
            GTRecipe copy = recipe.copy();
            DShanhaiRecipeModifierAPI.applyStripByType(copy);
            DShanhaiRecipeModifierAPI.applyReplaceByType(copy);
            return copy;
        }
        GTRecipe copy = recipe.copy();
        return DShanhaiRecipeModifierAPI.applyFromRecipe(mm, copy);
    }

    @Unique
    private List<GTRecipe> gtShanhai$collectCandidateRecipes(IRecipeCapabilityHolder holder) {
        List<List<AbstractMapIngredient>> ingredients = gtShanhai$prepareRecipeFind(holder);
        if (ingredients == null || ingredients.isEmpty()) {
            return List.of();
        }
        List<GTRecipe> candidates = new ArrayList<>();
        gtShanhai$recurseIngredientTreeFindRecipe(ingredients, this.lookup, recipe -> {
            if (recipe != null && !candidates.contains(recipe)) {
                candidates.add(recipe);
            }
            return false;
        });
        return candidates;
    }

    @Unique
    private String gtShanhai$getRecipeTypeId() {
        return this.recipeType == null || this.recipeType.registryName == null ? "unknown" : this.recipeType.registryName.toString();
    }

    @Invoker("prepareRecipeFind")
    protected abstract List<List<AbstractMapIngredient>> gtShanhai$prepareRecipeFind(IRecipeCapabilityHolder holder);

    @Invoker("recurseIngredientTreeFindRecipe")
    protected abstract GTRecipe gtShanhai$recurseIngredientTreeFindRecipe(List<List<AbstractMapIngredient>> ingredients, Branch branch, Predicate<GTRecipe> predicate);
}
