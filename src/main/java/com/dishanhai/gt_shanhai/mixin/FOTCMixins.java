package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;
import com.dishanhai.gt_shanhai.api.ShanhaiTextAPI;
import com.dishanhai.gt_shanhai.common.machine.part.DShanhaiMaintenanceHatchMachine;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.google.common.base.Predicate;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gtladd.gtladditions.common.machine.multiblock.controller.ForgeOfTheAntichrist;
import com.gtladd.gtladditions.common.machine.multiblock.controller.module.ForgeOfTheAntichristModuleBase;
import com.gtladd.gtladditions.common.machine.multiblock.controller.module.HeliophaseLeylineCrystallizer;
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

public final class FOTCMixins {

    private FOTCMixins() {}

    @Mixin(value = ForgeOfTheAntichrist.class, remap = false)
    public static class HostLock {

        private static final long LOCKED_SECS = 114514L * 3600L;

        @Shadow(remap = false)
        private long runningSecs;

        @Inject(method = "addDisplayText", at = @At("HEAD"))
        private void gtShanhai$lockRunningSecsDisplay(CallbackInfo ci) {
            if (hasMaintenanceHatch()) {
                runningSecs = LOCKED_SECS;
            }
        }

        @Inject(method = "updateRunningSecs", at = @At("HEAD"), cancellable = true)
        private void gtShanhai$freezeRunningSecs(CallbackInfo ci) {
            if (hasMaintenanceHatch()) {
                runningSecs = LOCKED_SECS;
                ci.cancel();
            }
        }

        @Inject(method = "addDisplayText", at = @At("TAIL"))
        private void gtShanhai$hubDisplayText(List<Component> textList, CallbackInfo ci) {
            if (!hasMaintenanceHatch()) return;
            textList.add(Component.literal(""));
            textList.add(DShanhaiTextUtil.createUltimateRainbow("⛓ 终焉枢纽已接入 — 运行时间永冻 锁定: 114,514h"));
            textList.add(DShanhaiTextUtil.createRainbowText("伪神模块已激活直接运行模式，无需主机运行"));
            textList.add(DShanhaiTextUtil.createRainbowText("群星的历史在空想伟力前不过是一个梦，从空想梦境中抽取无限时间"));
        }

        private boolean hasMaintenanceHatch() {
            try {
                IMultiController self = (IMultiController) (Object) this;
                if (!self.isFormed()) return false;
                for (IMultiPart part : self.getParts()) {
                    if (part instanceof DShanhaiMaintenanceHatchMachine) return true;
                }
            } catch (Exception ignored) {}
            return false;
        }
    }

    @Mixin(value = ForgeOfTheAntichristModuleBase.class, remap = false)
    public static class ModuleBase {

        @Mutable
        @Shadow(remap = false)
        @Final
        private static Predicate<IRecipeLogicMachine> BEFORE_WORKING;

        @Inject(method = "<clinit>", at = @At("TAIL"))
        private static void gtShanhai$bypassHostCheck(CallbackInfo ci) {
            Predicate<IRecipeLogicMachine> original = BEFORE_WORKING;
            BEFORE_WORKING = machine -> {
                if (DShanhaiConfig.COMMON.modulesWorkWithoutHost.get()) return true;
                if (machine instanceof ForgeOfTheAntichristModuleBase module) {
                    Object rawHost = module.getHost();
                    if (rawHost instanceof ForgeOfTheAntichrist host) {
                        try {
                            for (IMultiPart part : host.getParts()) {
                                if (part instanceof DShanhaiMaintenanceHatchMachine) return true;
                            }
                        } catch (Exception ignored) {}
                    }
                }
                return original.apply(machine);
            };
        }

        @Inject(method = "addDisplayText", at = @At("RETURN"))
        private void shanhai$fixIndependentDisplay(List<Component> textList, CallbackInfo ci) {
            if (!DShanhaiConfig.COMMON.modulesWorkWithoutHost.get()) return;

            ForgeOfTheAntichristModuleBase self = (ForgeOfTheAntichristModuleBase) (Object) this;
            if (self.getHost() != null && self.isConnectedToHost()) return;

            Component rainbowText = ShanhaiTextAPI.ultimateRainbow("◆ 独立运行中");
            boolean found = false;
            for (int i = 0; i < textList.size(); i++) {
                Component c = textList.get(i);
                if (c.getContents() instanceof TranslatableContents tc
                        && "tooltip.gtlcore.module_not_installed".equals(tc.getKey())) {
                    textList.set(i, rainbowText);
                    found = true;
                    break;
                }
            }
            if (!found) textList.add(rainbowText);
            textList.add(ShanhaiTextAPI.ultimateRainbow("解除模块对主机的依赖关系，允许其在无中枢联动的情况下独立执行任务。"));
            textList.add(ShanhaiTextAPI.ultimateRainbow("切断检测通道，绕过权限验证——模块将不再等待主机响应，而是直接读取本地世线缓存并自主决策。"));
            textList.add(ShanhaiTextAPI.ultimateRainbow("警告：独立运行会丧失协同增益，但可获得绝对的行动自由。"));
        }
    }

    @Mixin(targets = "com.gtladd.gtladditions.common.machine.multiblock.controller.module.ForgeOfTheAntichristModuleBase$Companion$ForgeOfTheAntichristModuleBaseLogic", remap = false)
    public static class ModuleLogic {

        @Redirect(method = "calculateParallels", at = @At(value = "INVOKE", target = "Lkotlin/jvm/internal/Intrinsics;checkNotNull(Ljava/lang/Object;)V", ordinal = 0))
        private void shanhai$skipHostCheckNotNull(Object obj) {
            if (!DShanhaiConfig.COMMON.modulesWorkWithoutHost.get()) {
                Objects.requireNonNull(obj);
            }
        }

        @Redirect(method = "calculateParallels", at = @At(value = "INVOKE", target = "Lcom/gtladd/gtladditions/common/machine/multiblock/controller/ForgeOfTheAntichrist;getRecipeOutputMultiply()D"))
        private double shanhai$getRecipeOutputMultiply(ForgeOfTheAntichrist host) {
            if (host == null && DShanhaiConfig.COMMON.modulesWorkWithoutHost.get()) return 15.0D;
            return host.getRecipeOutputMultiply();
        }
    }

    @Mixin(value = HeliophaseLeylineCrystallizer.class, remap = false)
    public static class HeliophaseLeylineCrystallizerBypass {

        @Mutable
        @Shadow(remap = false)
        @Final
        private static Predicate<IRecipeLogicMachine> BEFORE_WORKING;

        @Inject(method = "<clinit>", at = @At("TAIL"))
        private static void shanhai$bypassMaxEfficiencyCheck(CallbackInfo ci) {
            Predicate<IRecipeLogicMachine> original = BEFORE_WORKING;
            BEFORE_WORKING = machine -> {
                if (DShanhaiConfig.COMMON.modulesWorkWithoutHost.get()) return true;
                if (machine instanceof ForgeOfTheAntichristModuleBase module) {
                    Object rawHost = module.getHost();
                    if (rawHost instanceof ForgeOfTheAntichrist host) {
                        try {
                            for (IMultiPart part : host.getParts()) {
                                if (part instanceof DShanhaiMaintenanceHatchMachine) return true;
                            }
                        } catch (Exception ignored) {}
                    }
                }
                return original.apply(machine);
            };
        }
    }
}
