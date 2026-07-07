package com.dishanhai.gt_shanhai.mixin;

import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gregtechceu.gtceu.api.capability.IParallelHatch;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;

import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;

import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 为所有 GTCEu 原生机器（和 gtlcore 扩展机器）注入跨配方线程"无限"显示。
 * 扫描 parts 中的并行仓，如果任意仓显示无限并行，则追加线程行。
 */
@Mixin(targets = "com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine", remap = false)
public class ForcedThreadDisplayMixin {

    @Inject(method = "addDisplayText", at = @At("RETURN"))
    private void gtShanhai$addThreadDisplay(List<Component> textList, CallbackInfo ci) {
        try {
            var self = (IMultiController) this;
            if (!self.isFormed()) return;

            // 检测是否有无限并行
            boolean hasInfiniteParallel = false;
            for (IMultiPart part : self.getParts()) {
                if (part instanceof IParallelHatch hatch) {
                    int p = hatch.getCurrentParallel();
                    if (p >= Integer.MAX_VALUE - 1 || p < 0) {
                        hasInfiniteParallel = true;
                        break;
                    }
                }
            }
            // 部分机器自身直接返回 Integer.MAX_VALUE（如加了维护仓的）
            if (!hasInfiniteParallel) {
                // 检查显示文本中是否已有"无限"或类似关键词
                for (var c : textList) {
                    String s = c.getString();
                    if (s.contains("无限")) { hasInfiniteParallel = true; break; }
                }
            }
            if (!hasInfiniteParallel) return;

            var inf = DShanhaiTextUtil.createUltimateRainbow("无限");
            // 如果已有线程行则替换，否则追加
            boolean replaced = false;
            for (int i = 0; i < textList.size(); i++) {
                String s = textList.get(i).getString();
                if (s.contains("thread") || s.contains("线程") || s.contains("跨配方")) {
                    textList.set(i, Component.translatable("gtladditions.multiblock.threads", inf));
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                textList.add(Component.translatable("gtladditions.multiblock.threads", inf));
            }
        } catch (Exception ignored) {}
    }
}
