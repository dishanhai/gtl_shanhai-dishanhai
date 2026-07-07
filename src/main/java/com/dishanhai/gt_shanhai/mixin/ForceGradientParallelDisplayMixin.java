package com.dishanhai.gt_shanhai.mixin;

import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;

import java.util.List;

/**
 * 在 GTCEu 基类 MultiblockDisplayText.Builder.addParallelsLine 中截获并行显示。
 * 当并行值 >= Integer.MAX_VALUE 时，用终极彩虹"无限"替换原数字。
 * 覆盖所有 GTCEu 原生机器（电炉、大型化学反应釜等）。
 */
@Mixin(value = com.gregtechceu.gtceu.api.machine.multiblock.MultiblockDisplayText.Builder.class, remap = false)
public class ForceGradientParallelDisplayMixin {

    @Shadow(remap = false)
    private List<Component> textList;

    @Inject(method = "addParallelsLine", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$gradientParallelLine(int parallel, CallbackInfoReturnable<Object> cir) {
        if (parallel < Integer.MAX_VALUE - 1) return;
        var inf = DShanhaiTextUtil.createUltimateRainbow("无限");
        textList.add(Component.literal("")
                .append(Component.literal("同时处理至多"))
                .append(inf)
                .append(Component.literal("个配方")));
        cir.setReturnValue(this);
    }
}
