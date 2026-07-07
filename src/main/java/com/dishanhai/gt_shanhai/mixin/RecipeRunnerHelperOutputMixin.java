package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase;
import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;

import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;

import net.minecraft.world.item.ItemStack;

import org.gtlcore.gtlcore.api.machine.trait.IRecipeCapabilityMachine;
import org.gtlcore.gtlcore.api.machine.trait.MEIORecipeHandlePart;
import org.gtlcore.gtlcore.api.machine.trait.RecipeHandlePart;
import org.gtlcore.gtlcore.api.recipe.ingredient.LongIngredient;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 原初模块挂载超稳态黑洞种子时，允许输出端可写后的剩余溢出产物被吞噬。
 * 如果完全没有可用输出路径，仍阻止配方启动，避免整批吞产物。
 */
@Mixin(value = org.gtlcore.gtlcore.api.recipe.RecipeRunnerHelper.class, remap = false)
public abstract class RecipeRunnerHelperOutputMixin {

    @Inject(method = "matchRecipeOutput", at = @At("RETURN"), cancellable = true, require = 0)
    private static void gtShanhai$hyperstableBlackHoleOutputMatch(IRecipeCapabilityHolder holder,
                                                                  GTRecipe recipe,
                                                                  CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) {
            return;
        }
        if (holder instanceof MetaMachine machine
                && machine instanceof PrimordialOmegaEngineModuleBase module
                && module.hasHyperstableBlackHoleSeedMounted()
                && gtShanhai$canRouteAnyOutput(holder, recipe)) {
            cir.setReturnValue(true);
        }
    }

    private static boolean gtShanhai$canRouteAnyOutput(IRecipeCapabilityHolder holder, GTRecipe recipe) {
        if (!(holder instanceof IRecipeCapabilityMachine machine) || machine.emptyHandlePart()) {
            return false;
        }
        Reference2ObjectOpenHashMap<RecipeCapability<?>, List<Object>> remaining = gtShanhai$copyOutputs(recipe);
        double before = gtShanhai$outputAmount(remaining);
        if (before <= 0.0D) {
            return false;
        }

        for (MEIORecipeHandlePart<?> handler : machine.getMEOutputRecipeHandleParts()) {
            remaining = handler.meHandleOutput(remaining, true);
            double after = gtShanhai$outputAmount(remaining);
            if (after < before) {
                return true;
            }
            before = after;
        }

        for (RecipeHandlePart handler : machine.getNormalRecipeHandlePart(IO.OUT)) {
            remaining = handler.handleRecipe(IO.OUT, recipe, remaining, true);
            double after = gtShanhai$outputAmount(remaining);
            if (after < before) {
                return true;
            }
            before = after;
        }
        return false;
    }

    private static Reference2ObjectOpenHashMap<RecipeCapability<?>, List<Object>> gtShanhai$copyOutputs(GTRecipe recipe) {
        Reference2ObjectOpenHashMap<RecipeCapability<?>, List<Object>> result = new Reference2ObjectOpenHashMap<>();
        for (Map.Entry<RecipeCapability<?>, List<Content>> entry : recipe.outputs.entrySet()) {
            RecipeCapability<?> cap = entry.getKey();
            if (!cap.doMatchInRecipe()) {
                continue;
            }
            List<Object> contents = new ArrayList<>();
            for (Content content : entry.getValue()) {
                if (content != null && content.content != null) {
                    contents.add(content.content);
                }
            }
            if (!contents.isEmpty()) {
                result.put(cap, contents);
            }
        }
        return result;
    }

    private static double gtShanhai$outputAmount(Reference2ObjectOpenHashMap<RecipeCapability<?>, List<Object>> contents) {
        double amount = 0.0D;
        for (Map.Entry<RecipeCapability<?>, List<Object>> entry : contents.entrySet()) {
            RecipeCapability<?> cap = entry.getKey();
            for (Object content : entry.getValue()) {
                amount += gtShanhai$contentAmount(cap, content);
            }
        }
        return amount;
    }

    private static double gtShanhai$contentAmount(RecipeCapability<?> cap, Object content) {
        if (cap == ItemRecipeCapability.CAP) {
            if (content instanceof LongIngredient ingredient) return ingredient.getActualAmount();
            if (content instanceof SizedIngredient ingredient) return ingredient.getAmount();
            if (content instanceof ItemStack stack) return stack.getCount();
        }
        if (cap == FluidRecipeCapability.CAP) {
            if (content instanceof FluidIngredient ingredient) return ingredient.getAmount();
            if (content instanceof FluidStack stack) return stack.getAmount();
        }
        return 1.0D;
    }
}
