package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.machine.part.ShanhaiAbilityPredicate;
import com.gregtechceu.gtceu.api.pattern.predicates.SimplePredicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * EnhancedCore classifies a whole predicate as material IO when any candidate has
 * IO capability. Multi-ability hatches make that unsafe for energy/maintenance
 * (and other non-material) ability predicates. Preserve their factory semantics.
 * Pure material and non-factory predicates retain EnhancedCore's own behavior,
 * including its connection-port exclusions and material-port replacement.
 */
@Pseudo
@Mixin(targets = "com.gtl.enhancedcore.common.recipe.iv.IvNativeHatches", remap = false)
public abstract class EnhancedCoreNativeHatchesMixin {
    @Inject(method = "materials(Lcom/gregtechceu/gtceu/api/pattern/predicates/SimplePredicate;)Z",
            at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void gtShanhai$preserveNonMaterialAbilities(SimplePredicate predicate,
                                                              CallbackInfoReturnable<Boolean> cir) {
        if (predicate instanceof ShanhaiAbilityPredicate source && source.gtShanhai$hasNonMaterialAbility()) {
            // Do not touch the predicate or its global/layer count bounds.
            cir.setReturnValue(false);
        }
    }
}
