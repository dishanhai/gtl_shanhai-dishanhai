package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.integration.enhancedcore.EnhancedCoreCloudLibraryCompat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 山海：沿用終端的來源扣除交易，只擴充原件資格。 */
@Pseudo
@Mixin(targets = "com.gtl.enhancedcore.common.cloud.transfer.CloudTerminalUpload", remap = false)
public class EnhancedCoreCloudLibraryTerminalMixin {
    @Inject(method = "processingOriginal", at = @At("HEAD"), cancellable = true, require = 0)
    private static void gtShanhai$mother(ItemStack stack, Level level, CallbackInfoReturnable<Boolean> cir) {
        if (EnhancedCoreCloudLibraryCompat.mother(stack))
            cir.setReturnValue(EnhancedCoreCloudLibraryCompat.validationDetails(stack, level) != null);
    }
}
