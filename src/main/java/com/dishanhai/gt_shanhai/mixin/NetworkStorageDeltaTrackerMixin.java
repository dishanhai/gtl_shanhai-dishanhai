package com.dishanhai.gt_shanhai.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import com.dishanhai.gt_shanhai.api.ae2.INetworkStorageDeltaSink;
import com.dishanhai.gt_shanhai.api.ae2.IStorageServiceDeltaRecorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NetworkStorage insert/extract Hook：记录存储变化的 delta。
 */
@Mixin(targets = "appeng.me.storage.NetworkStorage", remap = false)
public abstract class NetworkStorageDeltaTrackerMixin implements INetworkStorageDeltaSink {

    @Unique
    private IStorageServiceDeltaRecorder gtShanhai$deltaRecorder;

    @Override
    public void gtShanhai$setDeltaRecorder(IStorageServiceDeltaRecorder recorder) {
        this.gtShanhai$deltaRecorder = recorder;
    }

    @Inject(method = "insert", at = @At("RETURN"), remap = false)
    private void gtShanhai$onInsert(AEKey what, long amount, Actionable mode, IActionSource src,
                                    CallbackInfoReturnable<Long> cir) {
        if (mode == Actionable.MODULATE && cir.getReturnValue() > 0) {
            recordDelta(what, cir.getReturnValue());
        }
    }

    @Inject(method = "extract", at = @At("RETURN"), remap = false)
    private void gtShanhai$onExtract(AEKey what, long amount, Actionable mode, IActionSource src,
                                      CallbackInfoReturnable<Long> cir) {
        if (mode == Actionable.MODULATE && cir.getReturnValue() > 0) {
            recordDelta(what, -cir.getReturnValue());
        }
    }

    private void recordDelta(AEKey key, long delta) {
        IStorageServiceDeltaRecorder recorder = gtShanhai$deltaRecorder;
        if (recorder != null) {
            recorder.gtShanhai$recordDelta(key, delta);
        }
    }
}
