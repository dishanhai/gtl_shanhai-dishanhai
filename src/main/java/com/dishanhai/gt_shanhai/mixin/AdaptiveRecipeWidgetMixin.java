package com.dishanhai.gt_shanhai.mixin;

// Author: dishanhai

import com.dishanhai.gt_shanhai.integration.jei.AdaptiveRecipeUI;
import com.google.common.collect.Table;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.integration.GTRecipeWidget;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

@Mixin(value = GTRecipeWidget.class, remap = false)
abstract class AdaptiveRecipeWidgetMixin {
    @WrapOperation(method = "setRecipeWidget", at = @At(value = "INVOKE",
            target = "Lcom/gregtechceu/gtceu/integration/GTRecipeWidget;addSlots(Lcom/google/common/collect/Table;Lcom/lowdragmc/lowdraglib/gui/widget/WidgetGroup;Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;)V"))
    private void shanhai$populateAdaptiveSlots(GTRecipeWidget widget, Table<IO, RecipeCapability<?>, List<Content>> contents,
                                               WidgetGroup group, GTRecipe recipe, Operation<Void> original) {
        AdaptiveRecipeUI.populate(recipe, contents, group);
        original.call(widget, contents, group, recipe);
    }
}
