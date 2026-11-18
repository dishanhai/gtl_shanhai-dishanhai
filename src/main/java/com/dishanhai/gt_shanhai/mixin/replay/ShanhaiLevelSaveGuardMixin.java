package com.dishanhai.gt_shanhai.mixin.replay;

import com.dishanhai.gt_shanhai.common.time.replay.ReplaySaveGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProgressListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ShanhaiLevelSaveGuardMixin {
    @Inject(method = "save(Lnet/minecraft/util/ProgressListener;ZZ)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void shanhai$guardLevelSave(ProgressListener progress, boolean flush, boolean skipSave, CallbackInfo ci) {
        if (ReplaySaveGuard.rejectSave()) ci.cancel();
    }
}
