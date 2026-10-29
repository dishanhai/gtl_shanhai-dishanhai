package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.cover.ShanhaiAeSteamCover;
import com.dishanhai.gt_shanhai.common.cover.ShanhaiAeSteamDemand;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.machine.steam.SteamEnergyRecipeHandler;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableFluidTank;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** 山海：只替換裝有 AE 蒸汽覆蓋板的原生耗汽入口；不改其他流體或無覆蓋板機器。 */
@Mixin(value = SteamEnergyRecipeHandler.class, remap = false)
public class ShanhaiAeSteamEnergyHandlerMixin {
    @Shadow @Final private NotifiableFluidTank steamTank;
    @Shadow @Final private double conversionRate;

    @Inject(method = "handleRecipeInner", at = @At("HEAD"), cancellable = true)
    private void shanhai$consumeSteam(IO io, GTRecipe recipe, List<Long> left, String slotName,
                                     boolean simulate, CallbackInfoReturnable<List<Long>> cir) {
        if (io != IO.IN) return;
        var cover = ShanhaiAeSteamCover.forConsumer(steamTank.getMachine());
        if (cover != null) cir.setReturnValue(cover.consume(left, conversionRate, simulate) ? null : left);
    }

    @Inject(method = "getContents", at = @At("HEAD"), cancellable = true)
    private void shanhai$availableContents(CallbackInfoReturnable<List<Object>> cir) {
        var cover = ShanhaiAeSteamCover.forConsumer(steamTank.getMachine());
        if (cover != null) cir.setReturnValue(List.of(ShanhaiAeSteamDemand.availableEnergy(cover.availableSteam(), conversionRate)));
    }

    @Inject(method = "getTotalContentAmount", at = @At("HEAD"), cancellable = true)
    private void shanhai$availableAmount(CallbackInfoReturnable<Double> cir) {
        var cover = ShanhaiAeSteamCover.forConsumer(steamTank.getMachine());
        if (cover != null) cir.setReturnValue((double) ShanhaiAeSteamDemand.availableEnergy(cover.availableSteam(), conversionRate));
    }

    @Inject(method = "getStored", at = @At("HEAD"), cancellable = true)
    private void shanhai$storedSteam(CallbackInfoReturnable<Long> cir) {
        var cover = ShanhaiAeSteamCover.forConsumer(steamTank.getMachine());
        if (cover != null) cir.setReturnValue(cover.availableSteam());
    }

    @Inject(method = "getCapacity", at = @At("HEAD"), cancellable = true)
    private void shanhai$networkCapacity(CallbackInfoReturnable<Long> cir) {
        if (ShanhaiAeSteamCover.forConsumer(steamTank.getMachine()) != null) cir.setReturnValue(Long.MAX_VALUE);
    }
}
