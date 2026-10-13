package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.gui.ShanhaiAdvancedCircuitConfigurator;
import com.gregtechceu.gtceu.api.machine.fancyconfigurator.CircuitFancyConfigurator;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;

import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把单选电路设置换成可一次选中 0–32 的多选页。
 */
@Mixin(value = CircuitFancyConfigurator.class, remap = false)
public class CircuitFancyConfiguratorMixin {

    @Shadow
    @Final
    private ItemStackTransfer circuitSlot;

    @Inject(method = "getTitle", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$advancedCircuitTitle(CallbackInfoReturnable<Component> cir) {
        cir.setReturnValue(Component.translatable("gt_shanhai.gui.advanced_circuit.title"));
    }

    @Inject(method = "createConfigurator", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$advancedCircuitConfigurator(CallbackInfoReturnable<Widget> cir) {
        cir.setReturnValue(ShanhaiAdvancedCircuitConfigurator.create(this.circuitSlot));
    }
}
