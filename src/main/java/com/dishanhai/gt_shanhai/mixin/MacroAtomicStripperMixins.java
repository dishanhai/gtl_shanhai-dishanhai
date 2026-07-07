package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

public final class MacroAtomicStripperMixins {

    private MacroAtomicStripperMixins() {}

    @Mixin(targets = "com.gtladd.gtladditions.common.machine.multiblock.controller.MacroAtomicResonantFragmentStripper", remap = false)
    public static class Controller {

        @Inject(method = "getMaxParallel", at = @At("RETURN"), cancellable = true)
        private void gtShanhai$infiniteParallel(CallbackInfoReturnable<Integer> cir) {
            cir.setReturnValue(Integer.MAX_VALUE);
        }

        @Inject(method = "getRealParallel", at = @At("RETURN"), cancellable = true)
        private void gtShanhai$infiniteRealParallel(CallbackInfoReturnable<Long> cir) {
            cir.setReturnValue(Long.MAX_VALUE);
        }

        @Inject(method = "addParallelDisplay", at = @At("HEAD"), cancellable = true)
        private void gtShanhai$infiniteParallelDisplay(List<Component> textList, CallbackInfo ci) {
            ci.cancel();
            textList.add(Component.literal("")
                    .append(Component.literal("同时处理至多"))
                    .append(DShanhaiTextUtil.createUltimateRainbow("无限"))
                    .append(Component.literal("个配方"))
                    .withStyle(ChatFormatting.GRAY));
            textList.add(Component.translatable("gtladditions.multiblock.threads",
                    DShanhaiTextUtil.createUltimateRainbow("无限")));
        }
    }

    @Mixin(targets = "com.gtladd.gtladditions.common.machine.multiblock.controller.MacroAtomicResonantFragmentStripper$Companion$MacroAtomicResonantFragmentStripperLogic", remap = false)
    public static class Logic {

        @Inject(method = "calculateParallels", at = @At("RETURN"), cancellable = true)
        private void gtShanhai$infiniteParallelCalc(
                CallbackInfoReturnable<com.gtladd.gtladditions.common.data.ParallelData> cir) {
            var data = cir.getReturnValue();
            if (data == null || data.getParallels() == null) return;
            var parallels = data.getParallels();
            for (int i = 0; i < parallels.length; i++) parallels[i] = Long.MAX_VALUE;
        }
    }
}
