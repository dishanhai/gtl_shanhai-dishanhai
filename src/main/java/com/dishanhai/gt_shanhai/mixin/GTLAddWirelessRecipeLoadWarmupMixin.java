package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.machine.WirelessRecipeLoadWarmup;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.trait.MachineTrait;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gtladd.gtladditions.api.machine.logic.GTLAddMultipleWirelessRecipesLogic;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RecipeLogic.class, remap = false)
public abstract class GTLAddWirelessRecipeLoadWarmupMixin {

    @Shadow(remap = false)
    protected TickableSubscription subscription;

    @Shadow(remap = false)
    protected GTRecipe lastRecipe;

    @Unique
    private static final long gtShanhai$wirelessWarmupTicks = 40L;

    @Unique
    private final WirelessRecipeLoadWarmup gtShanhai$wirelessLoadWarmup =
            new WirelessRecipeLoadWarmup(gtShanhai$wirelessWarmupTicks);

    @Inject(method = "onMachineLoad", at = @At("TAIL"), remap = false)
    private void gtShanhai$startWirelessLoadWarmup(CallbackInfo ci) {
        if (!gtShanhai$isWirelessMultiRecipeLogic()) {
            return;
        }
        gtShanhai$wirelessLoadWarmup.onMachineLoad(gtShanhai$getMetaMachine().getOffsetTimer());
    }

    @Inject(method = "serverTick", at = @At("TAIL"), remap = false)
    private void gtShanhai$keepWirelessSubscribedAfterLoad(CallbackInfo ci) {
        if (!gtShanhai$isWirelessMultiRecipeLogic()) {
            return;
        }
        MetaMachine machine = gtShanhai$getMetaMachine();
        if (machine == null || machine.getLevel() == null || machine.getLevel().isClientSide) {
            return;
        }
        if (!gtShanhai$wirelessLoadWarmup.shouldKeepSubscribed(machine.getOffsetTimer(), this.lastRecipe != null)) {
            return;
        }
        this.subscription = machine.subscribeServerTick(this.subscription, ((RecipeLogic) (Object) this)::serverTick);
    }

    @Unique
    private boolean gtShanhai$isWirelessMultiRecipeLogic() {
        return ((Object) this) instanceof GTLAddMultipleWirelessRecipesLogic;
    }

    @Unique
    private MetaMachine gtShanhai$getMetaMachine() {
        return ((MachineTrait) (Object) this).getMachine();
    }
}
