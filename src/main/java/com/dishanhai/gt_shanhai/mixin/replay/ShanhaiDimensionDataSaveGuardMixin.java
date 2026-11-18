package com.dishanhai.gt_shanhai.mixin.replay;

import com.dishanhai.gt_shanhai.common.time.replay.ReplaySaveGuard;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DimensionDataStorage.class)
public abstract class ShanhaiDimensionDataSaveGuardMixin {
    @Inject(method = "save()V", at = @At("HEAD"), cancellable = true, require = 1)
    private void shanhai$guardDataSave(CallbackInfo ci) {
        if (ReplaySaveGuard.rejectSave()) ci.cancel();
    }
}
