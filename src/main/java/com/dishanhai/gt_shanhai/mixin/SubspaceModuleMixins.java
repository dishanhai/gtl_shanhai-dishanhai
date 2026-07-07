package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.ShanhaiTextAPI;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.google.common.base.Predicate;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gtladd.gtladditions.common.machine.multiblock.controller.SubspaceCorridorHubIndustrialArray;
import com.gtladd.gtladditions.common.machine.multiblock.controller.module.SubspaceCorridorHubIndustrialArrayModuleBase;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Objects;

public final class SubspaceModuleMixins {

    private SubspaceModuleMixins() {}

    @Mixin(value = SubspaceCorridorHubIndustrialArrayModuleBase.class, remap = false)
    public static class ModuleBase {

        @Mutable
        @Shadow(remap = false)
        @Final
        private static Predicate<IRecipeLogicMachine> BEFORE_WORKING;

        @Inject(method = "<clinit>", at = @At("TAIL"))
        private static void shanhai$bypassHostCheck(CallbackInfo ci) {
            Predicate<IRecipeLogicMachine> original = BEFORE_WORKING;
            BEFORE_WORKING = machine -> {
                if (DShanhaiConfig.COMMON.modulesWorkWithoutHost.get()) return true;
                return original.apply(machine);
            };
        }

        @Inject(method = "addDisplayText", at = @At("RETURN"))
        private void shanhai$fixIndependentDisplay(List<Component> textList, CallbackInfo ci) {
            if (!DShanhaiConfig.COMMON.modulesWorkWithoutHost.get()) return;

            SubspaceCorridorHubIndustrialArrayModuleBase self =
                    (SubspaceCorridorHubIndustrialArrayModuleBase) (Object) this;
            if (self.getHost() != null && self.isConnectedToHost()) return;

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
    }

    @Mixin(targets = "com.gtladd.gtladditions.common.machine.multiblock.controller.module.SubspaceCorridorHubIndustrialArrayModuleBase$Companion$SubspaceCorridorHubIndustrialArrayModuleBaseLogic", remap = false)
    public static class ModuleLogic {

        @Redirect(method = "calculateParallels", at = @At(value = "INVOKE", target = "Lkotlin/jvm/internal/Intrinsics;checkNotNull(Ljava/lang/Object;)V"))
        private void shanhai$skipCheckNotNull(Object obj) {
            if (!DShanhaiConfig.COMMON.modulesWorkWithoutHost.get()) {
                Objects.requireNonNull(obj);
            }
        }

        @Redirect(method = "calculateParallels", at = @At(value = "INVOKE", target = "Lcom/gtladd/gtladditions/common/machine/multiblock/controller/SubspaceCorridorHubIndustrialArray;unlockParadoxical()Z"))
        private boolean shanhai$fixUnlockParadoxical(SubspaceCorridorHubIndustrialArray host) {
            if (host == null) return false;
            return host.unlockParadoxical();
        }
    }
}
