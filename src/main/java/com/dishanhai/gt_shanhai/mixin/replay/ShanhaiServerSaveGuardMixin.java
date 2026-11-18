package com.dishanhai.gt_shanhai.mixin.replay;

import com.dishanhai.gt_shanhai.common.time.replay.ReplaySaveGuard;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftServer.class)
public abstract class ShanhaiServerSaveGuardMixin {
    @Inject(method = "saveAllChunks(ZZZ)Z", at = @At("HEAD"), cancellable = true, require = 1)
    private void shanhai$guardChunkSave(boolean suppressLog, boolean flush, boolean force, CallbackInfoReturnable<Boolean> cir) {
        if (ReplaySaveGuard.rejectSave()) cir.setReturnValue(false);
    }

    @Inject(method = "saveEverything(ZZZ)Z", at = @At("HEAD"), cancellable = true, require = 1)
    private void shanhai$guardServerSave(boolean suppressLog, boolean flush, boolean force, CallbackInfoReturnable<Boolean> cir) {
        if (ReplaySaveGuard.rejectSave()) cir.setReturnValue(false);
    }
}
