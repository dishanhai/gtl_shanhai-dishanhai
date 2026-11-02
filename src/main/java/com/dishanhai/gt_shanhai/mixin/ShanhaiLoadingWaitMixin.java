package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.client.loading.ShanhaiLoadingClient;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 山海：保留 managedBlock 的任務排程與等待語義，僅為客戶端等待補畫面。 */
@Mixin(value = BlockableEventLoop.class, remap = false)
public abstract class ShanhaiLoadingWaitMixin {
    @Inject(method = {"managedBlock", "m_18701_"}, at = @At(value = "INVOKE",
            target = "Ljava/util/function/BooleanSupplier;getAsBoolean()Z", remap = false), remap = false)
    private void shanhai$betweenTasks(CallbackInfo ci) { ShanhaiLoadingClient.renderWhileWaiting(this); }

    @Inject(method = {"waitForTasks", "m_5667_"}, at = @At("HEAD"), remap = false)
    private void shanhai$waitingFrame(CallbackInfo ci) { ShanhaiLoadingClient.renderWhileWaiting(this); }
}
