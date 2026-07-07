package com.dishanhai.gt_shanhai.mixin;

import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtladd.gtladditions.api.machine.multiblock.GTLAddWorkableElectricMultipleRecipesMachine;

import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;

import java.util.List;

/**
 * 在 gtladditions 的基类 addParallelDisplay 中截获，当 getMaxParallel() 为无限时
 * 用终极彩虹"无限"替换数字显示+线程"无限"。
 * 覆盖 gtladditions/gtlcore 扩展机器（FOTC、Heliofusion 等）。
 */
@Mixin(value = GTLAddWorkableElectricMultipleRecipesMachine.class, remap = false)
public class GTLAddParallelDisplayMixin {

    @Inject(method = "addParallelDisplay", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$overrideParallelDisplay(List<Component> textList, CallbackInfo ci) {
        var self = (GTLAddWorkableElectricMultipleRecipesMachine) (Object) this;
        if (self.getMaxParallel() < Integer.MAX_VALUE) return;
        ci.cancel();
        var inf = DShanhaiTextUtil.createUltimateRainbow("无限");
        textList.add(Component.literal("")
                .append(Component.literal("同时处理至多"))
                .append(inf)
                .append(Component.literal("个配方")));
        // thread: 通过 gtladditions 的 %s 翻译传入 Component
        if (self instanceof com.gtladd.gtladditions.api.machine.feature.IThreadModifierPart tp) {
            int t = tp.getThreadCount();
            if (t >= Integer.MAX_VALUE || t < 0) {
                textList.add(Component.translatable("gtladditions.multiblock.threads", inf));
            }
        }
    }
}
