package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI;

import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MEPatternBufferPartMachine.class, remap = false)
public class MEPatternBufferCacheInvalidationMixin {

    @Inject(method = "onLoad", at = @At("TAIL"), remap = false)
    private void gtShanhai$registerRecipeModifierCacheOwner(CallbackInfo ci) {
        DShanhaiRecipeModifierAPI.registerPatternCacheOwner(this);
    }

    /**
     * 注销挂在 onMachineRemoved（目标类确有覆写）而非 onUnload——MEPatternBufferPartMachine
     * 及其 base 类都未声明 onUnload，注它会静默 no-op。区块卸载场景由 owner 表的
     * WeakReference 语义兜底，拆除场景在此主动注销。require 缺省为强制，失配时启动报错而非静默。
     */
    @Inject(method = "onMachineRemoved", at = @At("HEAD"), remap = false)
    private void gtShanhai$unregisterRecipeModifierCacheOwner(CallbackInfo ci) {
        DShanhaiRecipeModifierAPI.unregisterPatternCacheOwner(this);
    }
}
