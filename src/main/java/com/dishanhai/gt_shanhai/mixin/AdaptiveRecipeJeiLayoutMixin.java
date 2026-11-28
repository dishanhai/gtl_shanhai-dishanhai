package com.dishanhai.gt_shanhai.mixin;

// Author: dishanhai

import com.dishanhai.gt_shanhai.integration.jei.AdaptiveRecipeJeiSlot;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.lowdragmc.lowdraglib.gui.ingredient.IRecipeIngredientSlot;
import com.lowdragmc.lowdraglib.jei.ModularUIRecipeCategory;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.recipe.RecipeIngredientRole;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = ModularUIRecipeCategory.class, remap = false)
abstract class AdaptiveRecipeJeiLayoutMixin {
    @WrapOperation(method = "addJEISlot", at = @At(value = "INVOKE",
            target = "Lmezz/jei/api/gui/builder/IRecipeLayoutBuilder;addSlot(Lmezz/jei/api/recipe/RecipeIngredientRole;II)Lmezz/jei/api/gui/builder/IRecipeSlotBuilder;"), require = 0)
    private static IRecipeSlotBuilder shanhai$bindScrollableSlot(IRecipeLayoutBuilder layout, RecipeIngredientRole role,
                                                               int x, int y, Operation<IRecipeSlotBuilder> original,
                                                               IRecipeLayoutBuilder builder, IRecipeIngredientSlot slot) {
        if (AdaptiveRecipeJeiSlot.isScrollableSlot(slot.self())) return AdaptiveRecipeJeiSlot.bind(layout, role, slot.self());
        return original.call(layout, role, x, y);
    }
}
