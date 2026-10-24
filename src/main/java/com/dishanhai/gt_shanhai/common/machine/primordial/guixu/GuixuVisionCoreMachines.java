package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;
import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.gregtechceu.gtceu.GTCEu;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLEnvironment;

/** 山海：Integrate 歸墟視界樞核，沿用 master 的機器註冊階段。 */
public final class GuixuVisionCoreMachines {
    public static final String ID = "guixu_vision_core";
    private static MultiblockMachineDefinition definition;

    private GuixuVisionCoreMachines() {}

    public static void init() {
        if (definition != null) return;
        GuixuHostGeometry.verify();
        GuixuVisionCoreMachine.assertFieldHolderComplete();
        var casing = new ResourceLocation("gtlcore", "block/molecular_casing");
        var overlay = GTCEu.id("block/multiblock/guixu_vision_core");
        definition = GTDishanhaiRegistration.REGISTRATE
                .multiblock(ID, GuixuVisionCoreMachine::new)
                .rotationState(RotationState.NON_Y_AXIS)
                // 來源版尚未定義正式配方類型，保留組裝機占位。
                .recipeTypes(GTRecipeTypes.ASSEMBLER_RECIPES)
                .pattern(GuixuVisionCoreStructure::createPattern)
                .workableCasingRenderer(casing, overlay)
                .renderer(() -> new com.dishanhai.gt_shanhai.client.renderer.hostfx.GuixuHostFxRenderer(casing, overlay))
                .hasTESR(true)
                .appearanceBlock(GuixuVisionCoreStructure::appearanceBlock)
                .register();
        if (FMLEnvironment.dist.isClient()) {
            var renderer = definition.getRenderer();
            if (!renderer.getClass().getName().equals(
                    "com.dishanhai.gt_shanhai.client.renderer.hostfx.GuixuHostFxRenderer")) {
                throw new IllegalStateException("[SHANHAI-HOST] 動態特效渲染器未掛上：" + renderer.getClass().getName());
            }
        }
        GTDishanhaiMod.LOGGER.info("[SHANHAI-HOST] 已註冊 {}", ID);
    }

    public static MultiblockMachineDefinition guixuVisionCore() {
        return definition;
    }
}
