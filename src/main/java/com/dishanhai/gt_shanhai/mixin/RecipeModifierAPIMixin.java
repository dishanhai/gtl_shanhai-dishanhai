package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI;
import com.dishanhai.gt_shanhai.api.DShanhaiRuntimeRecipeCache;
import com.dishanhai.gt_shanhai.common.recipe.RecipeLookupOverflow;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.lookup.AbstractMapIngredient;
import com.gregtechceu.gtceu.api.recipe.lookup.Branch;
import com.gregtechceu.gtceu.api.recipe.lookup.GTRecipeLookup;
import com.gregtechceu.gtceu.api.recipe.lookup.RecipeIterator;

import org.gtlcore.gtlcore.api.machine.trait.IRecipeCapabilityMachine;
import org.gtlcore.gtlcore.api.machine.trait.MEPatternRecipeHandlePart;
import org.gtlcore.gtlcore.api.recipe.IAdditionalRecipeIterator;
import org.gtlcore.gtlcore.api.recipe.RecipeCacheStrategy;
import org.gtlcore.gtlcore.api.recipe.RecipeRunner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
        RecipeLookupOverflow.clear(this.lookup);
    }

    /**
     * GTCEu 的 RecipeManagerMixin 会把原始配方通过此入口写入索引；在这里生成唯一规则副本，
     * 使机器查找、RecipeIterator 与 Branch 使用同一份已修改内容。
     */
    @ModifyVariable(method = "addRecipe", at = @At("HEAD"), argsOnly = true, remap = false)
    private GTRecipe gtShanhai$prepareLookupRecipe(GTRecipe recipe) {
        return DShanhaiRecipeModifierAPI.prepareLookupRecipe(recipe);
    }

    @WrapOperation(method = "addRecipe", at = @At(value = "INVOKE",
            target = "Lcom/gregtechceu/gtceu/api/recipe/lookup/GTRecipeLookup;"
                    + "recurseIngredientTreeAdd(Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;"
                    + "Ljava/util/List;Lcom/gregtechceu/gtceu/api/recipe/lookup/Branch;II)Z"))
    private boolean gtShanhai$rejectEmptyIngredientTreeEntry(GTRecipeLookup lookup, GTRecipe recipe,
            List<List<AbstractMapIngredient>> ingredients, Branch root, int index, int count,
            Operation<Boolean> original) {
        // GTCEu reports success for an empty index even though no node can hold the recipe.
        if (ingredients.isEmpty() && DShanhaiRecipeModifierAPI.SUPPRESS_LOOKUP_RECIPE_MODIFIERS.get()) {
            return false;
        }
        return original.call(lookup, recipe, ingredients, root, index, count);
    }

    @Inject(method = "addRecipe", at = @At("HEAD"), remap = false)
    private void gtShanhai$removeDuplicateLookupRecipe(GTRecipe recipe,
                                                        org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        DShanhaiRecipeModifierAPI.removeDuplicateLookupRecipe(recipe);
    }

    @Inject(method = "findRecipe", at = @At("HEAD"), cancellable = true)
    private void gtShanhai$getCachedFindRecipe(IRecipeCapabilityHolder holder, CallbackInfoReturnable<GTRecipe> cir) {
        String typeId = gtShanhai$getRecipeTypeId();
        if (!gtShanhai$canCache(holder, typeId)) return;

        DShanhaiRuntimeRecipeCache.Key key = DShanhaiRuntimeRecipeCache.key(typeId, holder, "findRecipe");
        if (DShanhaiRuntimeRecipeCache.contains(key)) {
            cir.setReturnValue(DShanhaiRuntimeRecipeCache.get(key).orElse(null));
        }
    }

    @Inject(method = "findRecipe", at = @At("RETURN"), cancellable = true)
    private void gtShanhai$stripOnFindRecipe(IRecipeCapabilityHolder holder, CallbackInfoReturnable<GTRecipe> cir) {
        String typeId = gtShanhai$getRecipeTypeId();
        GTRecipe current = cir.getReturnValue();
        if (DShanhaiRecipeModifierAPI.isCanonicalLookupRecipe(current)
                && !DShanhaiRecipeModifierAPI.hasRuntimeJSModifiers(typeId)) return;
        if (!gtShanhai$canCache(holder, typeId)) {
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
        if (cir.getReturnValue() == null) {
            GTRecipe overflow = gtShanhai$findOverflow(holder, predicate);
            if (overflow == null) return;
            cir.setReturnValue(gtShanhai$applyRuntimeModifiers(holder, overflow, gtShanhai$getRecipeTypeId()));
            return;
        }
        applyStrip(holder, cir);
    }

    @Inject(method = "find", at = @At("HEAD"), cancellable = true)
    private void gtShanhai$getCachedFindCandidates(IRecipeCapabilityHolder holder, Predicate<GTRecipe> predicate, CallbackInfoReturnable<GTRecipe> cir) {
        String typeId = gtShanhai$getRecipeTypeId();
        List<MEPatternRecipeHandlePart> parts = gtShanhai$patternParts(holder);
        if (!parts.isEmpty()) {
            // Cached ME slots are intentionally absent from prepareRecipeFind.
            for (MEPatternRecipeHandlePart part : parts) {
                for (GTRecipe recipe : part.getCachedGTRecipe()) {
                    if (recipe != null && recipe.recipeType == recipeType && predicate.test(recipe)) {
                        cir.setReturnValue(gtShanhai$applyRuntimeModifiers(holder, recipe, typeId));
                        return;
                    }
                }
            }
            List<List<AbstractMapIngredient>> ingredients = gtShanhai$prepareRecipeFind(holder);
            GTRecipe recipe = ingredients == null ? null
                    : gtShanhai$recurseIngredientTreeFindRecipe(ingredients, this.lookup, predicate);
            if (recipe == null) recipe = gtShanhai$findOverflow(holder, predicate);
            cir.setReturnValue(gtShanhai$applyRuntimeModifiers(holder, recipe, typeId));
            return;
        }
        if (!DShanhaiRecipeModifierAPI.canUseRuntimeRecipeCache(typeId)) return;

        DShanhaiRuntimeRecipeCache.Key key = DShanhaiRuntimeRecipeCache.key(typeId, holder, "findCandidates");
        if (!DShanhaiRuntimeRecipeCache.contains(key)) {
            DShanhaiRuntimeRecipeCache.putCandidates(key, gtShanhai$collectCandidateRecipes(holder));
        }

        GTRecipe recipe = DShanhaiRuntimeRecipeCache.findFirstCandidate(key, predicate);
        if (recipe == null) recipe = gtShanhai$findOverflow(holder, predicate);
        cir.setReturnValue(gtShanhai$applyRuntimeModifiers(holder, recipe, typeId));
    }

    @Inject(method = "getRecipeIterator", at = @At("RETURN"))
    private void gtShanhai$appendOverflow(IRecipeCapabilityHolder holder, Predicate<GTRecipe> predicate,
            CallbackInfoReturnable<RecipeIterator> cir) {
        List<GTRecipe> overflow = RecipeLookupOverflow.recipes(this.lookup);
        if (overflow.isEmpty() || !(cir.getReturnValue() instanceof IAdditionalRecipeIterator additional)) return;
        LinkedHashSet<GTRecipe> merged = new LinkedHashSet<>();
        List<GTRecipe> existing = additional.getAdditionalRecipes();
        if (existing != null) merged.addAll(existing);
        for (GTRecipe recipe : overflow) {
            if (gtShanhai$matchesOverflowInput(holder, recipe)) merged.add(recipe);
        }
        additional.setAdditionalRecipes(new ArrayList<>(merged));
    }

    @Unique
    private GTRecipe gtShanhai$findOverflow(IRecipeCapabilityHolder holder, Predicate<GTRecipe> predicate) {
        for (GTRecipe recipe : RecipeLookupOverflow.recipes(this.lookup)) {
            if (gtShanhai$matchesOverflowInput(holder, recipe) && predicate.test(recipe)) return recipe;
        }
        return null;
    }

    @Unique
    private static boolean gtShanhai$matchesOverflowInput(IRecipeCapabilityHolder holder, GTRecipe recipe) {
        if (recipe.inputs.isEmpty()) return true;
        if (holder instanceof IRecipeCapabilityMachine) {
            return RecipeRunner.handle(recipe, IO.IN, holder, recipe.inputs, Map.of(),
                    true, RecipeCacheStrategy.NO_CACHE).isSuccess();
        }
        return recipe.matchRecipeContents(IO.IN, holder, recipe.inputs, false).isSuccess();
    }

    @Unique
    private boolean gtShanhai$canCache(IRecipeCapabilityHolder holder, String typeId) {
        return gtShanhai$patternParts(holder).isEmpty()
                && !RecipeLookupOverflow.hasRecipes(this.lookup)
                && DShanhaiRecipeModifierAPI.canUseRuntimeRecipeCache(typeId);
    }

    @Unique
    private static List<MEPatternRecipeHandlePart> gtShanhai$patternParts(IRecipeCapabilityHolder holder) {
        if (!(holder instanceof IRecipeCapabilityMachine machine)) return List.of();
        List<MEPatternRecipeHandlePart> parts = machine.getMEPatternRecipeHandleParts();
        return parts == null ? List.of() : parts;
    }

    private void applyStrip(IRecipeCapabilityHolder holder, CallbackInfoReturnable<GTRecipe> cir) {
        GTRecipe recipe = cir.getReturnValue();
        if (recipe == null) return;
        String typeId = recipe.recipeType == null ? gtShanhai$getRecipeTypeId() : recipe.recipeType.registryName.toString();
        if (!DShanhaiRecipeModifierAPI.hasRuntimeRecipeModifiers(typeId)) return;
        if (DShanhaiRecipeModifierAPI.isCanonicalLookupRecipe(recipe)
                && !DShanhaiRecipeModifierAPI.hasRuntimeJSModifiers(typeId)) return;
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
        if (DShanhaiRecipeModifierAPI.isCanonicalLookupRecipe(recipe)
                && !DShanhaiRecipeModifierAPI.hasRuntimeJSModifiers(typeId)) return recipe;
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
