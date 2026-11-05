package com.dishanhai.gt_shanhai.mixin;

// Author: dishanhai
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialThreadedRecipeLogic;
import com.dishanhai.gt_shanhai.integration.enhancedcore.EnhancedCoreCloudCompat;
import com.dishanhai.gt_shanhai.integration.enhancedcore.EnhancedCorePrimordialExecution;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = PrimordialThreadedRecipeLogic.class, remap = false)
public abstract class EnhancedCorePrimordialTickMixin {
    @Unique private EnhancedCorePrimordialExecution shanhai$cloud;

    @Inject(method = "serverTick", at = @At("HEAD"), cancellable = true)
    private void shanhai$cloudTick(CallbackInfo ci) {
        if (!EnhancedCoreCloudCompat.isAvailable()) return;
        if (shanhai$cloud == null) shanhai$cloud = new EnhancedCorePrimordialExecution();
        if (shanhai$cloud.tick((PrimordialThreadedRecipeLogic) (Object) this)) ci.cancel();
    }
}
