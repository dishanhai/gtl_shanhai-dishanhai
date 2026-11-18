package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.recipe.PrimordialMyriadRecipeData;
import com.gregtechceu.gtceu.integration.kjs.recipe.GTRecipeSchema.GTRecipeJS;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = GTRecipeJS.class, remap = false)
public abstract class GTRecipeJSMyriadBonusMixin {

    @Shadow
    public abstract GTRecipeJS addDataBool(String key, boolean data);

    public GTRecipeJS disablePrimordialMyriadBonus() {
        return addDataBool(PrimordialMyriadRecipeData.DISABLE_BONUS_KEY, true);
    }
}
