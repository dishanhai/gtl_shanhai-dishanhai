package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.util.RRFModuleRestrictionBypass;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gtladd.gtladditions.common.machine.multiblock.controller.rrf.CatalyticCascadeArray;
import com.gtladd.gtladditions.common.machine.multiblock.controller.rrf.MagnetorheologicalConvergenceCore;
import com.gtladd.gtladditions.common.machine.multiblock.controller.rrf.RRFModuleMachine;
import com.gtladd.gtladditions.common.machine.multiblock.controller.rrf.RRFWorkableModuleMachine;
import com.gtladd.gtladditions.common.machine.multiblock.controller.rrf.SupratemporalBoostingEngine;
import com.hepdd.gtmthings.common.block.machine.multiblock.part.HugeBusPartMachine;

import it.unimi.dsi.fastutil.objects.ObjectIntPair;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 递归反演阵列子模块限制旁路。
 * 只对已成型且已连接阵列的模块生效，避免独立模块凭空参与递归增益。
 */
public final class RRFModuleRestrictionBypassMixin {

    private RRFModuleRestrictionBypassMixin() {}

    @Mixin(value = RRFModuleMachine.class, remap = false)
    public static class BaseModule {
        @Inject(method = "isReadyForRecursiveReverseBuff", at = @At("HEAD"), cancellable = true)
        private void shanhai$readyWhenConnected(CallbackInfoReturnable<Boolean> cir) {
            RRFModuleMachine self = (RRFModuleMachine) (Object) this;
            if (RRFModuleRestrictionBypass.ready(self)) cir.setReturnValue(true);
        }
    }

    @Mixin(value = RRFWorkableModuleMachine.class, remap = false)
    public static class WorkableModule {
        @Inject(method = "beforeWorking", at = @At("HEAD"), cancellable = true)
        private void shanhai$allowWorkWhenConnected(GTRecipe recipe, CallbackInfoReturnable<Boolean> cir) {
            RRFWorkableModuleMachine self = (RRFWorkableModuleMachine) (Object) this;
            if (RRFModuleRestrictionBypass.ready(self)) cir.setReturnValue(true);
        }

        @Inject(method = "isReadyForRecursiveReverseBuff", at = @At("HEAD"), cancellable = true)
        private void shanhai$readyWhenConnected(CallbackInfoReturnable<Boolean> cir) {
            RRFWorkableModuleMachine self = (RRFWorkableModuleMachine) (Object) this;
            if (RRFModuleRestrictionBypass.ready(self)) cir.setReturnValue(true);
        }
    }

    @Mixin(value = CatalyticCascadeArray.class, remap = false)
    public static class CatalyticCascade {
        @Inject(method = "hasOutputBoost", at = @At("HEAD"), cancellable = true)
        private void shanhai$forceOutputBoost(CallbackInfoReturnable<Boolean> cir) {
            CatalyticCascadeArray self = (CatalyticCascadeArray) (Object) this;
            if (RRFModuleRestrictionBypass.ready(self)) cir.setReturnValue(true);
        }

        @Inject(method = "hasEuBuff", at = @At("HEAD"), cancellable = true)
        private void shanhai$forceEuBuff(CallbackInfoReturnable<Boolean> cir) {
            CatalyticCascadeArray self = (CatalyticCascadeArray) (Object) this;
            if (RRFModuleRestrictionBypass.ready(self)) cir.setReturnValue(true);
        }

        @Inject(method = "isReadyForRecursiveReverseEuBuff", at = @At("HEAD"), cancellable = true)
        private void shanhai$forceEuReady(CallbackInfoReturnable<Boolean> cir) {
            CatalyticCascadeArray self = (CatalyticCascadeArray) (Object) this;
            if (RRFModuleRestrictionBypass.ready(self)) cir.setReturnValue(true);
        }
    }

    @Mixin(value = MagnetorheologicalConvergenceCore.class, remap = false)
    public static class MagnetorheologicalConvergence {
        @Shadow
        private boolean focus;

        @Shadow
        private byte failItem1;

        @Shadow
        private byte failItem2;

        @Shadow
        private byte failFluid;

        @Shadow
        private boolean hasMagmatter;

        @Shadow
        private ObjectIntPair<Item>[] requestedItems;

        @Inject(method = "hasFocus", at = @At("HEAD"), cancellable = true)
        private void shanhai$forceFocus(CallbackInfoReturnable<Boolean> cir) {
            MagnetorheologicalConvergenceCore self = (MagnetorheologicalConvergenceCore) (Object) this;
            if (RRFModuleRestrictionBypass.ready(self)) cir.setReturnValue(true);
        }

        @Inject(method = "consumeFocusInputs", at = @At("HEAD"), cancellable = true)
        private void shanhai$bypassFocusInputs(CallbackInfo ci) {
            MagnetorheologicalConvergenceCore self = (MagnetorheologicalConvergenceCore) (Object) this;
            if (!RRFModuleRestrictionBypass.ready(self)) return;
            this.shanhai$forceBypassFocus();
            ci.cancel();
        }

        @Inject(method = "consumeItemHatch", at = @At("HEAD"), cancellable = true)
        private void shanhai$consumeHugeBusStorage(HugeBusPartMachine hatch, CallbackInfo ci) {
            ci.cancel();
            if (hatch == null) return;

            NotifiableItemStackHandler shareInventory = hatch.getShareInventory();
            NotifiableItemStackHandler inventory = hatch.getInventory();
            if (this.failItem1 == 0 && this.requestedItems[0] != null) {
                byte result = this.shanhai$consumeRequestedItem(this.requestedItems[0], shareInventory, inventory);
                if (result != 0) {
                    this.failItem1 = result;
                    return;
                }
            }
            if (this.failItem2 == 0 && this.requestedItems[1] != null) {
                byte result = this.shanhai$consumeRequestedItem(this.requestedItems[1], shareInventory, inventory);
                if (result != 0) this.failItem2 = result;
            }
        }

