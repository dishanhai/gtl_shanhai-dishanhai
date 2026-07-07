package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI;
import com.dishanhai.gt_shanhai.common.item.RecipeTypePatternSearchHelper;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gtladd.gtladditions.api.machine.logic.GTLAddMultipleRecipesLogic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

@Mixin(value = GTLAddMultipleRecipesLogic.class, remap = false)
public class GTLAddRecipesLogicMixins {

    @Inject(method = "getBeforeWorking", at = @At("RETURN"), cancellable = true)
    private void shanhai$bypassBeforeWorking(CallbackInfoReturnable<Predicate<IRecipeLogicMachine>> cir) {
        if (!DShanhaiConfig.COMMON.modulesWorkWithoutHost.get()) return;

        Predicate<IRecipeLogicMachine> original = cir.getReturnValue();
        cir.setReturnValue(machine -> {
            if (original == null) return true;
            if (original.test(machine)) return true;
            return machine instanceof org.gtlcore.gtlcore.api.machine.multiblock.IModularMachineModule module
                    && module.getHost() == null;
        });
    }

    @Inject(method = "getGTRecipe", at = @At("RETURN"), cancellable = true)
    private void gtShanhai$stripOnGetGTRecipe(CallbackInfoReturnable<GTRecipe> cir) {
        GTRecipe recipe = cir.getReturnValue();
        if (recipe == null) return;

        GTLAddMultipleRecipesLogic self = (GTLAddMultipleRecipesLogic) (Object) this;
        MetaMachine machine = self.getMachine();

        GTRecipe copy = recipe.copy();
        DShanhaiRecipeModifierAPI.applyFromRecipe(machine, copy);
        cir.setReturnValue(copy);
    }

    @Inject(method = "lookupRecipeIterator", at = @At("RETURN"), cancellable = true)
    private void gtShanhai$appendMarkedPatternRecipes(CallbackInfoReturnable<Set<GTRecipe>> cir) {
        GTLAddMultipleRecipesLogic self = (GTLAddMultipleRecipesLogic) (Object) this;
        MetaMachine machine = self.getMachine();
        if (!(machine instanceof IRecipeLogicMachine recipeMachine)) return;

        Set<GTRecipe> markedRecipes = RecipeTypePatternSearchHelper.collectMarkedPatternRecipes(recipeMachine);
        if (markedRecipes.isEmpty()) return;

        Set<GTRecipe> base = cir.getReturnValue();
        LinkedHashSet<GTRecipe> merged = new LinkedHashSet<>();
        if (base != null) {
            merged.addAll(base);
        }
        merged.addAll(markedRecipes);
        cir.setReturnValue(merged);
    }
}
