package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.DShanhaiTempBypass;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * searchRecipe RETURN 时记录配方温度需求，供虚拟线圈读取。
 */
@Mixin(RecipeLogic.class)
public class SearchRecipeTempMixin {

    @Inject(method = "searchRecipe", at = @At("RETURN"), remap = false)
    private void gtShanhai$captureRecipeTemp(CallbackInfoReturnable<GTRecipe> cir) {
        if (!DShanhaiTempBypass.isActive()) return;
        try {
            var recipe = cir.getReturnValue();
            int temp = (recipe != null) ? recipe.data.getInt("ebf_temp") : 0;
            DShanhaiTempBypass.setRecipeTemp(temp > 0 ? temp : 0);
        } catch (Exception e) {
            DShanhaiTempBypass.setRecipeTemp(0);
        }
    }
}