        @Inject(method = "consumeMagmatter", at = @At("HEAD"), cancellable = true)
        private void shanhai$bypassMagmatter(CallbackInfo ci) {
            MagnetorheologicalConvergenceCore self = (MagnetorheologicalConvergenceCore) (Object) this;
            if (!RRFModuleRestrictionBypass.ready(self)) return;
            this.shanhai$forceBypassFocus();
            ci.cancel();
        }

        private void shanhai$forceBypassFocus() {
            this.focus = true;
            this.hasMagmatter = true;
            this.failItem1 = -1;
            this.failItem2 = -1;
            this.failFluid = -1;
        }

        private byte shanhai$consumeRequestedItem(ObjectIntPair<Item> request,
                NotifiableItemStackHandler primary, NotifiableItemStackHandler secondary) {
            Item item = request.first();
            int required = request.rightInt();
            long found = this.shanhai$countItem(primary, item);
            if (secondary != primary) found += this.shanhai$countItem(secondary, item);

            if (found <= 0) return 0;
            if (found > required) return 1;
            if (found < required) return 2;

            int remaining = required;
            remaining = this.shanhai$extractItem(primary, item, remaining);
            if (remaining > 0 && secondary != primary) this.shanhai$extractItem(secondary, item, remaining);
            return -1;
        }

        private long shanhai$countItem(NotifiableItemStackHandler handler, Item item) {
            if (handler == null) return 0L;
            long count = 0L;
            for (int slot = 0; slot < handler.getSize(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (!stack.isEmpty() && stack.getItem() == item) count += stack.getCount();
            }
            return count;
        }

        private int shanhai$extractItem(NotifiableItemStackHandler handler, Item item, int remaining) {
            if (handler == null || remaining <= 0) return remaining;
            for (int slot = 0; slot < handler.getSize() && remaining > 0; slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (stack.isEmpty() || stack.getItem() != item) continue;
                int amount = Math.min(remaining, stack.getCount());
                handler.extractItemInternal(slot, amount, false);
                remaining -= amount;
            }
            return remaining;
        }
    }

    @Mixin(value = SupratemporalBoostingEngine.class, remap = false)
    public static class SupratemporalBoosting {
        private static final int SHANHAI_OPTIMAL_TEMPERATURE = 97000;

        @Shadow
        private int temperature;

        @Shadow
        private boolean overheated;

        @Inject(method = "onLoad", at = @At("RETURN"))
        private void shanhai$syncOptimalTemperatureOnLoad(CallbackInfo ci) {
            this.shanhai$syncOptimalTemperature();
        }

        @Inject(method = "onStructureFormed", at = @At("RETURN"))
        private void shanhai$syncOptimalTemperatureOnFormed(CallbackInfo ci) {
            this.shanhai$syncOptimalTemperature();
        }

        @Inject(method = "moduleTick", at = @At("RETURN"))
        private void shanhai$syncOptimalTemperatureAfterTick(CallbackInfo ci) {
            this.shanhai$syncOptimalTemperature();
        }

        @Inject(method = "addDisplayText", at = @At("HEAD"))
        private void shanhai$syncOptimalTemperatureForDisplay(java.util.List<net.minecraft.network.chat.Component> textList, CallbackInfo ci) {
            this.shanhai$syncOptimalTemperature();
        }

        @Inject(method = "isTemperatureOptimal", at = @At("HEAD"), cancellable = true)
        private void shanhai$forceOptimalTemperature(CallbackInfoReturnable<Boolean> cir) {
            SupratemporalBoostingEngine self = (SupratemporalBoostingEngine) (Object) this;
            if (RRFModuleRestrictionBypass.ready(self)) {
                this.shanhai$syncOptimalTemperature();
                cir.setReturnValue(true);
            }
        }

        @Inject(method = "isOverheated", at = @At("HEAD"), cancellable = true)
        private void shanhai$forceNotOverheated(CallbackInfoReturnable<Boolean> cir) {
            SupratemporalBoostingEngine self = (SupratemporalBoostingEngine) (Object) this;
            if (RRFModuleRestrictionBypass.ready(self)) {
                this.shanhai$syncOptimalTemperature();
                cir.setReturnValue(false);
            }
        }

        @Inject(method = "isBoostWindowActive", at = @At("HEAD"), cancellable = true)
        private void shanhai$forceBoostWindow(CallbackInfoReturnable<Boolean> cir) {
            SupratemporalBoostingEngine self = (SupratemporalBoostingEngine) (Object) this;
            if (RRFModuleRestrictionBypass.ready(self)) {
                this.shanhai$syncOptimalTemperature();
                cir.setReturnValue(true);
            }
        }

        @Inject(method = "getPerfectSupratemporalBoostParameter", at = @At("HEAD"), cancellable = true)
        private void shanhai$forcePerfectParameter(CallbackInfoReturnable<Double> cir) {
            SupratemporalBoostingEngine self = (SupratemporalBoostingEngine) (Object) this;
            if (RRFModuleRestrictionBypass.ready(self)) {
                this.shanhai$syncOptimalTemperature();
                cir.setReturnValue(1.0D);
            }
        }

        private void shanhai$syncOptimalTemperature() {
            SupratemporalBoostingEngine self = (SupratemporalBoostingEngine) (Object) this;
            if (!self.isFormed() || !self.isConnectedToHost()) return;
            this.temperature = SHANHAI_OPTIMAL_TEMPERATURE;
            this.overheated = false;
        }
    }
}
