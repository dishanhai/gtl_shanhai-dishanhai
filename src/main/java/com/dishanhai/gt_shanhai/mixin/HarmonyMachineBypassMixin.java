package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.machine.part.IMaintenanceBypassPart;
import com.dishanhai.gt_shanhai.api.ShanhaiTextAPI;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * 绕过 HarmonyMachine 的氢/氦消耗检查。
 * 当安装了聚合枢纽时，跳过氢氦不足导致的配方失败。
 */
@Mixin(value = org.gtlcore.gtlcore.common.machine.multiblock.electric.HarmonyMachine.class, remap = false, priority = 900)
public class HarmonyMachineBypassMixin {

    private static final Logger LOG = LoggerFactory.getLogger("gt_shanhai:harmony");
    private static final long GT_SHANHAI_LOCKED_HARMONY_FLUID = (long) Integer.MAX_VALUE;

    @Shadow private long hydrogen;
    @Shadow private long helium;

    private static boolean gtShanhai$hasEnabledHub(IMultiController controller) {
        if (!DShanhaiConfig.COMMON.maintenanceHatchEnabled.get()) return false;
        if (controller == null || !controller.isFormed()) return false;
        for (IMultiPart part : controller.getParts()) {
            if (part instanceof IMaintenanceBypassPart bp && bp.isVoltageBypassEnabled()) {
                return true;
            }
        }
        return false;
    }

    private void gtShanhai$lockHarmonyFluids() {
        this.hydrogen = GT_SHANHAI_LOCKED_HARMONY_FLUID;
        this.helium = GT_SHANHAI_LOCKED_HARMONY_FLUID;
    }

    private static void gtShanhai$lockHarmonyFluids(Object machine) {
        gtShanhai$setLongField(machine, "hydrogen", GT_SHANHAI_LOCKED_HARMONY_FLUID);
        gtShanhai$setLongField(machine, "helium", GT_SHANHAI_LOCKED_HARMONY_FLUID);
    }

    private static void gtShanhai$setLongField(Object target, String name, long value) {
        Class<?> cls = target.getClass();
        while (cls != null) {
            try {
                var field = cls.getDeclaredField(name);
                field.setAccessible(true);
                field.setLong(target, value);
                return;
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            } catch (Exception ignored) {
                return;
            }
        }
    }

    @Inject(method = "StartupUpdate", at = @At("TAIL"), require = 0)
    private void gtShanhai$lockHarmonyFluidsOnStartupTick(CallbackInfo ci) {
        try {
            if ((Object) this instanceof IMultiController controller && gtShanhai$hasEnabledHub(controller)) {
                gtShanhai$lockHarmonyFluids();
            }
        } catch (Exception e) {
            LOG.error("[lockHarmonyFluidsOnStartupTick] 异常", e);
        }
    }

    @Inject(method = "addDisplayText", at = @At("RETURN"), require = 0)
    private void gtShanhai$showLockedHarmonyFluidsAsInfinite(List<Component> textList, CallbackInfo ci) {
        try {
            if (!((Object) this instanceof IMultiController controller) || !gtShanhai$hasEnabledHub(controller)) {
                return;
            }
            Component infinity = ShanhaiTextAPI.ultimateRainbow("无限");
            for (int i = 0; i < textList.size(); i++) {
                Component component = textList.get(i);
                if (component.getContents() instanceof TranslatableContents tc) {
                    String key = tc.getKey();
                    if ("tooltip.gtlcore.hydrogen_storage".equals(key)
                            || "tooltip.gtlcore.helium_storage".equals(key)) {
                        textList.set(i, Component.translatable(key, infinity));
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("[showLockedHarmonyFluidsAsInfinite] 异常", e);
        }
    }

    @Inject(
            method = {"gtladditions$consumeCosmosStartup", "gtladditions$consumeAstralStartup"},
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void gtShanhai$bypassGTLAddStartup(CallbackInfoReturnable<Boolean> cir) {
        try {
            if ((Object) this instanceof IMultiController controller && gtShanhai$hasEnabledHub(controller)) {
                gtShanhai$lockHarmonyFluids();
                cir.setReturnValue(true);
            }
        } catch (Exception e) {
            LOG.error("[bypassGTLAddStartup] 异常", e);
        }
    }

    @Inject(method = "recipeModifier", at = @At("HEAD"), require = 0)
    private static void gtShanhai$lockHarmonyFluidsBeforeModifier(
            MetaMachine machine, GTRecipe recipe,
            com.gregtechceu.gtceu.api.recipe.logic.OCParams params,
            com.gregtechceu.gtceu.api.recipe.logic.OCResult result,
            CallbackInfoReturnable<GTRecipe> cir) {
        try {
            if (machine instanceof IMultiController controller && gtShanhai$hasEnabledHub(controller)) {
                gtShanhai$lockHarmonyFluids(machine);
            }
        } catch (Exception e) {
            LOG.error("[lockHarmonyFluidsBeforeModifier] 异常", e);
        }
    }

    @Inject(method = "recipeModifier", at = @At("RETURN"), cancellable = true)
    private static void gtShanhai$bypassHarmony(
            MetaMachine machine, GTRecipe recipe,
            com.gregtechceu.gtceu.api.recipe.logic.OCParams params,
            com.gregtechceu.gtceu.api.recipe.logic.OCResult result,
            CallbackInfoReturnable<GTRecipe> cir) {
        try {
            // 原方法已返回非 null → 有足够氢氦，不需要绕过
            if (cir.getReturnValue() != null) {
                if (machine instanceof IMultiController controller && gtShanhai$hasEnabledHub(controller)) {
                    gtShanhai$lockHarmonyFluids(machine);
                }
                return;
            }

            if (!(machine instanceof IMultiController controller)) return;
            if (!gtShanhai$hasEnabledHub(controller)) return;
            gtShanhai$lockHarmonyFluids(machine);

            // 原方法因氢/氦不足返回 null → 枢纽绕过限制
            int oc = 1;
            try {
                // 在 HarmonyMachine 及其父类中查找 oc 字段
                Class<?> cls = machine.getClass();
                while (cls != null) {
                    try {
                        var df = cls.getDeclaredField("oc");
                        df.setAccessible(true);
                        oc = df.getInt(machine);
                        break;
                    } catch (NoSuchFieldException e) {
                        cls = cls.getSuperclass();
                    }
                }
                if (oc <= 0) oc = 1;
            } catch (Exception ignored) {}
            var copied = recipe.copy();
            copied.duration = Math.max(1, (int) (4800.0 / Math.pow(2.0, oc)));
            cir.setReturnValue(copied);
            gtShanhai$lockHarmonyFluids(machine);
        } catch (Exception e) {
            LOG.error("[bypassHarmony] 异常", e);
        }
    }
}
