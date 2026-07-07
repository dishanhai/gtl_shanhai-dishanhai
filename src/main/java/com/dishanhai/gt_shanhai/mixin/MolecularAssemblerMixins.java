package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;
import com.dishanhai.gt_shanhai.util.MolecularAssemblerHelper;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import net.minecraft.network.chat.Component;
import org.gtlcore.gtlcore.api.machine.multiblock.MolecularAssemblerMultiblockMachineBase;
import org.gtlcore.gtlcore.common.machine.trait.MolecularAssemblerRecipesLogic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

public final class MolecularAssemblerMixins {

    private MolecularAssemblerMixins() {}

    @Mixin(targets = "com.gtladd.gtladditions.common.machine.multiblock.controller.MolecularAssemblerMultiblockMachine", remap = false)
    public static class GtlAddController {

        @Inject(method = "addDisplayText", at = @At("RETURN"), remap = false)
        private void gtShanhai$infiniteDisplay(List<Component> tooltips, CallbackInfo ci) {
            try {
                MolecularAssemblerMultiblockMachineBase self = (MolecularAssemblerMultiblockMachineBase) (Object) this;
                if (!MolecularAssemblerHelper.hasSuperParallelCore(self)) return;

                Component rainbowInfinity = Component.literal("")
                        .append(Component.literal("§e同时处理至多 "))
                        .append(DShanhaiTextUtil.createUltimateRainbow("无限"))
                        .append(Component.literal(" §e个配方"));
                for (int i = 0; i < tooltips.size(); i++) {
                    String text = tooltips.get(i).getString();
                    if (text.contains("2,147,483,647") || text.contains("2147483647")) {
                        tooltips.set(i, rainbowInfinity);
                        return;
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    @Mixin(MolecularAssemblerRecipesLogic.class)
    public static class RecipesLogic {

        @ModifyArg(
                method = "getRecipe",
                at = @At(value = "INVOKE", target = "Lorg/gtlcore/gtlcore/api/machine/trait/AECraft/IMolecularAssemblerHandler;extractGTRecipe(JI)Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;"),
                index = 0,
                remap = false
        )
        private long gtShanhai$superCoreLongParallel(long original) {
            try {
                RecipeLogic self = (RecipeLogic) (Object) this;
                if (self.getMachine() instanceof IMultiController controller && MolecularAssemblerHelper.hasSuperParallelCore(controller)) {
                    return Long.MAX_VALUE;
                }
            } catch (Exception ignored) {}
            return original;
        }
    }

    @Mixin(targets = "com.gtladd.gtladditions.common.machine.multiblock.controller.MolecularAssemblerMultiblockMachine$createRecipeLogic$1", remap = false)
    public static class GtlAddInfinityRecipeLogic {

        @ModifyArg(
                method = "getMaxRecipe",
                at = @At(value = "INVOKE", target = "Lorg/gtlcore/gtlcore/api/machine/trait/AECraft/IMolecularAssemblerHandler;extractGTRecipe(JI)Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;"),
                index = 0,
                remap = false
        )
        private long gtShanhai$fixInfinityModeParallel(long original) {
            try {
                RecipeLogic self = (RecipeLogic) (Object) this;
                if (original == Long.MIN_VALUE && self.getMachine() instanceof IMultiController controller && MolecularAssemblerHelper.hasSuperParallelCore(controller)) {
                    return Long.MAX_VALUE;
                }
            } catch (Exception ignored) {}
            return original;
        }
    }

    @Mixin(MolecularAssemblerMultiblockMachineBase.class)
    public static class BaseController {

        @Shadow(remap = false)
        protected int maxParallel;

        @Shadow(remap = false)
        protected int tickDuration;

        @Inject(method = "update", at = @At("RETURN"), remap = false)
        private void gtShanhai$fixMaxParallel(CallbackInfo ci) {
            try {
                MolecularAssemblerMultiblockMachineBase self = (MolecularAssemblerMultiblockMachineBase) (Object) this;
                if (MolecularAssemblerHelper.hasSuperParallelCore(self)) {
                    this.maxParallel = Integer.MAX_VALUE;
                    this.tickDuration = 1;
                }
            } catch (Exception ignored) {}
        }
    }
}
