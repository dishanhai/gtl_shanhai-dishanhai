package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase;
import com.dishanhai.gt_shanhai.common.machine.part.RecipeTypePatternBufferPartMachine;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;

import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachine;
import org.gtlcore.gtlcore.api.machine.trait.IRecipeCapabilityMachine;
import org.gtlcore.gtlcore.api.machine.trait.MEPatternRecipeHandlePart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import appeng.api.crafting.IPatternDetails;

/**
 * 星律样板总成的父类私有方法桥接。
 * 所有行为入口必须先限定为 RecipeTypePatternBufferPartMachine，不能改变同父类的超级样板总成。
 */
@Mixin(value = MEPatternBufferPartMachine.class, priority = 900, remap = false)
public abstract class SuperPatternAutoMatchMixin {

    @Inject(method = "onLoad", at = @At("TAIL"), remap = false)
    private void gtShanhai$refreshLoadedPatternCache(CallbackInfo ci) {
        MEPatternBufferPartMachine self = (MEPatternBufferPartMachine) (Object) this;
        if (!(self instanceof RecipeTypePatternBufferPartMachine)) return;
        if (!(self.getLevel() instanceof ServerLevel serverLevel)) return;

        serverLevel.getServer().tell(new TickTask(1, () -> this.gtShanhai$refreshControllers(self)));
        serverLevel.getServer().tell(new TickTask(20, () -> this.gtShanhai$refreshControllers(self)));
    }

    @Inject(method = "getRealPattern", at = @At("RETURN"), cancellable = true, remap = false)
    private void gtShanhai$rewriteStellarPatternMultiplier(int slot, ItemStack stack,
            CallbackInfoReturnable<IPatternDetails> cir) {
        MEPatternBufferPartMachine self = (MEPatternBufferPartMachine) (Object) this;
        if (!(self instanceof RecipeTypePatternBufferPartMachine stellar)) return;
        cir.setReturnValue(stellar.gtShanhai$applyOutputMultiplier(cir.getReturnValue(), stack));
    }

    private void gtShanhai$refreshControllers(MEPatternBufferPartMachine self) {
        for (IMultiController controller : self.getControllers()) {
            if (controller instanceof PrimordialOmegaEngineModuleBase) {
                continue;
            }
            if (controller instanceof IRecipeCapabilityMachine) {
                IRecipeCapabilityMachine machine = (IRecipeCapabilityMachine) controller;
                machine.upDate();
                this.gtShanhai$restorePatternMachineCache(machine);
            }
            if (controller instanceof IRecipeLogicMachine) {
                ((IRecipeLogicMachine) controller).getRecipeLogic().updateTickSubscription();
            }
        }
    }

    private void gtShanhai$restorePatternMachineCache(IRecipeCapabilityMachine machine) {
        java.util.List<MEPatternRecipeHandlePart> parts = machine.getMEPatternRecipeHandleParts();
        if (parts == null || parts.isEmpty()) return;
        for (MEPatternRecipeHandlePart part : parts) {
            if (part != null) {
                part.restoreMachineCache(machine::tryAddAndActiveRhp);
            }
        }
    }

}
