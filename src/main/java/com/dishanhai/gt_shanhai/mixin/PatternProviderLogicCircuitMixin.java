package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.KeyCounter;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.helpers.patternprovider.PatternProviderLogicHost;

import com.dishanhai.gt_shanhai.common.ae2.MachineCircuitDelivery;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.core.Direction;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 樣板供應器選定目標並完成發料後，把樣板上的編程電路寫進該機器的電路槽。
 */
@Mixin(value = PatternProviderLogic.class, remap = false)
public class PatternProviderLogicCircuitMixin {

    @Shadow
    @Final
    private PatternProviderLogicHost host;

    @Inject(method = "pushPattern", at = @At(value = "INVOKE",
            target = "Lappeng/api/crafting/IPatternDetails;pushInputsToExternalInventory([Lappeng/api/stacks/KeyCounter;Lappeng/api/crafting/IPatternDetails$PatternInputSink;)V",
            shift = At.Shift.AFTER), remap = false)
    private void gtShanhai$deliverProgrammedCircuit(IPatternDetails patternDetails, KeyCounter[] inputHolder,
            CallbackInfoReturnable<Boolean> cir, @Local(name = "direction") Direction direction) {
        MachineCircuitDelivery.deliver(this.host, direction, patternDetails);
    }
}
