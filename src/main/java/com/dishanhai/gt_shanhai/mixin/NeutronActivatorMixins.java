package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.machine.part.IMaintenanceBypassPart;
import com.gregtechceu.gtceu.api.capability.IParallelHatch;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gtladd.gtladditions.api.machine.feature.IThreadModifierPart;
import org.gtlcore.gtlcore.common.machine.multiblock.noenergy.NeutronActivatorMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

        private static final Logger LOG = LoggerFactory.getLogger("gt_shanhai:neutron");

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

        @Inject(method = "working", at = @At("HEAD"), cancellable = true, remap = false)
        private void gtShanhai$bypassEv(CallbackInfoReturnable<Boolean> cir) {
            if ((Object) this instanceof IMultiController controller && IMaintenanceBypassPart.anyVoltageBypassEnabled(controller)) {
                cir.setReturnValue(true);
            }
        }

        @Inject(method = "getMaxParallel", at = @At("RETURN"), cancellable = true)
        private void gtShanhai$boostNeutronParallel(CallbackInfoReturnable<Integer> cir) {
            try {
                if (!((Object) this instanceof IMultiController controller)) return;
                if (!controller.isFormed()) return;

                long hatchParallel = 0;
                long threadExtra = 0;
                for (IMultiPart part : controller.getParts()) {
                    if (part instanceof IParallelHatch hatch) {
                        int parallel = hatch.getCurrentParallel();
                        if (parallel > 0) hatchParallel += parallel;
                    }
                    if (part instanceof IThreadModifierPart threadModifier) {
                        int threads = threadModifier.getThreadCount();
                        if (threads > 0) threadExtra += threads - 1;
                    }
                }

                if (hatchParallel > 0) {
                    long threads = Math.max(0, threadExtra) + 1;
                    long effective = hatchParallel * threads;
                    int result = effective > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) effective;
                    cir.setReturnValue(result);
                    LOG.info("[boostNeutronParallel] original={} hatch={} threads={} → {} (×线程)",
                            cir.getReturnValue(), hatchParallel, threads, result);
                }
            } catch (Exception e) {
                LOG.error("[boostNeutronParallel] 异常", e);
            }
        }
    }
}
