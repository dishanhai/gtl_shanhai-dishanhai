package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.integration.enhancedcore.EnhancedCorePatternBufferCompat;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 讓 EnhancedCore 聯合工廠以任一樣板總成替代原本的超級樣板總成。 */
@Pseudo
@Mixin(targets = "com.gtl.enhancedcore.common.structure.IntegratedFactoryStructure", remap = false)
public abstract class EnhancedCoreIntegratedFactoryStructureMixin {

    @Redirect(method = "create", at = @At(value = "INVOKE",
            target = "Lcom/gtl/enhancedcore/common/registration/MachineRegistrationSupport;requiredBlock(Ljava/lang/String;)Lcom/gregtechceu/gtceu/api/pattern/TraceabilityPredicate;",
            ordinal = 0), require = 0, remap = false)
    private static TraceabilityPredicate gtShanhai$patternBufferPredicate(String ignoredId) {
        return EnhancedCorePatternBufferCompat.patternBufferPredicate();
    }
}
