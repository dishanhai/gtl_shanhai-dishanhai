package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.integration.enhancedcore.EnhancedCoreCloudLibraryCompat;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gtl.enhancedcore.common.cloud.CloudPatternMatching;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 山海：母樣板分類不能被相同 IO 的另一個配方 ID 搶占。 */
@Pseudo
@Mixin(targets = "com.gtl.enhancedcore.common.cloud.CloudPatternMatching", remap = false)
public class EnhancedCoreCloudLibraryMatchingMixin {
    @Inject(method = "matches", at = @At("HEAD"), cancellable = true, require = 0)
    private static void gtShanhai$pinRecipe(GTRecipe recipe, WorkableElectricMultiblockMachine machine,
                                           CloudPatternMatching.Probe probe, CallbackInfoReturnable<Boolean> cir) {
        if (!EnhancedCoreCloudLibraryCompat.matchesPinned(recipe, probe)) cir.setReturnValue(false);
    }
}
