package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.util.WirelessEnergyFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.math.BigInteger;

@Pseudo
@Mixin(targets = "com.gtl.enhancedcore.common.recipe.MachineDiagnostics", remap = false)
abstract class EnhancedCoreGridBalanceMixin {
    @Redirect(method = "power(Lcom/gregtechceu/gtceu/api/machine/multiblock/WorkableMultiblockMachine;J)Lnet/minecraft/network/chat/Component;",
            at = @At(value = "INVOKE", target = "Ljava/math/BigInteger;toString()Ljava/lang/String;"), require = 0)
    private static String shanhai$formatGridBalance(BigInteger value) {
        return WirelessEnergyFormat.balance(value);
    }
}
