package com.dishanhai.gt_shanhai.mixin;

// Author: dishanhai
import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetRecipeLogic;
import com.dishanhai.gt_shanhai.integration.enhancedcore.EnhancedCorePrimordialContext;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Set;

@Pseudo
@Mixin(value = SelectableRecipeTypeSetRecipeLogic.class, remap = false)
public abstract class EnhancedCorePrimordialLookupMixin {
    @Inject(method = "lookupRecipeIterator", at = @At("HEAD"), cancellable = true)
    private void shanhai$cloudCandidate(CallbackInfoReturnable<Set<GTRecipe>> cir) {
        var candidates = EnhancedCorePrimordialContext.candidates(this);
        if (candidates != null) cir.setReturnValue(candidates);
    }
}
