package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.machine.part.IMaintenanceBypassPart;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import org.gtlcore.gtlcore.common.machine.multiblock.noenergy.NeutronActivatorMachine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

public final class NeutronActivatorMixins {

    private NeutronActivatorMixins() {}

    @Mixin(value = NeutronActivatorMachine.class, remap = false)
    public interface EvAccessor {

        @Accessor("eV")
        void setEv(int value);

        @Accessor("eV")
        int getEv();
    }

    @Mixin(value = NeutronActivatorMachine.class, remap = false)
    public static class Controller {

        @Shadow
        private int eV;

        @Inject(method = "neutronEnergyUpdate", at = @At("RETURN"))
        private void gtShanhai$maintainEv(CallbackInfo ci) {
            if (!((Object) this instanceof IMultiController controller)) return;
            if (!IMaintenanceBypassPart.anyVoltageBypassEnabled(controller)) return;
            if (eV < 500_000_000) {
                eV = 1_000_000_000;
            }
        }

        @Inject(method = "recipeModifier", at = @At("HEAD"), cancellable = true)
        private static void gtShanhai$adjustEvForRecipe(MetaMachine machine, GTRecipe recipe,
                                                         CallbackInfoReturnable<GTRecipe> cir) {
            if (!(machine instanceof IMultiController controller)) return;
            if (!IMaintenanceBypassPart.anyVoltageBypassEnabled(controller)) return;

            if (machine instanceof NeutronActivatorMachine nam) {
                int evMax = recipe.data.getInt("ev_max") * 1_000_000;
                int evMin = recipe.data.getInt("ev_min") * 1_000_000;
                EvAccessor accessor = (EvAccessor) nam;
                int currentEv = accessor.getEv();

                if (currentEv >= evMax) {
                    accessor.setEv(evMax - 1);
                } else if (currentEv < evMin) {
                    accessor.setEv((evMin + evMax) / 2);
                }
            }
        }

        // 这里曾有 gtShanhai$bypassEv(method="working") 与 gtShanhai$boostNeutronParallel(method="getMaxParallel")，
        // 两个注入目标在 NeutronActivatorMachine 上**都不存在**——该类只有 onWorking()，并且完全没有
        // getMaxParallel()（javap/反编译源均确认）。由于 gt_shanhai.mixin.json 未设置 injectors.defaultRequire，
        // 二者只是静默失效，从未生效过，已删除。对应能力其实早有正确的落点：
        //   · eV 门槛：recipeModifier 要求 ev_min < eV < ev_max，上面的 gtShanhai$adjustEvForRecipe
        //     已在其 HEAD 把 eV 调进区间，配合 gtShanhai$maintainEv 兜底；
        //   · 并行：NeutronActivatorMachine#recipeModifier 走的是
        //     GTRecipeModifiers.accurateParallel(..., GTLRecipeModifiers.getHatchParallel(nMachine), false)，
        //     已被 AccurateParallelOverrideMixin 覆盖，且枢纽实现了 IParallelHatch 本就会被 getHatchParallel 计入。
    }
}
