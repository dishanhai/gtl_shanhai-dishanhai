package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.ShanhaiTextAPI;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.gregtechceu.gtceu.api.capability.IParallelHatch;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * 超时空装配线模块 — 独立运行修复
 * 显示修复 + 并行修正
 */
@Mixin(targets = "org.gtlcore.gtlcore.common.machine.multiblock.electric.SuprachronalAssemblyLineModuleMachine", remap = false)
public class SuprachronalModuleDisplayMixin {

    /** 独立运行时将"未成功安装"替换为"独立运行" */
    @Inject(method = "addDisplayText", at = @At("RETURN"))
    private void shanhai$fixDisplay(List<Component> textList, CallbackInfo ci) {
        if (!DShanhaiConfig.COMMON.modulesWorkWithoutHost.get()) return;

        Component rainbowText = ShanhaiTextAPI.ultimateRainbow("◆ 独立运行中");
        boolean replaced = false;
        for (int i = 0; i < textList.size(); i++) {
            Component c = textList.get(i);
            if (c.getContents() instanceof TranslatableContents tc
                    && "tooltip.gtlcore.module_not_installed".equals(tc.getKey())) {
                textList.set(i, rainbowText);
                replaced = true;
                break;
            }
        }
        if (!replaced) textList.add(rainbowText);
        textList.add(ShanhaiTextAPI.inline("{bodySilver}解除模块对主机的依赖关系，允许其在无中枢联动的情况下独立执行任务。{/}"));
        textList.add(ShanhaiTextAPI.inline("{bodySilver}切断检测通道，绕过权限验证——模块将不再等待主机响应，而是直接读取本地世线缓存并自主决策。{/}"));
        textList.add(ShanhaiTextAPI.inline("{bodySilver}警告：独立运行会丧失协同增益，但可获得{/}{ultimateRainbow}绝对的行动自由{/}{bodySilver}。{/}"));
    }

    /** 独立运行时 host = null → getParallel 返回 0，改从并行仓读或给默认值 */
    @Inject(method = "getParallel", at = @At("HEAD"), cancellable = true)
    private void shanhai$fixGetParallel(CallbackInfoReturnable<Integer> cir) {
        if (!DShanhaiConfig.COMMON.modulesWorkWithoutHost.get()) return;

        try {
            if (this instanceof IMultiController controller && controller.isFormed()) {
                for (var part : controller.getParts()) {
                    if (part instanceof IParallelHatch hatch) {
                        int p = hatch.getCurrentParallel();
                        if (p > 0) { cir.setReturnValue(p); return; }
                    }
                }
            }
        } catch (Exception ignored) {}
        cir.setReturnValue(64);
    }

    /** 独立运行时跳过 onWorking 中的 isConnectedToHost 进度重置 */
    @Redirect(method = "onWorking", at = @At(value = "INVOKE", target = "Lcom/gregtechceu/gtceu/api/machine/trait/RecipeLogic;setProgress(I)V"))
    private void shanhai$preventProgressReset(com.gregtechceu.gtceu.api.machine.trait.RecipeLogic logic, int progress) {
        if (!DShanhaiConfig.COMMON.modulesWorkWithoutHost.get()) {
            logic.setProgress(progress);
        }
    }
}
