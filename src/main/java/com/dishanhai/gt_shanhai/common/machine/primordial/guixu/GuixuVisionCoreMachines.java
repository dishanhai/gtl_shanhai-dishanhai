package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;
import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.gregtechceu.gtceu.GTCEu;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.dishanhai.gt_shanhai.api.recipe.DShanhaiRecipeTypes;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLEnvironment;

/** 山海：Integrate 歸墟視界樞核，沿用 master 的機器註冊階段。 */
public final class GuixuVisionCoreMachines {
    public static final String ID = "guixu_vision_core";
    private static MultiblockMachineDefinition definition;
    public static final String CONTAINMENT_ID = "guixu_penrose_containment_field";
    private static MultiblockMachineDefinition containmentDefinition;

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
                .recipeTypes(DShanhaiRecipeTypes.GUIXU_MATTER_DECOMPRESSION,
                        DShanhaiRecipeTypes.GUIXU_ENERGY_EXTRACTION,
                        DShanhaiRecipeTypes.GUIXU_PARADIGM_SIMPLIFICATION)
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
        registerContainment(casing, overlay);
    }

    /** 歸墟彭羅斯黑洞遏制場：被動模組，掛 DUMMY 配方類型只為滿足多方塊註冊。 */
    private static void registerContainment(ResourceLocation casing, ResourceLocation overlay) {
        containmentDefinition = GTDishanhaiRegistration.REGISTRATE
                .multiblock(CONTAINMENT_ID, GuixuPenroseContainmentModule::new)
                .rotationState(RotationState.ALL)
                .recipeTypes(GTRecipeTypes.DUMMY_RECIPES)
                .pattern(GuixuPenroseContainmentStructure::createPattern)
                .appearanceBlock(GuixuPenroseContainmentStructure::appearanceBlock)
                .workableCasingRenderer(casing, overlay)
                .register();
        containmentDefinition.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(Component.literal("§7需安裝在歸墟視界樞核的模組位"));
            tooltips.add(Component.literal("§7投入黑洞種子開啟；每秒消耗時空流體維持穩定"));
            tooltips.add(Component.literal("§d遏制中：整台歸墟並行 ×4、耗時 ×0.5、增產 +5、耗電 ×0.5、物質模塊數量 ×2"));
            tooltips.add(Component.literal("§d穩定度低於 50／20 時，並行與速度各再 ×2"));
            tooltips.add(Component.literal("§c失穩會吞噬產出；坍縮時整台歸墟停機 60 秒"));
            tooltips.add(Component.literal("§7投入黑洞坍縮器可安全關閉"));
        });
        GTDishanhaiMod.LOGGER.info("[SHANHAI-CONTAINMENT] 已註冊 {}", CONTAINMENT_ID);
    }

    public static MultiblockMachineDefinition guixuPenroseContainment() {
        return containmentDefinition;
    }

    public static MultiblockMachineDefinition guixuVisionCore() {
        return definition;
    }
}
