package com.dishanhai.gt_shanhai.common.machine.preview;

import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;
import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** 山海：僅作 JEI／終端結構預覽的控制器，不具備配方處理能力。 */
public final class ShanhaiNyarlathotepPreviewMachine extends MultiblockControllerMachine {
    public static final String ID = "nyarlathoteps_tentacle_preview";

    public ShanhaiNyarlathotepPreviewMachine(IMachineBlockEntity holder) {
        super(holder);
    }

    @Override
    public void asyncCheckPattern(long periodID) {
        // 預覽控制器不週期掃描 388,080 格，也不自動成形或開始工作。
    }

    public static MultiblockMachineDefinition register() {
        return GTDishanhaiRegistration.REGISTRATE
                .multiblock(ID, ShanhaiNyarlathotepPreviewMachine::new)
                .rotationState(RotationState.ALL)
                .tier(GTValues.UEV)
                .recipeTypes(GTRecipeTypes.DUMMY_RECIPES)
                .pattern(ShanhaiNyarlathotepPreviewStructure::createPattern)
                .appearanceBlock(ShanhaiNyarlathotepPreviewStructure::appearanceBlock)
                .workableCasingRenderer(new ResourceLocation("gtlcore", "block/molecular_casing"),
                        new ResourceLocation("gtceu", "block/multiblock/fusion_reactor"))
                .renderer(() -> new com.dishanhai.gt_shanhai.client.renderer.preview.ShanhaiNyarlathotepInstabilityRenderer(
                        new ResourceLocation("gtlcore", "block/molecular_casing"),
                        new ResourceLocation("gtceu", "block/multiblock/fusion_reactor")))
                .hasTESR(true)
                .tooltips(
                        Component.translatable("gt_shanhai.multiblock." + ID + ".tooltip.0"),
                        Component.translatable("gt_shanhai.multiblock." + ID + ".tooltip.1"),
                        Component.translatable("gt_shanhai.multiblock." + ID + ".tooltip.2"))
                .register();
    }
}
