package com.dishanhai.gt_shanhai.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.me.service.StorageService;
import com.dishanhai.gt_shanhai.api.ae2.IStorageServiceDeltaRecorder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NetworkStorage insert/extract Hook：记录存储变化的 delta
 * 配合 StorageServiceFastClearMixin 的增量更新机制
 */
@Mixin(targets = "appeng.me.storage.NetworkStorage", remap = false)
public abstract class NetworkStorageDeltaTrackerMixin {

    @Shadow @Final private StorageService service;

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
        if (service instanceof IStorageServiceDeltaRecorder recorder) {
            recorder.gtShanhai$recordDelta(key, delta);
        }
    }
}
